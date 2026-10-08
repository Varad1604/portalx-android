package com.pravahax.portalx.data

import android.content.Context
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalApi
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

// ---------- tolerant JSON helpers: the web sends a mix of camelCase and snake_case ----------
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
fun JsonElement?.objects(): List<JsonObject> = arr().mapNotNull { it as? JsonObject }
@JvmName("listObjects") fun List<JsonElement>.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
fun JsonObject?.str(vararg keys: String): String? {
    if (this == null) return null
    for (k in keys) {
        val v = this[k]
        if (v is JsonPrimitive && v !is JsonNull && v.content.isNotBlank()) return v.content
        if (v is JsonObject) v.str("name", "label", "title")?.let { return it } // e.g. { assignee: { name } }
    }
    return null
}
fun JsonObject?.num(vararg keys: String): Double? = str(*keys)?.toDoubleOrNull()?.takeIf { it.isFinite() }
fun JsonObject?.int(vararg keys: String): Int = num(*keys)?.toInt() ?: 0
fun JsonObject?.bool(vararg keys: String): Boolean = str(*keys)?.let { it.equals("true", true) || it == "1" } ?: false
fun JsonObject?.list(key: String): List<JsonElement> = this?.get(key).arr()

/** Number formatting without trailing ".0" (1.0 -> "1", 1.5 -> "1.5"). */
fun fmtNum(d: Double?): String = when {
    d == null -> "0"
    d == Math.floor(d) && !d.isInfinite() -> d.toLong().toString()
    else -> "%.1f".format(java.util.Locale.US, d)
}

/**
 * The record id exactly as the server sent it (number or string). v0.1.x coerced ids to Long and fell back
 * to 0, which would send `id: 0` for string/UUID ids. Returns null when there is no usable id.
 */
fun JsonObject?.idOf(vararg keys: String = arrayOf("id")): JsonPrimitive? {
    if (this == null) return null
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v is JsonNull || v.content.isBlank()) continue
        if (v.isString) v.content.toLongOrNull()?.let { return JsonPrimitive(it) } // numeric strings -> numbers, like the web's Number()
        return v
    }
    return null
}

/** Unique, stable LazyColumn keys even when ids are missing or duplicated (duplicate keys crash Compose). */
fun stableKeys(items: List<JsonObject>, prefix: String): List<String> {
    val seen = HashMap<String, Int>()
    return items.mapIndexed { i, o ->
        val base = "$prefix:" + (o.str("id") ?: "idx$i")
        val n = seen.merge(base, 1, Int::plus) ?: 1
        if (n == 1) base else "$base#$n"
    }
}

data class SessionUser(val raw: JsonObject) {
    val name get() = raw.str("name", "full_name") ?: "User"
    val firstName get() = name.trim().split(" ").firstOrNull { it.isNotBlank() } ?: name
    val userId get() = raw.str("userId", "user_id") ?: ""
    val email get() = raw.str("email", "work_email") ?: ""
    val orgRoles get() = (raw["organizationRoles"] ?: raw["organization_roles"]).arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val permissions get() = raw["permissions"].arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val role get() = if (orgRoles.contains("Organization Owner")) "super_admin" else raw.str("role") ?: "member"
    val roleLabel get() = when (role) {
        "super_admin" -> "Super Admin"; "member" -> "Employee"; "team_lead" -> "Team Lead"
        else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val mustChangePassword get() = raw.bool("mustChangePassword", "must_change_password")
    val isAdmin get() = role == "super_admin"
    fun can(p: String) = isAdmin || permissions.contains(p)
    // Mirrors the web: leave approvals = super_admin | Organization Owner | leave.manage (manager kept for older roles).
    val canApproveLeave get() = isAdmin || role == "manager" || can("leave.manage")
    val canApproveCorrections get() = isAdmin || role == "manager" || can("attendance.manage")
    val canManageTasks get() = isAdmin || role == "manager" || can("tasks.manage")
    val seesStats get() = isAdmin || role == "manager" || role == "operations" || listOf(
        "people.manage", "leave.manage", "attendance.manage", "projects.manage", "comms.manage", "tasks.manage"
    ).any { permissions.contains(it) }
}

/** Which cached reads each write makes stale, so screens refresh themselves after an action. */
object Invalidation {
    private val attendance = listOf(Fn.AttendanceToday, Fn.AttendanceHistory, Fn.DashboardStats)
    fun after(fn: Fn): List<Fn> = when (fn) {
        Fn.CheckIn, Fn.CheckOut, Fn.StartBreak, Fn.EndBreak -> attendance
        Fn.RequestCorrection, Fn.DecideCorrection -> attendance + Fn.Corrections
        Fn.ApplyLeave, Fn.DecideLeave -> listOf(Fn.MyLeave, Fn.PendingLeaveApprovals, Fn.DashboardStats, Fn.CalendarMonth)
        Fn.CreateTask, Fn.UpdateTaskStatus, Fn.AddTaskComment -> listOf(Fn.Tasks, Fn.TaskDetail, Fn.CalendarMonth)
        Fn.MarkAnnouncementRead -> listOf(Fn.Announcements)
        Fn.CreateMeeting -> listOf(Fn.Meetings, Fn.CalendarMonth)
        else -> emptyList()
    }
}

/** Repository with a small encrypted offline cache: last good response per key is kept on disk. */
class Repo(context: Context, val api: PortalApi = PortalApi(context), cacheName: String = "cache") {
    private val cache = SecureStore(context, cacheName)
    private val prefs = context.getSharedPreferences("portal_prefs", Context.MODE_PRIVATE) // non-sensitive: workspace, last user id

    private val _versions = MutableStateFlow<Map<Fn, Int>>(emptyMap())
    /** Bumped per Fn when data it returns becomes stale. Resources observe this to refetch. */
    val versions: StateFlow<Map<Fn, Int>> = _versions.asStateFlow()

    init {
        // Drop the v0.1.x plaintext response cache (it held directory/profile data in clear).
        context.getSharedPreferences("portal_cache", Context.MODE_PRIVATE).let { legacy ->
            legacy.getString("workspace", null)?.let { prefs.edit().putString("workspace", it).apply() }
            legacy.getString("lastUserId", null)?.let { prefs.edit().putString("lastUserId", it).apply() }
            legacy.getString("me", null)?.let { cache.put("me", it) }
            if (legacy.all.isNotEmpty()) legacy.edit().clear().apply()
        }
    }

    fun invalidate(fns: Collection<Fn>) {
        if (fns.isEmpty()) return
        _versions.update { m -> m + fns.associateWith { (m[it] ?: 0) + 1 } }
    }

    fun cached(key: String): JsonElement? = cache.get(key)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    suspend fun load(fn: Fn, data: JsonElement? = null, key: String = fn.name): JsonElement {
        val r = api.call(fn, data)
        // Cache only reasonably sized responses; never cache null results over good data.
        if (r !is JsonNull) { val s = r.toString(); if (s.length < 512_000) cache.put(key, s) }
        return r
    }

    /** Performs a write and marks dependent reads stale. */
    suspend fun act(fn: Fn, data: JsonElement? = null): JsonElement {
        val r = api.call(fn, data)
        invalidate(Invalidation.after(fn))
        return r
    }

    suspend fun login(workspace: String, userId: String, password: String): SessionUser {
        api.clearSession()
        clearCache()
        val ws = workspace.trim().lowercase(); val uid = userId.trim()
        val r = api.call(Fn.Login, buildJsonObject { put("workspace", ws); put("userId", uid); put("password", password) })
        val u = r.obj() ?: throw PortalException("Sign-in didn't complete. Please try again.")
        prefs.edit().putString("workspace", ws).putString("lastUserId", uid).apply()
        acceptUser(u)
        return SessionUser(u)
    }

    /** Stores a user object returned by the server (login, me, change password) and syncs the session scope. */
    fun acceptUser(u: JsonObject) {
        u.str("sessionScope")?.let { api.sessionScope = it }
        cache.put("me", u.toString())
    }

    suspend fun me(): SessionUser? {
        val r = api.call(Fn.CurrentUser).obj() ?: return null
        acceptUser(r)
        return SessionUser(r)
    }

    fun cachedMe(): SessionUser? = cached("me")?.obj()?.let { SessionUser(it) }
    fun lastWorkspace() = prefs.getString("workspace", "") ?: ""
    fun lastUserId() = prefs.getString("lastUserId", "") ?: ""

    private fun clearCache() { cache.clear(); _versions.value = emptyMap() }

    /** Best-effort server logout, then wipe cookies, scope and every cached response. Never throws. */
    suspend fun logout() {
        try { api.call(Fn.Logout) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        finally { api.clearSession(); clearCache() }
    }
}
