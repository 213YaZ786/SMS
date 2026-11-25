package com.yaz.sms.core.call

import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// Local JVM tests of the pure parts of WebCallMedia, as Android documents them
// (https://developer.android.com/training/testing/local-tests): the payload of the page's hash
// (calls-webapp's README, "Integrating", https://raw.githubusercontent.com/deltachat/calls-webapp/main/README.md:
// base64, then URL-encoded, "=" included, as the preload's btoa does,
// https://raw.githubusercontent.com/deltachat/deltachat-desktop/main/packages/target-electron/static/calls-webapp-preload.js#calls)
// and the page's messages (call.js, sendToHost: startCall, acceptCall, endCall, size, effect, state),
// with the hostile values the OWASP MASVS pass asks for (empty, wrong type, too large, unknown).
class WebCallMediaTest {

    @Test
    fun `a payload is base64 then url encoded with the equal sign`() {
        // "ab" in base64 is "YWI=", which ends with a padding "=".
        assertEquals("YWI%3D", Calls.btoa("ab"))
        val sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n"
        val encoded = Calls.btoa(sdp)
        assertFalse(encoded.contains('='))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        val back = String(Base64.getDecoder().decode(URLDecoder.decode(encoded, "UTF-8")))
        assertEquals(sdp, back)
    }

    @Test
    fun `an empty payload still encodes`() {
        assertEquals("", Calls.btoa(""))
    }

    @Test
    fun `startCall and acceptCall carry their sdp`() {
        assertEquals(PageMessage("startCall", "v=0"), PageMessage.parse("""{"type":"startCall","payload":"v=0"}"""))
        assertEquals(PageMessage("acceptCall", "v=0"), PageMessage.parse("""{"type":"acceptCall","payload":"v=0"}"""))
        assertNull(PageMessage.parse("""{"type":"startCall"}"""))
        assertNull(PageMessage.parse("""{"type":"startCall","payload":""}"""))
        assertNull(PageMessage.parse("""{"type":"acceptCall","payload":5}"""))
    }

    @Test
    fun `endCall needs nothing else`() {
        assertEquals(PageMessage("endCall"), PageMessage.parse("""{"type":"endCall"}"""))
    }

    @Test
    fun `size is two positive numbers`() {
        assertEquals(PageMessage("size", null, 640, 480), PageMessage.parse("""{"type":"size","width":640,"height":480}"""))
        assertNull(PageMessage.parse("""{"type":"size","width":0,"height":480}"""))
        assertNull(PageMessage.parse("""{"type":"size","width":-1,"height":480}"""))
        assertNull(PageMessage.parse("""{"type":"size","width":99999,"height":480}"""))
        assertNull(PageMessage.parse("""{"type":"size","width":"640","height":480}"""))
        assertNull(PageMessage.parse("""{"type":"size","width":640}"""))
    }

    @Test
    fun `an effect is a short name`() {
        assertEquals(PageMessage("effect", "FIREWORKS"), PageMessage.parse("""{"type":"effect","name":"FIREWORKS"}"""))
        assertNull(PageMessage.parse("""{"type":"effect","name":""}"""))
        assertNull(PageMessage.parse("""{"type":"effect","name":"${"A".repeat(33)}"}"""))
        assertNull(PageMessage.parse("""{"type":"effect","name":7}"""))
    }

    @Test
    fun `a state is one of the connection's`() {
        assertEquals(PageMessage("state", "connected"), PageMessage.parse("""{"type":"state","state":"connected"}"""))
        assertNull(PageMessage.parse("""{"type":"state","state":"exploded"}"""))
    }

    @Test
    fun `unknown and malformed messages are ignored`() {
        assertNull(PageMessage.parse(null))
        assertNull(PageMessage.parse(""))
        assertNull(PageMessage.parse("not json"))
        assertNull(PageMessage.parse("[]"))
        assertNull(PageMessage.parse("42"))
        assertNull(PageMessage.parse("{}"))
        assertNull(PageMessage.parse("""{"type":"reboot"}"""))
        assertNull(PageMessage.parse("""{"type":5}"""))
        assertNull(PageMessage.parse("""{"type":"startCall","payload":"${"x".repeat(300000)}"}"""))
    }
}
