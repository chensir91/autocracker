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
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.08
 * - 回调只保存HardwareBuffer引用，不在Proxy里做转换
 * - 所有转换在captureScreen主逻辑完成
 * - 4层兜底: wrap→lock+Unsafe→Image反射→SurfaceControl
 * - 诊断日志写app目录
 * - 隐藏API绕过
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    const val VERSION = "3.08"

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    var lastError: String? = null
        private set

    var lastDiagnostic: String? = null
        private set

    private var hiddenApiBypassed = false
    var diagFilePath: String? = null

    fun diagLog(msg: String) {
        Log.d(TAG, msg)
        try {
            val path = diagFilePath ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            File(path).appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }

    fun bypassHiddenApiRestrictions() {
        if (hiddenApiBypassed) return
        if (Build.VERSION.SDK_INT < 28) { hiddenApiBypassed = true; return }
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, Array<Any>::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf(Array<String>::class.java)
            ) as Method
            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf(arrayOf("L")))
            hiddenApiBypassed = true
            diagLog("✅ 隐藏API限制已绕过")
        } catch (e: Exception) {
            diagLog("⚠️ 隐藏API绕过失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        val width = hardwareBuffer.width
        val height = hardwareBuffer.height
        val format = hardwareBuffer.format
        val usage = hardwareBuffer.usage

        lastDiagnostic = "fmt=$format ${width}x${height} u=$usage"
        diagLog("===== 新截图 v$VERSION =====")
        diagLog("HwBuf: ${width}x${height} fmt=$format usage=$usage")

        bypassHiddenApiRestrictions()

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
                    diagLog("✅ wrapHardwareBuffer成功")
                    lastDiagnostic = "OK(wrap)"
                    return copy
                }
            }
            diagLog("⚠️ wrapHardwareBuffer返回null, fmt=$format")
        } catch (e: UnsupportedOperationException) {
            diagLog("⚠️ wrapHardwareBuffer不支持: fmt=$format msg=${e.message}")
        } catch (e: Exception) {
            diagLog("⚠️ wrapHardwareBuffer异常: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案2: HardwareBuffer.lock() + Unsafe
        try {
            val bitmap = hardwareBufferLockRead(hardwareBuffer, width, height, format)
            if (bitmap != null) {
                diagLog("✅ HardwareBuffer.lock成功")
                lastDiagnostic = "OK(lock)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ HardwareBuffer.lock失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案3: Image.newInstance反射
        try {
            val bitmap = imageReflectionRead(hardwareBuffer, width, height, format)
            if (bitmap != null) {
                diagLog("✅ Image反射成功")
                lastDiagnostic = "OK(Image)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ Image反射失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案4: SurfaceControl.screenshot()
        try {
            val bitmap = surfaceControlScreenshot(width, height)
            if (bitmap != null) {
                diagLog("✅ SurfaceControl截图成功")
                lastDiagnostic = "OK(SC)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ SurfaceControl失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        lastDiagnostic = "ALL_FAIL fmt=$format"
        diagLog("❌ 全部失败: fmt=$format usage=$usage ${width}x${height}")
        return null
    }

    private fun hardwareBufferLockRead(hardwareBuffer: HardwareBuffer, width: Int, height: Int, format: Int): Bitmap? {
        val CPU_READ_OFTEN = 6L
        var nativePtr = 0L
        var stride = 0
        var lockResult: Any? = null

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val lockMethod = HardwareBuffer::class.java.getDeclaredMethod("lock", Long::class.javaPrimitiveType)
                lockMethod.isAccessible = true
                lockResult = lockMethod.invoke(hardwareBuffer, CPU_READ_OFTEN)
                diagLog("lock(API34)返回: ${lockResult?.javaClass?.name}")
            } catch (e: Exception) {
                diagLog("lock(API34)失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (lockResult == null) {
            try {
                for (method in HardwareBuffer::class.java.declaredMethods) {
                    if (method.name == "lock" && method.returnType == Long::class.javaPrimitiveType) {
                        method.isAccessible = true
                        val paramTypes = method.parameterTypes
                        val args = arrayOfNulls<Any>(paramTypes.size)
                        args[0] = CPU_READ_OFTEN.toInt()
                        for (i in 1 until paramTypes.size) { args[i] = null }
                        nativePtr = method.invoke(hardwareBuffer, *args) as Long
                        diagLog("lock(旧API)成功: nativePtr=$nativePtr")
                        break
                    }
                }
            } catch (e: Exception) {
                diagLog("lock(旧API)失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (lockResult != null && nativePtr == 0L) {
            try {
                for (m in lockResult!!.javaClass.declaredMethods) {
                    m.isAccessible = true
                    if (m.returnType == Long::class.javaPrimitiveType && m.parameterCount == 0) {
                        nativePtr = m.invoke(lockResult) as Long
                        diagLog("  LockResult.long ${m.name}() = $nativePtr")
                    }
                    if (m.returnType == Int::class.javaPrimitiveType && m.parameterCount == 0) {
                        stride = m.invoke(lockResult) as Int
                        diagLog("  LockResult.int ${m.name}() = $stride")
                    }
                }
            } catch (e: Exception) {
                diagLog("LockResult解析失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (nativePtr == 0L) {
            diagLog("HardwareBuffer.lock()未获取到native指针")
            return null
        }

        try {
            val bitmap = readPixelsUnsafe(nativePtr, width, height, stride, format)
            try { for (m in HardwareBuffer::class.java.declaredMethods) { if (m.name == "unlock" && m.parameterCount == 0) { m.isAccessible = true; m.invoke(hardwareBuffer); break } } } catch (_: Exception) {}
            return bitmap
        } catch (e: Exception) {
            diagLog("Unsafe读像素失败: ${e.javaClass.simpleName}: ${e.message}")
            try { for (m in HardwareBuffer::class.java.declaredMethods) { if (m.name == "unlock" && m.parameterCount == 0) { m.isAccessible = true; m.invoke(hardwareBuffer); break } } } catch (_: Exception) {}
            return null
        }
    }

    private fun readPixelsUnsafe(nativePtr: Long, width: Int, height: Int, stride: Int, format: Int): Bitmap? {
        val unsafe = getUnsafe() ?: return null
        val getByte = unsafe.javaClass.getMethod("getByte", Long::class.javaPrimitiveType)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val bytesPerPixel = 4
        val rowStride = if (stride > 0) stride * bytesPerPixel else width * bytesPerPixel

        if (nativePtr > 0) {
            val b0 = getByte.invoke(unsafe, nativePtr) as Byte
            val b1 = getByte.invoke(unsafe, nativePtr + 1) as Byte
            val b2 = getByte.invoke(unsafe, nativePtr + 2) as Byte
            val b3 = getByte.invoke(unsafe, nativePtr + 3) as Byte
            diagLog("首像素: [${b0.toInt() and 0xFF}, ${b1.toInt() and 0xFF}, ${b2.toInt() and 0xFF}, ${b3.toInt() and 0xFF}]")
        }

        for (y in 0 until height) {
            val rowOffset = nativePtr + y * rowStride
            for (x in 0 until width) {
                val off = rowOffset + x * bytesPerPixel
                val b = getByte.invoke(unsafe, off) as Byte
                val g = getByte.invoke(unsafe, off + 1) as Byte
                val r = getByte.invoke(unsafe, off + 2) as Byte
                val a = getByte.invoke(unsafe, off + 3) as Byte
                pixels[y * width + x] = ((a.toInt() and 0xFF) shl 24) or
                    ((r.toInt() and 0xFF) shl 16) or
                    ((g.toInt() and 0xFF) shl 8) or
                    (b.toInt() and 0xFF)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun getUnsafe(): Any? {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = try { unsafeClass.getDeclaredField("THE_ONE") }
            catch (_: NoSuchFieldException) { unsafeClass.getDeclaredField("theUnsafe") }
            field.isAccessible = true
            field.get(null)
        } catch (e: Exception) { diagLog("获取Unsafe失败: ${e.javaClass.simpleName}"); null }
    }

    private fun imageReflectionRead(hardwareBuffer: HardwareBuffer, width: Int, height: Int, format: Int): Bitmap? {
        val imageClass = Class.forName("android.media.Image")
        val image = try {
            val m = imageClass.getDeclaredMethod("newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(null, hardwareBuffer, format, 0L)
        } catch (e: Exception) {
            diagLog("Image.newInstance(3参)失败: ${e.javaClass.simpleName}: ${e.message}")
            try {
                val m = imageClass.getDeclaredMethod("newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(null, hardwareBuffer, 1)
            } catch (e2: Exception) {
                diagLog("Image.newInstance(2参)也失败: ${e2.javaClass.simpleName}: ${e2.message}")
                null
            }
        } ?: return null

        try {
            val planes = imageClass.getMethod("getPlanes").invoke(image) as? Array<Any>
            if (planes == null || planes.isEmpty()) { diagLog("Image.getPlanes返回空"); return null }
            val plane = planes[0]
            val planeClass = plane.javaClass
            val buffer = planeClass.getMethod("getBuffer").invoke(plane) as? java.nio.ByteBuffer
            if (buffer == null) { diagLog("Plane.getBuffer返回null"); return null }
            val rowStride = planeClass.getMethod("getRowStride").invoke(plane) as Int
            val pixelStride = planeClass.getMethod("getPixelStride").invoke(plane) as Int
            diagLog("Image planes: rowStride=$rowStride pixelStride=$pixelStride bufCap=${buffer.capacity()}")

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            if (pixelStride == 4) {
                val rowBytes = width * 4
                val pixels = IntArray(width * height)
                if (rowStride == rowBytes) {
                    buffer.asIntBuffer().get(pixels)
                    for (i in pixels.indices) { pixels[i] = (pixels[i] ushr 8) or (pixels[i] shl 24) }
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                } else {
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        val rowPixels = IntArray(width)
                        val intBuf = buffer.asIntBuffer(); intBuf.limit(width); intBuf.get(rowPixels)
                        for (i in rowPixels.indices) { rowPixels[i] = (rowPixels[i] ushr 8) or (rowPixels[i] shl 24) }
                        bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
                    }
                }
            } else {
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
        } catch (e: Exception) {
            diagLog("Image读取失败: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    private fun surfaceControlScreenshot(width: Int, height: Int): Bitmap? {
        val scClass = Class.forName("android.view.SurfaceControl")
        try {
            val getInternalDisplayToken = scClass.getDeclaredMethod("getInternalDisplayToken")
            getInternalDisplayToken.isAccessible = true
            val displayToken = getInternalDisplayToken.invoke(null) ?: return null
            val crop = Rect(0, 0, width, height)

            val methodSets = listOf(
                listOf(android.os.IBinder::class.java, Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                listOf(android.os.IBinder::class.java, Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                listOf(Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                listOf(Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            )

            for (paramTypes in methodSets) {
                try {
                    val method = scClass.getDeclaredMethod("screenshot", *paramTypes.toTypedArray())
                    method.isAccessible = true
                    val args = when (paramTypes.size) {
                        6 -> arrayOf(displayToken, crop, width, height, false, 0)
                        7 -> arrayOf(displayToken, crop, width, height, 0, false, 0)
                        4 -> arrayOf(crop, width, height, 0)
                        3 -> arrayOf(crop, width, height)
                        else -> continue
                    }
                    val result = method.invoke(null, *args)
                    if (result is Bitmap) { diagLog("SC(${paramTypes.size}参)成功"); return result }
                    diagLog("SC(${paramTypes.size}参)返回: ${result?.javaClass?.simpleName}")
                } catch (_: Exception) { continue }
            }
        } catch (e: Exception) {
            diagLog("SurfaceControl完全失败: ${e.message}")
        }
        return null
    }

    /**
     * 截取屏幕
     * v3.08核心: onSuccess回调只保存HardwareBuffer引用
     * 所有转换在latch.await之后做，不在Proxy里做
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) {
            lastError = "需Android 11+"
            return null
        }

        lastError = null
        diagFilePath = "${service.getExternalFilesDir(null)?.absolutePath ?: service.filesDir.absolutePath}/screenshot_diag.txt"
        diagLog("captureScreen v$VERSION 开始")

        var resultBitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        var captureError: String? = null
        var capturedHardwareBuffer: HardwareBuffer? = null
        var capturedScreenshot: Any? = null
        var callbackError: String? = null

        try {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.display?.displayId ?: 0
            } else { 0 }

            val callbackClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val screenshotClass = Class.forName("android.accessibilityservice.AccessibilityService\$Screenshot")

            val executor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

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
                                capturedHardwareBuffer = hardwareBuffer
                                capturedScreenshot = screenshot
                            } else {
                                callbackError = "hwBuf=null"
                            }
                        } catch (e: Throwable) {
                            callbackError = "cb:${e.javaClass.simpleName}"
                        }
                        latch.countDown()
                        null
                    }
                    "onFailure" -> {
                        callbackError = "err=${args[0] as? Int ?: -1}"
                        latch.countDown()
                        null
                    }
                    else -> null
                }
            }

            AccessibilityService::class.java.getMethod(
                "takeScreenshot",
                Int::class.javaPrimitiveType,
                java.util.concurrent.Executor::class.java,
                callbackClass
            ).invoke(service, displayId, executor, callback)

            if (!latch.await(5, TimeUnit.SECONDS)) {
                captureError = "超时"
            }

            // 回调外做转换
            if (captureError == null && callbackError == null && capturedHardwareBuffer != null) {
                try {
                    resultBitmap = hardwareBufferToBitmap(capturedHardwareBuffer!!)
                } catch (e: Throwable) {
                    diagLog("hardwareBufferToBitmap逃逸异常: ${e.javaClass.simpleName}: ${e.message}")
                    lastDiagnostic = "EXC ${e.javaClass.simpleName}"
                }

                if (resultBitmap == null) {
                    val fmt = capturedHardwareBuffer!!.format
                    val w = capturedHardwareBuffer!!.width
                    val h = capturedHardwareBuffer!!.height
                    captureError = "v$VERSION f$fmt ${w}x$h ${lastDiagnostic ?: "fail"}"
                }

                try { capturedHardwareBuffer!!.close() } catch (e: Throwable) { diagLog("hwBuf.close异常: ${e.javaClass.simpleName}") }
            } else if (callbackError != null) {
                captureError = callbackError
            }

            if (capturedScreenshot != null) {
                try { screenshotClass.getMethod("close").invoke(capturedScreenshot) } catch (e: Throwable) { diagLog("screenshot.close异常: ${e.javaClass.simpleName}") }
            }

        } catch (e: SecurityException) {
            captureError = "请重开无障碍"
        } catch (e: Exception) {
            captureError = "${e.javaClass.simpleName}"
        }

        if (resultBitmap == null) {
            lastError = captureError ?: "未知失败"
            diagLog("截图失败: lastError=$lastError diag=$lastDiagnostic")
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
