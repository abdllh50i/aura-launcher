# Aura launcher — no reflection-heavy libraries are used, defaults are enough.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Views inflated from XML by name
-keep class com.abdllh.aura.ui.** { *; }

# Notification listener is bound by the system
-keep class com.abdllh.aura.media.AuraNotificationListener { *; }

# BRouter (offline routing): a profile names its path model class ("---model:btools.router.KinematicModel"),
# which the engine creates by name; the engine is kept whole (it is small)
-keep class btools.** { *; }
-dontwarn btools.**
