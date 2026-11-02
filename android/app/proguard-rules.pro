# WebRTC (JNI) — conserve les classes appelées par le natif.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Modèles JSON locaux
-keep class com.potatoremote.signaling.** { *; }
