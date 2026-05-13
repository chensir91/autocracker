package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 截图辅助工具 v2.0
 * 提供截图和像素颜色检测功能
 * 注意：takeScreenshot API 需要 API 30+ (Android 11+)
 * 低于 API 30 的设备将返回 null，调用方应 fallback 到固定等待
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    
    /**
     * 截取屏幕
     * 需要 API 30+ (Android 11+)
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (!isSupported) return null
        return try {
            var resultBitmap: Bitmap? = null
            val latch = CountDownLatch(1)
            
            val displayId = service.display?.displayId ?: 0
            
            service.takeScreenshot(
                displayId,
                Handler(Looper.getMainLooper())::post,
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
            
            latch.await(5, TimeUnit.SECONDS)
            resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error", e)
            null
        }
    }
    
    /**
     * 检查指定坐标的像素颜色是否匹配目标颜色
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
     * 不支持截图的设备会直接返回 false
     */
    fun waitForPixel(
        service: AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500,
        tolerance: Int = 30
    ): Boolean {
        if (!isSupported) {
            Log.w(TAG, "Screenshot not supported on this device, falling back to fixed wait")
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
        Log.w(TAG, "waitForPixel timeout at ($x,$y)")
        return false
    }
    
    /**
     * 等待像素颜色不匹配（消失）
     */
    fun waitForPixelNot(
        service: AccessibilityService,
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
        Log.w(TAG, "waitForPixelNot timeout at ($x,$y)")
        return false
    }
}
