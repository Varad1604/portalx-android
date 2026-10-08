# Portal One wire protocol (mapped from the web bundle, 2026-10-07; revised for app v0.2.0)

The web app is TanStack Start. There is no REST API: every call is a *server function*.

- URL: `https://portal.pravahax.com/_serverFn/<sha256 id>`
- Headers: `x-tsr-serverFn: true`, `accept: application/json`, **`Origin: https://portal.pravahax.com`** (without it Cloudflare/the app returns 403).
- POST body / GET `?payload=`: seroval JSON of `{ data: {...} }`, wrapped as `{"t":<node>,"f":127,"m":[]}`.
  Strings inside are JS-escaped (`\\`, `\"`, `\n`, `<` -> `\x3C`).
- Response: header `x-tss-serialized: true`, body is a seroval node of `{ result, error, context }`.
  Errors come back as HTTP 200 with `error` = `$TSR/Error` plugin node `{ message }` (e.g. "Invalid User ID or password.").
- Auth: cookie session set by `login` PLUS header `x-portal-session-scope: <user.sessionScope>` on every call ("anonymous" before login). Missing/wrong scope -> error "Your company session changed. Reload the page before continuing." App refreshes scope via CurrentUser and retries once.
- RISK: ids are content hashes and change on every web redeploy. All ids live in `app/src/main/java/com/pravahax/portalx/net/PortalApi.kt` (enum `Fn`). Long-term fix: a versioned REST/JSON API.

| Fn | Method | Input `data` | Output (as used by web) |
|---|---|---|---|
| Login 207a5ea9 | POST | workspace, userId, password | user {name, userId, role, organizationRoles[], permissions[], mustChangePassword} |
| CurrentUser 286e28ba | GET | – | user |
| Logout 52b2ebc2 | POST | – | – |
| ChangePassword da74c847 | POST | currentPassword, newPassword (web enforces >= 12 chars) | updated user (web calls setUser with it) |
| RequestPasswordReset 3515ccd4 | POST | workspace, userId | – |
| DashboardStats 43ad996c | GET | – | activeUsers, presentToday, onLeaveToday, pendingCorrections, pendingLeaves, activeProjects, myPendingApprovals |
| DashboardDetails 6cb37f53 | POST | type (active_users, present_today, on_leave, pending_corrections, pending_leaves, active_projects) | list (shape unverified) |
| AttendanceToday 79faa9f7 | GET | – | check_in, check_out, is_on_break, last_break_start, total_break_seconds, onLeave, leaveType |
| AttendanceHistory 38efe2fe | GET | – | [ {attendance_date, check_in, check_out, total_break_seconds, status} ] |
| CheckIn 16829df7 / CheckOut 33e4d807 | POST | selfie (data:image/jpeg;base64) | – |
| StartBreak 20c2bcc2 | POST | breakType "lunch" | – |
| EndBreak 3194259e | POST | – | – |
| RequestCorrection 4040d2b1 | POST | date, reason, note | – |
| Corrections 91c01d0a / DecideCorrection 5cab5bc5 | GET / POST | – / id (as received), decision approved/rejected | [{id,name,memberUserId,date,reason,checkIn,checkOut,note?,status?}] |
| MyLeave 797eb922 | GET | – | balances[{typeId,type,remaining,allocated,used,paid,code}], requests[{id,type,startDate,endDate,days,status,reviewer}] |
| PendingLeaveApprovals 035ac67e | GET | – | [{id,name,memberUserId,type,startDate,endDate,days,reason}] |
| ApplyLeave 621aa543 | POST | leaveTypeId (Number), startDate, endDate (YYYY-MM-DD), halfDay, reason | – |
| DecideLeave 9655ef81 | POST | id (as received), decision approved/rejected | – (status colours: pending amber, approved green, rejected red, cancelled slate) |
| Tasks 5a47778b | GET | – | [{id,title,status,priority,priorityLabel,projectName,dueDate,assignee}] |
| TaskDetail aa093f07 | GET | taskId | task + statusLabel + comments[{id,body,author,at}] |
| CreateTask f43d6a7a | POST | title, description, projectId (Number or null), assigneeId (Number or null), priority, dueDate ("" when unset) | – |
| UpdateTaskStatus 3fd0524b | POST | taskId, status todo/in_progress/review/done | – |
| AddTaskComment aa29f952 | POST | taskId, body | – |
| Projects 485b5727 | GET | – | [{id,name}] |
| ActivePeople 849c483f / Directory 9ff8f804 | GET | – | [{id,name,userId,team,manager,designation,role,roleLabel,status,organizationRoles}] |
| Announcements 5f3b2ada | GET | – | {published[{id,title,body,author,publishedAt,teamName,readByMe,canEdit}], drafts[]} |
| MarkAnnouncementRead 843a47b6 | POST | id | – |
| Meetings e9a4ba23 / CreateMeeting 80c10974 | GET / POST | – / title, description, startAt, endAt, location, link, participantIds | [{id,title,startAt,endAt,location,link,organizer,description}] |
| CalendarMonth 5baa12ac | GET | year, month (1-12) | {holidays[{id,name,date}], meetings[], tasks[], leave[{name,startDate,endDate}], projectDeadlines[{name,endDate}]} |

~90 more server functions exist (admin, documents, performance, custom modules); they're opened in the browser from the app's More tab for now.

## Verified details (v0.2.0 audit, from the 2026-10-07 bundle)

- Client user normalisation (`R0` in index-B3c43WmQ.js): name|full_name, email|work_email, userId|user_id, organizationRoles|organization_roles,
  role = "super_admin" if organizationRoles includes "Organization Owner", mustChangePassword|must_change_password, sessionScope, id = Number(id).
- The web clears its query cache whenever `sessionScope` changes; the app clears its encrypted cache on login/logout.
- Selfie: web captures the front camera to a canvas at the video's size (typically 640x480) and sends `canvas.toDataURL("image/jpeg", .8)`.
  The app sends a JPEG (quality 80) downscaled to <= 960 px on the long edge, EXIF-rotated.
- Correction request default date is the browser-local date (`toLocaleDateString("en-CA")`); reasons: forgot_check_in, forgot_check_out,
  late_arrival, early_departure, other.
- Leave approvals visible to: super_admin, Organization Owner, or `leave.manage` permission. Tasks manage: super_admin, manager, `tasks.manage`.
- Additional server fns seen but not used by the app (ids from fnmap.txt): time-management f/h/k/l/o (approved-leave management, revoke),
  work-service b/d/f/g/s (task edit/unassign/projects detail), communications c/d/p/s (announcement drafts/publish, meeting edits).
- Availability: on 2026-10-07 ~18:28 UTC the portal intermittently returned Cloudflare `530 / error code: 1033` (tunnel down) as plain text.
  The app maps non-JSON 5xx to "Portal One is temporarily unavailable" and never signs the user out for it.

## Web design tokens (assets/app-CR0H9OFW.css)

- Fonts: `--font-sans: "Manrope"`, `--font-display`/`--font-serif: "Playfair Display"` (fallback "Cormorant Garamond"). No dark mode in CSS.
- `--portal-primary #b8832b`, `--portal-secondary #c8953c`, `--portal-primary-soft #f4eadb`, `--portal-secondary-soft #f7ede0`, `--portal-on-primary #fff`.
- Tailwind `slate` is remapped warm: 50 #fbfaf7, 100 #f4f0e7, 200 #e7dfd0, 300 #d2c4ac, 400 #ae9d83, 500 #8d7b62, 600 #6f5e4a, 700 #534636,
  800 #352c24, 900 #282018, 950 #17120e. Tailwind `indigo` is remapped to gold: 50 #fdf8ed ... 600 #ad7824 ... 950 #30200f.
- Radii: md .375rem, lg .5rem, xl 1rem, 2xl 1.375rem. Shadows: soft `0 1px 2px #15110d0d, 0 10px 28px -12px #15110d1f`; lifted `0 2px 6px #15110d0f, 0 28px 56px -20px #15110d38`.
- Brand panel: `linear-gradient(145deg,#241a10,#100d0a)` + gold radial glow; kicker colours #e0bd76 (on dark) / #9b6c1c (on light).

## Fragility / recommendation

Server-function ids are content hashes of the web build and change on every redeploy; when they do, every call returns 404/HTML and the app shows
"This feature isn't available right now. The app may need an update." The durable fix is a small versioned JSON API (e.g. `/api/mobile/v1/...`)
with the same session cookie + scope header, or at minimum a stable alias map served from the portal (e.g. `/mobile-manifest.json` mapping
names to current hashes) that the app fetches at start-up.
