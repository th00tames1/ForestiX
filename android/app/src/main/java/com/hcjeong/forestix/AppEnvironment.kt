// Composition root — the Android analogue of iOS AppEnvironment. Owns the
// long-lived singletons (settings + measurement history) and is created
// once by ForestixApplication, then handed to Compose via a
// CompositionLocal so any screen can read them like @EnvironmentObject.

package com.hcjeong.forestix

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.Room
import com.hcjeong.forestix.data.AppSettings
import com.hcjeong.forestix.data.ForestixDatabase
import com.hcjeong.forestix.data.QuickMeasureHistory

class AppEnvironment private constructor(
    val settings: AppSettings,
    val history: QuickMeasureHistory,
) {
    companion object {
        suspend fun create(context: Context): AppEnvironment {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, ForestixDatabase::class.java, "forestix.db").build()
            val history = QuickMeasureHistory.get(app, db.dao())
            return AppEnvironment(AppSettings(app), history)
        }
    }
}

val LocalAppEnvironment = staticCompositionLocalOf<AppEnvironment> {
    error("AppEnvironment not provided")
}
