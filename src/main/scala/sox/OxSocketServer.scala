package sox

import java.io.*
import java.net.*
import ox.*
import ox.channels.*
import ox.IO
import scala.concurrent.duration.*
import scala.util.control.Breaks.*
import java.util.concurrent.atomic.AtomicBoolean

trait OxSocketServer:
  def sockets(port: Int, capacity: Int = 1000)(using
      Ox
  ): (Source[Socket], () => Unit)
  def channel(socket: Socket, capacity: Int = 10)(using
      IO,
      Ox
  ): Channel[Chunk[Byte]]

object OxSocketServer:
  def apply(): OxSocketServer = new OxSocketServer:
    def sockets(port: Int, capacity: Int = 1000)(using
        Ox
    ): (Source[Socket], () => Unit) =
      val sockets = Channel.buffered[Socket](capacity)
      val serverSocket = ServerSocket(port)
      fork:
        try
          while true do
            val socket = serverSocket.accept()
            sockets.send(socket)
        catch case _: SocketException => ()
      (
        sockets,
        () =>
          serverSocket.close()
          sockets.done()
      )
    def channel(socket: Socket, capacity: Int = 10)(using
        IO,
        Ox
    ): Channel[Chunk[Byte]] =
      // kludge
      val channel =
        Source
          .fromInputStream(socket.getInputStream())
          .asInstanceOf[Channel[Chunk[Byte]]]
      fork(channel.toOutputStream(socket.getOutputStream()))
      channel

  def serve(port: Int): Unit =
    supervised:
      val serverSocket = ServerSocket(port, 10000)
      fork:
        println(s"starting socket server on port $port...")
        forever:
          supervised:
            val socket = useCloseableInScope(serverSocket.accept())
            fork:
              val request = BufferedReader(
                InputStreamReader(socket.getInputStream())
              ).readLine()
              val writer =
                BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
              writer.write(request)
              writer.flush()
            .join()
      .join()

  @main
  def runOxSocketServer() = serve(8080)

  @main
  def runOxSocketServerSockets() =
    supervised:
      val socketServer = OxSocketServer()
      val (sockets, stop) = socketServer.sockets(8080)
      fork:
        println("server started for 10 seconds")
        sleep(10.seconds)
        stop()
        println("server stopped")
      fork:
        breakable:
          forever:
            sockets.receiveOrClosed() match
              case _: ChannelClosed => break
              case socket: Socket =>
                IO.unsafe:
                  val channel = socketServer.channel(socket)
                  val bs = channel.receive()
                  channel.send(bs)
                  channel.done()
      .join()
