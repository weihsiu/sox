package sox

import java.io.InputStream
import scala.collection.mutable
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
