# Privacy

Memo collects nothing about you and has no analytics, ads or tracking of any kind.

- **Notes, lists, recordings and scans** stay on the phone, or go to the place you chose for
  them: a folder on the phone, your own Nextcloud, or a WebDAV server you named. Nowhere else.
- **Tasks**, with the notes on a Nextcloud, are read from and written to that same Nextcloud
  over CalDAV, with the same app password. Without one, they stay on the phone.
- **Voice notes** are heard on the phone itself. For a language other than English, the speech
  model is downloaded once from Hugging Face when you choose it; nothing you record is sent.
- **The Nextcloud app password** and a WebDAV password are kept in the app's private storage,
  and backups are turned off, so they leave the phone only for the server they belong to.
- **Deleted notes** are kept for 7 days in a hidden folder next to your notes, wherever you keep
  them, then removed.
- **Reminders** are set on the phone's own alarm clock and ring only on the phone.
- **Pinned notes on the lock screen** are handed to Glance, only when it is on the phone and the
  setting is on; they go no further.
- **Desktop Sync** is off until you switch it on in Settings, and only runs while it is on. While
  it runs, the phone itself serves your notes and tasks, in full, to a browser that has the link
  or the six-digit code. Nothing goes through any server of ours or anyone's: the browser talks
  to the phone directly. By default only a computer on your VPN (Tailscale or another) can reach
  it; turn *VPN only* off and any computer on the same home network with the link can; addresses on the
  internet or mobile data are always refused. It uses plain
  `http`, so on a network without a VPN the notes travel unencrypted. Each computer that pairs gets its own key, listed in Settings, where it can be removed (or all of
  them); a key not used for 30 days is dropped, and the phone keeps only a hash of it. Nothing you wrote is written to a log. Switch it off or tap Stop
  on its notification and it stops. Until then it stays on, and comes back by itself after a
  restart of the phone or an app update.
- **Dream Log** (the sibling app 夢帳, signed with the same key) may write a note into Memo through a capture provider, without a confirmation screen; Memo checks the caller's signing certificate first, and no other app can.
- **The microphone** is used only while you are recording.
- **Terms:** free software under the GNU General Public License v3, with no warranty.

<!-- shared-about privacy section -->
## Who we are

Memo is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. Memo keeps what you put in it on your phone, apart from what it sends to Hugging Face as described above, and sends nothing to the developer, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Memo is sold or shared.

Since Memo keeps what it stores only on your phone, you can exercise most of these rights yourself: delete a note and empty the trash in the app, remove a paired browser in Settings, clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Memo connects to Hugging Face. They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.
If you point Memo at a server of your own, what that server keeps is yours to manage.
<!-- /shared-about privacy section -->
