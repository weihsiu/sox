package sox

import java.io.InputStream
import scala.collection.mutable
import scala.annotation.tailrec
import scodec.bits.ByteVector

extension (inputStream: InputStream)
  def readBytes(len: Int): ByteVector =
    val bs = Array.ofDim[Byte](len)
    var b: Int = -1
    var i = 0
    while
      b = inputStream.read()
      b != -1 && i < len
    do
      bs(i) = b.toByte
      i += 1
    ByteVector.view(bs)

  def readTillNull(maxLen: Int): Option[ByteVector] =
    val ab = mutable.ArrayBuffer[Byte]()
    var b: Int = -1
    while
      if ab.length >= maxLen then None
      b = inputStream.read()
      b != -1 && b != 0
    do ab += b.toByte
    ab += 0
    Some(ByteVector(ab))

  // def readAvailable(): Option[ByteVector] =
  //   val bs = Array.ofDim[Byte](1024)
  //   val len = inputStream.read(bs)
  //   if len == -1 then None else Some(ByteVector.view(bs, 0, len))

  // Reads available bytes from InputStream, blocks if no input is available.  Returns None if InputStream is closed.
  def readAvailable(): Option[ByteVector] =
    @tailrec
    def loop(initial: ByteVector): Option[ByteVector] =
      val len = inputStream.available()
      if len > 0 then
        val bs = Array.ofDim[Byte](len)
        inputStream.read(bs)
        Some(initial ++ ByteVector.view(bs))
      else if !initial.isEmpty then Some(initial)
      else
        val byte = inputStream.read()
        if byte == -1 then None
        else loop(ByteVector(byte))
    loop(ByteVector.empty)
