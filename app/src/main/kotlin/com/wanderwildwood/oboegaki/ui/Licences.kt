package com.wanderwildwood.oboegaki.ui

import android.content.Context
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
    const val OFL = "OFL-1.1"

    val components: List<Component> = listOf(
        Component("AndroidX (core, activity, lifecycle) and Jetpack Compose", APACHE, "https://developer.android.com/jetpack"),
        Component("MMD, Mudita Mindful Design", APACHE, "https://github.com/mudita/MMD"),
        Component("OkHttp and Okio", APACHE, "https://square.github.io/okhttp/"),
        Component("NanoHTTPD, the web server behind Desktop Sync", BSD3, "https://github.com/NanoHttpd/nanohttpd"),
        Component("Kotlin and kotlinx.coroutines", APACHE, "https://kotlinlang.org"),
        Component("Lato typeface, inside MMD (Copyright 2010-2014 tyPoland Lukasz Dziedzic)", OFL, "https://www.latofonts.com"),
        Component("Material Symbols icons", APACHE, "https://fonts.google.com/icons"),
        Component("whisper.cpp and ggml, speech recognition (ggerganov)", MIT, "https://github.com/ggml-org/whisper.cpp"),
        Component("Whisper speech model, by OpenAI: English base model bundled, multilingual models fetched from ggerganov/whisper.cpp on Hugging Face", MIT, "https://huggingface.co/ggerganov/whisper.cpp"),
    )

    /** The licence texts shown in full after the list: one per distinct licence the list names. */
    @RawRes
    fun textFor(licence: String): Int = when (licence) {
        APACHE -> R.raw.licence_apache2
        MIT -> R.raw.licence_mit
        BSD3 -> R.raw.licence_bsd3
        OFL -> R.raw.licence_ofl
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
}
