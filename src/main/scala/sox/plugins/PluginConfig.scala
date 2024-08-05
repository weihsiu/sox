package sox.plugins

import ox.*
import ox.channels.*
import sox.{FromToAddresses, ProxyConfig, EventConfig}
import sox.experimental.*
import sox.EventConfig.ProxyEvent

class PluginConfig(using Ox):
  val eventTopic = Topic[ProxyEvent]

  def proxyConfig(addresses: FromToAddresses): ProxyConfig = EventConfig(eventTopic)(addresses)

  def startPlugin(plugin: ProxyPlugin): () => Unit =
    val upstreamProcessor = plugin.upstreamProcessor()
    val downstreamProcessor = plugin.downstreamProcessor()
    val pluginChannel = Channel.rendezvous[ProxyEvent]
    eventTopic.addReceiver(pluginChannel)
    val process = fork:
      repeatWhile:
        pluginChannel.receiveOrClosed() match
          case _: ChannelClosed => false
          case ProxyEvent.UpstreamConnectEvent(addrs, source) =>
            upstreamProcessor(addrs).foreach(_(source()))
            true
          case ProxyEvent.DownstreamConnectEvent(addrs, source) =>
            downstreamProcessor(addrs).foreach(_(source()))
            true
          case _ => true
    val stop = plugin.start()
    () =>
      stop()
      eventTopic.removeReceiver(pluginChannel)
      pluginChannel.doneOrClosed()
      process.join()
