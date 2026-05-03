package com.autoclicker.arknights.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 设置管理器，负责保存和加载应用设置
 */
class SettingsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val PREFS_NAME = "arknights_autoclicker_prefs"
        private const val KEY_MIN_INTERVAL = "min_interval"
        private const val KEY_MAX_INTERVAL = "max_interval"
        private const val KEY_LOOP_COUNT = "loop_count"
        private const val KEY_SCHEMES = "schemes"
        
        @Volatile
        private var instance: SettingsManager? = null
        
        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    /**
     * 获取当前设置
     */
    fun getSettings(): AppSettings {
        return AppSettings(
            minIntervalMs = prefs.getInt(KEY_MIN_INTERVAL, 100),
            maxIntervalMs = prefs.getInt(KEY_MAX_INTERVAL, 200),
            loopCount = prefs.getInt(KEY_LOOP_COUNT, -1)
        )
    }
    
    /**
     * 保存设置
     */
    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putInt(KEY_MIN_INTERVAL, settings.minIntervalMs)
            putInt(KEY_MAX_INTERVAL, settings.maxIntervalMs)
            putInt(KEY_LOOP_COUNT, settings.loopCount)
            apply()
        }
    }
    
    /**
     * 获取所有保存的方案
     */
    fun getAllSchemes(): List<ClickScheme> {
        val json = prefs.getString(KEY_SCHEMES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ClickScheme>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 保存方案
     */
    fun saveScheme(scheme: ClickScheme) {
        val schemes = getAllSchemes().toMutableList()
        // 如果已存在同名方案，替换它
        val existingIndex = schemes.indexOfFirst { it.name == scheme.name }
        if (existingIndex >= 0) {
            schemes[existingIndex] = scheme
        } else {
            schemes.add(scheme)
        }
        saveAllSchemes(schemes)
    }
    
    /**
     * 删除方案
     */
    fun deleteScheme(name: String) {
        val schemes = getAllSchemes().filter { it.name != name }
        saveAllSchemes(schemes)
    }
    
    /**
     * 保存所有方案
     */
    private fun saveAllSchemes(schemes: List<ClickScheme>) {
        val json = gson.toJson(schemes)
        prefs.edit().putString(KEY_SCHEMES, json).apply()
    }
}
