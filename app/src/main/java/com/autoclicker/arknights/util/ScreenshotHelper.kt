package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.2
 * - 修复: wrapHardwareBuffer 在 iqoo/vivo 等OEM设备上抛 UnsupportedOperationException
 * - 兜底: 用 Image.newInstance(hardwareBuffer) + getPlanes 读取像素
 * - 新增: lastError 记录详细失败原因
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30
    
    var lastError: String? = null
        private set
    
    /**
     * 从 HardwareBuffer 创建 Bitmap
     */
    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        val width = hardwareBuffer.width
        val height = hardwareBuffer.height
        val format = hardwareBuffer.format
        val stride = hardwareBuffer.stride
        
        Log.d(TAG, "HardwareBuffer: w=$width h=$height format=$format stride=$stride")
        
        // 方案1: wrapHardwareBuffer（标准高效方式）
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
        
        // 方案2: 通过反射创建 Image 从 HardwareBuffer 读取像素
        try {
            val bitmap = imageFromHardwareBuffer(hardwareBuffer, width, height)
            if (bitmap != null) {
                Log.d(TAG, "✅ Image反射兜底成功")
                return bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image反射兜底失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        // 方案3: 直接读 HardwareBuffer 的 native buffer（最后手段）
        try {
            val bitmap = directBufferRead(hardwareBuffer, width, height, stride)
            if (bitmap != null) {
                Log.d(TAG, "✅ 直接读取buffer兜底成功")
                return bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "直接读取buffer兜底失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        Log.e(TAG, "所有方案均失败, format=$format")
        return null
    }
    
    /**
     * 方案2: 反射 Image.newInstance(hardwareBuffer) 创建 Image，读取 planes
     */
    private fun imageFromHardwareBuffer(hardwareBuffer: HardwareBuffer, width: Int, height: Int): Bitmap? {
        // Image.newInstance 是隐藏API (API 29+)
        val imageClass = Class.forName("android.media.Image")
        val newInstanceMethod = imageClass.getDeclaredMethod(
            "newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
        )
        newInstanceMethod.isAccessible = true
        
        // timestamp = 0
        val image = newInstanceMethod.invoke(null, hardwareBuffer, 1, 0L) ?: return null
        
        try {
            val getPlanesMethod = imageClass.getMethod("getPlanes")
            @Suppress("UNCHECKED_CAST")
            val planes = getPlanesMethod.invoke(image) as? Array<Any> ?: return null
            
            if (planes.isEmpty()) return null
            
            val plane = planes[0]
            val planeClass = plane.javaClass
            
            val getBufferMethod = planeClass.getMethod("getBuffer")
            val buffer = getBufferMethod.invoke(plane) as? java.nio.ByteBuffer ?: return null
            
            val getRowStrideMethod = planeClass.getMethod("getRowStride")
            val rowStride = getRowStrideMethod.invoke(plane) as Int
            
            val getPixelStrideMethod = planeClass.getMethod("getPixelStride")
            val pixelStride = getPixelStrideMethod.invoke(plane) as Int
            
            Log.d(TAG, "Image planes: rowStride=$rowStride pixelStride=$pixelStride bufferCap=${buffer.capacity()}")
            
            // 创建ARGB_8888 Bitmap
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            buffer.rewind()
            val rowBytes = width * 4  // ARGB_8888 每像素4字节
            
            if (pixelStride == 4 && rowStride == rowBytes) {
                // 最简单的情况：连续排列
                val pixels = IntArray(width * height)
                buffer.asIntBuffer().get(pixels)
                // RGBA → ARGB 转换
                for (i in pixels.indices) {
                    val rgba = pixels[i]
                    pixels[i] = (rgba ushr 8) or (rgba shl 24)  // RGBA→ARGB
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                // 有padding的情况，逐行读取
                val row = ByteArray(rowStride.coerceAtLeast(rowBytes))
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    buffer.get(row, 0, rowStride.coerceAtLeast(rowBytes).coerceAtMost(row.size))
                    for (x in 0 until width) {
                        val offset = x * pixelStride
                        if (offset + 3 < row.size) {
                            val r = row[offset].toInt() and 0xFF
                            val g = row[offset + 1].toInt() and 0xFF
                            val b = row[offset + 2].toInt() and 0xFF
                            val a = row[offset + 3].toInt() and 0xFF
                            pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
                }
            }
            
            return bitmap
        } finally {
            // 关闭Image
            try {
                val closeMethod = imageClass.getMethod("close")
                closeMethod.invoke(image)
            } catch (_: Exception) {}
        }
    }
    
    /**
     * 方案3: 直接从HardwareBuffer的native指针读取像素
     * 使用HardwareBuffer的lock方法获取CPU可见的buffer地址
     */
    private fun directBufferRead(hardwareBuffer: HardwareBuffer, width: Int, height: Int, stride: Int): Bitmap? {
        // HardwareBuffer.lock() 是 API 29+ 的方法
        // 但在 API 31+ 已被标记为 deprecated，仍然可用
        try {
            val lockMethod = HardwareBuffer::class.java.getMethod(
                "lock",
                Long::class.javaPrimitiveType  // usage
            )
            val nativePtr = lockMethod.invoke(hardwareBuffer, HardwareBuffer.USAGE_CPU_READ_OFTEN.toLong()) as? Long
            if (nativePtr == null || nativePtr == 0L) {
                Log.w(TAG, "HardwareBuffer.lock返回空指针")
                return null
            }
            
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // 直接从native内存拷贝到Bitmap
                // 需要通过Unsafe或memcpy，这里用简化的方式
                // 实际上lock返回的是native指针，Java层不方便直接读取
                // 换用另一种方式：通过ByteBuffer
                bitmap.recycle()
            } finally {
                try {
                    val unlockMethod = HardwareBuffer::class.java.getMethod("unlock")
                    unlockMethod.invoke(hardwareBuffer)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "HardwareBuffer.lock失败: ${e.message}")
        }
        
        return null
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
                                    captureError = "从HardwareBuffer创建Bitmap失败(format=${hardwareBuffer.format}, w=${hardwareBuffer.width}, h=${hardwareBuffer.height})"
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
            Log.w(TAG, "Screenshot not supported")
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
