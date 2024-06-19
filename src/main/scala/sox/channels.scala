package sox

import ox.*
import ox.channels.*

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

abstract class Conduit[A](
    processFunc: (ArrayBuffer[Source[A]], ArrayBuffer[Sink[A]]) => Unit
)(using
    Ox
) extends Closeable:
  private class ConduitActor(
      processFunc: (ArrayBuffer[Source[A]], ArrayBuffer[Sink[A]]) => Unit
  ):
    var senders: ArrayBuffer[Source[A]] = ArrayBuffer.empty
    var receivers: ArrayBuffer[Sink[A]] = ArrayBuffer.empty
    def startProcess() = forkCancellable(
      forever(processFunc(senders, receivers))
    )
    var process = startProcess()
    def restartProcess() =
      process.cancel()
      process = startProcess()
    def addSender(sender: Source[A]): Unit =
      senders += sender
      restartProcess()
    def addReceiver(receiver: Sink[A]): Unit =
      receivers += receiver
      restartProcess()
    def removeSender(sender: Source[A]): Unit =
      senders -= sender
      restartProcess()
    def removeReceiver(receiver: Sink[A]): Unit =
      receivers -= receiver
      restartProcess()
    def close(): Unit =
      process.cancel()

  private val conduitActor = Actor.create(ConduitActor(processFunc))

  def addSender(sender: Source[A]): Unit = conduitActor.ask(_.addSender(sender))
  def addReceiver(receiver: Sink[A]): Unit =
    conduitActor.ask(_.addReceiver(receiver))
  def removeSender(sender: Source[A]): Unit =
    conduitActor.ask(_.removeSender(sender))
  def removeReceiver(receiver: Sink[A]): Unit =
    conduitActor.ask(_.removeReceiver(receiver))
  def close(): Unit = conduitActor.ask(_.close())

class Topic[A](using Ox)
    extends Conduit[A]((senders, receivers) =>
      if senders.isEmpty then sleep(10.seconds)
      else
        val value = select(senders.map(_.receiveClause).toList)
        receivers.foreach(recv => fork(recv.send(value.value)))
    )

class Queue[A](using Ox)
    extends Conduit[A]({
      var index = -1
      (senders, receivers) =>
        if senders.isEmpty then sleep(10.seconds)
        else
          val value = select(senders.map(_.receiveClause).toList)
          if receivers.nonEmpty then
            index += 1
            if index >= receivers.length then index = 0
            fork(receivers(index).send(value.value))
    })
