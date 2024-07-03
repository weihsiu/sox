package sox

import java.io.*
import java.net.*
import ox.*
import ox.channels.*
import ox.either.*
import scala.collection.mutable
import scodec.*
import scodec.bits.*
import scodec.codecs.{either as _, *}
import scodec.Attempt.Failure
import scodec.Attempt.Successful
import scala.io.StdIn
import java.util.concurrent.atomic.AtomicReference

trait SocksProxy:
  def start(port: Int)(using Ox): () => Unit

object SocksProxy:
  enum ReplyCode(val value: Int):
    case RequestGranted extends ReplyCode(90)
    case RequestRejected91 extends ReplyCode(91)
    case RequestRejected92 extends ReplyCode(92)
    case RequestRejected93 extends ReplyCode(93)

  enum Request:
    case ConnectRequest(destPort: Int, destIp: ByteVector, userId: String)
    case BindRequest(destPort: Int, destIp: ByteVector, userId: String)

  case class Reply(code: ReplyCode, destPort: Int, destIp: ByteVector)

  val replyCodeCodec = uint8.xmap(x => ReplyCode.values(x - 90), _.value)

  val bodyCodec = uint16 :: bytes(4) :: cstring

  val requestCodec = discriminated[Request]
    .by(uint16)
    .subcaseP[Request.ConnectRequest](0x0401) {
      case x: Request.ConnectRequest => x
    }(
      bodyCodec.as[Request.ConnectRequest]
    )
    .subcaseP[Request.BindRequest](0x0402) { case x: Request.BindRequest => x }(
      bodyCodec.as[Request.BindRequest]
    )

  val replyCodec =
    (constant[Byte](0) :: replyCodeCodec :: uint16 :: bytes(4)).as[Reply]

  case class Connection(
      clientAddress: InetSocketAddress,
      serverAddress: InetSocketAddress
  )

  case class Sources(
      fromClient: Source[ByteVector],
      fromServer: Source[ByteVector]
  )

  def forward(
      socket: Socket,
      inputStream: BufferedInputStream,
      outputStream: BufferedOutputStream,
      sink: Sink[ByteVector]
  ): Unit =
    val bs = Array.ofDim[Byte](2048)
    var len = -1
    while
      len = inputStream.read(bs)
      len != -1
    do
      outputStream.write(bs, 0, len)
      outputStream.flush()
      sink.send(ByteVector(bs, 0, len))
    socket.shutdownOutput()
    sink.done()

  def tunnel(
      fromClientSocket: Socket,
      toServerSocket: Socket,
      connectionSources: AtomicReference[Map[Connection, Sources]]
  )(using Ox): Unit =
    val clientIn = BufferedInputStream(fromClientSocket.getInputStream())
    val clientOut = BufferedOutputStream(fromClientSocket.getOutputStream())
    val serverIn = BufferedInputStream(toServerSocket.getInputStream())
    val serverOut = BufferedOutputStream(toServerSocket.getOutputStream())
    val clientChannel = Channel.bufferedDefault[ByteVector]
    val serverChannel = Channel.bufferedDefault[ByteVector]
    val connection = Connection(
      fromClientSocket.getRemoteSocketAddress().asInstanceOf[InetSocketAddress],
      toServerSocket.getRemoteSocketAddress().asInstanceOf[InetSocketAddress]
    )
    val sources = Sources(clientChannel, serverChannel)
    connectionSources.updateAndGet(_ + (connection -> sources))
    fork:
      val f1 = fork(forward(toServerSocket, clientIn, serverOut, clientChannel))
      val f2 =
        fork(forward(fromClientSocket, serverIn, clientOut, serverChannel))
      f1.join()
      f2.join()
      connectionSources.updateAndGet(_ - connection)
      fromClientSocket.close()
      toServerSocket.close()
      clientChannel.done()
      serverChannel.done()

  def handleRequest(
      inSocket: Socket,
      connectionSources: AtomicReference[Map[Connection, Sources]],
      inSink: Sink[ByteVector],
      outSink: Sink[ByteVector]
  )(using Ox): Unit =
    val inInput = BufferedInputStream(inSocket.getInputStream())
    val inOutput = BufferedOutputStream(inSocket.getOutputStream())

    def writeReply(code: ReplyCode, destPort: Int, destIp: ByteVector): Unit =
      val reply = Reply(code, destPort, destIp)
      inOutput.write(replyCodec.encode(reply).getOrElse(???).toByteArray)
      inOutput.flush()

    val bv = inInput.readBytes(8) ++ inInput
      .readTillNull(100)
      .getOrElse(
        throw Exception("invalid request")
      )
    requestCodec.decode(bv.toBitVector) match
      case Failure(e) =>
        throw Exception(s"failed to decode request $bv: ${e.message}")
      case Successful(r) => r.value
    match
      case Request.ConnectRequest(destPort, destIp, userId) =>
        println("connect request received")
        val outSocket =
          Socket(InetAddress.getByAddress(destIp.toArray), destPort)
        tunnel(inSocket, outSocket, inSink, outSink)
        writeReply(ReplyCode.RequestGranted, destPort, destIp)
      case Request.BindRequest(destPort, destIp, userId) =>
        println("unsupported bind request")
        writeReply(ReplyCode.RequestRejected91, destPort, destIp)

  def apply(): SocksProxy = new SocksProxy:
    def start(port: Int)(using Ox): () => Unit =
      val serverSocket = ServerSocket(port)
      fork:
        forever:
          println("accepting")
          val inSocket = serverSocket.accept()
          println("accepted")
          fork:
            val inSink = Channel.unlimited[ByteVector]
            val outSink = Channel.unlimited[ByteVector]
            // fork(forever(println(s"from client: ${inSink.receive()}")))
            // fork(forever(println(s"from server: ${outSink.receive()}")))
            handleRequest(inSocket, inSink, outSink)
      () => serverSocket.close()

  @main
  def runSocksProxy() =
    supervised:
      val stop = SocksProxy().start(1080)
      StdIn.readLine()
