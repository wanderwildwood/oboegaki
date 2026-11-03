# Desktop Sync (design)

An HTTP + WebSocket server that runs inside Notes, on the phone, and serves its own web page
to a browser on a computer. No cloud, no relay. It is modelled on Messaging's Desktop Sync and
keeps its access rules; the code is Notes' own copy (NanoHTTPD, BSD-3-Clause, is the one library
added).

## When it runs

Off by default. The switch in Settings > Desktop Sync starts a foreground service (notification
with **Stop**); switching it off, Stop, or a failed bind stops the server and the service. With it
off there is no service, no socket and no timer; the restart receiver wakes at boot and after an
update, sees it off, and does nothing.

Once on, it stays on until the reader switches it off. The switch (`desktop_wanted`) is cleared
only by Stop and by a start that fails (the port cannot be bound); a failure is kept with its
reason and Settings shows it once. Everything else brings it back, by whichever route Android 12
allows first: a receiver for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (both may start a
foreground service from the background), the service being sticky, the process starting
(`MemoApplication`), and Memo being opened. A start Android refuses leaves the switch on for the
next route. Switching it on asks for Memo to be left out of battery optimization, which also lets
the background starts through.

## Access control

Two independent gates, checked on every request (and on the WebSocket upgrade):

1. **Where from.** *VPN only* (on by default): the peer address must be loopback, in Tailscale's
   ranges (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`), or inside the subnet of another VPN interface
   the phone has up (not one that overlaps Wi-Fi/Ethernet). Anything else gets an empty 404
   before the token is looked at. Off: still only private peers (loopback, `10/8`, `172.16/12`,
   `192.168/16`, link-local, unique-local `fc00::/7`, Tailscale's `100.64/10`), by `isPrivatePeer`;
   a global IPv6 or cellular address gets the same empty 404.
2. **Session token, one per browser.** Made when a browser pairs: 32 random bytes from
   `SecureRandom`, as 64 hex characters, handed to that browser once and kept by it. The phone
   keeps only the token's SHA-256, with a label ("Firefox on Linux", from the User-Agent), the
   time it was made and the time it was last used, in the app's private preferences (see
   `PairedBrowsers`). Sent as `Authorization: Bearer <token>` (HTTP) or `?token=` (WebSocket, which
   cannot set headers), checked on every request against every entry, compared in constant time,
   never logged. At most 8 browsers; a ninth pairing replaces the one unused longest. A token not
   used for 30 days is gone. Settings > Desktop Sync lists the paired computers; tapping one arms
   its row (six seconds), a second tap removes it, and *Remove all* does the same for everyone. A
   removal closes that browser's open page at once. There is no shared token and no link with a
   token in it: the one permanent token earlier versions handed to every browser was dropped, so a
   browser that paired before pairs again once (the page says so when it finds itself unpaired).

**Pairing code.** (The Show link dialog refreshes its code by itself: when the code runs out or is used while the dialog is open, a new one replaces it, with "Works once, for 2:41" counting down once a second only while the dialog is on screen; one code is valid at a time and the five-tries lockout stands, after which no new code appears until the dialog is opened again. Messaging's Desktop Sync dialog should get the same change later.) *Show link* shows the address(es) and a six-digit
code: valid 3 minutes, one use, 5 wrong tries kill it, a new *Show link* replaces it. The browser
posts it to `POST /api/pair` (the only route that answers without a token; needs header
`X-Notes-Pairing: 1`, body at most 512 bytes) and gets its own session token back, then remembers it.

The server speaks plain `http`; over a VPN the tunnel encrypts it, on a home network it is
readable by others on it, and Settings and About say so. *Copy link* marks the clipboard entry
sensitive (`EXTRA_IS_SENSITIVE`, Android 13+) and clears it after 60 s if nothing else was copied.

Every response carries `Cache-Control: no-store`, a strict CSP (a per-response nonce for the one
inline script and stylesheet; `default-src 'none'`; images only from the page itself and `blob:`;
sound only from `blob:`; the typeface only from the page itself;
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
| folder (the nearest thing to a label; Nextcloud Notes' category) | the note's folder; set on create, changed with `POST /api/notes/move` (`Notes.moveTo`, the phone's *Move to folder*; the folder is checked by `moveTarget`, never used as sent) |
| reminder | `reminder:` in front matter (note) or `⏰ yyyy-MM-dd HH:mm` on an item line |
| pictures | `![[name.jpg]]` embeds; fetched through `GET /api/file` (pictures only, only beside a note); added with `POST /api/notes/picture` (see Pictures from the computer) |
| tasks | `Tasks.shown`: lists, and per task summary, description, due (day or time), priority, done |

What the app does not have is not invented: there are no colours and no labels. *Delete* moves
a note to the trash, exactly as on the phone, and can be undone from the page's *Trash*; *Delete
forever* and *Empty trash* are permanent, and the page asks first.

**Voice notes and scans.** A voice note plays in the browser, with seeking. On a card its *Play
voice note* button sits where the recording is in the note, so the words the phone wrote under it
(`Notes.addUnder`) follow it; the recording is fetched only when it is pressed. In an open note
each recording has its player with its transcript under it (the paragraph after its line; it is
body text, so it is corrected in the text above), or a line saying the phone has not listened yet. A scan's PDF opens in a new tab in the browser's own
PDF viewer (*Open*), or is saved (*Save*). Both come through `GET /api/media` with the token in
the `Authorization` header like every other route. There is no link with a token in it, so the
page fetches the file and plays or shows it from memory (a `blob:` address), which is also what
lets a recording seek anywhere. The server answers a `Range` (206, 416 past the end), as
Messaging's does for audio, so a long recording can be asked for in parts. Play-only and
view-only: neither can be recorded, changed or added from the browser.

**Checklist items.** Beside each item, on a card and in an open note, a ⋯ opens what holding an
item does on the phone: *Remind me* (a day-and-time picker; *Set*, or *Take the reminder off*),
*Move to Tasks · <list>* and *Remove item* (tap, then tap again within six seconds). Each goes
through the phone's own code: the reminder is `withItemReminder` and rings on the phone
(`Reminders.claim`, as the Remind dialog does), and a time gone by is refused as there; Move to
Tasks is `itemAsTask`/`itemRingsHere` (remind/ItemToTask.kt, which the phone's menu now calls
too): the line leaves the note and becomes a task in the default list, its reminder the due time,
ringing here if it rang here; Remove is `removeLine`, and a reminder on the line goes with it.
Like a tick, each names the item by its line and its words, and is refused with 409 if the line
no longer says that. In an open note, what is typed is saved first.

**Pictures, full size.** A picture on a card or in an open note opens over the whole window,
fitted; a click (or *Full size / fit*) shows it at its own size, to scroll about, and back. Esc
or *Close* shuts it. Nothing new on the server: it is the same `GET /api/file`.

**Pictures from the computer.** An open note has *Add picture*: a JPEG, PNG or WebP, up to 8 MB,
put at the end of the note as its own paragraph and kept beside it, named for the note as a
shared-in picture is (`Notes.addPicture`, sharing `keepPicture` with `savePictures`). The body
of `POST /api/notes/picture?id=<note>` is the picture itself; the token is in the header as
everywhere, the note in the address. What it is comes from its first bytes, never its name or
the type the browser says (`cleanUpload`, desktop/Upload.kt). It is never kept as it came: it
is rewritten with only what draws it, so Exif (GPS, time, camera), XMP, IPTC, comments, PNG
text chunks, JFIF thumbnails and anything after the picture's end (where some phones put a
second picture or a video) are left out; a JPEG keeps only its orientation, as a one-entry Exif.
The phone must then be able to read its size (`BitmapFactory`), or it is refused. 413 over 8 MB
(by the declared length, before a byte is read), 415 for anything else. (The phone's own
share-in path, `savePictures`, keeps a JPEG, PNG or WebP as it came, Exif included; only other
kinds are re-encoded there.)

**Repeating tasks.** A task's rule is shown in words ("Every 2 weeks on Monday", "Every month,
until 2026-12-31"; `repeatWords`, from its RRULE), or just *Repeats* for a rule the words don't
cover. The task editor says what a tick does, as the phone's task screen does: it moves the task
on (`movesOn`), or, for a rule Memo can't follow, finishes it. A rule can't be set from the page.

**Tasks to another list.** The task editor has *List* (only lists that can take tasks, shown when
there is more than one): `POST /api/tasks/move`, `Tasks.move`, then the fields are written, the
order the phone's task screen uses.

**Sharing.** With the notes on Nextcloud, an open note has *Share*: who it is shared with, a *Can
edit* box beside each, *Stop sharing* (tap, then tap again within six seconds), and the people it
could be shared with. A new share can edit, as on the phone. A note someone shared with this
account says by whom. It is the phone's Share screen as data: the server calls the app's own
`Sharing` (sync/Shares.kt), syncs the note up first as the phone does, and names a person or a
share only by what Nextcloud just listed for that note (`requirePerson`, `requireShare`), so the
page cannot stop a share of some other file or share with someone not on the server. Cards say
*Shared* or *Shared with you* (`shared`, `sharedWithMe` in the state, from `Notes.shared` and
`Notes.sharedWithMe`). On WebDAV or on the phone alone there is no sharing and no *Share* button.

## Endpoints

All JSON, all except the first three need the token and `X-Notes: 1` on POST.

- `GET /` the dashboard (one HTML file, no external requests). Carries no data, so no token.
- `GET /fonts/lato-regular.ttf`, `/fonts/lato-bold.ttf` the page's typeface, Lato (SIL OFL 1.1),
  out of the app's own resources (it comes inside MMD). No token: a stylesheet's `@font-face`
  cannot send one, and a typeface says nothing about the notes. Nothing else answers without it.
- `POST /api/pair` `{code}` -> `{token}` (a new session token for this browser).
- `GET /ws?token=` WebSocket; the phone sends `{"type":"changed"}` when notes or tasks change;
  pings every 30 s only while a browser is connected; a peer silent for 95 s is dropped.
- `GET /api/state` everything: notes (not archived and archived), `trash` (`{id, title, folder,
  archived, deleted, until}`, newest first, times in milliseconds) and `trashDays`, folders, task
  lists, and `rev`; answers `304` when the browser's `If-None-Match` matches (the polling fallback).
- `POST /api/notes/create` `{title, text, folder?, reminder?}`
- `POST /api/notes/update` `{id, v, baseText, title, text, reminder?}` (see conflicts)
- `POST /api/notes/tick` `{id, line, expect}` ticks/unticks one line
- `POST /api/notes/additem` `{id, text}` a new item after the last
- `POST /api/notes/removeitem` `{id, line, expect}` takes the item out (`removeLine`)
- `POST /api/notes/itemreminder` `{id, line, expect, reminder}` sets the item's reminder
  (`yyyy-MM-dd HH:mm`, still to come) or takes it off (`null`); it rings on the phone
- `POST /api/notes/itemtotasks` `{id, line, expect}` the item becomes a task in the default list
  -> `{note, list, task}`
- `POST /api/notes/picture?id=<note>` body: the picture's bytes -> `{note}` (see Pictures from the computer)
- `POST /api/notes/pin` `{id, pinned}`, `/archive` `{id, archived}`, `/delete` `{id, v?}`
  (moves the note to the trash; see conflicts for `v`; answers `{deleted, trash}`)
- `POST /api/trash/restore` `{id}` -> `{note, restored, trash}`; `/api/trash/delete` `{id}` (for
  good) -> `{deleted, trash}`; `/api/trash/empty` `{}` -> `{emptied, trash}`. `id` is a trash
  entry's id as `state` gave it out (a folder name in the trash, a number); anything else is 404.
- `POST /api/tasks/add` `{list, summary, description?, due?, priority?}`
- `POST /api/tasks/update` `{list, name, from:{...}, to:{...}}`, `/tick`, `/delete` `{list, name}`
- `POST /api/tasks/move` `{list, name, to}` to another list that can take tasks -> `{list, task}`
- `GET /api/file?id=<note>&name=<picture>` the picture bytes.
- `GET /api/media?id=<note>&name=<file>` a recording (`m4a mp3 wav ogg opus aac`) or a scan
  (`pdf`) the note embeds, streamed, with `Accept-Ranges: bytes`; one `bytes=` range is answered
  with 206 and `Content-Range`, several or a malformed one with the whole file, one past the end
  with 416. The name is a plain file name beside the note (`isSafeMediaName`), nothing else.
- `GET /api/shares?id=<note>` -> `{available, shares:[{id, with, name, canEdit}], people:[{id, name}],
  sharedWithMe, from}` (`{available:false}` off Nextcloud). Syncs first, so it can take seconds.
- `POST /api/shares/add` `{id, person, canEdit?}`, `/edit` `{id, share, canEdit}`, `/stop`
  `{id, share}`, each answering as `GET /api/shares`. A Nextcloud failure is 503 with its words.
- `POST /api/unpair` `{}` this browser forgets itself: its own token is removed on the phone,
  and only its own (the cog's *Forget this computer*).

Which routes need the token is decided in one place, `Routes.kt` (`gate` and `API_ROUTES`); the
server answers no route that is not in that list, and `RoutesTest` walks the list and proves each
one is refused without a token, with a token the phone never gave out, and (POST) without
`X-Notes: 1`.

## The page's look

Messaging's: black on white (a dark theme under the cog, *Auto*, *Light* or *Dark*, kept per
browser), Lato, the app's name in bold over a 3 px rule, the connection in words beside the cog
("connecting…", "live", "reconnecting…", "the phone did not answer"). Ctrl/Cmd+F or `/` goes to
the search box; in an open note Ctrl/Cmd+F is the browser's own find. Esc closes the cog's panel
and an open note. Cards are kept, in ink: a thin black border, a thicker one under the pointer.

**Whole pages, no scrollbars.** As in the apps, nothing scrolls and no scrollbar is drawn. The
notes, the trash, the tasks and the side list show what fits, whole, and turn by whole pages:
the chevrons at the right (shown only when there is more than one page), the mouse wheel (one
page per push), Page Up / Page Down, and Home / End. Cards are laid in columns, each under the
shortest; a card that would run past the foot of the window starts the next page, and a
heading (*Pinned*, *Others*, a task list's name) is never left alone at the foot of one. A card's
picture has a set height, so the page knows what fits before the picture arrives. The page
shown is remembered by its first item for each view, folder and search, so a change from the
phone, a tick or a resized window keeps the same notes in view. An open note and the cog's
panel are not lists: the note's text moves with its cursor, without a bar.

**Views and order.** The side list has the phone's views beside the page's own: *Notes*,
*Reminders*, *Tasks*, *Lists* (notes with checklist items), *Voice*, *Scans*, *Shared* and *Shared
with me* (on Nextcloud only; the second only when something is), *Archive*, *Trash*, then folders.
The cog's *Order* turns between *Newest first*, *Oldest first* and *By title*, as the phone's
order does, kept per browser; pinned notes stay above the rest in *Notes* and folders, and
*Reminders* are always soonest first. The rules match `notes/View.kt`; the page sorts and filters
what the state already holds, so there is no server change.

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
only `jpg/jpeg/png/webp` that are linked by a note; one added from the computer up to 8 MB, a
JPEG, PNG or WebP by its bytes, cleaned before it is kept. Recordings and scans up to 256 MB, likewise. Unknown JSON fields are ignored; wrong types
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
