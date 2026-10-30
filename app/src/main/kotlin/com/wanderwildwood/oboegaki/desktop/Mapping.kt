package com.wanderwildwood.oboegaki.desktop

import com.wanderwildwood.oboegaki.notes.ARCHIVE_FOLDER
import com.wanderwildwood.oboegaki.notes.Line
import com.wanderwildwood.oboegaki.notes.Note
import com.wanderwildwood.oboegaki.notes.embeds
import com.wanderwildwood.oboegaki.notes.frontMatterHead
import com.wanderwildwood.oboegaki.notes.isArchived
import com.wanderwildwood.oboegaki.notes.isPicture
import com.wanderwildwood.oboegaki.notes.isSound
import com.wanderwildwood.oboegaki.notes.itemReminder
import com.wanderwildwood.oboegaki.notes.lines
import com.wanderwildwood.oboegaki.notes.noteReminder
import com.wanderwildwood.oboegaki.notes.withoutReminder
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import com.wanderwildwood.oboegaki.tasks.Item
import com.wanderwildwood.oboegaki.tasks.TaskList
import com.wanderwildwood.oboegaki.tasks.day
import com.wanderwildwood.oboegaki.tasks.local
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * How the app's notes and tasks look to the page, and back. See the table in docs/DESKTOP-SYNC.md.
 * The page never sees a path it could not have got from the app, and never builds one.
 */

/** The note's words without its front matter: what the phone's editor shows. */
fun bodyOf(text: String): String = text.removePrefix(frontMatterHead(text))

/** [text] with its front matter kept from [whole] and its words replaced. */
fun withBody(whole: String, body: String): String = frontMatterHead(whole) + body

fun noteJson(note: Note, pinned: Boolean): JSONObject {
    val text = note.text
    val items = JSONArray()
    for (line in lines(text)) {
        if (line !is Line.Task) continue
        items.put(
            JSONObject()
                .put("line", line.index)
                .put("done", line.done)
                .put("text", withoutReminder(line.text))
                .put("reminder", itemReminder(line.text) ?: JSONObject.NULL),
        )
    }
    val embedded = embeds(text)
    return JSONObject()
        .put("id", note.path)
        .put("title", note.title)
        .put("body", bodyOf(text))
        .put("v", versionOf(text))
        .put("modified", note.modified)
        .put("pinned", pinned)
        .put("archived", isArchived(note.path))
        .put("folder", if (isArchived(note.path)) note.folder.removePrefix(ARCHIVE_FOLDER).trimStart('/') else note.folder)
        .put("reminder", noteReminder(text) ?: JSONObject.NULL)
        .put("items", items)
        .put("pictures", JSONArray(embedded.filter(::isPicture).filter(::isSafePictureName)))
        .put("recordings", JSONArray(embedded.filter(::isSound)))
        .put("scans", JSONArray(embedded.filter { it.lowercase().endsWith(".pdf") }))
}

/** A due as the page's inputs write it: "2026-10-08" for a day, "2026-10-08T17:00" for a time on the phone's clock. */
fun dueText(due: Due?): String? = when (due) {
    null -> null
    is Due.Day -> due.date.toString()
    is Due.At -> due.local()?.let { it.withSecond(0).withNano(0).toString().take(16) }
}

/** The inverse of [dueText]; "" or null is no due date; anything else is refused. */
fun parseDueInput(text: String?, zone: ZoneId = ZoneId.systemDefault()): Due? {
    if (text.isNullOrEmpty()) return null
    return try {
        if (text.length == 10) Due.Day(LocalDate.parse(text))
        else if (text.length == 16 && text[10] == 'T') Due.At(LocalDateTime.parse(text), zone)
        else throw Refusal(400, "due must be like 2026-10-08 or 2026-10-08T17:00")
    } catch (e: java.time.format.DateTimeParseException) {
        throw Refusal(400, "due is not a real date")
    }
}

fun taskJson(item: Item, rings: Boolean): JSONObject {
    val f = item.task.fields
    return JSONObject()
        .put("list", item.list)
        .put("name", item.name)
        .put("summary", f.summary)
        .put("description", f.description)
        .put("due", dueText(f.due) ?: JSONObject.NULL)
        .put("priority", f.priority)
        .put("done", f.done)
        .put("repeats", item.task.repeats != null)
        .put("rings", rings)
}

fun taskListJson(list: TaskList, items: List<Item>, rings: (Item) -> Boolean): JSONObject =
    JSONObject()
        .put("id", list.id)
        .put("name", list.name)
        .put("readOnly", list.readOnly)
        .put("onPhone", list.onPhone)
        .put("items", JSONArray(items.map { taskJson(it, rings(it)) }))

/** Fields from a JSON object the page sent; only what is there, checked. */
fun fieldsFrom(o: JSONObject, base: Fields? = null, titleNeeded: Boolean = true): Fields {
    fun str(key: String, max: Int, fallback: String) =
        if (o.has(key)) requireText(o.optString(key, ""), key, max) else fallback
    val summary = str("summary", Limits.TASK_SUMMARY, base?.summary ?: "").replace('\n', ' ').trim()
    if (titleNeeded && summary.isEmpty()) throw Refusal(400, "a task needs a title")
    val priority = if (o.has("priority")) o.optInt("priority", -1) else (base?.priority ?: 0)
    if (priority !in 0..9) throw Refusal(400, "priority is 0 to 9")
    return Fields(
        summary = summary,
        description = str("description", Limits.TASK_DESCRIPTION, base?.description ?: ""),
        due = if (o.has("due")) parseDueInput(if (o.isNull("due")) null else o.optString("due")) else base?.due,
        priority = priority,
        done = if (o.has("done")) o.optBoolean("done") else (base?.done ?: false),
    )
}
