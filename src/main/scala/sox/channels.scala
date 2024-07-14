package sox

import java.io.*
import java.net.SocketException
import ox.*
import ox.channels.*
import scala.util.boundary
import scodec.bits.*
import scala.util.control.NonFatal

extension (source: Source[ByteVector])
  private inline def close(
      closeable: Closeable,
      cause: Option[Throwable] = None
  )(using IO): Unit =
    try closeable.close()
    catch
      case NonFatal(e) =>
        cause.foreach(_.addSuppressed(e))
        throw cause.getOrElse(e)

  def toOutputStreamBV(outputStream: OutputStream)(using IO): Unit =
    repeatWhile:
      source.receiveOrClosed() match
        case ChannelClosed.Done =>
          close(outputStream)
          false
        case ChannelClosed.Error(e) =>
          close(outputStream, Some(e))
          throw e
        case bv: ByteVector =>
          try
            outputStream.write(bv.toArray)
            outputStream.flush()
            true
          catch
            case NonFatal(e) =>
              close(outputStream, Some(e))
              throw e

extension (sink: Sink[ByteVector])
  def fromInputStreamBV(inputStream: InputStream)(using IO): Unit =
    repeatWhile:
      try
        inputStream.readAvailable() match
          case None =>
            sink.done()
            false
          case Some(bv) =>
            sink.send(bv)
            true
      catch
        case NonFatal(e) =>
          if e.isInstanceOf[SocketException] then
            sink.doneOrClosed()
            false
          else
            sink.error(e)
            throw e
