package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.autoclicker.arknights.service.AutoClickAccessibilityService

/**
 * 权限工具类，用于检查和请求必要的权限
 */
object PermissionUtils {
    
    /**
     * 检查是否具有悬浮窗权限
     */
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
    
    /**
     * 请求悬浮窗权限
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }
    
    /**
     * 检查无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        val serviceName = ComponentName(context, AutoClickAccessibilityService::class.java)
        
        return enabledServices.any { it.resolveInfo.serviceInfo.let { info ->
            info.packageName == serviceName.packageName && info.name == serviceName.className
        } }
    }
    
    /**
     * 跳转到无障碍服务设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        context.startActivity(intent)
    }
    
    /**
     * 检查通知权限（Android 13+）
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }
    }
}
