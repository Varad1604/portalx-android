package com.pravahax.portalx.net

import android.content.Context
import com.pravahax.portalx.BuildConfig
import com.pravahax.portalx.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Every failure surfaced to the UI is a PortalException with a user-friendly message.
 * [code] 401 = session is gone (sign out). [offline] = network unreachable / timed out.
 * [uncertain] = a write may or may not have reached the server (timeout after sending).
 */
class PortalException(
    message: String,
    val code: Int = 0,
    val scopeChanged: Boolean = false,
    val offline: Boolean = false,
    val uncertain: Boolean = false,
    val retryable: Boolean = false,
) : IOException(message)

/** Server function ids, copied from the web bundle. They change when the web app is redeployed. */
enum class Fn(val id: String, val get: Boolean) {
    // auth
    CurrentUser("286e28ba4037bc49e4ebe00fc385a6a0947fbbfac4a1ea10c183dbaaca9330e8", true),
    Login("207a5ea977dfd1f754900adab1b543ce36d10593c3a047d2ddaaae3bf3778eb6", false),
    Logout("52b2ebc28cb3c94b358c218ef6eb4c615cc852a749c0fc45162e2f6cb9bba182", false),
    ChangePassword("da74c847756f2c2dc5ab519dbe834cce8932639303a3de61f5038c4d55e034b7", false),
    RequestPasswordReset("3515ccd41969b8c8c6131312dd1bec0544c942c4a0540713ba3f4a2fed4bd8fb", false),
    // dashboard
    DashboardStats("43ad996c4de69e830389f34d904e10b987fc62fa8efbeba068f3f4da3ac1a5ab", true),
    DashboardDetails("6cb37f53644d2eb8b8b86a226abb402894f2e61e0a61994d008e98cf5b96fb49", false),
    // attendance
    AttendanceToday("79faa9f7a7ffc662f8ecf075ca0b92fc8843c56dee500896a0b7c879b673483a", true),
    AttendanceHistory("38efe2fea19c9241029d66f89621db7a06768550fff0d4e0faa5d32090a5ef91", true),
    CheckIn("16829df7f297df11f434dc8f9eea2c2c5c0c709c8661c8606f43391f47b5d68d", false),
    CheckOut("33e4d8071c7379e459d7bc200e41320114162ea07cad7ec1bae0f4ec0dc1f8d1", false),
    StartBreak("20c2bcc20aff1107d470a0af7a5901660dba5ac37106e6bac923a53781619856", false),
    EndBreak("3194259e3b80fc5dcd8f475725f2314bdaea1875adab9adbcb0d6f18bdba8cdb", false),
    RequestCorrection("4040d2b1d80fef2514bf5808a6917b8ce35561d6804b31bb878e98268af21a55", false),
    Corrections("91c01d0ad13c832645d8f8831ba1710825b5b2d9719249d90b5f7ca382158062", true),
    DecideCorrection("5cab5bc5813d1c160ee9c347f0fba00b4e7be40662ef20efab89530ce581d7ce", false),
    // leave
    MyLeave("797eb922b00e3677ceae7ebd5f61b1fec3f0d2f62efa7ef28d0095d0f2ccad3d", true),
    PendingLeaveApprovals("035ac67eedb2a2a7aa44f75a36c33cf1af8837de61861b49589aad6bfd11773b", true),
    ApplyLeave("621aa5438f3bb6b84da1f03a8bbdbd527decb1bc8b2565d668e7e1706d0225b7", false),
    DecideLeave("9655ef81cc6d9988e5ee9b203598d7b9541eef72c4297288879437d05eb5ecfc", false),
    // work
    Tasks("5a47778b6860a953cdce704abe93129c9f8bc19640d93f42da17a149f56ef471", true),
    TaskDetail("aa093f0782a84ce613dc2e2171d2592d08bd92c1c5f6c6c0f1c1773d9614b9e0", true),
    CreateTask("f43d6a7a2d6fd95fbe7bb6a8a90a4d1d7f0e52fe82f63b7b5e4e869cd1ce2a88", false),
    UpdateTaskStatus("3fd0524b3b9a82e26af2e7b0d57826bcbf68f60667e2da25cf49339bb29159b8", false),
    AddTaskComment("aa29f952ff750bb05ba8a305623a824b025b1a0c1bacc51a3794f1023322d358", false),
    Projects("485b572724a4433cb09c8e360c06e91e599aecade0e555f9ec24198bbafc895d", true),
    // people & comms
    ActivePeople("849c483f7aae4a68ce81c1eec87f33d77fae5dd9932c9033ef4ac3d75fffa81a", true),
    Directory("9ff8f804c6ca4d156bed9cfeb9cd6322784f3f9decf5f866be8244ab571b0582", true),
    Announcements("5f3b2ada5046ae13851a12b55f39a17af0e07a06f8322eb85cf81dc856e9fa0e", true),
    MarkAnnouncementRead("843a47b6bfbc2f035b5573e22302500dc8476e9d8276dced6302aca317c79ba2", false),
    Meetings("e9a4ba23dd3022074fba9fe341dcd4d3514287f41c7143558ccd2e9e399e091d", true),
    CreateMeeting("80c10974355e58c726b72352c6e774f16d96141b59b07a5a0fa18ad48eb1fc10", false),
    CalendarMonth("5baa12ac686e42e38ffa3c919f309001eb70c88090d3731ca66ae1216fee2a54", true),
}

class PortalApi(
    context: Context,
    val baseUrl: String = DEFAULT_BASE_URL,
    private val store: SecureStore = SecureStore(context, "session"),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://portal.pravahax.com"
        private const val SCOPE_KEY = "__scope"
        /** Largest response body accepted (the biggest real payload, the directory, is well under 1 MB). */
        internal const val MAX_BODY_BYTES = 8L * 1024 * 1024
        /** Deeper nesting than this is never legitimate and could overflow the recursive JSON/seroval decoders. */
        internal const val MAX_JSON_DEPTH = 256

        /** Reads at most [MAX_BODY_BYTES]; a bigger (or endless) body is rejected instead of exhausting memory. */
        internal fun readCapped(body: ResponseBody?): String {
            if (body == null) return ""
            if (body.contentLength() > MAX_BODY_BYTES) throw PortalException("Portal One sent an unexpectedly large response.")
            val src = body.source()
            if (src.request(MAX_BODY_BYTES + 1)) throw PortalException("Portal One sent an unexpectedly large response.")
            return src.buffer.readUtf8()
        }

        /** Maximum {}/[] nesting outside string literals (linear scan, no recursion). */
        internal fun jsonDepth(s: String): Int {
            var depth = 0; var max = 0; var inStr = false; var esc = false
            for (ch in s) {
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{', '[' -> { depth++; if (depth > max) max = depth }; '}', ']' -> depth-- }
            }
            return max
        }

        /** Messages the server uses when the session is genuinely gone. Kept deliberately narrow. */
        internal fun looksUnauthenticated(msg: String): Boolean {
            val m = msg.lowercase()
            return m.contains("unauthenticated") || m.contains("not authenticated") || m.contains("unauthorized") ||
                m.contains("session expired") || m.contains("please sign in again") || m.contains("please log in again") ||
                m.contains("not signed in") || m.contains("login required")
        }

        internal fun friendly(e: Throwable): PortalException = when (e) {
            is PortalException -> e
            is UnknownHostException, is ConnectException ->
                PortalException("You're offline or Portal One can't be reached. Check your connection.", offline = true)
            is SocketTimeoutException, is InterruptedIOException ->
                PortalException("Portal One is taking too long to respond. Please try again.", offline = true)
            is SSLException -> PortalException("A secure connection to Portal One couldn't be established.", offline = true)
            else -> PortalException("Something went wrong talking to Portal One. Please try again.")
        }
    }

    private val cookieJar = PersistentCookieJar(baseUrl, store, context)

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // Server functions never redirect. Following one would replay the session-scope header (and the selfie body)
        // to wherever the redirect points, so any 3xx is treated as an error instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Writes must never be replayed silently by OkHttp (double check-in, duplicate leave request). */
    private val writeClient = client.newBuilder().retryOnConnectionFailure(false).build()

    private val json = Json { ignoreUnknownKeys = true }
    private val scopeMutex = Mutex()

    /** The web sends `x-portal-session-scope` (user.sessionScope, or "anonymous") on every server fn call. */
    var sessionScope: String
        get() = store.get(SCOPE_KEY)?.ifBlank { null } ?: "anonymous"
        set(v) { store.put(SCOPE_KEY, v.ifBlank { "anonymous" }) }

    fun clearSession() { cookieJar.clear(); store.remove(SCOPE_KEY) }
    fun hasSession() = cookieJar.hasAny()

    suspend fun call(fn: Fn, data: JsonElement? = null): JsonElement = try {
        rawCall(fn, data)
    } catch (e: PortalException) {
        // Scope out of sync with the server: refresh it from the current user and retry once.
        if (e.scopeChanged && fn != Fn.CurrentUser) {
            val usedScope = sessionScope
            scopeMutex.withLock {
                // Another call may already have refreshed it while we waited.
                if (sessionScope == usedScope) {
                    val me = try { rawCall(Fn.CurrentUser, null) as? JsonObject } catch (ce: CancellationException) { throw ce } catch (x: Exception) { null }
                    val scope = (me?.get("sessionScope") as? JsonPrimitive)?.contentOrNull
                    if (scope.isNullOrBlank()) throw PortalException("Your session expired. Please sign in again.", 401)
                    sessionScope = scope
                }
            }
            rawCall(fn, data)
        } else throw e
    }

    private suspend fun rawCall(fn: Fn, data: JsonElement?): JsonElement = withContext(Dispatchers.IO) {
        try {
            guarded(fn, data)
        } catch (e: PortalException) {
            // Reads are safe to retry once on a transient network failure; writes never are.
            if (fn.get && e.retryable) { delay(600); guarded(fn, data) } else throw e
        }
    }

    private fun guarded(fn: Fn, data: JsonElement?): JsonElement = try {
        execute(fn, data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PortalException) {
        throw e
    } catch (e: IOException) {
        val f = friendly(e)
        // A POST that timed out after the body was sent may have been applied server-side.
        val uncertain = !fn.get && (e is SocketTimeoutException || e is InterruptedIOException)
        throw PortalException(f.message ?: "", f.code, offline = f.offline, uncertain = uncertain, retryable = e !is UnknownHostException)
    } catch (e: Exception) {
        throw friendly(e)
    }

    private fun execute(fn: Fn, data: JsonElement?): JsonElement {
        val payload = Seroval.encodeRequest(data)
        val url = "$baseUrl/_serverFn/${fn.id}"
        val builder = Request.Builder()
            .header("x-tsr-serverFn", "true")
            .header("accept", "application/json")
            .header("Origin", baseUrl)
            .header("x-portal-session-scope", sessionScope)
            .header("Referer", "$baseUrl/")
            .header("User-Agent", "PortalX-Android/${BuildConfig.VERSION_NAME} (Mobile)")
        val req = if (fn.get) {
            val u = url.toHttpUrl().newBuilder()
            if (data != null) u.addQueryParameter("payload", payload)
            builder.url(u.build()).get().build()
        } else {
            builder.url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
        }
        val c = if (fn.get) client else writeClient
        c.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw PortalException("Your session expired. Please sign in again.", 401)
            if (resp.code in 300..399) throw PortalException("Portal One answered with an unexpected redirect (${resp.code}). Please try again later.", resp.code)
            val body = readCapped(resp.body)
            if (jsonDepth(body) > MAX_JSON_DEPTH) throw PortalException("Unexpected response from Portal One.", resp.code)
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                throw when {
                    resp.code in 500..599 -> PortalException("Portal One is temporarily unavailable (${resp.code}). Please try again shortly.", resp.code)
                    resp.code == 403 -> PortalException("Portal One refused the request (403). Please update the app or try again later.", 403)
                    resp.code == 404 -> PortalException("This feature isn't available right now. The app may need an update.", 404)
                    !resp.isSuccessful -> PortalException("Server error (${resp.code}).", resp.code)
                    else -> PortalException("Unexpected response from Portal One.", resp.code)
                }
            }
            val raw = try { json.parseToJsonElement(body) } catch (e: Exception) {
                throw PortalException("Unexpected response from Portal One.", resp.code)
            }
            val decoded = if (resp.header("x-tss-serialized") != null || raw.isSeroval()) Seroval.decode(raw) else raw
            val obj = decoded as? JsonObject
            return if (obj != null && (obj.containsKey("result") || obj.containsKey("error"))) {
                val err = obj["error"]
                if (err != null && err !is JsonNull) {
                    val msg = ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: (err as? JsonPrimitive)?.contentOrNull ?: "Request failed."
                    val scopeChanged = msg.contains("session changed", true)
                    throw PortalException(msg, if (!scopeChanged && looksUnauthenticated(msg)) 401 else resp.code, scopeChanged)
                }
                obj["result"] ?: JsonNull
            } else {
                if (!resp.isSuccessful) throw PortalException("Server error (${resp.code}).", resp.code)
                decoded
            }
        }
    }

    private fun JsonElement.isSeroval() = this is JsonObject && this["t"]?.let { it is JsonPrimitive || it is JsonObject } == true
}

/**
 * Cookie jar persisted (encrypted, Keystore-backed) so the session survives app restarts.
 * Migrates the plaintext jar written by v0.1.x and deletes it.
 */
class PersistentCookieJar(baseUrl: String, private val store: SecureStore, context: Context? = null) : CookieJar {
    private val host = "${baseUrl.trimEnd('/')}/".toHttpUrlOrNull() ?: "https://portal.pravahax.com/".toHttpUrl()
    private val store2 = HashMap<String, Cookie>()
    private val prefix = "c:"

    init {
        // One-time migration from the v0.1.x plaintext SharedPreferences jar.
        context?.getSharedPreferences("portal_cookies", Context.MODE_PRIVATE)?.let { legacy ->
            if (legacy.all.isNotEmpty()) {
                legacy.all.forEach { (name, v) -> (v as? String)?.let { store.put(prefix + name, it) } }
                legacy.edit().clear().apply()
            }
        }
        context?.getSharedPreferences("portal_scope", Context.MODE_PRIVATE)?.let { legacy ->
            legacy.getString("scope", null)?.let { store.put("__scope", it) }
            legacy.edit().clear().apply()
        }
        val now = System.currentTimeMillis()
        store.all().forEach { (k, v) ->
            if (!k.startsWith(prefix)) return@forEach
            val c = runCatching { Cookie.parse(host, v) }.getOrNull()
            if (c != null && c.expiresAt > now) {
                store2[key(c)] = c
                if (k != prefix + key(c)) { store.remove(k); store.put(prefix + key(c), v) } // re-key migrated entries
            } else store.remove(k)
        }
    }

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { c ->
            val k = key(c)
            if (c.expiresAt <= now) { store2.remove(k); store.remove(prefix + k) }
            else { store2[k] = c; store.put(prefix + k, c.toString()) }
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store2.values.filter { it.matches(url) && it.expiresAt > now }
    }

    @Synchronized fun clear() {
        store2.clear()
        store.keys().filter { it.startsWith(prefix) }.forEach { store.remove(it) }
    }

    @Synchronized fun hasAny() = store2.values.any { it.expiresAt > System.currentTimeMillis() }
}
