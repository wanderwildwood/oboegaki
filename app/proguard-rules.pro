# The native code finds its entry point by name: Java_com_wanderwildwood_oboegaki_hearing_Whisper_transcribe.
-keep class com.wanderwildwood.oboegaki.hearing.Whisper {
    native <methods>;
}

# NanoHTTPD and NanoWSD, the server behind Desktop Sync.
-dontwarn fi.iki.elonen.**
-keep class fi.iki.elonen.** { *; }
