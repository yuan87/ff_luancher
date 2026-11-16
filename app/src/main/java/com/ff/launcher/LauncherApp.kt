package com.ff.launcher

import android.app.Application
import com.ff.launcher.data.AppRepository
import com.ff.launcher.data.FavoritesRepository
import com.ff.launcher.data.OnboardingRepository
import com.ff.launcher.data.QuickActionPreferencesRepository
import com.ff.launcher.data.TriggerPreferencesRepository
import com.ff.launcher.data.db.LauncherDatabase
import com.ff.launcher.data.glance.HeaderPreferencesRepository
import com.ff.launcher.data.glance.WeatherRepository
import com.ff.launcher.data.notification.BadgePreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LauncherApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: LauncherDatabase by lazy { LauncherDatabase.get(this) }
    val appRepository: AppRepository by lazy { AppRepository(this, applicationScope) }
    val favoritesRepository: FavoritesRepository by lazy { FavoritesRepository(this, database) }
    val triggerRepository: TriggerPreferencesRepository by lazy { TriggerPreferencesRepository(this) }
    val quickActionRepository: QuickActionPreferencesRepository by lazy { QuickActionPreferencesRepository(this) }
    val headerRepository: HeaderPreferencesRepository by lazy { HeaderPreferencesRepository(this) }
    val weatherRepository: WeatherRepository by lazy { WeatherRepository(this) }
    val badgePreferencesRepository: BadgePreferencesRepository by lazy { BadgePreferencesRepository(this) }
    val onboardingRepository: OnboardingRepository by lazy { OnboardingRepository(this) }

    override fun onTerminate() {
        super.onTerminate()
        appRepository.dispose()
    }
}
