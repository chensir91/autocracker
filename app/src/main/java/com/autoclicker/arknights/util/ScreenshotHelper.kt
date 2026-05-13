package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 截图辅助工具 v2.0
 * 使用反射调用 AccessibilityService.takeScreenshot（该API在部分SDK版本中为@hide）
 * 需要 API 21+ 运行，API 30+ 才真正支持截图
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30
    
    /**
     * 截取屏幕（通过反射调用隐藏API）
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            var resultBitmap: Bitmap? = null
            val latch = CountDownLatch(1)
            
            // 获取 displayId
            val displayId = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    service.display?.displayId ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                0
            }
            
            // 通过反射获取 TakeScreenshotCallback 类和 Screenshot 类
            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val screenshotClass = Class.forName("android.accessibilityservice.AccessibilityService\$Screenshot")
            
            // 创建 callback 代理
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
                            Log.e(TAG, "Error processing screenshot", e)
                        }
                        latch.countDown()
                        null
                    }
                    "onFailure" -> {
                        val errorCode = args[0] as? Int ?: -1
                        Log.e(TAG, "takeScreenshot failed: $errorCode")
                        latch.countDown()
                        null
                    }
                    else -> null
                }
            }
            
            // 调用 takeScreenshot 方法
            val takeScreenshotMethod = AccessibilityService::class.java.getMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                callbackClass
            )
            
            val executor = Handler(Looper.getMainLooper())::post
            
            takeScreenshotMethod.invoke(service, displayId, executor, callback)
            
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
            Log.w(TAG, "Screenshot not supported, falling back to fixed wait")
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
