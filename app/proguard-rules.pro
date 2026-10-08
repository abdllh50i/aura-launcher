# Aura launcher — no reflection-heavy libraries are used, defaults are enough.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Views inflated from XML by name
-keep class com.abdllh.aura.ui.** { *; }

# Notification listener is bound by the system
-keep class com.abdllh.aura.media.AuraNotificationListener { *; }
