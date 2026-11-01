# Network and cryptography notes, for a reviewer

## Plain http to the reader's own server

Memo can sync tasks with a Nextcloud server and serves the Desktop Sync page to the reader's own computer over the local network. Android refuses plain http by default, so `res/xml/network_security_config.xml` permits it; permitting
cleartext does not weaken https, it only stops Android refusing http. Android cannot allow it for a range of
addresses only, so it is allowed everywhere. Plain http to a public host is refused (`Nextcloud.kt`); Desktop Sync answers only to private-network peers (`DesktopServer.kt`, `Network.peerAllowed`). A scan that flags `cleartextTrafficPermitted` is seeing this
and not an accident: removing it would stop every home server that has no certificate from working.
