package sox.plugins

import ox.*
import ox.channels.*
import sox.EventConfig.ProxyEvent
import sox.FromToAddresses
import scodec.bits.ByteVector
import com.typesafe.scalalogging.Logger

trait ProxyPlugin(using Ox):
  def start(): () => Unit
  def upstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit]
  def downstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit]

object ProxyPlugin:
  val logger = Logger[ProxyPlugin.type]
  def loggingPlugin(using Ox): ProxyPlugin = new ProxyPlugin:
    def start(): () => Unit =
      logger.info("proxy started")
      () => logger.info("proxy stopped")
    def upstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit] = addresses =>
      Some(source =>
        fork:
          repeatWhile:
            source.receiveOrClosed() match
              case ChannelClosed.Done =>
                logger.info(s"${addresses.fromAddress} -> ${addresses.toAddress} upstream done")
                false
              case ChannelClosed.Error(e) =>
                logger.info(s"${addresses.fromAddress} -> ${addresses.toAddress} upstream error $e")
                false
              case bv =>
                logger.info(s"${addresses.fromAddress} -> ${addresses.toAddress} upstream receives $bv")
                true
      )

    def downstreamProcessor(): FromToAddresses => Option[Source[ByteVector] => Unit] = addresses =>
      Some(source =>
        fork:
          repeatWhile:
            source.receiveOrClosed() match
              case ChannelClosed.Done =>
                logger.info(s"${addresses.fromAddress} <- ${addresses.toAddress} downstream done")
                false
              case ChannelClosed.Error(e) =>
                logger.info(s"${addresses.fromAddress} <- ${addresses.toAddress} downstream error $e")
                false
              case bv =>
                logger.info(s"${addresses.fromAddress} <- ${addresses.toAddress} downstream receives $bv")
                true
      )
