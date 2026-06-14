package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.0
 * - 新增 lastError：记录最近一次截图失败的详细原因
 * - 修复 Bitmap.wrapHardwareBuffer ColorSpace 问题（null→SRGB）
 * - 新增详细 onFailure 错误码解读
 * - 新增 SecurityException 专门捕获（权限未生效）
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30
    
    /** 最近一次截图失败的详细原因，可用于向用户展示 */
    var lastError: String? = null
        private set
    
    /**
     * 截取屏幕（通过反射调用 AccessibilityService.takeScreenshot）
     * API 33+ 是公开API但SDK stubs可能未暴露，统一用反射
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) {
            lastError = "系统版本低于Android 11，不支持截图"
            return null
        }
        
        lastError = null
        var resultBitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        var captureError: String? = null
        
        try {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.display?.displayId ?: 0
            } else {
                0
            }
            
            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val screenshotClass = Class.forName("android.accessibilityservice.AccessibilityService\$Screenshot")
            
            val executor = Executor { runnable ->
                Handler(Looper.getMainLooper()).post(runnable)
            }
            
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
                                // 修复: ColorSpace传null在某些设备上wrapHardwareBuffer返回null
                                // 使用SRGB作为默认ColorSpace
                                val colorSpace = try {
                                    @Suppress("DEPRECATION")
                                    ColorSpace.get(ColorSpace.Named.SRGB)
                                } catch (e: Exception) {
                                    null
                                }
                                resultBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                if (resultBitmap == null) {
                                    captureError = "wrapHardwareBuffer返回null(format=${hardwareBuffer.format}, w=${hardwareBuffer.width}, h=${hardwareBuffer.height})"
                                }
                                hardwareBuffer.close()
                            } else {
                                captureError = "hardwareBuffer为null"
                            }
                            screenshotClass.getMethod("close").invoke(screenshot)
                        } catch (e: Exception) {
                            captureError = "处理截图异常: ${e.javaClass.simpleName}: ${e.message}"
                            Log.e(TAG, "Error processing screenshot", e)
                        }
                        latch.countDown()
                        null
                    }
                    "onFailure" -> {
                        val errorCode = args[0] as? Int ?: -1
                        val errorName = when (errorCode) {
                            1 -> "INVALID_DISPLAY(无效显示器ID)"
                            3 -> "INTERNAL_ERROR(系统内部错误)"
                            4 -> "NO_ACCESSIBILITY(截图权限未授予!请关闭再重新开启无障碍服务)"
                            else -> "UNKNOWN($errorCode)"
                        }
                        captureError = "takeScreenshot onFailure: $errorName"
                        Log.e(TAG, captureError!!)
                        latch.countDown()
                        null
                    }
                    else -> null
                }
            }
            
            val takeScreenshotMethod = AccessibilityService::class.java.getMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                callbackClass
            )
            
            takeScreenshotMethod.invoke(service, displayId, executor, callback)
            
            val awaited = latch.await(5, TimeUnit.SECONDS)
            if (!awaited) {
                captureError = "截图超时(5秒无回调)"
                Log.e(TAG, captureError!!)
            }
            
        } catch (e: SecurityException) {
            captureError = "SecurityException: 截图权限未生效，请在系统设置中【关闭再重新开启】无障碍服务"
            Log.e(TAG, captureError!!, e)
        } catch (e: NoSuchMethodException) {
            captureError = "NoSuchMethodException: takeScreenshot方法不存在，系统版本不兼容"
            Log.e(TAG, captureError!!, e)
        } catch (e: Exception) {
            captureError = "截图异常: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, captureError!!, e)
        }
        
        if (resultBitmap == null) {
            lastError = captureError ?: "未知截图失败"
            Log.w(TAG, "截图失败: $lastError")
        }
        
        return resultBitmap
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
     * RGB 范围判断
     * 传入 R/G/B 各自的验证函数，返回是否全部满足
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
     * 在区域内搜索满足条件的像素点
     * 返回第一个满足条件的坐标，未找到返回 null
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
     * 等待像素颜色不匹配（原方法，保持兼容）
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
