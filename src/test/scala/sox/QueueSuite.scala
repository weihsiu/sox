package sox

import ox.*
import ox.channels.*
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class QueueSuite extends munit.FunSuite:
  test("one sender one receiver"):
    supervised:
      val queue = Queue[String]
      val sender = Channel.rendezvous[String]
      val receiver = Channel.rendezvous[String]
      queue.addSender(sender)
      queue.addReceiver(receiver)
      sender.send("hello")
      assertEquals(receiver.receive(), "hello")
      queue.removeSender(sender)
      queue.removeReceiver(receiver)
      queue.close

  test("one sender multiple receivers"):
    supervised:
      val queue = Queue[String]
      val sender = Channel.rendezvous[String]
      val receivers = List.fill(10)(Channel.rendezvous[String])
      queue.addSender(sender)
      receivers.foreach(queue.addReceiver)
      val counter = AtomicInteger(0)
      val cancels = receivers.map(receiver =>
        forkCancellable:
          forever:
            assertEquals(receiver.receive(), "hello")
            counter.incrementAndGet()
      )
      (1 to 10).foreach(_ => sender.send("hello"))
      sleep(1.second)
      assertEquals(counter.get(), 10)
      cancels.foreach(_.cancel())
      queue.close()
