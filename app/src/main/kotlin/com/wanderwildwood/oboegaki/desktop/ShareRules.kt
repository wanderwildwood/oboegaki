package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.sync.Incoming
import com.wanderwildwood.oboegaki.sync.Person
import com.wanderwildwood.oboegaki.sync.Share
import org.json.JSONArray
import org.json.JSONObject

/*
 * Sharing from the page goes through the app's own Nextcloud sharing (sync/Shares.kt), the same
 * calls the phone's Share screen makes. These are the checks in front of it: the page names a
 * person or a share only by what the server just listed for that note, never by anything else.
 */

/** A person the note can be shared with, by an id the server listed for this account; anything else is refused. */
fun requirePerson(id: String?, people: List<Person>): Person {
    if (id.isNullOrEmpty() || id.length > 200) throw Refusal(400, "share with whom?")
    return people.firstOrNull { it.id == id } ?: throw Refusal(404, "that person is not on this Nextcloud")
}

/** One of this note's own shares, by its id; a share of another note, or none, is refused. */
fun requireShare(id: String?, shares: List<Share>): Share {
    if (id.isNullOrEmpty() || id.length > 40) throw Refusal(400, "which share?")
    return shares.firstOrNull { it.id == id } ?: throw Refusal(404, "that note is not shared that way any more")
}

/**
 * What the page is told about one note's sharing: who it is shared with and whether they may
 * change it, who else it could be shared with, and, for a note someone else shared with this
 * account, who that was. [serverPath] is the note's path on the server, to find it among [incoming].
 */
fun sharesJson(shares: List<Share>, people: List<Person>, incoming: List<Incoming>, serverPath: String): JSONObject {
    val from = incoming.firstOrNull { it.path == serverPath }
    return JSONObject()
        .put("available", true)
        .put("shares", JSONArray(shares.map { JSONObject().put("id", it.id).put("with", it.with).put("name", it.name).put("canEdit", it.canEdit) }))
        .put("people", JSONArray(people.filter { p -> shares.none { it.with == p.id } }.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("sharedWithMe", from != null)
        .put("from", from?.from ?: JSONObject.NULL)
}

/** For notes not kept on Nextcloud: sharing is Nextcloud's own, a plain WebDAV server or the phone alone has none. */
fun noSharingJson(): JSONObject = JSONObject().put("available", false)
