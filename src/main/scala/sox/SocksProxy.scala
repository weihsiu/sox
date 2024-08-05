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
import sox.TopicConfig.TopicsAR
import sox.EventConfig.ProxyEvent
import sox.plugins.*
import scala.util.Try
import com.typesafe.scalalogging.Logger

case class FromToAddresses(fromAddress: InetSocketAddress, toAddress: InetSocketAddress)

case class ProxyConfig(
    upstreamSink: Sink[ByteVector], // receives input from client
    upstreamSource: Source[ByteVector], // sends output to server
    downstreamSink: Sink[ByteVector], // receives input from server
    downstreamSource: Source[ByteVector] // sends output to client
)

trait SocksProxy:
  def start(port: Int, proxyConfig: FromToAddresses => ProxyConfig)(using IO, Ox): () => Unit

object SocksProxy:
  val logger = Logger[SocksProxy.type]

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
    .subcaseP[Request.ConnectRequest](0x0401) { case x: Request.ConnectRequest =>
      x
    }(
      bodyCodec.as[Request.ConnectRequest]
    )
    .subcaseP[Request.BindRequest](0x0402) { case x: Request.BindRequest => x }(
      bodyCodec.as[Request.BindRequest]
    )

  val replyCodec =
    (constant[Byte](0) :: replyCodeCodec :: uint16 :: bytes(4)).as[Reply]

  case class Connection(clientAddress: InetSocketAddress, serverAddress: InetSocketAddress)

  case class Sources(fromClient: Source[ByteVector], fromServer: Source[ByteVector])

  def forward(inputStream: InputStream, outputStream: OutputStream, sink: Sink[ByteVector], source: Source[ByteVector])(
      using
      IO,
      Ox
  ): Unit =
    par(
      sink.fromInputStreamBV(BufferedInputStream(inputStream)),
      source.toOutputStreamBV(BufferedOutputStream(outputStream))
    )

  def tunnel(clientSocket: Socket, serverSocket: Socket, proxyConfig: ProxyConfig)(using IO, Ox): Unit =
    par(
      forward(
        clientSocket.getInputStream(),
        serverSocket.getOutputStream(),
        proxyConfig.upstreamSink,
        proxyConfig.upstreamSource
      ),
      forward(
        serverSocket.getInputStream(),
        clientSocket.getOutputStream(),
        proxyConfig.downstreamSink,
        proxyConfig.downstreamSource
      )
    )

  def handleRequest(clientSocket: Socket, generateProxyConfig: FromToAddresses => ProxyConfig)(using IO, Ox): Unit =
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
        tunnel(
          clientSocket,
          serverSocket,
          generateProxyConfig(
            FromToAddresses(
              clientSocket
                .getRemoteSocketAddress()
                .asInstanceOf[InetSocketAddress],
              serverSocket
                .getRemoteSocketAddress()
                .asInstanceOf[InetSocketAddress]
            )
          )
        )
        serverSocket.close()
      case Request.BindRequest(destPort, destIp, userId) =>
        println("unsupported bind request")
        writeReply(ReplyCode.RequestRejected91, destPort, destIp)

  def apply(): SocksProxy = new SocksProxy:
    def start(port: Int, generateProxyConfig: FromToAddresses => ProxyConfig)(using IO, Ox): () => Unit =
      val serverSocket = ServerSocket(port)
      fork:
        repeatWhile:
          Try(serverSocket.accept()).fold(
            _ match
              case _: SocketException if serverSocket.isClosed() => false
              case e                                             => throw e
            ,
            clientSocket =>
              fork:
                handleRequest(clientSocket, generateProxyConfig)
                clientSocket.close()
              true
          )
      () => serverSocket.close()

  def forwardingConfig(addresses: FromToAddresses): ProxyConfig =
    val ch1 = Channel.rendezvous[ByteVector]
    val ch2 = Channel.rendezvous[ByteVector]
    ProxyConfig(ch1, ch1, ch2, ch2)

  def transformingConfig[A, B](
      upstreamInitial: () => A,
      upstreamTransform: (A, ByteVector) => (A, ByteVector),
      upstreamComplete: A => Option[ByteVector],
      downstreamInitial: () => B,
      downstreamTransform: (B, ByteVector) => (B, ByteVector),
      downstreamComplete: B => Option[ByteVector]
  )(using Ox)(addresses: FromToAddresses): ProxyConfig =
    val ch1 = Channel.rendezvous[ByteVector]
    val upstreamSource = ch1.mapStateful(upstreamInitial)(upstreamTransform, upstreamComplete)
    val ch2 = Channel.rendezvous[ByteVector]
    val downstreamSource = ch2.mapStateful(downstreamInitial)(downstreamTransform, downstreamComplete)
    ProxyConfig(ch1, upstreamSource, ch2, downstreamSource)

  def loggingConfig(using Ox)(addresses: FromToAddresses): ProxyConfig =
    transformingConfig(
      () => (),
      (_, bv) =>
        logger.info(s"${addresses.fromAddress} -> $bv")
        ((), bv)
      ,
      _ =>
        logger.info("upstream completes")
        None
      ,
      () => (),
      (_, bv) =>
        logger.info(s"${addresses.toAddress} <- $bv")
        ((), bv)
      ,
      _ =>
        logger.info("downstream completes")
        None
    )(addresses)

  case class Topics(upstreamTopic: Topic[ByteVector], downstreamTopic: Topic[ByteVector])

  @main
  def runSocksProxy() =
    supervised:
      IO.unsafe:
        // val stop = SocksProxy().start(1080, forwardingConfig)

        // val stop = SocksProxy().start(1080, loggingConfig)

        // val topicsAR: TopicsAR = AtomicReference()
        // val eventChannel = Channel.bufferedDefault[TopicConfig.ProxyEvent]
        // fork(forever(eventChannel.receive().pipe(println)))
        // val stop = SocksProxy().start(1080, TopicConfig(topicsAR, eventChannel))

        // val eventChannel = Channel.bufferedDefault[EventConfig.ProxyEvent]
        // fork(forever(eventChannel.receive() match
        //   case ProxyEvent.UpstreamConnectEvent(addresses, upstreamSource) =>
        //     println(s"upstream connect $addresses")
        //     fork(upstreamSource().drain())
        //   case ProxyEvent.DownstreamConnectEvent(addresses, downstreamSource) =>
        //     println(s"downstream connect $addresses")
        //     fork(downstreamSource().drain())
        //   case _ => ()
        // ))
        // val stop = SocksProxy().start(1080, EventConfig(eventChannel))

        val pluginConfig = PluginConfig()
        val stopLoggingPlugin = pluginConfig.startPlugin(ProxyPlugin.loggingPlugin)
        val stopWSServerPlugin = pluginConfig.startPlugin(MonitorPlugin(9090))
        val stopSocksProxy = SocksProxy().start(1080, pluginConfig.proxyConfig)

        StdIn.readLine()
        stopWSServerPlugin()
        stopLoggingPlugin()
        stopSocksProxy()
