package sox

import java.io.*
import java.net.*
import ox.*
import ox.either.*
import scala.collection.mutable
import scodec.*
import scodec.bits.*
import scodec.codecs.{either as _, *}
import scodec.Attempt.Failure
import scodec.Attempt.Successful
import scala.io.StdIn

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

  def readBytes(inputStream: InputStream, len: Int): ByteVector =
    val bs = Array.ofDim[Byte](len)
    var b: Int = -1
    var i = 0
    while
      b = inputStream.read()
      b != -1 && i < len
    do
      bs(i) = b.toByte
      i += 1
    ByteVector.view(bs)

  def readTillNull(inputStream: InputStream, maxLen: Int): Option[ByteVector] =
    val ab = mutable.ArrayBuffer[Byte]()
    var b: Int = -1
    while
      if ab.length >= maxLen then None
      b = inputStream.read()
      b != -1 && b != 0
    do ab += b.toByte
    ab += 0
    Some(ByteVector(ab))

  def handleRequest(inSocket: Socket)(using Ox): Unit =
    val inInput = BufferedInputStream(inSocket.getInputStream())
    val inOutput = BufferedOutputStream(inSocket.getOutputStream())

    def writeReply(code: ReplyCode, destPort: Int, destIp: ByteVector): Unit =
      val reply = Reply(code, destPort, destIp)
      inOutput.write(replyCodec.encode(reply).getOrElse(???).toByteArray)
      inOutput.flush()

    def tunnel(socket1: Socket, socket2: Socket)(using Ox): Unit =
      def forward(
          socket: Socket,
          in: BufferedInputStream,
          out: BufferedOutputStream
      ): Unit =
        val bs = Array.ofDim[Byte](2048)
        var len = -1
        while
          len = in.read(bs)
          len != -1
        do
          out.write(bs, 0, len)
          out.flush()
        socket.shutdownOutput()

      val in1 = BufferedInputStream(socket1.getInputStream())
      val out1 = BufferedOutputStream(socket1.getOutputStream())
      val in2 = BufferedInputStream(socket2.getInputStream())
      val out2 = BufferedOutputStream(socket2.getOutputStream())
      fork:
        val f1 = fork(forward(socket2, in1, out2))
        val f2 = fork(forward(socket1, in2, out1))
        f1.join()
        f2.join()
        socket1.close()
        socket2.close()

    val bv = readBytes(inInput, 8) ++ readTillNull(inInput, 100).getOrElse(
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
        tunnel(inSocket, outSocket)
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
            handleRequest(inSocket)
      () => serverSocket.close()

  @main
  def runSocksProxy() =
    supervised:
      val stop = SocksProxy().start(1080)
      StdIn.readLine()
