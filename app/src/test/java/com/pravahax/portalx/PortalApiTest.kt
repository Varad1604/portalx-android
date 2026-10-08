package com.pravahax.portalx

import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.security.SecureStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PortalApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PortalApi

    private fun seroval(result: String = """{"t":2,"s":1}""", error: String = """{"t":2,"s":1}""") =
        MockResponse().setHeader("x-tss-serialized", "true").setHeader("content-type", "application/json")
            .setBody("""{"t":{"t":10,"i":0,"p":{"k":["result","error","context"],"v":[$result,$error,{"t":10,"i":9,"p":{"k":[],"v":[]},"o":0}]},"o":0},"f":63,"m":[]}""")
    private fun err(msg: String) = seroval(error = """{"t":25,"i":1,"s":{"message":{"t":1,"s":"$msg"}},"c":"${'$'}TSR/Error"}""")
    private fun user(scope: String) = seroval(result = """{"t":10,"i":1,"p":{"k":["name","sessionScope"],"v":[{"t":1,"s":"Varad"},{"t":1,"s":"$scope"}]},"o":0}""")

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "test-${System.nanoTime()}"))
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun sendsServerFnHeadersAndScope() = runBlocking {
        server.enqueue(seroval(result = """{"t":0,"s":3}"""))
        api.sessionScope = "scope-A"
        val r = api.call(Fn.DashboardStats)
        assertEquals(3, r.jsonPrimitive.int)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("true", req.getHeader("x-tsr-serverFn"))
        assertEquals("scope-A", req.getHeader("x-portal-session-scope"))
        assertTrue(req.getHeader("Origin")!!.startsWith("http"))
        assertTrue(req.path!!.contains(Fn.DashboardStats.id))
    }

    @Test fun refreshesScopeOnceAndRetries() = runBlocking {
        server.enqueue(err("Your company session changed. Reload the page before continuing."))
        server.enqueue(user("scope-B"))
        server.enqueue(seroval(result = """{"t":1,"s":"ok"}"""))
        val r = api.call(Fn.Tasks)
        assertEquals("ok", r.jsonPrimitive.content)
        assertEquals("scope-B", api.sessionScope)
        server.takeRequest(); assertTrue(server.takeRequest().path!!.contains(Fn.CurrentUser.id))
        assertEquals("scope-B", server.takeRequest().getHeader("x-portal-session-scope"))
    }

    @Test fun scopeRefreshWithoutUserMeansSessionExpired() = runBlocking {
        server.enqueue(err("Your company session changed. Reload the page before continuing."))
        server.enqueue(seroval()) // CurrentUser -> null
        val e = runCatching { api.call(Fn.Tasks) }.exceptionOrNull() as PortalException
        assertEquals(401, e.code)
    }

    @Test fun businessErrorsAreNotTreatedAsSignOut() = runBlocking {
        server.enqueue(err("Invalid User ID or password."))
        val e = runCatching { api.call(Fn.Login, buildJsonObject { put("workspace", "w") }) }.exceptionOrNull() as PortalException
        assertEquals("Invalid User ID or password.", e.message)
        assertNotEquals(401, e.code)
        server.enqueue(err("Session expired"))
        assertEquals(401, (runCatching { api.call(Fn.Tasks) }.exceptionOrNull() as PortalException).code)
    }

    @Test fun cloudflareHtmlErrorIsFriendlyAndKeepsSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(530).setBody("error code: 1033"))
        val e = runCatching { api.call(Fn.Tasks) }.exceptionOrNull() as PortalException
        assertEquals(530, e.code)
        assertTrue(e.message!!.contains("temporarily unavailable"))
    }

    @Test fun readsRetryOnceButWritesNever() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(seroval(result = """{"t":1,"s":"second try"}"""))
        assertEquals("second try", api.call(Fn.Tasks).jsonPrimitive.content)

        val before = server.requestCount
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(seroval(result = """{"t":1,"s":"should not be used"}"""))
        val e = runCatching { api.call(Fn.CheckIn, buildJsonObject { put("selfie", "x") }) }.exceptionOrNull()
        assertTrue(e is PortalException)
        assertEquals(1, server.requestCount - before)
    }

    @Test fun cookiesAreKeptAndCleared() = runBlocking {
        server.enqueue(user("s1").setHeader("Set-Cookie", "portal_session=abc; Path=/; HttpOnly; Max-Age=3600"))
        api.call(Fn.CurrentUser)
        assertTrue(api.hasSession())
        server.enqueue(seroval())
        api.call(Fn.Tasks)
        server.takeRequest(); assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("portal_session=abc"))
        api.clearSession()
        assertFalse(api.hasSession())
        assertEquals("anonymous", api.sessionScope)
    }

    // ---- v0.3.0 hardening ----
    @Test fun doesNotFollowRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(seroval(result = """{"t":1,"s":"should-not-be-read"}"""))
        api.sessionScope = "secret-scope"
        val e = try { api.call(Fn.CheckIn, buildJsonObject { put("selfie", "x") }); null } catch (x: PortalException) { x }
        assertNotNull(e)
        assertEquals(302, e!!.code)
        assertEquals(1, server.requestCount) // the redirect target never received the scope header or the selfie
    }

    @Test fun rejectsAbsurdlyDeepJson() = runBlocking {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        server.enqueue(MockResponse().setBody(deep))
        val e = try { api.call(Fn.Tasks); null } catch (x: PortalException) { x }
        assertNotNull(e) // a clean error, not a StackOverflowError crash
    }

    @Test fun jsonDepthIgnoresBracketsInStrings() {
        assertEquals(2, PortalApi.jsonDepth("""{"a":["[[[{{{\"]]"]}"""))
        assertEquals(0, PortalApi.jsonDepth("\"{[\""))
    }
}
