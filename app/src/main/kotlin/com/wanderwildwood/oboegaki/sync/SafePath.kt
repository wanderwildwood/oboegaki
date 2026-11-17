package com.wanderwildwood.oboegaki.sync

import java.io.File

/**
 * True for a path, as a server lists it relative to the notes folder, that stays inside that
 * folder: no "." or ".." step, no empty step, no backslash and no control character. A server
 * that is broken or not the person's own could otherwise name a file that climbs out of the
 * notes folder into the rest of the app's storage.
 */
fun isSafeRelative(path: String): Boolean {
    val p = path.trimEnd('/')
    if (p.isEmpty() || p.startsWith("/")) return false
    if (p.any { it < ' ' || it == '\\' }) return false
    return p.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** [root]/[path], or null when it would land anywhere but under [root]. */
fun inside(root: File, path: String): File? {
    val file = File(root, path)
    val base = root.canonicalFile.path + File.separator
    return if (file.canonicalFile.path.startsWith(base)) file else null
}
