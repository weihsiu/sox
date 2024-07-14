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
import scala.util.control.NonFatal

trait SocksProxy:
  def start(port: Int)(using IO, Ox): () => Unit

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
      inputStream: InputStream,
      outputStream: OutputStream,
      sink: Sink[ByteVector],
      source: Source[ByteVector]
  )(using IO, Ox): Unit =
    par(
      sink.fromInputStreamBV(BufferedInputStream(inputStream)),
      source.toOutputStreamBV(BufferedOutputStream(outputStream))
    )

  def tunnel(
      clientSocket: Socket,
      serverSocket: Socket,
      clientChannel: Channel[ByteVector],
      serverChannel: Channel[ByteVector]
  )(using IO, Ox): Unit =
    par(
      forward(
        clientSocket.getInputStream(),
        serverSocket.getOutputStream(),
        clientChannel,
        clientChannel
      ),
      forward(
        serverSocket.getInputStream(),
        clientSocket.getOutputStream(),
        serverChannel,
        serverChannel
      )
    )

  def handleRequest(
      clientSocket: Socket,
      clientChannel: Channel[ByteVector],
      serverChannel: Channel[ByteVector]
  )(using IO, Ox): Unit =
    val clientBIS = BufferedInputStream(clientSocket.getInputStream())
    val clientBOS = BufferedOutputStream(clientSocket.getOutputStream())

    def writeReply(code: ReplyCode, destPort: Int, destIp: ByteVector): Unit =
      val reply = Reply(code, destPort, destIp)
      clientBOS.write(replyCodec.encode(reply).getOrElse(???).toByteArray)
      clientBOS.flush()

    val bv = clientBIS.readBytes(8) ++ clientBIS
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
        val serverSocket =
          Socket(InetAddress.getByAddress(destIp.toArray), destPort)
        writeReply(ReplyCode.RequestGranted, destPort, destIp)
        tunnel(clientSocket, serverSocket, clientChannel, serverChannel)
        serverSocket.close()
      case Request.BindRequest(destPort, destIp, userId) =>
        println("unsupported bind request")
        writeReply(ReplyCode.RequestRejected91, destPort, destIp)

  def apply(): SocksProxy = new SocksProxy:
    def start(port: Int)(using IO, Ox): () => Unit =
      val serverSocket = ServerSocket(port)
      fork:
        forever:
          try
            val clientSocket = serverSocket.accept()
            fork:
              val clientChannel = Channel.rendezvous[ByteVector]
              val serverChannel = Channel.rendezvous[ByteVector]
              handleRequest(clientSocket, clientChannel, serverChannel)
              clientSocket.close()
          catch
            case NonFatal(e) =>
              println(e)
              throw e
      () => serverSocket.close()

  @main
  def runSocksProxy() =
    supervised:
      IO.unsafe:
        val stop = SocksProxy().start(1080)
        StdIn.readLine()
        stop()
