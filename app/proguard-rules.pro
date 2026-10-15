# Recipe Catcher ProGuard / R8 Rules

# --------------------------------------------------------------------------------
# KotlinX Serialization
# --------------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    <fields>;
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }

# --------------------------------------------------------------------------------
# Room Persistence Library
# --------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep class * extends androidx.room.TypeConverter { *; }
-keep class org.eu.angsumanray.recipecatcher.data.db.converter.** { *; }
-keep class org.eu.angsumanray.recipecatcher.data.db.entity.** { *; }

# --------------------------------------------------------------------------------
# Jetpack Glance & AppWidget
# --------------------------------------------------------------------------------
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class org.eu.angsumanray.recipecatcher.widget.** { *; }

# --------------------------------------------------------------------------------
# Wearable Data Layer & Google Play Services
# --------------------------------------------------------------------------------
-keep class org.eu.angsumanray.recipecatcher.wear.** { *; }
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# --------------------------------------------------------------------------------
# Domain & Core Models
# --------------------------------------------------------------------------------
-keep class org.eu.angsumanray.recipecatcher.core.model.** { *; }
-keep class org.eu.angsumanray.recipecatcher.core.llm.** { *; }
-keep class org.eu.angsumanray.recipecatcher.core.domain.** { *; }

# --------------------------------------------------------------------------------
# WorkManager
# --------------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class org.eu.angsumanray.recipecatcher.capture.CaptureWorker { *; }
-keep class org.eu.angsumanray.recipecatcher.capture.ImportWorker { *; }

# --------------------------------------------------------------------------------
# Ktor & OkHttp
# --------------------------------------------------------------------------------
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class io.ktor.** { *; }
