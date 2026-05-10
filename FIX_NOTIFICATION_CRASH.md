# 明日方舟连点器闪退bug修复方案

## 问题描述
APP授予通知权限后立即闪退

## 问题根因
1. `FloatingWindowService.onStartCommand()` 中直接调用 `startForeground()` 时，通知渠道可能还未创建完成
2. `createNotificationChannel()` 没有异常处理，可能导致 NPE
3. Android 13+ 的 `POST_NOTIFICATIONS` 权限处理缺少防御性检查
4. 权限授予回调中立即启动服务可能导致时序问题

## 修复内容

### 1. FloatingWindowService.kt 修改

**添加标志位跟踪通知渠道创建状态**
```kotlin
// 通知渠道是否已创建的标志
private var notificationChannelCreated = false
```

**修改 onCreate() - 添加异常处理**
```kotlin
try {
    createNotificationChannel()
    createCompletionNotificationChannel()
    notificationChannelCreated = true
    Log.d(TAG, "Notification channels created successfully")
} catch (e: Exception) {
    Log.e(TAG, "Error creating notification channels", e)
}
```

**修改 onStartCommand() - 添加防御性检查**
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 确保通知渠道已创建
    if (!notificationChannelCreated) {
        Log.w(TAG, "Notification channels not created yet, creating now...")
        try {
            createNotificationChannel()
            createCompletionNotificationChannel()
            notificationChannelCreated = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channels in onStartCommand", e)
        }
    }
    
    // Android 13+ 检查通知权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager != null && !notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled, cannot start foreground service properly")
        }
    }
    
    try {
        val notification = createNotification()
        if (notification != null) {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service started with notification")
        } else {
            Log.e(TAG, "Failed to create notification")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error starting foreground service", e)
    }
    
    return START_STICKY
}
```

**修改 createNotificationChannel() - 添加防御性处理**
```kotlin
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created: $CHANNEL_ID")
            } else {
                Log.e(TAG, "NotificationManager is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }
}
```

**修改 createNotification() - 返回可空类型**
```kotlin
private fun createNotification(): Notification? {
    try {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // ... 通知构建逻辑
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "Error creating notification", e)
        return null
    }
}
```

### 2. MainActivity.kt 修改

**修改 notificationPermissionLauncher 回调**
```kotlin
private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        // 权限授予后，等待一小段时间确保系统完成权限状态更新
        binding.root.postDelayed({
            try {
                startFloatingService()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service after permission granted", e)
            }
        }, 100)
    }
}
```

**修改 checkAndRequestPermissions() - 添加异常处理**
```kotlin
try {
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
} catch (e: Exception) {
    Log.e(TAG, "Error launching permission request", e)
}
return

// 隐藏权限提示
binding.permissionHint.visibility = View.GONE

try {
    startFloatingService()
} catch (e: Exception) {
    Log.e(TAG, "Error starting floating service", e)
}
```

## 推送方式

由于当前环境没有 GitHub token，请手动执行以下命令推送代码：

```bash
cd ~/autocracker
git push origin main
```

推送后，GitHub Actions 会自动触发编译并创建 Release。

## 验证方式

1. 推送代码后，等待 GitHub Actions 完成编译
2. 下载生成的 APK
3. 安装到设备并测试通知权限授予流程

## 预期效果

修复后，APP在授予通知权限后不会再闪退，悬浮窗服务能正常启动。
