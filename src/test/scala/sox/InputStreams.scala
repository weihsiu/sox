package sox

import java.io.InputStream

object InputStreams:
  // mockBytes is a list of byte arrays where each element is a byte array that can be read in full.
  // available() will return the number of bytes in the array.
  def mockInputStream(mockBytes: List[Array[Byte]]): InputStream =
    new InputStream:
      var bss = mockBytes
      override def available(): Int = bss.headOption.fold(0)(_.length)
      override def close(): Unit = bss = List.empty
      override def read(): Int =
        bss = bss.dropWhile(_.isEmpty)
        if bss.isEmpty then -1
        else
          val (bs1, bs2) = bss.head.splitAt(1)
          bss = if bs2.isEmpty then bss.tail else bs2 :: bss.tail
          bs1.head
