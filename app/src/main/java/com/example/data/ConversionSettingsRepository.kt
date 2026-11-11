package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "docprism_settings")

enum class ThemePreference(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark"),
    READING("Reading (Sepia)")
}

data class ConversionPolicySettings(
    val serverEndpointUrl: String = DEFAULT_ENDPOINT_URL,
    val maxFileSizeMb: Int = 25,
    val conversionTimeoutSec: Int = 30,
    val rateLimitPerMinute: Int = 8,
    val maxConcurrentWorkers: Int = 2,
    val fitWideSpreadsheets: Boolean = true,
    val simulateOfflineMode: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM
) {
    val maxFileSizeBytes: Long
        get() = maxFileSizeMb * 1024L * 1024L

    companion object {
        const val DEFAULT_ENDPOINT_URL = "https://edge-converter.docprism.internal/"
    }
}

class ConversionSettingsRepository(private val context: Context) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_endpoint_url")
        val MAX_FILE_SIZE_MB = intPreferencesKey("max_file_size_mb")
        val TIMEOUT_SEC = intPreferencesKey("conversion_timeout_sec")
        val RATE_LIMIT_PER_MIN = intPreferencesKey("rate_limit_per_min")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent_workers")
        val FIT_WIDE_SHEETS = booleanPreferencesKey("fit_wide_spreadsheets")
        val SIMULATE_OFFLINE = booleanPreferencesKey("simulate_offline_mode")
        val THEME_PREF = stringPreferencesKey("theme_preference")
    }

    val settingsFlow: Flow<ConversionPolicySettings> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_PREF] ?: ThemePreference.SYSTEM.name
        val theme = runCatching { ThemePreference.valueOf(themeStr) }.getOrDefault(ThemePreference.SYSTEM)
        ConversionPolicySettings(
            serverEndpointUrl = prefs[Keys.SERVER_URL] ?: ConversionPolicySettings.DEFAULT_ENDPOINT_URL,
            maxFileSizeMb = prefs[Keys.MAX_FILE_SIZE_MB] ?: 25,
            conversionTimeoutSec = prefs[Keys.TIMEOUT_SEC] ?: 30,
            rateLimitPerMinute = prefs[Keys.RATE_LIMIT_PER_MIN] ?: 8,
            maxConcurrentWorkers = prefs[Keys.MAX_CONCURRENT] ?: 2,
            fitWideSpreadsheets = prefs[Keys.FIT_WIDE_SHEETS] ?: true,
            simulateOfflineMode = prefs[Keys.SIMULATE_OFFLINE] ?: false,
            themePreference = theme
        )
    }

    suspend fun updateServerUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        context.dataStore.edit { it[Keys.SERVER_URL] = normalized }
    }

    suspend fun updateMaxFileSizeMb(sizeMb: Int) {
        context.dataStore.edit { it[Keys.MAX_FILE_SIZE_MB] = sizeMb.coerceIn(1, 50) }
    }

    suspend fun updateTimeoutSec(seconds: Int) {
        context.dataStore.edit { it[Keys.TIMEOUT_SEC] = seconds.coerceIn(5, 90) }
    }

    suspend fun updateRateLimitPerMin(requestsPerMin: Int) {
        context.dataStore.edit { it[Keys.RATE_LIMIT_PER_MIN] = requestsPerMin.coerceIn(1, 60) }
    }

    suspend fun updateFitWideSpreadsheets(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FIT_WIDE_SHEETS] = enabled }
    }

    suspend fun updateSimulateOffline(offline: Boolean) {
        context.dataStore.edit { it[Keys.SIMULATE_OFFLINE] = offline }
    }

    suspend fun updateThemePreference(pref: ThemePreference) {
        context.dataStore.edit { it[Keys.THEME_PREF] = pref.name }
    }
}
