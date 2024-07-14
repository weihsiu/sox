package sox

import eu.monniot.scala3mock.ScalaMocks
import java.io.*
import scodec.bits.ByteVector

class IOsSuite extends munit.FunSuite with ScalaMocks:
  test("readAvailable() - empty InputStream"):
    val inputStream = InputStreams.mockInputStream(List.empty)
    assertEquals(inputStream.readAvailable(), None)

  test("readAvailable() - InputStream with 1 byte"):
    val inputStream = InputStreams.mockInputStream(List(Array(1)))
    assertEquals(
      inputStream.readAvailable().get.toSeq,
      Seq[Byte](1)
    )
    assertEquals(inputStream.readAvailable(), None)

  test("readAvailable() - InputStream with some bytes"):
    val inputStream = InputStreams.mockInputStream(List(Array(1, 2, 3)))
    assertEquals(
      inputStream.readAvailable().get.toSeq,
      Seq[Byte](1, 2, 3)
    )
    assertEquals(inputStream.readAvailable(), None)

  test("readAvailable() - InputStream with some bytes delayed"):
    val inputStream =
      InputStreams.mockInputStream(
        List(Array(1), Array.empty, Array(2, 3), Array.empty, Array(4))
      )
    assertEquals(
      inputStream.readAvailable().get.toSeq,
      Seq[Byte](1)
    )
    assertEquals(
      inputStream.readAvailable().get.toSeq,
      Seq[Byte](2, 3)
    )
    assertEquals(
      inputStream.readAvailable().get.toSeq,
      Seq[Byte](4)
    )
    assertEquals(inputStream.readAvailable(), None)

  // test("readAvailable() - empty InputStream"):
  //   withExpectations():
  //     val inputStream = mock[InputStream]
  //     when(() => inputStream.available()).expects().returning(0)
  //     when(() => inputStream.read()).expects().returning(-1)
  //     assertEquals(inputStream.readAvailable(), None)

  // test("readAvailable() - InputStream with some bytes"):
  //   withExpectations():
  //     val inputStream = mock[InputStream]
  //     when(() => inputStream.available()).expects().returning(3)
  //     when(bs => inputStream.read(bs))
  //       .expects(where((_: Array[Byte]).length == 3))
  //       .onCall(bs =>
  //         (0 to 2).foreach(i => bs(i) = (i + 1).toByte)
  //         bs.length
  //       )
  //     assertEquals(
  //       inputStream.readAvailable().get.toSeq,
  //       Seq[Byte](1, 2, 3)
  //     )

  // test("readAvailable() - InputStream with some bytes delayed"):
  //   withExpectations():
  //     val inputStream = mock[InputStream]
  //     when(() => inputStream.available()).expects().returning(0)
  //     when(() => inputStream.read()).expects().returning(1)
  //     when(() => inputStream.available()).expects().returning(2)
  //     when(bs => inputStream.read(bs))
  //       .expects(where((_: Array[Byte]).length == 2))
  //       .onCall(bs =>
  //         (0 to 1).foreach(i => bs(i) = (i + 2).toByte)
  //         bs.length
  //       )
  //     assertEquals(
  //       inputStream.readAvailable().get.toSeq,
  //       Seq[Byte](1, 2, 3)
  //     )
