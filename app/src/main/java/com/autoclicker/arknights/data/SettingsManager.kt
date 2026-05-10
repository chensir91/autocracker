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
        
        // 安全性设置
        private const val KEY_OFFSET_RANGE = "offset_range"
        private const val KEY_PAUSE_MIN_CLICKS = "pause_min_clicks"
        private const val KEY_PAUSE_MAX_CLICKS = "pause_max_clicks"
        private const val KEY_PAUSE_MIN_DURATION = "pause_min_duration"
        private const val KEY_PAUSE_MAX_DURATION = "pause_max_duration"
        
        // 操作类型默认值
        private const val KEY_LONG_PRESS_DURATION = "long_press_duration"
        private const val KEY_WAIT_DURATION = "wait_duration"
        
        // 屏幕分辨率
        private const val KEY_SCREEN_WIDTH = "screen_width"
        private const val KEY_SCREEN_HEIGHT = "screen_height"
        
        // 默认分辨率选项
        const val DEFAULT_WIDTH = 2800
        const val DEFAULT_HEIGHT = 1260
        const val ALT_WIDTH = 2400
        const val ALT_HEIGHT = 1080
        
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
            loopCount = prefs.getInt(KEY_LOOP_COUNT, -1),
            // 安全性设置
            offsetRange = prefs.getInt(KEY_OFFSET_RANGE, 8),
            pauseMinClicks = prefs.getInt(KEY_PAUSE_MIN_CLICKS, 20),
            pauseMaxClicks = prefs.getInt(KEY_PAUSE_MAX_CLICKS, 50),
            pauseMinDuration = prefs.getInt(KEY_PAUSE_MIN_DURATION, 1000),
            pauseMaxDuration = prefs.getInt(KEY_PAUSE_MAX_DURATION, 3000),
            // 操作类型默认值
            longPressDuration = prefs.getInt(KEY_LONG_PRESS_DURATION, 500),
            waitDuration = prefs.getInt(KEY_WAIT_DURATION, 1000),
            // 屏幕分辨率
            screenWidth = prefs.getInt(KEY_SCREEN_WIDTH, DEFAULT_WIDTH),
            screenHeight = prefs.getInt(KEY_SCREEN_HEIGHT, DEFAULT_HEIGHT)
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
            // 安全性设置
            putInt(KEY_OFFSET_RANGE, settings.offsetRange)
            putInt(KEY_PAUSE_MIN_CLICKS, settings.pauseMinClicks)
            putInt(KEY_PAUSE_MAX_CLICKS, settings.pauseMaxClicks)
            putInt(KEY_PAUSE_MIN_DURATION, settings.pauseMinDuration)
            putInt(KEY_PAUSE_MAX_DURATION, settings.pauseMaxDuration)
            // 操作类型默认值
            putInt(KEY_LONG_PRESS_DURATION, settings.longPressDuration)
            putInt(KEY_WAIT_DURATION, settings.waitDuration)
            // 屏幕分辨率
            putInt(KEY_SCREEN_WIDTH, settings.screenWidth)
            putInt(KEY_SCREEN_HEIGHT, settings.screenHeight)
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
    
    /**
     * 保存屏幕分辨率
     */
    fun saveResolution(width: Int, height: Int) {
        prefs.edit().apply {
            putInt(KEY_SCREEN_WIDTH, width)
            putInt(KEY_SCREEN_HEIGHT, height)
            apply()
        }
    }
    
    /**
     * 获取当前屏幕分辨率
     * @return Pair(width, height)
     */
    fun getResolution(): Pair<Int, Int> {
        val width = prefs.getInt(KEY_SCREEN_WIDTH, DEFAULT_WIDTH)
        val height = prefs.getInt(KEY_SCREEN_HEIGHT, DEFAULT_HEIGHT)
        return Pair(width, height)
    }
}
