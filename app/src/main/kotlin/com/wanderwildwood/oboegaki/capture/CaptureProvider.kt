package com.wanderwildwood.oboegaki.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.wanderwildwood.oboegaki.notes.Notes
import java.security.MessageDigest

/**
 * Where the other apps of this shop write notes into this one: Dream Log keeping each dream as
 * a note, say. Everything is a [call]; nothing is queried or inserted.
 *
 * - `ready`: whether there is somewhere to keep notes yet (`not_set_up` if not).
 * - `put`, arg a path such as `Dreams/2026-10-05 0712.md`, extra `text`: makes that note or
 *   replaces its text, and it syncs like any edit.
 * - `remove`, arg such a path: deletes that note and nothing else; already gone is fine.
 *
 * The answer is a Bundle: `ok`, and when not ok a `reason` (`refused`, `not_set_up`,
 * `bad_path`, `failed`) and for `failed` a `message`.
 *
 * Only the apps in [ALLOWED] get an answer, each known by its package name and the certificate
 * it is signed with, so an app that took the same name gets nowhere.
 */
class CaptureProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return refusal(REFUSED)
        if (!allowed(context, Binder.getCallingUid())) return refusal(REFUSED)
        val identity = Binder.clearCallingIdentity()
        return try {
            Notes.init(context)
            when (method) {
                "ready" -> if (Notes.isSetUp()) ok() else refusal(NOT_SET_UP)
                "put" -> {
                    val path = capturePath(arg) ?: return refusal(BAD_PATH)
                    val text = extras?.getString("text") ?: return failed("No text was given.")
                    if (!Notes.isSetUp()) return refusal(NOT_SET_UP)
                    Notes.put(path, text)
                    ok()
                }
                "remove" -> {
                    val path = capturePath(arg) ?: return refusal(BAD_PATH)
                    if (!Notes.isSetUp()) return refusal(NOT_SET_UP)
                    Notes.remove(path)
                    ok()
                }
                else -> failed("No such method: $method")
            }
        } catch (e: Exception) {
            failed(e.message ?: e.javaClass.simpleName)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Package name to the SHA-256 of the certificate its releases are signed with. */
        private val ALLOWED = mapOf(
            "com.wanderwildwood.yumecho" to "6ce096a3c48c12e1ef0d158e0d4eb3a8c355c74be8ac84c92697de2d84f9f62f",
        )

        private const val REFUSED = "refused"
        private const val NOT_SET_UP = "not_set_up"
        private const val BAD_PATH = "bad_path"

        /** Whether any package of [uid] is on the list, signed by the certificate listed for it. */
        private fun allowed(context: Context, uid: Int): Boolean {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid) ?: return false
            return packages.any { name ->
                val want = ALLOWED[name] ?: return@any false
                val signers = runCatching {
                    pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
                }.getOrNull()
                !signers.isNullOrEmpty() && signers.all { sha256(it.toByteArray()) == want }
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun ok() = Bundle().apply { putBoolean("ok", true) }

        private fun refusal(reason: String) = Bundle().apply {
            putBoolean("ok", false)
            putString("reason", reason)
        }

        private fun failed(message: String) = refusal("failed").apply { putString("message", message) }
    }
}
