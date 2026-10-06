package com.wanderwildwood.oboegaki.hearing

import android.content.res.AssetManager

/** whisper.cpp, through the few lines of native code in `cpp/hearing.cpp`. */
internal object Whisper {
    /** The English model as it sits in the APK. */
    const val MODEL = "model.bin"

    init {
        System.loadLibrary("hearing")
    }

    /**
     * What was said in [samples], in [language] ("en", "de", …), or null if the model could not
     * be loaded or run. The model is [path] when given, otherwise [model] out of the APK.
     */
    external fun transcribe(
        assets: AssetManager,
        model: String,
        path: String?,
        language: String,
        samples: FloatArray,
        threads: Int,
    ): String?
}
