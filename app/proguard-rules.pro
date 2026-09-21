-dontwarn okhttp3.**
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class com.ollacore.app.data.model.** { *; }
