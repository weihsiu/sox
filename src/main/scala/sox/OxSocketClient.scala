package sox

import java.io.*
import java.net.*
import ox.*
import scala.util.Try

object OxSocketClient:
  def connect(host: String, port: Int): Unit =
    supervised:
      val socket = useCloseableInScope(Socket(host, port))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
      writer.write("hello\n")
      writer.flush()
      val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
      val response = reader.readLine()
      println(s"response = $response")
      // assert(response == "HELLO")
      socket.close()

  @main
  def runOxSocketClient() =
    System.setProperty("socksProxyHost", "localhost")
    System.setProperty("socksProxyPort", "1080")
    System.setProperty("socksProxyVersion", "4")
    // to include localhost, etc.
    System.setProperty("socksNonProxyHosts", "")
    val n = 10
    supervised:
      val result = forkAll((1 to n).map(_ => () => connect("localhost", 8080)))
        // fork:
        //   connect("localhost", 8080)
        .join()
      println(s"result length = ${result.length}")
      assert(result.length == n)
