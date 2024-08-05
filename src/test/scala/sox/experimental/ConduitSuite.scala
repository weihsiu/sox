package sox.experimental

import ox.*
import ox.channels.*

class ConduitSuite extends munit.FunSuite:
  test("topic"):
    supervised:
      val topic = Topic[String]
      val receivers = List.fill(10)(Channel.bufferedDefault[String])
      receivers.foreach(topic.addReceiver)
      val f = fork:
        receivers.foreach(_.receive().pipe(println))
      topic.send("hello")
      f.join()
