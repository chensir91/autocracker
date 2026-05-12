package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs

/**
 * 截图辅助工具 v2.0
 * 提供截图和像素颜色检测功能
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    /**
     * 截取屏幕
     * 使用 takeScreenshot 异步API，通过 CountDownLatch 转同步
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        return try {
            var resultBitmap: Bitmap? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            
            val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                service.display?.displayId ?: 0
            } else {
                @Suppress("DEPRECATION")
                service.windowManager.defaultDisplay.displayId
            }
            
            service.takeScreenshot(
                displayId,
                android.os.Handler(android.os.Looper.getMainLooper())::post,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.Screenshot) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            resultBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            screenshot.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot", e)
                        }
                        latch.countDown()
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed: $errorCode")
                        latch.countDown()
                    }
                }
            )
            
            // 等待截图完成，最多5秒
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error", e)
            null
        }
    }
    
    /**
     * 检查指定坐标的像素颜色是否匹配目标颜色
     * @param bitmap 截图Bitmap
     * @param x X坐标
     * @param y Y坐标
     * @param targetColor 目标颜色 (ARGB)
     * @param tolerance 颜色容差 (0-255 per channel)
     */
    fun checkPixelColor(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int = 30): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return abs(Color.red(pixel) - Color.red(targetColor)) <= tolerance &&
               abs(Color.green(pixel) - Color.green(targetColor)) <= tolerance &&
               abs(Color.blue(pixel) - Color.blue(targetColor)) <= tolerance
    }
    
    /**
     * 等待像素颜色匹配（轮询）
     * @param service 无障碍服务实例
     * @param x X坐标
     * @param y Y坐标
     * @param targetColor 目标颜色 (ARGB)
     * @param timeoutMs 最大等待时间
     * @param intervalMs 检查间隔
     * @param tolerance 颜色容差
     * @return 是否在超时前匹配到目标颜色
     */
    fun waitForPixel(
        service: AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500,
        tolerance: Int = 30
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val matched = checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (matched) return true
            try { Thread.sleep(intervalMs) } catch (e: InterruptedException) { return false }
        }
        Log.w(TAG, "waitForPixel timeout at ($x,$y)")
        return false
    }
    
    /**
     * 等待像素颜色不匹配（消失）
     * @param service 无障碍服务实例
     * @param x X坐标
     * @param y Y坐标
     * @param targetColor 目标颜色 (ARGB)
     * @param timeoutMs 最大等待时间
     * @param intervalMs 检查间隔
     * @param tolerance 颜色容差
     * @return 是否在超时前颜色不再匹配
     */
    fun waitForPixelNot(
        service: AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500,
        tolerance: Int = 30
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val notMatched = !checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (notMatched) return true
            try { Thread.sleep(intervalMs) } catch (e: InterruptedException) { return false }
        }
        Log.w(TAG, "waitForPixelNot timeout at ($x,$y)")
        return false
    }
}
