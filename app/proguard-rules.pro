# --- FF Launcher ProGuard / R8 rules ---

# Room entities & DAOs: R8 strips @Entity constructors used only by generated code.
-keep class com.ff.launcher.data.db.*Entity { *; }
-keep class com.ff.launcher.data.db.*Dao { *; }

# NotificationListenerService is resolved by the system via AndroidManifest component name.
-keep class com.ff.launcher.notification.BadgeNotificationListener { *; }

# DataStore serializers and enum names used via reflection / .name strings.
-keepclassmembers enum com.ff.launcher.data.TriggerEdgeSide { *; }
-keepclassmembers enum com.ff.launcher.data.TriggerMode { *; }
-keepclassmembers enum com.ff.launcher.data.QuickActionType { *; }
-keepclassmembers enum com.ff.launcher.data.glance.HeaderMode { *; }
-keepclassmembers enum com.ff.launcher.data.glance.HeaderSize { *; }
-keepclassmembers enum com.ff.launcher.data.notification.BadgeScope { *; }

# AppWidgetProvider subclasses are resolved by the system via manifest receivers.
-keep class com.ff.launcher.widget.providers.* { *; }
