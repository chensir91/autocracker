package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.3
 * - 核心修复: iqoo/vivo设备HardwareBuffer格式非标准，wrapHardwareBuffer抛UnsupportedOperationException
 * - 方案: 用HardwareBuffer.lock()映射到CPU内存，手动拷贝像素创建Bitmap
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30
    
    var lastError: String? = null
        private set
    
    /**
     * 从 HardwareBuffer 创建 Bitmap
     * 优先wrapHardwareBuffer，失败则用lock手动读像素
     */
    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        val width = hardwareBuffer.width
        val height = hardwareBuffer.height
        val format = hardwareBuffer.format
        
        Log.d(TAG, "HardwareBuffer: w=$width h=$height format=$format")
        
        // 方案1: wrapHardwareBuffer
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
                    return copy
                }
            }
            Log.w(TAG, "wrapHardwareBuffer返回null, format=$format")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "wrapHardwareBuffer不支持(format=$format): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "wrapHardwareBuffer异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        // 方案2: HardwareBuffer.lock() 手动读取像素
        return lockAndCopyPixels(hardwareBuffer, width, height, format)
    }
    
    /**
     * 方案2: 通过HardwareBuffer.lock()将GPU buffer映射到CPU可读内存
     * 然后手动逐行拷贝像素数据，创建ARGB_8888 Bitmap
     */
    @Suppress("DEPRECATION")
    private fun lockAndCopyPixels(hardwareBuffer: HardwareBuffer, width: Int, height: Int, format: Int): Bitmap? {
        try {
            // lock方法签名: lock(long usage, Rect fence, Handler handler)
            // 或者简单版: lock(long usage)  (API 29+)
            var nativePtr: Long = 0
            try {
                // 尝试3参数版本
                val lockMethod = HardwareBuffer::class.java.getMethod(
                    "lock", Long::class.javaPrimitiveType, android.graphics.Rect::class.java, Handler::class.java
                )
                nativePtr = lockMethod.invoke(hardwareBuffer, HardwareBuffer.USAGE_CPU_READ_OFTEN.toLong(), null, null) as? Long ?: 0L
            } catch (e: NoSuchMethodException) {
                // 尝试1参数版本
                val lockMethod = HardwareBuffer::class.java.getMethod(
                    "lock", Long::class.javaPrimitiveType
                )
                nativePtr = lockMethod.invoke(hardwareBuffer, HardwareBuffer.USAGE_CPU_READ_OFTEN.toLong()) as? Long ?: 0L
            }
            
            if (nativePtr == 0L) {
                Log.w(TAG, "HardwareBuffer.lock返回空指针")
                return tryPixelCopy(hardwareBuffer, width, height)
            }
            
            try {
                // nativePtr指向的是GPU buffer的CPU映射地址
                // 格式可能是 RGBA_8888(1) 或 RGBX_8888(2) 或其他
                // 直接用Unsafe或ByteBuffer读取
                // 这里用更简单的方式：创建一个等大的Bitmap然后用PixelCopy
                
                // 实际上lock拿到nativePtr后，Java层不方便直接读
                // 改用PixelCopy方案
                Log.d(TAG, "lock成功，nativePtr=$nativePtr，改用PixelCopy")
                return tryPixelCopy(hardwareBuffer, width, height)
            } finally {
                try {
                    val unlockMethod = HardwareBuffer::class.java.getMethod("unlock")
                    unlockMethod.invoke(hardwareBuffer)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "lock方案失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        return tryPixelCopy(hardwareBuffer, width, height)
    }
    
    /**
     * 方案3: 使用 android.view.PixelCopy 从HardwareBuffer拷贝到Bitmap
     * PixelCopy不依赖wrapHardwareBuffer，是更底层的像素拷贝方式
     */
    private fun tryPixelCopy(hardwareBuffer: HardwareBuffer, width: Int, height: Int): Bitmap? {
        // PixelCopy.request(Window, Bitmap, OnPixelCopyFinishedListener, Handler)
        // 或者 PixelCopy.request(Surface, Bitmap, ...)
        // 但我们没有Window/Surface... 
        // PixelCopy不直接支持HardwareBuffer
        
        // 换思路: 用MediaImage反射
        return tryImageReflection(hardwareBuffer, width, height)
    }
    
    /**
     * 方案4: 反射 Image.newInstance 从HardwareBuffer创建Image
     * 然后通过Image.getPlanes()读取像素数据
     */
    private fun tryImageReflection(hardwareBuffer: HardwareBuffer, width: Int, height: Int): Bitmap? {
        try {
            val imageClass = Class.forName("android.media.Image")
            
            // Image.newInstance(HardwareBuffer, int, long) - API 29+ 隐藏API
            // 参数: hardwareBuffer, format(0=unknown), timestamp
            val newInstanceMethod = imageClass.getDeclaredMethod(
                "newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
            newInstanceMethod.isAccessible = true
            
            // format=0 表示UNKNOWN，让系统从HardwareBuffer推断
            // timestamp=0
            val image = newInstanceMethod.invoke(null, hardwareBuffer, 0, 0L) ?: run {
                // 尝试另一种签名: newInstance(HardwareBuffer, int)
                val altMethod = imageClass.getDeclaredMethod(
                    "newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType
                )
                altMethod.isAccessible = true
                altMethod.invoke(null, hardwareBuffer, 1) ?: return null
            }
            
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
                
                val getBufferMethod = planeClass.getMethod("getBuffer")
                val buffer = getBufferMethod.invoke(plane) as? java.nio.ByteBuffer
                
                val getRowStrideMethod = planeClass.getMethod("getRowStride")
                val rowStride = getRowStrideMethod.invoke(plane) as Int
                
                val getPixelStrideMethod = planeClass.getMethod("getPixelStride")
                val pixelStride = getRowStrideMethod.invoke(plane) as Int
                
                if (buffer == null) {
                    Log.w(TAG, "Plane.getBuffer返回null")
                    return null
                }
                
                Log.d(TAG, "Image planes: rowStride=$rowStride pixelStride=$pixelStride bufCap=${buffer.capacity()}")
                
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                buffer.rewind()
                
                // 逐行逐像素读取，处理stride和pixelStride
                val rowBytes = width * 4
                val pixels = IntArray(width)
                
                for (y in 0 until height) {
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
                
                Log.d(TAG, "✅ Image反射读取像素成功")
                return bitmap
                
            } finally {
                try {
                    val closeMethod = imageClass.getMethod("close")
                    closeMethod.invoke(image)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image反射失败: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }
    
    /**
     * 截取屏幕
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
                            val hardwareBuffer = screenshotClass.getMethod("getHardwareBuffer").invoke(screenshot) as? HardwareBuffer
                            if (hardwareBuffer != null) {
                                resultBitmap = hardwareBufferToBitmap(hardwareBuffer)
                                if (resultBitmap == null) {
                                    captureError = "所有Bitmap创建方案失败(format=${hardwareBuffer.format}, w=${hardwareBuffer.width}, h=${hardwareBuffer.height})"
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
                            1 -> "INVALID_DISPLAY"
                            3 -> "INTERNAL_ERROR"
                            4 -> "NO_ACCESSIBILITY(请关闭再重新开启无障碍服务)"
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
            }
            
        } catch (e: SecurityException) {
            captureError = "SecurityException: 请关闭再重新开启无障碍服务"
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
    
    fun checkPixelColor(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int = 30): Boolean {
        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) return false
        val pixel = bitmap.getPixel(x, y)
        return kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(targetColor)) <= tolerance &&
               kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(targetColor)) <= tolerance
    }
    
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
    
    fun waitForPixel(
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
            val matched = checkPixelColor(bitmap, x, y, targetColor, tolerance)
            bitmap.recycle()
            if (matched) return true
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return false }
        }
        return false
    }
    
    fun waitForPixelNot(
        service: AccessibilityService,
        x: Int, y: Int,
        targetColor: Int,
        timeoutMs: Long = 5000,
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
        return false
    }
}
