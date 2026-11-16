# FF Launcher

An ultra-lightweight, minimalist Android home launcher built with **Kotlin** and **Jetpack Compose (Material 3)**, inspired by [Niagara Launcher](https://niagaralauncher.com/). 

FF Launcher combines a focused, one-handed home screen with an ergonomic right-edge alphabet scroller, inline notification indicators, a customizable edge trigger for full-screen widgets, and intelligent search.

See [CHANGELOG.md](CHANGELOG.md) for recent updates and bug fixes, and [SPEC.md](SPEC.md) for the technical architecture specification.

---

## Features

### 📱 Primary Home Screen
- **Material 3 Smart Header (At-a-Glance)**: Integrated customizable header at the top of the home screen featuring real-time clock, date, upcoming calendar events, and live weather conditions from Open-Meteo. Supports custom third-party widgets as home header replacements.
- **Minimalist Pinned Favorites**: Shows only your pinned favorite applications on the home screen for a distraction-free environment.
- **Immersive Floating Pinned Apps Manager**: Floating glass sheet over wallpaper where applications can be pinned/unpinned via checkboxes, reordered via **Drag & Drop** or Up/Down buttons, and managed (App info, Uninstall, Hide).
- **Wide Touch Targets**: App rows provide an expanded clickable surface (>80% screen width) for natural one-handed tapping.
- **A–Z Wave Scroller**: Docked to the right edge with zero-latency section jumping, ICU-backed international alphabet collating with Pinyin rescue for CJK scripts, dynamic wave bubble indicator, and haptic feedback.
- **Quick Settings Access**: Dedicated gear icon (⚙) at the bottom of the alphabet scroller opens Launcher Settings directly.
- **Dynamic App Drawer**: Pulling or touching the alphabet scroller reveals the full locale-collated app drawer, which collapses back to favorites on Back or Home gesture.

### 🔔 Notification Indicators & Inline Panel
- **Notification Arrows**: Subtle arrow indicators on app rows that have active eligible notifications.
- **Inline Expandable Panel**: Tapping an arrow expands an inline card panel with notification previews, direct action buttons (e.g., Mark as Read), and individual or batch dismissals.
- **Privacy-First**: Operates via Android's `NotificationListenerService`; titles and bodies are processed transiently on-demand in memory and never logged or written to disk.
- **Customizable Scope**: Configure arrows for all apps or pinned favorites only, filter silent notifications, mute specific apps, or hide message previews.

### ⚡ Quick Action Button
- **Floating Action Handle**: A customizable floating button parked on either edge for one-tap utility access.
- **Preset Tools**: Instant trigger for Flashlight (hardware camera torch), Camera, Alarm, Assistant, any installed App, or the Widget Deck.
- **Live Tuning**: Adjust position, vertical bias, button size, and opacity with real-time preview and persistence.

### 🔍 App Search & Recents
- **Instant Search**: Quick fuzzy/prefix search filtering installed applications in real time.
- **Recent Applications**: Automatically surfaces recently launched applications at the top of the search view.
- **Newly Installed Applications**: Highlights applications installed within the past 48 hours.
- **Intentional Pull-to-Search**: Pulling up deliberately at the bottom of the home screen opens the search sheet, backed by a guarded 64dp overscroll threshold.

### 🧩 Widget Deck & Material 3 Picker
- **Bundled FF Launcher Widgets**: Includes three native Android AppWidgets (`ClockWidgetProvider`, `CalendarWidgetProvider`, and `WeatherWidgetProvider`) usable in FF Launcher and external launchers.
- **Edge-Triggered Secondary Screen**: A subtle, live-tunable edge pill opens the full-screen widget deck with an animated slide/fade transition.
- **In-App Material 3 Widget Picker**: Browse and add widgets categorized by parent application with icons, item counts, previews, and size badges.
- **Floating Over Wallpaper**: The widget deck and slot containers render with clean transparency over your wallpaper.
- **Custom Slot Weights & Sizing**: Supports Small (25%), Medium (27.5%), and Large (30%) widget heights with drag-and-drop slot reordering and configuration activity handling.

### ⚙️ Launcher Settings & Architecture
- **Standalone Settings Activity**: Runs in its own task affinity (`com.ff.launcher.settings`) so it retains an independent card in Android's Recent Apps carousel.
- **AppEntry Trampoline**: Transparent trampoline activity exposed in other launchers' app drawers to cleanly switch back to FF Launcher without leaving ghost tasks.
- **System Gesture Exclusion**: Automatically registers Android gesture exclusion rects when swipe-to-open is active to prevent conflicts with the system back gesture.
- **Hidden Applications**: Hide unwanted applications from the launcher, with easy unhiding from Launcher Settings.
- **Default Launcher Guidance**: First-run onboarding tour and re-engagement banner to set FF Launcher as the system default home app.

---

## Tech Stack & Architecture

- **Language & Framework**: Kotlin 2.1 · Jetpack Compose (BOM 2024.12, Material 3)
- **Architecture**: Modular MVVM with unidirectional data flow (`ViewModel`, `StateFlow`, Coroutines)
  - Decomposed coordinator `LauncherViewModel` with domain managers:
    - `WidgetHostManager`: AppWidget allocation, binding, configuration, and listening lifecycle
    - `TriggerTuningManager`: Edge handle live preview, commit, and settle lifecycle
    - `QuickActionManager`: Quick action floating button & torch control
    - `NotificationPanelManager`: Notification badge filtering and inline expanded card panel
    - `HomeStateBuilder`: Pure function for ICU AlphabeticIndex grouping with Pinyin rescue
- **Local Persistence**: 
  - Room 2.7 (Favorites sequence, hidden apps, widget slots, recent apps)
  - Jetpack DataStore Preferences (Edge trigger, quick action, smart header, and onboarding state)
- **Modern Android APIs**: Activity Result API (`registerForActivityResult`), `LauncherApps` multi-user/work-profile support, `AppWidgetHost` with idle view cache purging.
- **Build Optimization**: R8 code shrinking and resource shrinking enabled for release builds with explicit keep rules for Room, widgets, and notification services.

---

## Requirements

- **Android Device**: Android 8.0+ (API level 26+; gesture exclusion supported on Android 10+)
- **Target SDK**: Android 15 (API level 35)
- **JDK**: Java 17+ (JDK 17 or JDK 21)

---

## Build & Installation

Clone the repository and build using Gradle:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release APK (R8 minified)
./gradlew assembleRelease

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation:
1. Press the **Home** button on your device.
2. Select **FF Launcher** and choose **Always**.

---

## Project Structure

```
app/src/main/java/com/ff/launcher/
├── LauncherApp.kt                       # Application singleton holding shared repositories & DB
├── MainActivity.kt                      # Home launcher activity, system bars, edge-to-edge
├── AppEntryActivity.kt                  # Trampoline entry point for external launcher switching
├── SettingsActivity.kt                  # Standalone settings activity in Recent Apps
├── data/
│   ├── AppInfo.kt                       # Application model with composite key (user profile isolation)
│   ├── HiddenAppUi.kt                   # UI model for hidden apps management
│   ├── AppRepository.kt                 # LauncherApps query, work profiles, package broadcast receiver
│   ├── FavoritesRepository.kt           # Room-backed favorite persistence, default seeding & ordering
│   ├── OnboardingRepository.kt          # First-run & default home role state
│   ├── TriggerPreferencesRepository.kt  # DataStore-backed edge handle parameters
│   ├── QuickActionPreferencesRepository.kt # DataStore-backed quick action parameters
│   ├── glance/                          # Smart header & bundled widget data layer
│   │   ├── TimeProvider.kt              # Zero-polling broadcast receiver for time & date
│   │   ├── CalendarProvider.kt          # CalendarContract query for upcoming events
│   │   ├── WeatherRepository.kt         # Open-Meteo client, WMO codes, API 33+ Geocoder & cache
│   │   └── HeaderPreferencesRepository.kt # Smart header display mode & unit preferences
│   ├── notification/
│   │   └── BadgePreferencesRepository.kt # Notification arrow & badge preferences
│   └── db/
│       └── LauncherDatabase.kt          # Room database (favorites, hidden apps, widget slots, recent apps)
├── notification/
│   ├── BadgeNotificationListener.kt     # NotificationListenerService tracking eligible notifications
│   ├── NotificationAccessHelper.kt      # Permission check and system settings intent launcher
│   ├── NotificationBadgeStore.kt        # Reactive in-memory badge store
│   └── NotificationModels.kt            # BadgeKey and NotificationCard models
├── ui/
│   ├── LauncherRoot.kt                  # Root composition (Home, Deck, Edge Trigger, Sheets)
│   ├── WidgetHostViewCache.kt           # Activity-scoped Compose AppWidgetHostView cache & idle purge
│   ├── home/
│   │   ├── HomeScreen.kt                # Pinned favorites, dynamic drawer, overscroll search trigger
│   │   ├── HomeListItems.kt             # Decomposed list rows, app icons, section headers & guides
│   │   ├── HomeWidgetArea.kt            # Home custom widget container & size adjustment sheet
│   │   ├── DefaultLauncherBanner.kt     # Material 3 default launcher re-engagement card
│   │   ├── HomeSmartHeader.kt           # Material 3 smart header (clock, calendar, weather)
│   │   ├── AppSearchSheet.kt            # App search, recent applications, newly installed apps
│   │   ├── ManageFavoritesSheet.kt      # Unified favorites reordering & selection sheet
│   │   └── WaveScroller.kt              # Right-edge alphabet scroller, wave bubble, settings shortcut
│   ├── notification/
│   │   ├── NotificationArrow.kt         # App row indicator arrow
│   │   └── NotificationPanel.kt         # Inline expandable notification card list
│   ├── quickaction/
│   │   ├── QuickActionButton.kt         # Floating quick action handle
│   │   ├── QuickActionIcons.kt          # Preset action icons
│   │   └── QuickActionPickerSheet.kt    # Action selection sheet
│   ├── secondary/
│   │   ├── SecondaryScreen.kt           # Full-screen widget deck & slot containers
│   │   └── WidgetPickerSheet.kt         # Material 3 widget picker grouped by application
│   ├── settings/
│   │   └── SettingsScreen.kt            # Standalone launcher settings screen
│   ├── onboarding/
│   │   └── WelcomeOnboardingScreen.kt   # Welcome flow & default launcher prompt
│   ├── trigger/
│   │   └── EdgeTrigger.kt               # Tunable edge pill with gesture exclusion rects
│   └── theme/                           # Material 3 colors, typography, shapes & theme setup
├── util/
│   └── Logger.kt                        # Logging facade compiled out in release builds
├── viewmodel/
│   ├── LauncherViewModel.kt             # Coordinator ViewModel for home & widget interactions
│   ├── SettingsViewModel.kt             # ViewModel for standalone settings activity
│   ├── HomeListState.kt                 # UI row models & alphabetical section collation
│   ├── HomeStateBuilder.kt              # Pure function for ICU collation with Pinyin rescue
│   ├── NotificationPanelManager.kt      # Expanded notification cards & badge preferences
│   ├── QuickActionManager.kt            # Quick action execution & torch control
│   ├── TriggerTuningManager.kt          # Edge trigger preview/commit/settle lifecycle
│   └── WidgetHostManager.kt             # Widget binding, configuration, and host lifecycle
└── widget/
    ├── LauncherAppWidgetHost.kt         # Custom AppWidgetHost with specialized HostView factory
    └── providers/                       # Bundled widgets (Clock, Calendar, Weather)
│   ├── HomeStateBuilder.kt              # Pure function building collated list with Pinyin rescue
│   ├── WidgetHostManager.kt             # AppWidget allocation, binding, configure & release
│   ├── TriggerTuningManager.kt          # Edge handle preview, commit & settle manager
│   ├── QuickActionManager.kt            # Quick action button execution & torch manager
│   └── NotificationPanelManager.kt      # Notification badge filtering & panel expansion manager
└── widget/
    ├── LauncherAppWidgetHost.kt         # Custom AppWidgetHost & long-press intercepting view holder
    └── providers/                       # Standard Android AppWidgetProviders for FF Launcher
        ├── ClockWidgetProvider.kt       # Digital clock & date AppWidget
        ├── CalendarWidgetProvider.kt    # Date & upcoming event AppWidget
        └── WeatherWidgetProvider.kt     # Open-Meteo live condition & temp AppWidget
```

---

## Documentation

- [CHANGELOG.md](CHANGELOG.md): History of feature updates, UX enhancements, and bug fixes.
- [SPEC.md](SPEC.md): Full technical specification and architecture roadmap.
