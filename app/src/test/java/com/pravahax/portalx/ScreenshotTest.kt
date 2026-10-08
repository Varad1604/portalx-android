package com.pravahax.portalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import com.pravahax.portalx.data.Repo
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders key screens to PNG at phone size (393×852 dp @ 3x) in the light theme.
 * Needs Robolectric NATIVE graphics, which only ships for x86_64 Linux/macOS, so it is skipped in the normal
 * suite and run explicitly with: -Dportalx.screenshots=<out dir> -Drobolectric.graphicsMode=NATIVE
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScreenshotTest {
    private val out: String? = System.getProperty("portalx.screenshots")

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
        .setBody(buildJsonObject { put("t", ser(buildJsonObject { put("result", result); put("error", JsonNull) })); put("f", 63); put("m", JsonArray(emptyList())) }.toString())

    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
    private fun d(plus: Long) = today.plusDays(plus).toString()
    private fun ist(day: LocalDate, hm: String) = "${day}T$hm:00+05:30"
    var attendanceState = "out"

    private fun fixture(fn: Fn?): String = when (fn) {
        Fn.Login, Fn.CurrentUser -> """{"id":2,"name":"Varad Kulkarni","userId":"PSE-00002","email":"varad@pravahax.com","role":"member","organizationRoles":["Organization Owner"],"permissions":[],"sessionScope":"s1","mustChangePassword":false,"team":"Leadership","designation":"Founder"}"""
        Fn.DashboardStats -> """{"activeUsers":18,"presentToday":14,"onLeaveToday":2,"pendingCorrections":1,"pendingLeaves":2,"activeProjects":5,"myPendingApprovals":2}"""
        Fn.AttendanceToday -> when (attendanceState) {
            "in" -> """{"check_in":"${ist(today, "09:02")}","check_out":null,"is_on_break":false,"total_break_seconds":900,"onLeave":false}"""
            else -> """{"check_in":null,"check_out":null,"is_on_break":false,"total_break_seconds":0,"onLeave":false}"""
        }
        Fn.AttendanceHistory -> (1..5).joinToString(",", "[", "]") { i ->
            val day = today.minusDays(i.toLong())
            """{"attendance_date":"$day","check_in":"${ist(day, "09:0$i")}","check_out":"${ist(day, "18:1$i")}","total_break_seconds":${1800 + i * 60},"status":"present"}"""
        }
        Fn.Corrections -> """[{"id":11,"name":"Asha Rao","memberUserId":"PSE-00007","date":"${d(-2)}","reason":"forgot_check_out","checkIn":"${ist(today.minusDays(2), "09:00")}","checkOut":"${ist(today.minusDays(2), "18:00")}","status":"pending","note":"Left in a hurry for a client visit"}]"""
        Fn.MyLeave -> """{"balances":[{"typeId":1,"type":"Casual leave","remaining":6.5,"allocated":12,"used":5.5,"paid":true},{"typeId":2,"type":"Sick leave","remaining":7,"allocated":8,"used":1,"paid":true},{"typeId":3,"type":"Unpaid","remaining":0,"allocated":0,"used":0,"paid":false}],"requests":[{"id":5,"type":"Casual leave","startDate":"${d(9)}","endDate":"${d(10)}","days":2,"status":"pending"},{"id":4,"type":"Sick leave","startDate":"${d(-20)}","endDate":"${d(-20)}","days":1,"status":"approved","reviewer":"Asha Rao"}]}"""
        Fn.PendingLeaveApprovals -> """[{"id":7,"name":"Rohan Mehta","memberUserId":"PSE-00009","type":"Sick leave","startDate":"${d(0)}","endDate":"${d(1)}","days":2,"reason":"Fever, doctor advised rest"}]"""
        Fn.Tasks -> """[{"id":1,"title":"Finalise Q3 payroll inputs","status":"in_progress","priority":"high","priorityLabel":"High","projectName":"Finance ops","dueDate":"${d(-1)}","assignee":"Varad Kulkarni"},
            {"id":2,"title":"Review mobile app release notes","status":"review","priority":"medium","priorityLabel":"Medium","projectName":"PortalX","dueDate":"${d(1)}","assignee":"Varad Kulkarni"},
            {"id":3,"title":"Onboard two new interns","status":"todo","priority":"medium","priorityLabel":"Medium","projectName":"People","dueDate":"${d(4)}","assignee":"Asha Rao"},
            {"id":4,"title":"Renew office internet plan","status":"todo","priority":"low","priorityLabel":"Low","projectName":"Admin","assignee":"Rohan Mehta"},
            {"id":5,"title":"Diwali gifting vendors","status":"done","priority":"low","priorityLabel":"Low","projectName":"Admin","dueDate":"${d(-5)}"}]"""
        Fn.Announcements -> """{"published":[{"id":1,"title":"Office closed for Dussehra","body":"The office will remain closed on Tuesday for Dussehra. Wishing everyone a joyful festival with family.","author":"HR Team","publishedAt":"${ist(today.minusDays(1), "10:30")}","readByMe":false},{"id":2,"title":"New leave policy is live","body":"Casual leave now accrues monthly. See the policy document for details.","author":"Asha Rao","publishedAt":"${ist(today.minusDays(6), "16:00")}","readByMe":true}],"drafts":[]}"""
        Fn.Meetings -> """[{"id":1,"title":"Weekly leadership sync","startAt":"${ist(today.plusDays(1), "10:00")}","endAt":"${ist(today.plusDays(1), "10:45")}","location":"Board room","link":"https://meet.google.com/abc-defg-hij","organizer":"Varad Kulkarni"}]"""
        Fn.Directory, Fn.ActivePeople -> """[{"id":7,"name":"Asha Rao","userId":"PSE-00007","team":"People","designation":"HR Manager","organizationRoles":["HR"]},{"id":9,"name":"Rohan Mehta","userId":"PSE-00009","team":"Engineering","designation":"Android Engineer"},{"id":2,"name":"Varad Kulkarni","userId":"PSE-00002","team":"Leadership","designation":"Founder","organizationRoles":["Organization Owner"]},{"id":12,"name":"Neha Joshi","userId":"PSE-00012","team":"Engineering","designation":"Designer"}]"""
        Fn.CalendarMonth -> """{"holidays":[{"id":1,"name":"Dussehra","date":"${d(5)}"}],"meetings":[{"title":"Weekly leadership sync","startAt":"${ist(today.plusDays(1), "10:00")}"}],"tasks":[{"title":"Review mobile app release notes","dueDate":"${d(1)}"},{"title":"Finalise Q3 payroll inputs","dueDate":"${d(-1)}"}],"leave":[{"name":"Rohan","startDate":"${d(0)}","endDate":"${d(1)}"}],"projectDeadlines":[{"name":"PortalX","endDate":"${d(12)}"}]}"""
        Fn.Projects -> """[{"id":1,"name":"PortalX"}]"""
        else -> "null"
    }

    private fun shoot(name: String, signedIn: Boolean, route: String, state: String = "out") {
        attendanceState = state
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = request.path!!.substringAfter("/_serverFn/").substringBefore("?")
                val fn = Fn.entries.firstOrNull { it.id == id }
                val r = wrap(Json.parseToJsonElement(fixture(fn)))
                return if (fn == Fn.Login) r.setHeader("Set-Cookie", "portal_session=abc; Path=/; HttpOnly; Max-Age=3600") else r
            }
        }
        server.start()
        try {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val api = PortalApi(ctx, server.url("/").toString().trimEnd('/'), SecureStore(ctx, "shot-$name"))
            val repo = Repo(ctx, api, "cache-shot-$name")
            if (signedIn) runBlocking { repo.login("pravahax", "PSE-00002", "x") }
            org.robolectric.RuntimeEnvironment.setFontScale(if (name.endsWith("200pct")) 2.0f else 1.0f)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            controller.get().setTheme(R.style.Theme_PortalX) // the app's real (light) window theme, no action bar
            controller.setup()
            val activity = controller.get()
            activity.enableEdgeToEdge(
                statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            activity.setContent {
                PortalTheme { CompositionLocalProvider(LocalRepo provides repo, LocalReduceMotion provides true) { PortalApp(repo, true, startRoute = route) } }
            }
            // Real network threads + advance the paused looper clock so enter/size animations finish before capture.
            repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(60) }
            repeat(10) { ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            val v = activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(out!!).mkdirs()
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SHOT_OK $name ${v.width}x${v.height}")
            controller.pause().stop().destroy()
        } finally { runCatching { server.shutdown() } }
    }

    @Test fun screens() {
        assumeTrue("screenshots run only with -Dportalx.screenshots", out != null)
        val only = System.getProperty("portalx.only")?.takeIf { it.isNotBlank() && it != "all" }?.split(",")?.toSet()
        listOf(
            Triple("01-login", false, "home"), Triple("02-home", true, "home"), Triple("03-home-checked-in", true, "home"),
            Triple("04-attendance", true, "attendance"), Triple("05-tasks", true, "tasks"), Triple("06-leave", true, "leave"),
            Triple("07-calendar", true, "calendar"), Triple("08-directory", true, "directory"), Triple("09-more", true, "more"),
            Triple("10-announcements", true, "announcements"), Triple("11-home-200pct", true, "home"), Triple("12-attendance-200pct", true, "attendance"),
        ).filter { only == null || it.first in only }.forEach { (n, s, r) -> shoot(n, s, r, if (n == "03-home-checked-in") "in" else "out") }
    }
}
