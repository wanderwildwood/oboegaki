package com.wanderwildwood.oboegaki.hearing

import android.content.res.AssetManager

/** whisper.cpp, through the few lines of native code in `cpp/hearing.cpp`. */
internal object Whisper {
    /** The model as it sits in the APK. */
    const val MODEL = "model.bin"

    init {
        System.loadLibrary("hearing")
    }

    /** What was said in [samples], or null if the model could not be loaded or run. */
    external fun transcribe(assets: AssetManager, model: String, samples: FloatArray, threads: Int): String?
}
