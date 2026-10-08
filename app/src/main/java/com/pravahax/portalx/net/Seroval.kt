package com.pravahax.portalx.net

import kotlinx.serialization.json.*

/**
 * Minimal codec for the seroval JSON format that TanStack Start server functions speak.
 *
 * Encoding covers plain data (objects, arrays, strings, numbers, booleans, null).
 * Decoding is total: it never throws on unexpected shapes. Unknown node types become JsonNull,
 * malformed escapes are kept literally, and mismatched key/value arrays are truncated.
 *
 * Node types (seroval SerovalNodeType): 0 Number, 1 String, 2 Constant, 3 BigInt, 4 IndexedValue (ref),
 * 5 Date, 6 RegExp, 7 Set, 8 Map, 9 Array, 10 Object, 11 NullConstructor, 13 Error, 21 Boxed,
 * 25 Plugin (e.g. $TSR/Error). Constants: 0 null, 1 undefined, 2 true, 3 false, 4 -0, 5 Inf, 6 -Inf, 7 NaN.
 */
object Seroval {

    // ---------- encode ----------
    fun encodeRequest(data: JsonElement?): String {
        val counter = intArrayOf(0)
        val root = if (data == null) buildObj(counter, emptyList()) else buildObj(counter, listOf("data" to data))
        return buildJsonObject {
            put("t", root); put("f", 127); put("m", JsonArray(emptyList()))
        }.toString()
    }

    private fun buildObj(counter: IntArray, entries: List<Pair<String, JsonElement>>): JsonObject {
        val id = counter[0]++
        return buildJsonObject {
            put("t", 10); put("i", id)
            put("p", buildJsonObject {
                put("k", JsonArray(entries.map { JsonPrimitive(escape(it.first)) }))
                put("v", JsonArray(entries.map { node(counter, it.second) }))
            })
            put("o", 0)
        }
    }

    private fun node(counter: IntArray, e: JsonElement): JsonElement = when (e) {
        is JsonNull -> constant(0)
        is JsonPrimitive -> when {
            e.isString -> buildJsonObject { put("t", 1); put("s", escape(e.content)) }
            e.booleanOrNull != null -> constant(if (e.boolean) 2 else 3)
            e.doubleOrNull?.let { it.isNaN() } == true -> constant(7)
            e.doubleOrNull == Double.POSITIVE_INFINITY -> constant(5)
            e.doubleOrNull == Double.NEGATIVE_INFINITY -> constant(6)
            else -> buildJsonObject { put("t", 0); put("s", e) }
        }
        is JsonArray -> {
            val id = counter[0]++
            buildJsonObject {
                put("t", 9); put("i", id)
                put("a", JsonArray(e.map { node(counter, it) })); put("o", 0)
            }
        }
        is JsonObject -> buildObj(counter, e.entries.map { it.key to it.value })
    }

    private fun constant(v: Int) = buildJsonObject { put("t", 2); put("s", v) }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '<' -> sb.append("\\x3C")
            '\u2028' -> sb.append("\\u2028")
            '\u2029' -> sb.append("\\u2029")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** Reverses [escape] (and JS string escapes in general). Malformed sequences are kept as-is. */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'v' -> { sb.append('\u000B'); i += 2 }
                '0' -> { sb.append('\u0000'); i += 2 }
                'x' -> {
                    val hex = s.substring(i + 2, minOf(i + 4, s.length)).toIntOrNull(16)
                    if (hex != null && i + 4 <= s.length) { sb.append(hex.toChar()); i += 4 } else { sb.append(n); i += 2 }
                }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)
                    if (hex != null && i + 6 <= s.length) { sb.append(hex.toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    // ---------- decode ----------
    /** Decodes a seroval document. Never throws; anything unrecognised becomes JsonNull. */
    fun decode(root: JsonElement): JsonElement {
        val refs = HashMap<Int, JsonElement>()
        val top = (root as? JsonObject)?.takeIf { it.containsKey("f") }?.get("t") as? JsonObject ?: root
        return try { dec(top, refs, 0) } catch (e: Exception) { JsonNull }
    }

    private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun dec(n: JsonElement, refs: HashMap<Int, JsonElement>, depth: Int): JsonElement {
        if (depth > 200) return JsonNull // pathological nesting guard
        if (n !is JsonObject) return n
        val t = n["t"].intOrNull() ?: return n
        val id = n["i"].intOrNull()
        val out: JsonElement = when (t) {
            0 -> n["s"].prim()?.let { p ->
                // Numbers arrive as JSON numbers; guard against non-finite values that JSON can't hold.
                if (p.isString) p.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonNull else p
            } ?: JsonNull
            1 -> n["s"].strOrNull()?.let { JsonPrimitive(unescape(it)) } ?: JsonPrimitive("")
            2 -> when (n["s"].intOrNull()) {
                2 -> JsonPrimitive(true); 3 -> JsonPrimitive(false); 4 -> JsonPrimitive(0)
                else -> JsonNull // null, undefined, ±Infinity, NaN: not representable, treat as absent
            }
            3 -> n["s"].strOrNull()?.let { s -> s.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(s) } ?: JsonNull
            4 -> refs[id] ?: JsonNull
            5 -> n["s"].strOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
            6 -> n["c"].strOrNull()?.let { JsonPrimitive(unescape(it)) } ?: JsonNull
            7, 9 -> {
                val a = n["a"] as? JsonArray ?: JsonArray(emptyList())
                JsonArray(a.map { if (it is JsonNull) JsonNull else dec(it, refs, depth + 1) })
            }
            8 -> { // Map -> object keyed by the string form of each key
                val e = n["e"] as? JsonObject
                val ks = e?.get("k") as? JsonArray ?: JsonArray(emptyList())
                val vs = e?.get("v") as? JsonArray ?: JsonArray(emptyList())
                val m = LinkedHashMap<String, JsonElement>()
                for (i in 0 until minOf(ks.size, vs.size)) {
                    val k = dec(ks[i], refs, depth + 1)
                    m[(k as? JsonPrimitive)?.content ?: k.toString()] = dec(vs[i], refs, depth + 1)
                }
                JsonObject(m)
            }
            10, 11 -> {
                val p = n["p"] as? JsonObject
                val ks = p?.get("k") as? JsonArray ?: JsonArray(emptyList())
                val vs = p?.get("v") as? JsonArray ?: JsonArray(emptyList())
                val m = LinkedHashMap<String, JsonElement>()
                for (i in 0 until minOf(ks.size, vs.size)) {
                    val k = ks[i].strOrNull() ?: continue
                    m[unescape(k)] = dec(vs[i], refs, depth + 1)
                }
                JsonObject(m)
            }
            13, 14 -> { // Error
                val m = LinkedHashMap<String, JsonElement>()
                (n["p"] as? JsonObject)?.let { p ->
                    val ks = p["k"] as? JsonArray ?: JsonArray(emptyList())
                    val vs = p["v"] as? JsonArray ?: JsonArray(emptyList())
                    for (i in 0 until minOf(ks.size, vs.size)) ks[i].strOrNull()?.let { m[unescape(it)] = dec(vs[i], refs, depth + 1) }
                }
                m["message"] = JsonPrimitive(unescape(n["m"].strOrNull() ?: (m["message"] as? JsonPrimitive)?.content ?: "Request failed."))
                JsonObject(m)
            }
            21 -> n["f"]?.let { dec(it, refs, depth + 1) } ?: JsonNull // Boxed primitive
            25 -> { // plugin node, e.g. $TSR/Error -> { message: ... }
                val s = n["s"] as? JsonObject
                JsonObject((s ?: JsonObject(emptyMap())).mapValues { dec(it.value, refs, depth + 1) } +
                    ("__plugin" to JsonPrimitive(n["c"].strOrNull() ?: "")))
            }
            else -> JsonNull
        }
        if (id != null && t != 4) refs[id] = out
        return out
    }
}
