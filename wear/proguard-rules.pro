# Recipe Catcher Wear OS ProGuard / R8 Rules

# --------------------------------------------------------------------------------
# KotlinX Serialization & Wire Models
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
-keep class org.eu.angsumanray.recipecatcher.wear.data.** { *; }

# --------------------------------------------------------------------------------
# Play Services Wearable Data Layer
# --------------------------------------------------------------------------------
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }
-keep class org.eu.angsumanray.recipecatcher.wear.WearDataListenerService { *; }

# --------------------------------------------------------------------------------
# Wear Tiles & Complications
# --------------------------------------------------------------------------------
-keep class * extends androidx.wear.tiles.TileService { *; }
-keep class * extends androidx.wear.watchface.complications.datasource.ComplicationDataSourceService { *; }
-keep class org.eu.angsumanray.recipecatcher.wear.tile.** { *; }
-keep class org.eu.angsumanray.recipecatcher.wear.complication.** { *; }
-keep class androidx.wear.protolayout.** { *; }
-keep class androidx.wear.tiles.** { *; }
