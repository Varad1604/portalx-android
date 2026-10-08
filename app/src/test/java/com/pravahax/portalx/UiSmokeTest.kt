package com.pravahax.portalx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.SessionUser
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.security.SecureStore
import com.pravahax.portalx.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper

/** Renders every signed-in screen against fixture responses (realistic and hostile) and fails on any crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class UiSmokeTest {
    private lateinit var server: MockWebServer
    private val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Minimal seroval encoder for fixtures.
    private var nextId = 0
    private fun ser(e: JsonElement): JsonElement = when (e) {
        is JsonNull -> buildJsonObject { put("t", 2); put("s", 0) }
        is JsonPrimitive -> when {
            e.isString -> buildJsonObject { put("t", 1); put("s", e.content) }
            e.booleanOrNull != null -> buildJsonObject { put("t", 2); put("s", if (e.boolean) 2 else 3) }
            else -> buildJsonObject { put("t", 0); put("s", e) }
        }
        is JsonArray -> buildJsonObject { put("t", 9); put("i", nextId++); put("a", JsonArray(e.map { ser(it) })); put("o", 0) }
        is JsonObject -> buildJsonObject {
            put("t", 10); put("i", nextId++)
            put("p", buildJsonObject { put("k", JsonArray(e.keys.map { JsonPrimitive(it) })); put("v", JsonArray(e.values.map { ser(it) })) }); put("o", 0)
        }
    }
    private fun wrap(result: JsonElement) = MockResponse().setHeader("x-tss-serialized", "true")
        .setBody(buildJsonObject {
            put("t", ser(buildJsonObject { put("result", result); put("error", JsonNull) })); put("f", 63); put("m", JsonArray(emptyList()))
        }.toString())

    private val me = Json.parseToJsonElement("""{"id":2,"name":"Varad Kulkarni","userId":"PSE-00002","role":"member","organizationRoles":["Organization Owner"],"permissions":[],"sessionScope":"s1","mustChangePassword":false}""")

    private fun realistic(fn: Fn?): JsonElement = Json.parseToJsonElement(when (fn) {
        Fn.Login, Fn.CurrentUser -> me.toString()
        Fn.DashboardStats -> """{"activeUsers":12,"presentToday":9,"onLeaveToday":1,"pendingCorrections":2,"pendingLeaves":1,"activeProjects":4,"myPendingApprovals":1}"""
        Fn.AttendanceToday -> """{"check_in":"2026-10-07T03:30:00.000Z","check_out":null,"is_on_break":true,"last_break_start":"2026-10-07T07:00:00.000Z","total_break_seconds":600,"onLeave":false}"""
        Fn.AttendanceHistory -> """[{"attendance_date":"2026-10-06T18:30:00.000Z","check_in":"2026-10-06T03:30:00Z","check_out":"2026-10-06T12:30:00Z","total_break_seconds":1800,"status":"present"},{"attendance_date":"2026-10-05","check_in":null,"check_out":null}]"""
        Fn.Corrections -> """[{"id":11,"name":"Asha","memberUserId":"PSE-00007","date":"2026-10-05","reason":"forgot_check_out","checkIn":"2026-10-05T03:30:00Z","checkOut":null,"status":"pending"}]"""
        Fn.MyLeave -> """{"balances":[{"typeId":1,"type":"Casual","remaining":4.5,"allocated":12,"used":7.5,"paid":true},{"typeId":2,"type":"Unpaid","remaining":0,"allocated":0,"used":1,"paid":false}],"requests":[{"id":5,"type":"Casual","startDate":"2026-10-10","endDate":"2026-10-11","days":2,"status":"pending","reviewer":null}]}"""
        Fn.PendingLeaveApprovals -> """[{"id":"7","name":"Rohan","memberUserId":"PSE-00009","type":"Sick","startDate":"2026-10-08","endDate":"2026-10-08","days":1,"reason":"Fever"}]"""
        Fn.Tasks -> """[{"id":1,"title":"Ship app","status":"in_progress","priority":"high","priorityLabel":"High","projectName":"PortalX","dueDate":"2026-10-01","assignee":"Varad"},{"id":2,"title":"Done thing","status":"done","priority":"low"},{"title":"No id task","status":"todo"}]"""
        Fn.Announcements -> """{"published":[{"id":1,"title":"Diwali","body":"Office closed","author":"HR","publishedAt":"2026-10-01T04:00:00Z","readByMe":false}],"drafts":[]}"""
        Fn.Meetings -> """[{"id":1,"title":"Standup","startAt":"2030-01-01T04:00:00Z","endAt":"2030-01-01T04:15:00Z","link":"https://meet.example.com/x"},{"id":2,"title":"Old","startAt":"2020-01-01T04:00:00Z","link":"javascript:alert(1)"}]"""
        Fn.Directory, Fn.ActivePeople -> """[{"id":1,"name":"Asha Rao","userId":"PSE-00007","team":"Ops","email":"asha@example.com","phone":"+91 98765 43210","organizationRoles":["HR"]},{"id":1,"name":"Dup Id"}]"""
        Fn.CalendarMonth -> """{"holidays":[{"id":1,"name":"Dussehra","date":"2026-10-20"}],"meetings":[{"title":"Review","startAt":"2026-10-07T05:00:00Z"}],"tasks":[{"title":"T","dueDate":"2026-10-07"}],"leave":[{"name":"Asha","startDate":"2026-10-08","endDate":"2026-10-09"}],"projectDeadlines":[{"name":"PortalX","endDate":"2026-10-31"}]}"""
        Fn.Projects -> """[{"id":1,"name":"PortalX"}]"""
        else -> "null"
    })

    /** Wrong types everywhere: strings for arrays, nulls, numbers for objects, nested junk. */
    private fun hostile(fn: Fn?): JsonElement = Json.parseToJsonElement(when (fn) {
        Fn.Login, Fn.CurrentUser -> me.toString()
        Fn.DashboardStats -> """{"activeUsers":"lots","presentToday":null,"pendingLeaves":[1]}"""
        Fn.AttendanceToday -> """{"check_in":"garbage","check_out":5,"is_on_break":"yes","total_break_seconds":"NaN"}"""
        Fn.AttendanceHistory, Fn.Corrections, Fn.Tasks, Fn.Meetings, Fn.Directory -> """[null, 1, "x", {"id":null,"title":{"weird":true},"startAt":"not-a-date","status":7}]"""
        Fn.MyLeave -> """{"balances":"none","requests":[{"days":"two","startDate":"31-31-2026"}]}"""
        Fn.Announcements -> """[1,2,3]"""
        Fn.CalendarMonth -> """{"leave":[{"startDate":"2026-10-30","endDate":"2026-01-01"},{"startDate":"2000-01-01","endDate":"2099-12-31"}],"holidays":{"a":1}}"""
        else -> "\"unexpected\""
    })

    private var mode = "realistic"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.path!!.substringAfter("/_serverFn/").substringBefore("?")
                val fn = Fn.entries.firstOrNull { it.id == id }
                hits.merge(fn?.name ?: id, 1, Int::plus)
                val r = wrap(if (mode == "realistic") realistic(fn) else hostile(fn))
                return if (fn == Fn.Login) r.setHeader("Set-Cookie", "portal_session=abc; Path=/; HttpOnly; Max-Age=3600") else r
            }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    private fun renderAll(tag: String) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "ui-$tag"))
        val repo = Repo(ctx, api, "cache-$tag")
        val user = runBlocking { repo.login("pravahax", "PSE-00002", "x") }
        val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            "home" to { HomeScreen(user) {} },
            "attendance" to { AttendanceScreen(user) },
            "tasks" to { TasksScreen(user, false) {} },
            "calendar" to { CalendarScreen() },
            "more" to { MoreScreen(user, {}, {}) },
            "leave" to { LeaveScreen(user, false) {} },
            "meetings" to { MeetingsScreen() },
            "announcements" to { AnnouncementsScreen() },
            "directory" to { DirectoryScreen {} },
            "profile" to { ProfileScreen(user, {}) },
            "login" to { LoginScreen("Notice") {} },
        )
        // v0.3.0: light theme only (the web has no dark mode), so there is a single render per screen.
        for ((name, screen) in screens) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            activity.setContent {
                PortalTheme {
                    CompositionLocalProvider(LocalRepo provides repo, LocalSnackbar provides SnackbarHostState()) { screen() }
                }
            }
            // Let network (real IO threads) complete and recompose with the results.
            repeat(12) { ShadowLooper.idleMainLooper(); Thread.sleep(40) }
            ShadowLooper.idleMainLooper()
            println("RENDER_OK $tag/$name")
            controller.pause().stop().destroy()
        }
    }

    @Test fun rendersAllScreensWithRealisticData() { mode = "realistic"; renderAll("real"); assertTrue((hits[Fn.Tasks.name] ?: 0) > 0) }
    @Test fun rendersAllScreensWithHostileData() { mode = "hostile"; renderAll("hostile") }
}
