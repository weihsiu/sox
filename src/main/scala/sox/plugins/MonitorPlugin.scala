package sox.plugins

import ox.*
import ox.channels.*
import scodec.bits.ByteVector
import sttp.tapir.*
import sttp.tapir.CodecFormat.*
import sttp.tapir.json.circe.*
import sttp.tapir.generic.auto.*
import io.circe.generic.auto.*
import sox.*
import sttp.tapir.server.netty.sync.{NettySyncServer, OxStreams}
import sttp.tapir.server.netty.NettyConfig
import com.typesafe.scalalogging.Logger
import java.util.concurrent.atomic.AtomicReference
import java.net.InetSocketAddress
import sttp.ws.WebSocketFrame
import scala.concurrent.duration.*
import java.util.Base64

enum MonitorData:
  case UpstreamData(from: String, to: String, data: String)
  case DownstreamData(from: String, to: String, data: String)

class MonitorPlugin(port: Int)(using Ox) extends ProxyPlugin:
  val logger = Logger[MonitorPlugin]

  case class MonitorSpec(from: String, to: String, sink: Sink[MonitorData])

  val monitorSpecsAR = AtomicReference(List.empty[MonitorSpec])

  def processor(from: String, to: String): OxStreams.Pipe[String, MonitorData] = in =>
    val out = Channel.rendezvous[MonitorData]
    val spec = MonitorSpec(from, to, out)
    monitorSpecsAR.updateAndGet(spec :: _)
    fork:
      in.drain()
      monitorSpecsAR.updateAndGet(_.filterNot(_ == spec))
      out.doneOrClosed()
    out

  def fromAddress(addresses: FromToAddresses): String =
    addresses.fromAddress.getAddress().getHostAddress()

  def toAddress(addresses: FromToAddresses): String =
    addresses.toAddress.getAddress().getHostAddress() + ":" + addresses.toAddress.getPort()

  def matchMonitorSpecs(addresses: FromToAddresses): List[MonitorSpec] =
    monitorSpecsAR.get().filter(spec => spec.from == fromAddress(addresses) && spec.to == toAddress(addresses))

  def streamProcessor(
      monitorData: (String, String, String) => MonitorData
  ): FromToAddresses => Option[Source[ByteVector] => Unit] = addrs =>
    val specs = matchMonitorSpecs(addrs)
    if specs.isEmpty then None
    else
      Some: source =>
        fork:
          repeatWhile:
            source.receiveOrClosed() match
              case _: ChannelClosed => false
              case bv: ByteVector =>
                val data = monitorData(fromAddress(addrs), toAddress(addrs), bv.toBase64)
                specs.foreach(_.sink.send(data))
                true

  def start(): () => Unit =
    val wsEndpoint =
      endpoint.get
        .in("monitor")
        .in(query[String]("from") and query[String]("to"))
        .out(webSocketBody[String, TextPlain, MonitorData, Json](OxStreams))
    val wsServerEndpoint = wsEndpoint.handleSuccess(processor)
    val binding = NettySyncServer().port(port).addEndpoint(wsServerEndpoint).start()
    binding.stop

  def upstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit] =
    streamProcessor(MonitorData.UpstreamData.apply)

  def downstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit] =
    streamProcessor(MonitorData.DownstreamData.apply)
