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

case class ProxyConfig(
    clientSink: Sink[ByteVector], // receives input from client
    serverSource: Source[ByteVector], // sends output to server
    serverSink: Sink[ByteVector], // receives input from server
    clientSource: Source[ByteVector] // sends output to client
)

trait SocksProxy:
  def start(
      port: Int,
      proxyConfig: () => ProxyConfig
  )(using IO, Ox): () => Unit

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
      proxyConfig: ProxyConfig
  )(using IO, Ox): Unit =
    par(
      forward(
        clientSocket.getInputStream(),
        serverSocket.getOutputStream(),
        proxyConfig.clientSink,
        proxyConfig.serverSource
      ),
      forward(
        serverSocket.getInputStream(),
        clientSocket.getOutputStream(),
        proxyConfig.serverSink,
        proxyConfig.clientSource
      )
    )

  def handleRequest(clientSocket: Socket, proxyConfig: ProxyConfig)(using
      IO,
      Ox
  ): Unit =
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
        tunnel(clientSocket, serverSocket, proxyConfig)
        serverSocket.close()
      case Request.BindRequest(destPort, destIp, userId) =>
        println("unsupported bind request")
        writeReply(ReplyCode.RequestRejected91, destPort, destIp)

  def apply(): SocksProxy = new SocksProxy:
    def start(
        port: Int,
        proxyConfig: () => ProxyConfig
    )(using IO, Ox): () => Unit =
      val serverSocket = ServerSocket(port)
      fork:
        forever:
          try
            val clientSocket = serverSocket.accept()
            fork:
              handleRequest(clientSocket, proxyConfig())
              clientSocket.close()
          catch
            case NonFatal(e) =>
              println(e)
              throw e
      () => serverSocket.close()

  def forwardingConfig(): ProxyConfig =
    val ch1 = Channel.rendezvous[ByteVector]
    val ch2 = Channel.rendezvous[ByteVector]
    ProxyConfig(ch1, ch1, ch2, ch2)

  def transformingConfig[A, B](
      clientZero: A,
      clientTransform: (A, ByteVector) => (A, ByteVector),
      serverZero: B,
      serverTransform: (B, ByteVector) => (B, ByteVector)
  )(using Ox)(): ProxyConfig =
    val ch1 = Channel.rendezvous[ByteVector]
    val serverSource = ch1.mapStateful(() => clientZero)(clientTransform)
    val ch2 = Channel.rendezvous[ByteVector]
    val clientSource = ch2.mapStateful(() => serverZero)(serverTransform)
    ProxyConfig(ch1, serverSource, ch2, clientSource)

  @main
  def runSocksProxy() =
    supervised:
      IO.unsafe:
        // val stop = SocksProxy().start(1080, forwardingConfig)
        val stop = SocksProxy().start(
          1080,
          transformingConfig(
            (),
            (_, bv) =>
              println(
                s"sending ${utf8.decodeValue(bv.toBitVector).getOrElse(???)}"
              )
              ((), bv)
            ,
            (),
            (_, bv) =>
              println(
                s"receiving ${utf8.decodeValue(bv.toBitVector).getOrElse(???)}"
              )
              ((), utf8.encode("goodbye").getOrElse(???).toByteVector)
          )
        )
        StdIn.readLine()
        stop()
