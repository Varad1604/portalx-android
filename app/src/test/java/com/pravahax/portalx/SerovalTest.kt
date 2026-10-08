package com.pravahax.portalx

import com.pravahax.portalx.net.Seroval
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SerovalTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test fun encodesWrappedDataObject() {
        val out = Seroval.encodeRequest(buildJsonObject { put("taskId", 7); put("status", "done"); put("half", true); put("x", JsonNull) })
        val j = parse(out).jsonObject
        assertEquals(127, j["f"]!!.jsonPrimitive.int)
        val root = j["t"]!!.jsonObject
        assertEquals(10, root["t"]!!.jsonPrimitive.int)
        assertEquals("data", root["p"]!!.jsonObject["k"]!!.jsonArray[0].jsonPrimitive.content)
        val data = root["p"]!!.jsonObject["v"]!!.jsonArray[0].jsonObject["p"]!!.jsonObject
        val vs = data["v"]!!.jsonArray
        assertEquals(0, vs[0].jsonObject["t"]!!.jsonPrimitive.int)          // number
        assertEquals("done", vs[1].jsonObject["s"]!!.jsonPrimitive.content)  // string
        assertEquals(2, vs[2].jsonObject["s"]!!.jsonPrimitive.int)           // true constant
        assertEquals(0, vs[3].jsonObject["s"]!!.jsonPrimitive.int)           // null constant
    }

    @Test fun escapeRoundTrips() {
        val s = "a\"b\\c\n<script>\u2028 ✓"
        assertEquals(s, Seroval.unescape(Seroval.escape(s)))
        assertTrue(Seroval.escape("<").contains("\\x3C"))
    }

    @Test fun unescapeIsTotalOnMalformedInput() {
        assertEquals("ab", Seroval.unescape("a\\b").let { "ab" }) // just must not throw
        Seroval.unescape("\\x4"); Seroval.unescape("\\u12"); Seroval.unescape("\\")
    }

    @Test fun decodesResultWithRefsDatesAndMaps() {
        val doc = """{"t":{"t":10,"i":0,"p":{"k":["result","error"],"v":[
            {"t":9,"i":1,"a":[{"t":10,"i":2,"p":{"k":["id","when","n","ok","m"],"v":[{"t":0,"s":5},{"t":5,"s":"2026-10-07T03:30:00.000Z"},{"t":3,"s":"12"},{"t":2,"s":2},
              {"t":8,"i":4,"e":{"k":[{"t":1,"s":"a"}],"v":[{"t":0,"s":1}]}}]},"o":0},{"t":4,"i":2}],"o":0},
            {"t":2,"s":1}]},"o":0},"f":63,"m":[]}"""
        val r = Seroval.decode(parse(doc)).jsonObject
        val arr = r["result"]!!.jsonArray
        assertEquals(2, arr.size)
        val first = arr[0].jsonObject
        assertEquals(5, first["id"]!!.jsonPrimitive.int)
        assertEquals("2026-10-07T03:30:00.000Z", first["when"]!!.jsonPrimitive.content)
        assertEquals(12L, first["n"]!!.jsonPrimitive.long)
        assertTrue(first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, first["m"]!!.jsonObject["a"]!!.jsonPrimitive.int)
        assertEquals(first, arr[1]) // ref resolved
        assertEquals(JsonNull, r["error"])
    }

    @Test fun decodesTsrErrorPlugin() {
        val doc = """{"t":{"t":10,"i":0,"p":{"k":["result","error"],"v":[{"t":2,"s":1},{"t":25,"i":1,"s":{"message":{"t":1,"s":"Invalid User ID or password."}},"c":"${'$'}TSR/Error"}]},"o":0},"f":63,"m":[]}"""
        val r = Seroval.decode(parse(doc)).jsonObject
        assertEquals("Invalid User ID or password.", r["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test fun neverThrowsOnGarbage() {
        listOf(
            """{"t":1}""", """{"t":10,"p":{"k":[1,{"x":1}],"v":[]}}""", """{"t":10,"p":{"k":["a","b"],"v":[{"t":0,"s":1}]}}""",
            """{"t":9,"a":"nope"}""", """{"t":99}""", """{"t":"x"}""", """[1,2,3]""", """{"t":2,"s":5}""", """{"t":0,"s":"NaN"}""",
        ).forEach { Seroval.decode(parse(it)) }
        val mismatched = Seroval.decode(parse("""{"t":10,"p":{"k":["a","b"],"v":[{"t":0,"s":1}]}}""")).jsonObject
        assertEquals(1, mismatched.size)
    }
}
