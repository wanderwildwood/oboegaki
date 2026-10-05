package com.wanderwildwood.oboegaki.capture

/**
 * A note's path as another app gives it, "Dreams/2026-10-05 0712.md", checked: relative to the
 * notes folder, a Markdown file, never climbing out with "..", never a hidden file, and nothing
 * a file name cannot hold on the places notes are synced to. Null when it is not acceptable.
 */
fun capturePath(arg: String?): String? {
    val path = arg ?: return null
    if (path.isEmpty() || path.length > 500) return null
    if (path.startsWith("/") || path.endsWith("/")) return null
    if (!path.endsWith(".md") || path == ".md") return null
    if (path.any { it < ' ' || it in "\\:*?\"<>|" }) return null
    val parts = path.split('/')
    for (part in parts) {
        if (part.isEmpty() || part == "." || part == "..") return null
        if (part.startsWith(".") || part != part.trim()) return null
    }
    if (parts.last().length <= ".md".length) return null
    return path
}
