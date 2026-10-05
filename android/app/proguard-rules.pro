-dontwarn okhttp3.**
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class com.ollacore.app.data.model.** { *; }
# WebRTC: org.webrtc.Environment is provided by app sources to match the
# bundled libjingle natives (classic JNI names). Never rename/strip it or
# release builds will fail JNI linkage at call setup.
-keep class org.webrtc.Environment { *; }
-keep class org.webrtc.Environment$Builder { *; }
