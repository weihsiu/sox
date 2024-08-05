package sox

import java.util.concurrent.atomic.AtomicReference
import java.net.InetSocketAddress
import ox.*
import ox.channels.*
import scala.concurrent.duration.*
import scodec.bits.ByteVector
import sox.SocksProxy.*
import sox.experimental.*

object EventConfig:
  enum ProxyEvent:
    case UpstreamConnectEvent(addresses: FromToAddresses, upstreamSource: () => Source[ByteVector])
    case UpstreamDisconnectEvent(addresses: FromToAddresses)
    case DownstreamConnectEvent(addresses: FromToAddresses, downstreamSource: () => Source[ByteVector])
    case DownstreamDisconnectEvent(addresses: FromToAddresses)

  def apply(eventSink: Sink[ProxyEvent])(using Ox)(addresses: FromToAddresses): ProxyConfig =
    transformingConfig(
      () =>
        val topic = Topic[ByteVector]
        eventSink.send(
          ProxyEvent.UpstreamConnectEvent(
            addresses,
            () =>
              val sink = Channel.rendezvous[ByteVector]
              topic.addReceiver(sink)
              sink
          )
        )
        topic
      ,
      (topic, bv) =>
        topic.send(bv)
        (topic, bv)
      ,
      topic =>
        eventSink.send(ProxyEvent.UpstreamDisconnectEvent(addresses))
        topic.doneOrClosed()
        None
      ,
      () =>
        val topic = Topic[ByteVector]
        eventSink.send(
          ProxyEvent.DownstreamConnectEvent(
            addresses,
            () =>
              val sink = Channel.rendezvous[ByteVector]
              topic.addReceiver(sink)
              sink
          )
        )
        topic
      ,
      (topic, bv) =>
        topic.send(bv)
        (topic, bv)
      ,
      topic =>
        eventSink.send(ProxyEvent.DownstreamDisconnectEvent(addresses))
        topic.doneOrClosed()
        None
    )(addresses)
