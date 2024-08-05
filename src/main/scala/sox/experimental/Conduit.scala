package sox.experimental

import com.softwaremill.jox.{Channel => JChannel}
import ox.*
import ox.channels.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

abstract class Conduit[A](sendFunc: (A, List[Sink[A]]) => Unit)(using Ox) extends Sink[A]:
  private val receivers = AtomicReference(List.empty[Sink[A]])
  private val jChannel: JChannel[Any] = JChannel(0)
  private val source = new Source[A]:
    protected val delegate = jChannel
  protected val delegate = jChannel

  fork:
    repeatWhile:
      source.receiveOrClosed() match
        case ChannelClosed.Done =>
          receivers.get().foreach(_.doneOrClosed())
          false
        case ChannelClosed.Error(e) =>
          receivers.get().foreach(_.errorOrClosed(e))
          false
        case x =>
          sendFunc(x.asInstanceOf[A], receivers.get())
          true

  def addReceiver(receiver: Sink[A]): Unit = receivers.updateAndGet(receiver :: _)

  def removeReceiver(receiver: Sink[A]): Unit = receivers.updateAndGet(_.filterNot(_ == receiver))

class Topic[A](using Ox) extends Conduit[A]((x, receivers) => receivers.foreach(_.sendOrClosed(x)))
