package com.pravahax.portalx.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pravahax.portalx.data.*
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class Stat(val type: String, val label: String, val count: Int, val icon: ImageVector, val alert: Boolean = false)

@Composable
fun HomeScreen(user: SessionUser, navigate: (String) -> Unit) {
    val stats = rememberResource(Fn.DashboardStats, enabled = user.seesStats)
    val today = rememberResource(Fn.AttendanceToday)
    val tasks = rememberResource(Fn.Tasks)
    val leave = rememberResource(Fn.MyLeave)
    val ann = rememberResource(Fn.Announcements)
    val refreshing = stats.refreshing || today.refreshing || tasks.refreshing || leave.refreshing || ann.refreshing
    var detail by remember { mutableStateOf<Stat?>(null) }

    RefreshList(refreshing, { stats.refresh(); today.refresh(); tasks.refresh(); leave.refresh(); ann.refresh() }) {
        item(key = "greet") {
            Column(Modifier.padding(top = Space.xs, bottom = Space.xs)) {
                Kicker(Dates.today().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale.ENGLISH)))
                Spacer(Modifier.height(Space.xs))
                Text("${greeting()}, ${user.firstName}", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (user.userId.isNotBlank()) StatusChip(user.userId, MaterialTheme.colorScheme.primary, dot = false)
                    Text(user.roleLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val firstErr = listOf(today, tasks, stats).firstOrNull { it.error != null }
        firstErr?.let { r -> item(key = "err") { ErrorBanner(r.error ?: "", stale = r.stale) { today.refresh(); tasks.refresh(); stats.refresh() } } }

        item(key = "hero") { AttendanceHero(today, navigate) }

        if (user.seesStats) {
            item(key = "ovh") { SectionHeader("Overview") }
            item(key = "ov") {
                val s = stats.data.obj()
                fun n(k: String) = if (s.num(k) == null) -1 else s.int(k) // "—" rather than a fake 0 when the value is missing
                val list = listOf(
                    Stat("active_users", "Active users", n("activeUsers"), Icons.Outlined.Groups),
                    Stat("present_today", "Present today", n("presentToday"), Icons.Outlined.HowToReg),
                    Stat("on_leave", "On leave", n("onLeaveToday"), Icons.Outlined.BeachAccess),
                    Stat("pending_corrections", "Pending corrections", n("pendingCorrections"), Icons.Outlined.EditCalendar, s.int("pendingCorrections") > 0),
                    Stat("pending_leaves", "Pending leaves", n("pendingLeaves"), Icons.Outlined.PendingActions, s.int("pendingLeaves") > 0),
                    Stat("active_projects", "Active projects", n("activeProjects"), Icons.Outlined.FolderOpen),
                )
                if (stats.initialLoading) Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) { repeat(2) { Skeleton(118.dp, Modifier.weight(1f)) } }
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md), contentPadding = PaddingValues(end = Space.sm)) {
                    items(list, key = { it.type }) { st -> StatTile(st) { detail = st } }
                }
            }
            val n = stats.data.obj().int("myPendingApprovals")
            if (n > 0) item(key = "appr") {
                FilledTonalButton(onClick = { navigate("leave") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Outlined.Inbox, null); Spacer(Modifier.width(Space.sm)); Text("Review $n leave request${if (n > 1) "s" else ""}")
                }
            }
        }

        item(key = "th") { SectionHeader("Open tasks", "View all") { navigate("tasks") } }
        // Most urgent first: overdue/soonest due date, undated last.
        val open = tasks.data.objects().filter { it.str("status") != "done" }
            .sortedBy { Dates.day(it.str("dueDate", "due_date"))?.toEpochDay() ?: Long.MAX_VALUE }.take(5)
        when {
            tasks.initialLoading -> item(key = "tsk") { Skeleton(160.dp) }
            open.isEmpty() -> item(key = "tempty") {
                SectionCard { ListRow("You're all caught up", "No open tasks right now. New assignments will appear here.", leading = { CircleIcon(Icons.Outlined.TaskAlt, PortalTheme.status.success) }) }
            }
            else -> item(key = "tlist") {
                SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    open.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val overdue = Dates.isOverdue(t.str("dueDate"), false)
                        ListRow(
                            t.str("title") ?: "Untitled",
                            listOfNotNull(t.str("projectName"), t.str("dueDate")?.let { (if (overdue) "Overdue · " else "Due ") + fmtShortDate(it) }).joinToString(" · "),
                            trailing = { StatusChip(t.str("priorityLabel") ?: pretty(t.str("priority")), statusColor(t.str("priority"))) },
                            onClick = { navigate("tasks") },
                        )
                    }
                }
            }
        }

        item(key = "lh") { SectionHeader("Leave balance", "Manage") { navigate("leave") } }
        item(key = "lb") {
            val balances = leave.data.obj().list("balances").objects()
            when {
                leave.initialLoading -> Skeleton(110.dp)
                balances.isEmpty() -> SectionCard { Text("No leave types configured yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(balances.size, key = { "bal:" + (balances[it].str("typeId", "type") ?: it.toString()) + ":" + it }) { i -> BalanceTile(balances[i]) }
                }
            }
        }

        item(key = "ah") { SectionHeader("Announcements", "View all") { navigate("announcements") } }
        val published = ann.data.obj().list("published").objects().take(3)
        item(key = "al") {
            when {
                ann.initialLoading -> Skeleton(120.dp)
                published.isEmpty() -> SectionCard { Text("No announcements yet. Company updates will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> SectionCard(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
                    published.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(a.str("title") ?: "Announcement", a.str("body"), onClick = { navigate("announcements") },
                            trailing = if (!a.bool("readByMe")) ({ Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }) else null)
                    }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(Space.xxl)) }
    }

    detail?.let { st -> StatDetailSheet(st.type, st.label) { detail = null } }
}

@Composable
fun CircleIcon(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Today's attendance at a glance, with the day's ONE next action right on Home:
 * not checked in → "Check in" (opens the selfie camera directly), working → Break / Check out, on break → Resume.
 * Light brand panel: primary-soft → white with the web's gold corner glow.
 */
@Composable
private fun AttendanceHero(today: Resource, navigate: (String) -> Unit) {
    val t = today.data.obj()
    val checkIn = t.str("check_in", "checkIn"); val checkOut = t.str("check_out", "checkOut")
    val onLeave = t.bool("onLeave", "on_leave")
    val onBreak = t.bool("is_on_break", "isOnBreak")
    val active = checkIn != null && checkOut == null
    val online = LocalOnline.current
    val ctl = rememberAttendanceController(onAlreadyDone = { today.refresh() })
    val now by rememberNow(30_000)
    val p = PortalTheme.status
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        onClick = { navigate("attendance") }, shape = shape, color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().shadow(10.dp, shape, clip = false, ambientColor = Web.ShadowInk.copy(alpha = 0.10f), spotColor = Web.ShadowInk.copy(alpha = 0.14f))
            .semantics { contentDescription = "Today's attendance. Opens Attendance." },
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(Web.PrimarySoft, Color.White))).goldGlow().padding(Space.xl).animateContentSize()
        ) {
            if (today.initialLoading) {
                Kicker("Today"); Spacer(Modifier.height(Space.sm)); Skeleton(28.dp, Modifier.fillMaxWidth(0.6f), MaterialTheme.shapes.small)
                Spacer(Modifier.height(Space.md)); Skeleton(52.dp, shape = MaterialTheme.shapes.medium)
                return@Column
            }
            val (dot, title) = when {
                t == null -> p.neutral to "Attendance"
                onLeave -> p.warning to "On approved leave"
                checkIn == null -> p.danger to "Not checked in yet"
                checkOut == null -> if (onBreak) p.warning to "On a break" else p.success to "Checked in · ${fmtTime(checkIn)}"
                else -> p.success to "Day complete"
            }
            Kicker("Today's attendance")
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(Space.sm))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val sub = when {
                checkIn != null && checkOut != null -> "${fmtTime(checkIn)} – ${fmtTime(checkOut)}" + ((t.num("total_break_seconds") ?: 0.0).toLong().takeIf { it > 0 }?.let { " · break ${fmtDuration(it)}" } ?: "")
                active -> {
                    val start = parseInstant(checkIn)?.toInstant()?.toEpochMilli()
                    val brk = (t.num("total_break_seconds") ?: 0.0).toLong()
                    start?.let { "${fmtDuration(((now - it) / 1000 - brk).coerceAtLeast(0))} worked so far" }
                }
                onLeave -> t.str("leaveType")?.let { "$it · check-in is disabled today" } ?: "Check-in is disabled today"
                t == null -> today.error ?: "Pull down to refresh."
                else -> if (online) "One tap, a quick selfie, and you're in." else "You're offline. Reconnect to check in."
            }
            sub?.let { Spacer(Modifier.height(Space.xs)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            if (t != null && !onLeave && checkOut == null) {
                Spacer(Modifier.height(Space.lg))
                val enabled = online && !ctl.busy
                when {
                    checkIn == null -> Button(onClick = ctl.checkIn, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(Space.sm)); BusyLabel(ctl.busy, "Check in")
                    }
                    onBreak -> Button(onClick = ctl.endBreak, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(Space.sm)); BusyLabel(ctl.busy, "Resume work")
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        OutlinedButton(onClick = ctl.startBreak, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)) {
                            Icon(Icons.Outlined.Coffee, null); Spacer(Modifier.width(6.dp)); Text("Break")
                        }
                        Button(onClick = ctl.checkOut, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null); Spacer(Modifier.width(6.dp)); BusyLabel(ctl.busy, "Check out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(s: Stat, onClick: () -> Unit) {
    SectionCard(Modifier.width(152.dp), onClick = onClick, padding = PaddingValues(Space.md + Space.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(s.icon, MaterialTheme.colorScheme.primary, 34.dp)
            Spacer(Modifier.weight(1f))
            if (s.alert) Box(Modifier.size(8.dp).clip(CircleShape).background(PortalTheme.status.danger))
        }
        Spacer(Modifier.height(Space.md))
        Text(if (s.count < 0) "—" else "${s.count}", style = MaterialTheme.typography.headlineLarge)
        Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BalanceTile(b: JsonObject) {
    SectionCard(Modifier.width(164.dp)) {
        Text(b.str("type") ?: "Leave", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtNum(b.num("remaining")), style = MaterialTheme.typography.headlineLarge)
            Text(" / ${fmtNum(b.num("allocated"))}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        val alloc = b.num("allocated") ?: 0.0; val rem = b.num("remaining") ?: 0.0
        LinearProgressIndicator(
            progress = { if (alloc > 0) (rem / alloc).toFloat().coerceIn(0f, 1f) else 0f },
            Modifier.fillMaxWidth().padding(top = Space.sm).height(6.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatDetailSheet(type: String, label: String, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val expired = LocalSessionExpired.current
    var items by remember { mutableStateOf<List<JsonObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(type, tick) {
        err = null
        try { items = repo.api.call(Fn.DashboardDetails, buildJsonObject { put("type", type) }).objects() }
        catch (e: CancellationException) { throw e }
        catch (e: PortalException) { if (e.code == 401) expired() else err = e.message ?: "Could not load details." }
        catch (e: Exception) { err = "Could not load details." }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxxl).navigationBarsPadding()) {
            Text(label, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Space.md))
            val list = items
            when {
                err != null -> ErrorBanner(err ?: "") { tick++ }
                list == null -> SkeletonList(3, 56.dp)
                list.isEmpty() -> Text("Nothing here right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    list.take(100).forEach { o ->
                        val name = o.str("name", "full_name", "title", "projectName") ?: "—"
                        ListRow(name, listOfNotNull(o.str("userId", "user_id", "memberUserId"), o.str("team", "type", "status"), o.str("check_in")?.let { "In ${fmtTime(it)}" }).joinToString(" · "),
                            leading = { Avatar(name, 36.dp) })
                    }
                }
            }
        }
    }
}

