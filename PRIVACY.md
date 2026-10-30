# Privacy

Notes collects nothing about you and has no analytics, ads or tracking of any kind.

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
  it; turn *VPN only* off and any computer on the same network with the link can. It uses plain
  `http`, so on a network without a VPN the notes travel unencrypted. *Reset link* cuts off every
  browser that has the old link. Nothing you wrote is written to a log. Switch it off, tap Stop
  on its notification, or restart the phone, and it stops.
- **The microphone** is used only while you are recording.
- **Terms:** free software under the GNU General Public License v3, with no warranty.
