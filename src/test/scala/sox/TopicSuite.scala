package sox

import ox.*
import ox.channels.*

class TopicSuite extends munit.FunSuite:
  test("one pub one sub"):
    supervised:
      val topic = Topic[String]
      val sender = Channel.rendezvous[String]
      val receiver = Channel.rendezvous[String]
      topic.addSender(sender)
      topic.addReceiver(receiver)
      sender.send("hello")
      assertEquals(receiver.receive(), "hello")
      topic.removeSender(sender)
      topic.removeReceiver(receiver)
      topic.close()

  test("one pub multiple subs"):
    supervised:
      val topic = Topic[String]
      val sender = Channel.rendezvous[String]
      val receivers = List.fill(10)(Channel.rendezvous[String])
      topic.addSender(sender)
      receivers.foreach(topic.addReceiver)
      sender.send("hello")
      receivers.foreach(receiver => assertEquals(receiver.receive(), "hello"))
      topic.removeSender(sender)
      receivers.foreach(topic.removeReceiver)
      topic.close()

  test("multiple pubs multiple subs"):
    supervised:
      val topic = Topic[String]
      val senders = List.fill(10)(Channel.rendezvous[String])
      val receivers = List.fill(10)(Channel.rendezvous[String])
      senders.foreach(topic.addSender)
      receivers.foreach(topic.addReceiver)
      senders.foreach(sender => sender.send("hello"))
      receivers.foreach(receiver =>
        (1 to 10).foreach(_ => assertEquals(receiver.receive(), "hello"))
      )
      topic.close()
