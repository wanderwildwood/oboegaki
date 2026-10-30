# Desktop Sync (design)

An HTTP + WebSocket server that runs inside Notes, on the phone, and serves its own web page
to a browser on a computer. No cloud, no relay. It is modelled on Messaging's Desktop Sync and
keeps its access rules; the code is Notes' own copy (NanoHTTPD, BSD-3-Clause, is the one library
added).

## When it runs

Off by default. Never started by the app, by a boot, or by an update. The switch in
Settings > Desktop Sync starts a foreground service (notification with **Stop**); switching it
off, Stop, or a failed bind stops the server and the service. With it off there is no service,
no socket, no timer and no receiver doing anything for it. A reboot leaves it off.

## Access control

Two independent gates, checked on every request (and on the WebSocket upgrade):

1. **Where from.** *VPN only* (on by default): the peer address must be loopback, in Tailscale's
   ranges (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`), or inside the subnet of another VPN interface
   the phone has up (not one that overlaps Wi-Fi/Ethernet). Anything else gets an empty 404
   before the token is looked at. Off: still only private peers (loopback, `10/8`, `172.16/12`,
   `192.168/16`, link-local, unique-local `fc00::/7`, Tailscale's `100.64/10`), by `isPrivatePeer`;
   a global IPv6 or cellular address gets the same empty 404.
2. **Token.** 24 characters from Crockford's alphabet, made on first start, kept in the app's
   private preferences. Sent as `Authorization: Bearer <token>` (HTTP) or `?token=` (WebSocket,
   which cannot set headers). Compared in constant time. *Reset link* mints a new one and
   restarts the server, so old bookmarks and open tabs stop at once.

**Pairing code.** (The Show link dialog refreshes its code by itself: when the code runs out or is used while the dialog is open, a new one replaces it, with "Works once, for 2:41" counting down once a second only while the dialog is on screen; one code is valid at a time and the five-tries lockout stands, after which no new code appears until the dialog is opened again. Messaging's Desktop Sync dialog should get the same change later.) *Show link* shows the address(es), the full tokenised link, and a six-digit
code: valid 3 minutes, one use, 5 wrong tries kill it, a new *Show link* replaces it. The browser
posts it to `POST /api/pair` (the only route that answers without a token; needs header
`X-Notes-Pairing: 1`, body at most 512 bytes) and gets the token back, then remembers it.

The server speaks plain `http`; over a VPN the tunnel encrypts it, on a home network it is
readable by others on it, and Settings and About say so. *Copy link* marks the clipboard entry
sensitive (`EXTRA_IS_SENSITIVE`, Android 13+) and clears it after 60 s if nothing else was copied.

Every response carries `Cache-Control: no-store`, a strict CSP (a per-response nonce for the one
inline script and stylesheet; `default-src 'none'`; images only from the page itself and `blob:`;
no framing), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`. No CORS headers
are ever sent, `OPTIONS` is refused, and every POST needs the custom `X-Notes: 1` header, which a
cross-origin page cannot add without a preflight that is never answered.

No note content, title, or task text is written to the log.

## Data: only through the app's own layer

The server calls `Notes` and `Tasks`, the objects the screens use, and nothing else; it never
opens the notes folder, the task files or the preferences of the sync itself. So the locks,
renames, pins, archive moves, reminder bookkeeping, Glance refresh, and the Nextcloud sync that
follow an edit on the phone follow an edit from the browser too.

| Dashboard | App |
|---|---|
| note `id` | the note's path, e.g. `Garden/kiln.md` (looked up in `Notes.list`; never built from input) |
| `title` | the file name without `.md` (renaming a title renames the file, as on the phone) |
| `text` | the Markdown without its front matter (the front matter is kept as it is) |
| checklist item | a `- [ ] item` / `- [x] item` line; `line` is its index in the whole file |
| `pinned` | in the pins file (`Notes.pins`, `Notes.togglePin`) |
| `archived` | under `Archive/` (`Notes.archive`/`unarchive`, the same move the phone makes) |
| trash | `Notes.trash`: notes in the hidden `.trash` folder (see Trash below); `id` is the note's folder in the trash, never a path |
| folder (the nearest thing to a label) | the note's folder; shown and settable on create |
| reminder | `reminder:` in front matter (note) or `⏰ yyyy-MM-dd HH:mm` on an item line |
| pictures | `![[name.jpg]]` embeds; fetched through `GET /api/file` (pictures only, only beside a note) |
| tasks | `Tasks.shown`: lists, and per task summary, description, due (day or time), priority, done |

What the app does not have is not invented: there are no colours and no labels. *Delete* moves
a note to the trash, exactly as on the phone, and can be undone from the page's *Trash*; *Delete
forever* and *Empty trash* are permanent, and the page asks first. Voice notes and scans show as
a line naming the file; they are not played or opened in the browser.

## Endpoints

All JSON, all except the first three need the token and `X-Notes: 1` on POST.

- `GET /` the dashboard (one HTML file, no external requests). Carries no data, so no token.
- `POST /api/pair` `{code}` -> `{token}`.
- `GET /ws?token=` WebSocket; the phone sends `{"type":"changed"}` when notes or tasks change;
  pings every 30 s only while a browser is connected; a peer silent for 95 s is dropped.
- `GET /api/state` everything: notes (not archived and archived), `trash` (`{id, title, folder,
  archived, deleted, until}`, newest first, times in milliseconds) and `trashDays`, folders, task
  lists, and `rev`; answers `304` when the browser's `If-None-Match` matches (the polling fallback).
- `POST /api/notes/create` `{title, text, folder?, reminder?}`
- `POST /api/notes/update` `{id, v, baseText, title, text, reminder?}` (see conflicts)
- `POST /api/notes/tick` `{id, line, expect}` ticks/unticks one line
- `POST /api/notes/additem` `{id, text}` a new item after the last
- `POST /api/notes/pin` `{id, pinned}`, `/archive` `{id, archived}`, `/delete` `{id, v?}`
  (moves the note to the trash; see conflicts for `v`; answers `{deleted, trash}`)
- `POST /api/trash/restore` `{id}` -> `{note, restored, trash}`; `/api/trash/delete` `{id}` (for
  good) -> `{deleted, trash}`; `/api/trash/empty` `{}` -> `{emptied, trash}`. `id` is a trash
  entry's id as `state` gave it out (a folder name in the trash, a number); anything else is 404.
- `POST /api/tasks/add` `{list, summary, description?, due?, priority?}`
- `POST /api/tasks/update` `{list, name, from:{...}, to:{...}}`, `/tick`, `/delete` `{list, name}`
- `GET /api/file?id=<note>&name=<picture>` the picture bytes.

## Conflicts

Each note has `v`, a version tag: the first 12 hex digits of the SHA-256 of its whole text. (A
counter kept by the server would miss edits made on the phone, which do not pass through it;
a hash of the content cannot.) An edit from the browser says which `v` it started from, and
the text it started from.

1. If the note on the phone still has that `v` (or the same body), the browser's text is written.
2. Otherwise both sides changed it. The app's own line merge (`sync.merge`) is tried with the
   browser's starting text as the base; if the two touched different lines, both changes are kept.
3. If they touched the same lines, the later write wins, **and the text it replaced is not
   lost**: it is added at the end of the note as a separate paragraph headed `## Edited elsewhere`.
   The reply says `conflict: "kept-both"` and the page tells the person.

**Trashing is a move, not a delete.** The note, with its recordings and scans, goes to the trash
and nothing is lost for 7 days. A request to trash a note says which `v` it was looking at; if
the note has changed since (an edit on the phone, a sync), it is refused with `409` and the page
reloads, so words the person never saw are not trashed on their behalf. Without `v` the note is
trashed as it is. Restoring a note that a name now stands in the way of keeps both: the restored
one is "name (1)", and nothing is written over.

A tick is one character on one line, and is applied to the note as it is now; if the line at that
index is no longer the item the browser meant (`expect`), it is refused with 409 and the page
reloads. Task edits send the fields they started from, and only fields that changed are written,
which is how the phone's own task screen already behaves, so a due date changed on the phone is
not undone by a title edited in the browser.

## Trash

Deleting, on the phone or here, moves the note to `.trash/<moment>/` inside the notes folder,
with the recordings and scans only it links and a `.meta` file saying where it came from
(`from: Garden/kiln.md`) and when (`at:` in milliseconds). Archive is a visible folder that syncs;
the trash is a hidden one, and every walk of the notes skips a name that starts with a dot, so a
trashed note is never listed, searched, synced to Nextcloud or WebDAV (where the move looks like
a deletion, as before), handed to Glance, or counted; a pin is dropped. Its reminders are kept
silent beside it and ring again if it is restored; a purged note's go. *Restore* puts it back in
its folder (the top of the notes if that folder has gone, "name (1)" if the name is taken).
Notes older than 7 days (`TRASH_DAYS`, one constant) are removed when the app starts. The page's
*Trash* lists them with the day they were deleted, *Restore*, *Delete forever* and *Empty trash*,
the last two behind a confirm. Tasks are not in it: they come from CalDAV and have their own delete.

## Limits

Request body 512 KB (pairing: 512 bytes). Note text 200,000 characters, title 120, folder 200 and
no `..`/dot-segments/archive (the app's `cleanFolder`), 3,000 characters per added item, reminder
must parse as `yyyy-MM-dd HH:mm`. Task summary 500, description 20,000. Pictures up to 8 MB and
only `jpg/jpeg/png/webp` that are linked by a note. Unknown JSON fields are ignored; wrong types
are 400.

## Idle cost

Off: nothing. On: a listening socket and a service; the flows that tell the page "changed" are
collected only while the server runs; the keep-alive ping runs only while a browser is connected.
Nothing in the app's own UI redraws for it: the Settings rows are static text (no countdown).

## Known delay

A tick or edit made on the phone reaches an open browser at once when it comes from a sync or
another app, but one typed in the phone's own note editor is noticed when the note is left or at
the next ten-second look at the notes folder, so up to about ten seconds. Changes from the
browser reach the phone at once.
