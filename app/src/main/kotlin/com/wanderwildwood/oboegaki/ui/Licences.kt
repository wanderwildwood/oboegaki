package com.wanderwildwood.oboegaki.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.RawRes
import com.wanderwildwood.oboegaki.R

/** A third-party part of the app: what it is, its licence, and where it lives. */
data class Component(val name: String, val licence: String, val link: String)

/**
 * What the APK carries that is not this app's own: the libraries it is built from, the speech
 * engine and model, and the icons. Apache-2.0 and MIT texts are shown once each below the list.
 */
object Licences {

    const val SOURCE = "https://github.com/wanderwildwood/oboegaki"

    const val APACHE = "Apache-2.0"
    const val MIT = "MIT"
    const val BSD3 = "BSD-3-Clause"

    val components: List<Component> = listOf(
        Component("AndroidX (core, activity, lifecycle) and Jetpack Compose", APACHE, "https://developer.android.com/jetpack"),
        Component("MMD, Mudita Mindful Design", APACHE, "https://github.com/mudita/MMD"),
        Component("OkHttp and Okio", APACHE, "https://square.github.io/okhttp/"),
        Component("NanoHTTPD, the web server behind Desktop Sync", BSD3, "https://github.com/NanoHttpd/nanohttpd"),
        Component("Kotlin and kotlinx.coroutines", APACHE, "https://kotlinlang.org"),
        Component("Material Symbols icons", APACHE, "https://fonts.google.com/icons"),
        Component("whisper.cpp and ggml, speech recognition", MIT, "https://github.com/ggml-org/whisper.cpp"),
        Component("Whisper speech model, by OpenAI", MIT, "https://github.com/openai/whisper"),
    )

    /** The licence texts shown in full after the list: one per distinct licence the list names. */
    @RawRes
    fun textFor(licence: String): Int = when (licence) {
        APACHE -> R.raw.licence_apache2
        MIT -> R.raw.licence_mit
        BSD3 -> R.raw.licence_bsd3
        else -> throw IllegalArgumentException("no text for $licence")
    }

    fun distinctLicences(): List<String> = components.map { it.licence }.distinct()

    /** A licence text as paragraphs: a blank line ends one, and the line breaks inside run together. */
    fun paragraphs(text: String): List<String> =
        text.split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
            .map { it.trim().replace(Regex("\\s*\\r?\\n\\s*"), " ") }
            .filter { it.isNotEmpty() }

    fun read(context: Context, @RawRes id: Int): List<String> =
        paragraphs(context.resources.openRawResource(id).bufferedReader().use { it.readText() })

    /** Opens an address; false when the phone has nothing to open it with. */
    fun open(context: Context, address: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
