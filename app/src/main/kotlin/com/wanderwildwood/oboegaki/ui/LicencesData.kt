package com.wanderwildwood.oboegaki.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.oboegaki.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/oboegaki"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("AndroidX (core, activity, lifecycle) and Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("MMD, Mudita Mindful Design", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("OkHttp and Okio", "Square", "Apache-2.0", "https://square.github.io/okhttp/"),
    LicenceComponent("NanoHTTPD, the web server behind Desktop Sync", "the NanoHTTPD authors", "BSD-3-Clause", "https://github.com/NanoHttpd/nanohttpd"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("Lato typeface, inside MMD", "tyPoland Lukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
    LicenceComponent("whisper.cpp and ggml, speech recognition", "Georgi Gerganov", "MIT", "https://github.com/ggml-org/whisper.cpp"),
    LicenceComponent("Whisper speech model (English base bundled; other languages fetched from ggerganov/whisper.cpp on Hugging Face)", "OpenAI", "MIT", "https://huggingface.co/ggerganov/whisper.cpp"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.licence_gpl3,
    "apache" to R.raw.licence_apache2,
    "mit" to R.raw.licence_mit,
    "bsd" to R.raw.licence_bsd3,
    "ofl" to R.raw.licence_ofl,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Memo",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "mit" -> R.string.licences_full_mit
            "bsd" -> R.string.licences_full_bsd
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        licenceHeading = stringResource(R.string.licences_licence_head),
        builtWithHeading = stringResource(R.string.licences_built_head),
        fullTextsHeading = stringResource(R.string.licences_full_head),
        noBrowser = { address -> none.replace("@@", address) },
    )
}
