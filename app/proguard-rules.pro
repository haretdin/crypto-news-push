# Keep runtime-visible annotations used by Room and AndroidX.
-keepattributes *Annotation*

# Room generates and reflects on database/DAO metadata.
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Kotlin serialization models used for network payloads.
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }

# Keep app entry points and foreground service.
-keep class com.cryptonews.push.MainActivity { *; }
-keep class com.cryptonews.push.NewsApp { *; }
-keep class com.cryptonews.push.service.NewsForegroundService { *; }
