package com.autoclicker.arknights.util

import android.graphics.Bitmap

/**
 * 截图辅助工具 v2.1
 * 新增 checkPixelRange：支持 RGB 范围判断（如 R>220 && G>180 && B<80）
 * 原有 checkPixelColor / waitForPixel / waitForPixelNot 保持不变
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= 30
    
    /**
     * 截取屏幕（通过反射调用隐藏API）
     */
    fun captureScreen(service: android.accessibilityservice.AccessibilityService): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return try {
            var resultBitmap: Bitmap? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            
            val displayId = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    service.display?.displayId ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                0
            }
            
            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val screenshotClass = Class.forName("android.accessibilityservice.AccessibilityService\$Screenshot")
            
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onSuccess" -> {
                        try {
                            val screenshot = args[0]
                            val hardwareBuffer = screenshotClass.getMethod("getHardwareBuffer").invoke(screenshot) as? android.hardware.HardwareBuffer
                            if (hardwareBuffer != null) {
                                resultBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                            }
                            screenshotClass.getMethod("close").invoke(screenshot)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error processing screenshot", e)
                        }
                        latch.countDown()
                        null
                    }
                    "onFailure" -> {
                        val errorCode = args[0] as? Int ?: -1
                        android.util.Log.e(TAG, "takeScreenshot failed: $errorCode")
                        latch.countDown()
                        null
                    }
                    else -> null
                }
            }
            
            val takeScreenshotMethod = android.accessibilityservice.AccessibilityService::class.java.getMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                callbackClass
            )
            
            val executor = android.os.Handler(android.os.Looper.getMainLooper())::post
            takeScreenshotMethod.invoke(service, displayId, executor, callback)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            resultBitmap
        } catch (e: Exception) {
            android.util.Log.e(TAG, "captureScreen error", e)
            null
        }
    }
    
    /**
     * 检查指定坐标的像素颜色是否匹配目标颜色（原方法，保持兼容）
     */
    fun checkPixelColor(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int = 30): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(targetColor)) <= tolerance
    }
    
    /**
     * 【新增】RGB 范围判断
     * 传入 R/G/B 各自的验证函数，返回是否全部满足
     * 示例: checkPixelRange(bmp, x, y, { it > 220 }, { it > 180 }, { it < 80 })
     */
    fun checkPixelRange(
        bitmap: Bitmap, x: Int, y: Int,
        checkR: (Int) -> Boolean,
        checkG: (Int) -> Boolean,
        checkB: (Int) -> Boolean
    ): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)
        return checkR(r) && checkG(g) && checkB(b)
    }
    
    /**
     * 【新增】在区域内搜索满足条件的像素点
     * 返回第一个满足条件的坐标，未找到返回 null
     * 用于访问下位、信用翻页等坐标不固定的场景
     */
    fun searchPixel(
        bitmap: Bitmap,
        searchLeft: Int, searchTop: Int, searchRight: Int, searchBottom: Int,
        checkR: (Int) -> Boolean,
        checkG: (Int) -> Boolean,
        checkB: (Int) -> Boolean,
        stepX: Int = 4, stepY: Int = 4
    ): Pair<Int, Int>? {
        for (y in searchTop.coerceAtLeast(0) until searchBottom.coerceAtMost(bitmap.height) step stepY) {
            for (x in searchLeft.coerceAtLeast(0) until searchRight.coerceAtMost(bitmap.width) step stepX) {
                if (checkPixelRange(bitmap, x, y, checkR, checkG, checkB)) {
                    return x to y
                }
            }
        }
        return null
    }
    
    /**
     * 等待像素颜色匹配（原方法，保持兼容）
     */
    fun waitForPixel(
        service: android.accessibilityservice.AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500,
        tolerance: Int = 30
    ): Boolean {
        if (!isSupported) {
            android.util.Log.w(TAG, "Screenshot not supported, falling back to fixed wait")
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) {}
            return false
        }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val matched = checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (matched) return true
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return false }
        }
        android.util.Log.w(TAG, "waitForPixel timeout at ($x,$y)")
        return false
    }
    
    /**
     * 等待像素颜色不匹配（原方法，保持兼容）
     */
    fun waitForPixelNot(
        service: android.accessibilityservice.AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500,
        tolerance: Int = 30
    ): Boolean {
        if (!isSupported) {
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) {}
            return false
        }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val notMatched = !checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (notMatched) return true
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return false }
        }
        android.util.Log.w(TAG, "waitForPixelNot timeout at ($x,$y)")
        return false
    }
}
