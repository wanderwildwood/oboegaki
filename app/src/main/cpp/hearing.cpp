// The bridge between the app and whisper.cpp: load the model out of the APK, transcribe one
// recording, hand back the text. Nothing is kept between calls.

#include <jni.h>
#include <string>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "whisper.h"

namespace {

// whisper.cpp reads its model through these callbacks, so it can come straight out of the
// APK instead of being copied to storage first, which would keep two copies of 57 MB.
size_t asset_read(void *ctx, void *output, size_t bytes) {
    return AAsset_read(static_cast<AAsset *>(ctx), output, bytes);
}

bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_wanderwildwood_oboegaki_hearing_Whisper_transcribe(
        JNIEnv *env, jobject, jobject asset_manager, jstring asset_name,
        jfloatArray samples, jint threads) {
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    const char *name = env->GetStringUTFChars(asset_name, nullptr);
    AAsset *asset = AAssetManager_open(manager, name, AASSET_MODE_STREAMING);
    env->ReleaseStringUTFChars(asset_name, name);
    if (asset == nullptr) return nullptr;

    whisper_model_loader loader = {asset, asset_read, asset_eof, asset_close};
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // The loader closes the asset itself, whether or not the load succeeds.
    whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (ctx == nullptr) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = threads;
    params.no_context = true;
    // Non-speech markers like [BLANK_AUDIO] and (wind blowing) are not words anyone said.
    params.suppress_nst = true;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    jstring result = nullptr;
    if (rc == 0) {
        std::string text;
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) text += whisper_full_get_segment_text(ctx, i);
        result = env->NewStringUTF(text.c_str());
    }
    whisper_free(ctx);
    return result;
}
