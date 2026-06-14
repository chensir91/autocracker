package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.4
 * - 修复: Toast信息截断 → 改用短消息+详细日志
 * - 修复: Image.newInstance参数和pixelStride bug
 * - 新增: SurfaceControl.screenshot() 作为终极备用
 * - 诊断: 记录HardwareBuffer format数值
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30
    
    var lastError: String? = null
        private set
    
    /** 诊断信息，用于状态栏显示 */
    var lastDiagnostic: String? = null
        private set
    
    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        val width = hardwareBuffer.width
        val height = hardwareBuffer.height
        val format = hardwareBuffer.format
        val usage = hardwareBuffer.usage
        
        lastDiagnostic = "HwBuf: ${width}x${height} fmt=$format usage=$usage"
        Log.d(TAG, lastDiagnostic!!)
        
        // 方案1: wrapHardwareBuffer（标准方式）
        try {
            val colorSpace = try {
                @Suppress("DEPRECATION")
                ColorSpace.get(ColorSpace.Named.SRGB)
            } catch (_: Exception) { null }
            
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (wrapped != null) {
                val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                if (copy != null) {
                    Log.d(TAG, "✅ wrapHardwareBuffer成功")
                    lastDiagnostic = "截图OK(wrap)"
                    return copy
                }
            }
            Log.w(TAG, "wrapHardwareBuffer返回null, fmt=$format")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "wrapHardwareBuffer不支持: fmt=$format msg=${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "wrapHardwareBuffer异常: ${e.javaClass.simpleName}")
        }
        
        // 方案2: Image.newInstance反射 + getPlanes读像素
        try {
            val bitmap = imageReflectionRead(hardwareBuffer, width, height, format)
            if (bitmap != null) {
                Log.d(TAG, "✅ Image反射成功")
                lastDiagnostic = "截图OK(Image反射)"
                return bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image反射失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        // 方案3: SurfaceControl.screenshot() 完全不同的截图路径
        try {
            val bitmap = surfaceControlScreenshot(width, height)
            if (bitmap != null) {
                Log.d(TAG, "✅ SurfaceControl截图成功")
                lastDiagnostic = "截图OK(SurfaceControl)"
                return bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceControl失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        lastDiagnostic = "全部失败: fmt=$format"
        return null
    }
    
    /**
     * 方案2: Image.newInstance 反射读取像素
     */
    private fun imageReflectionRead(hardwareBuffer: HardwareBuffer, width: Int, height: Int, format: Int): Bitmap? {
        val imageClass = Class.forName("android.media.Image")
        
        // 尝试多种方法签名
        val image = try {
            // 签名1: newInstance(HardwareBuffer, int format, long timestamp)
            val m = imageClass.getDeclaredMethod(
                "newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            m.isAccessible = true
            m.invoke(null, hardwareBuffer, format, 0L)
        } catch (e: Exception) {
            Log.d(TAG, "Image.newInstance(3参数)失败: ${e.message}")
            try {
                // 签名2: newInstance(HardwareBuffer, int numPlanes)
                val m = imageClass.getDeclaredMethod(
                    "newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType
                )
                m.isAccessible = true
                m.invoke(null, hardwareBuffer, 1)
            } catch (e2: Exception) {
                Log.d(TAG, "Image.newInstance(2参数)也失败: ${e2.message}")
                null
            }
        } ?: return null
        
        try {
            val getPlanesMethod = imageClass.getMethod("getPlanes")
            @Suppress("UNCHECKED_CAST")
            val planes = getPlanesMethod.invoke(image) as? Array<Any>
            if (planes == null || planes.isEmpty()) {
                Log.w(TAG, "Image.getPlanes返回空")
                return null
            }
            
            val plane = planes[0]
            val planeClass = plane.javaClass
            
            val buffer = planeClass.getMethod("getBuffer").invoke(plane) as? java.nio.ByteBuffer
            if (buffer == null) {
                Log.w(TAG, "Plane.getBuffer返回null")
                return null
            }
            
            val rowStride = planeClass.getMethod("getRowStride").invoke(plane) as Int
            val pixelStride = planeClass.getMethod("getPixelStride").invoke(plane) as Int
            
            Log.d(TAG, "Image planes: rowStride=$rowStride pixelStride=$pixelStride bufCap=${buffer.capacity()}")
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            
            if (pixelStride == 4) {
                // RGBA_8888格式，可以批量读取
                val rowBytes = width * 4
                val pixels = IntArray(width * height)
                
                if (rowStride == rowBytes) {
                    // 连续排列，最快
                    buffer.asIntBuffer().get(pixels)
                    for (i in pixels.indices) {
                        val rgba = pixels[i]
                        pixels[i] = (rgba ushr 8) or (rgba shl 24) // RGBA→ARGB
                    }
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                } else {
                    // 有行padding
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        val rowPixels = IntArray(width)
                        val intBuf = buffer.asIntBuffer()
                        intBuf.limit(width)
                        intBuf.get(rowPixels)
                        for (i in rowPixels.indices) {
                            val rgba = rowPixels[i]
                            rowPixels[i] = (rgba ushr 8) or (rgba shl 24)
                        }
                        bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
                    }
                }
            } else {
                // 非标准pixelStride，逐像素读
                for (y in 0 until height) {
                    val pixels = IntArray(width)
                    for (x in 0 until width) {
                        val pos = y * rowStride + x * pixelStride
                        if (pos + 3 < buffer.capacity()) {
                            val r = buffer.get(pos).toInt() and 0xFF
                            val g = buffer.get(pos + 1).toInt() and 0xFF
                            val b = buffer.get(pos + 2).toInt() and 0xFF
                            val a = buffer.get(pos + 3).toInt() and 0xFF
                            pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
                }
            }
            
            return bitmap
        } finally {
            try { imageClass.getMethod("close").invoke(image) } catch (_: Exception) {}
        }
    }
    
    /**
     * 方案3: SurfaceControl.screenshot() - 完全不同的截图路径
     * 不经过takeScreenshot/HardwareBuffer，直接返回Bitmap
     */
    private fun surfaceControlScreenshot(width: Int, height: Int): Bitmap? {
        val scClass = Class.forName("android.view.SurfaceControl")
        
        // 尝试多种方法签名
        // API 34+: screenshot(IBinder, Rect, int, int, boolean, int)
        // API 33: screenshot(IBinder, Rect, int, int, int, boolean, int)  
        // 更老: screenshot(Rect, int, int, int)
        
        try {
            // 获取默认display token
            val getInternalDisplayToken = scClass.getDeclaredMethod("getInternalDisplayToken")
            getInternalDisplayToken.isAccessible = true
            val displayToken = getInternalDisplayToken.invoke(null) ?: return null
            
            val crop = Rect(0, 0, width, height)
            
            // 先试5参数版本 (API 34+)
            try {
                val method = scClass.getDeclaredMethod(
                    "screenshot",
                    android.os.IBinder::class.java, Rect::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                val result = method.invoke(null, displayToken, crop, width, height, false, 0)
                if (result is Bitmap) return result
            } catch (_: Exception) {}
            
            // 试6参数版本 (API 33)
            try {
                val method = scClass.getDeclaredMethod(
                    "screenshot",
                    android.os.IBinder::class.java, Rect::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                val result = method.invoke(null, displayToken, crop, width, height, 0, false, 0)
                if (result is Bitmap) return result
            } catch (_: Exception) {}
            
            // 试4参数版本 (老API)
            try {
                val method = scClass.getDeclaredMethod(
                    "screenshot",
                    Rect::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                val result = method.invoke(null, crop, width, height, 0)
                if (result is Bitmap) return result
            } catch (_: Exception) {}
            
            // 试3参数版本
            try {
                val method = scClass.getDeclaredMethod(
                    "screenshot",
                    Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                val result = method.invoke(null, crop, width, height)
                if (result is Bitmap) return result
            } catch (_: Exception) {}
            
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceControl完全失败: ${e.message}")
        }
        
        return null
    }
    
    /**
     * 截取屏幕
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) {
            lastError = "需Android 11+"
            return null
        }
        
        lastError = null
        var resultBitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        var captureError: String? = null
        
        try {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.display?.displayId ?: 0
            } else { 0 }
            
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
                            val hardwareBuffer = screenshotClass.getMethod("getHardwareBuffer").invoke(screenshot) as? HardwareBuffer
                            if (hardwareBuffer != null) {
                                resultBitmap = hardwareBufferToBitmap(hardwareBuffer)
                                if (resultBitmap == null) {
                                    // 用short消息
                                    captureError = "fmt=${hardwareBuffer.format}不可读"
                                }
                                hardwareBuffer.close()
                            } else {
                                captureError = "hwBuf=null"
                            }
                            screenshotClass.getMethod("close").invoke(screenshot)
                        } catch (e: Exception) {
                            captureError = "${e.javaClass.simpleName}"
                        }
                        latch.countDown()
                        null
                    }
                    "onFailure" -> {
                        val errorCode = args[0] as? Int ?: -1
                        captureError = "err=$errorCode"
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
            
            if (!latch.await(5, TimeUnit.SECONDS)) {
                captureError = "超时"
            }
            
        } catch (e: SecurityException) {
            captureError = "请重开无障碍"
        } catch (e: Exception) {
            captureError = e.javaClass.simpleName
        }
        
        if (resultBitmap == null) {
            lastError = captureError ?: "未知失败"
            Log.w(TAG, "截图失败: $lastError diag=$lastDiagnostic")
        }
        
        return resultBitmap
    }
    
    fun checkPixelColor(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int = 30): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(targetColor)) <= tolerance
    }
    
    fun checkPixelRange(
        bitmap: Bitmap, x: Int, y: Int,
        checkR: (Int) -> Boolean, checkG: (Int) -> Boolean, checkB: (Int) -> Boolean
    ): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return checkR(android.graphics.Color.red(pixel)) &&
               checkG(android.graphics.Color.green(pixel)) &&
               checkB(android.graphics.Color.blue(pixel))
    }
    
    fun searchPixel(
        bitmap: Bitmap,
        searchLeft: Int, searchTop: Int, searchRight: Int, searchBottom: Int,
        checkR: (Int) -> Boolean, checkG: (Int) -> Boolean, checkB: (Int) -> Boolean,
        stepX: Int = 4, stepY: Int = 4
    ): Pair<Int, Int>? {
        for (y in searchTop.coerceAtLeast(0) until searchBottom.coerceAtMost(bitmap.height) step stepY) {
            for (x in searchLeft.coerceAtLeast(0) until searchRight.coerceAtMost(bitmap.width) step stepX) {
                if (checkPixelRange(bitmap, x, y, checkR, checkG, checkB)) return x to y
            }
        }
        return null
    }
    
    fun waitForPixel(service: AccessibilityService, x: Int, y: Int, targetColor: Int, timeoutMs: Long = 30000, intervalMs: Long = 500, tolerance: Int = 30): Boolean {
        if (!isSupported) { try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) {}; return false }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val matched = checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (matched) return true
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return false }
        }
        return false
    }
    
    fun waitForPixelNot(service: AccessibilityService, x: Int, y: Int, targetColor: Int, timeoutMs: Long = 30000, intervalMs: Long = 500, tolerance: Int = 30): Boolean {
        if (!isSupported) { try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) {}; return false }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bitmap = captureScreen(service) ?: continue
            val notMatched = !checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (notMatched) return true
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return false }
        }
        return false
    }
}
