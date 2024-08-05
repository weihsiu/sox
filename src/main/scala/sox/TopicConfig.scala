package sox

import java.util.concurrent.atomic.AtomicReference
import java.net.InetSocketAddress
import ox.*
import ox.channels.*
import scodec.bits.ByteVector
import sox.SocksProxy.*

object TopicConfig:
  type TopicsAR = AtomicReference[Map[InetSocketAddress, Map[InetSocketAddress, Topics]]]

  enum ProxyEvent:
    case ConnectEvent(addresses: FromToAddresses, topiics: Topics)
    case DisconnectEvent(addresses: FromToAddresses)

  def addTopics(topicsAR: TopicsAR, addrs: FromToAddresses, topics: Topics): Unit =
    topicsAR.updateAndGet(_.updatedWith(addrs.toAddress):
      case None     => Some(Map(addrs.fromAddress -> topics))
      case Some(ts) => Some(ts.updatedWith(addrs.fromAddress)(_.orElse(Some(topics))))
    )

  def removeTopics(topicsAR: TopicsAR, addrs: FromToAddresses): Unit =
    topicsAR.updateAndGet(_.updatedWith(addrs.toAddress):
      _.flatMap(ts =>
        val ts2 = ts - addrs.fromAddress
        if ts2.isEmpty then None else Some(ts2)
      )
    )

  def apply(topicsAR: TopicsAR, eventSink: Sink[ProxyEvent])(using Ox)(addresses: FromToAddresses): ProxyConfig =
    topicsAR.updateAndGet(tss => if tss == null then Map.empty else tss)
    val upstreamTopic = Topic[ByteVector]
    val downstreamTopic = Topic[ByteVector]
    val upstreamChannel = Channel.rendezvous[ByteVector]
    val downstreamChannel = Channel.rendezvous[ByteVector]
    upstreamTopic.addSender(upstreamChannel)
    downstreamTopic.addSender(downstreamChannel)
    val topics = Topics(upstreamTopic, downstreamTopic)
    transformingConfig(
      () =>
        addTopics(topicsAR, addresses, topics)
        eventSink.send(ProxyEvent.ConnectEvent(addresses, topics))
      ,
      (_, bv) =>
        upstreamChannel.send(bv)
        ((), bv)
      ,
      _ =>
        removeTopics(topicsAR, addresses)
        eventSink.send(ProxyEvent.DisconnectEvent(addresses))
        upstreamChannel.done()
        upstreamTopic.close()
        println(s"upstream cleaned up ${topicsAR.get()}")
        None
      ,
      () => addTopics(topicsAR, addresses, topics),
      (_, bv) =>
        downstreamChannel.send(bv)
        ((), bv)
      ,
      _ =>
        removeTopics(topicsAR, addresses)
        downstreamChannel.done()
        downstreamTopic.close()
        println(s"downstream cleaned up ${topicsAR.get()}")
        None
    )(addresses)
