package sox

import SocksProxy.*
import scodec.bits.*

class SocksProxySuite extends munit.FunSuite:
  test("requestCodec"):
    val connectRequest: Request =
      Request.ConnectRequest(8080, ByteVector(127, 0, 0, 1), "walter")
    val result =
      requestCodec.decode(requestCodec.encode(connectRequest).getOrElse(???))
    assertEquals(result.getOrElse(???).value, connectRequest)

  test("replyCodec"):
    val connectReply =
      Reply(ReplyCode.RequestGranted, 8080, ByteVector(127, 0, 0, 1))
    val result =
      replyCodec.decode(replyCodec.encode(connectReply).getOrElse(???))
    assertEquals(result.getOrElse(???).value, connectReply)
