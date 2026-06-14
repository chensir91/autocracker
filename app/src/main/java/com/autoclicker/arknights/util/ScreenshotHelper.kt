package com.autoclicker.arknights.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 截图辅助工具 v3.09
 * 
 * v3.09 关键改动:
 * - 使用公开API takeScreenshot() 替代反射 (API 30+ 是公开接口!)
 * - 从 ScreenshotResult 获取正确的 ColorSpace
 * - 回调只存引用, latch.await 后再转换
 * - 每一步都有独立 step 标记的错误追踪
 * - 诊断日志 + Toast 显示关键信息
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    const val VERSION = "3.10"

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 30

    var lastError: String? = null
        private set

    var lastDiagnostic: String? = null
        private set

    var diagFilePath: String? = null
    private var hiddenApiBypassed = false

    fun diagLog(msg: String) {
        Log.d(TAG, msg)
        try {
            val path = diagFilePath ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
            val fw = FileWriter(path, true)
            fw.write("[$ts] $msg\n")
            fw.flush()
            fw.close()
        } catch (e: Exception) {
            Log.e(TAG, "diagLog写入失败: ${e.message}")
        }
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

    /**
     * 截取屏幕 - 使用公开API
     */
    fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) {
            lastError = "需Android 11+"
            return null
        }

        lastError = null
        lastDiagnostic = null

        // 初始化诊断日志
        diagFilePath = try {
            val dir = service.getExternalFilesDir(null) ?: service.filesDir
            "${dir.absolutePath}/screenshot_diag.txt"
        } catch (e: Exception) {
            "${service.filesDir.absolutePath}/screenshot_diag.txt"
        }

        // 清空旧日志
        try { File(diagFilePath!!).writeText("") } catch (_: Exception) {}

        diagLog("===== captureScreen v$VERSION =====")
        diagLog("设备: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.SDK_INT}")

        var resultBitmap: Bitmap? = null
        var stepError: String? = null
        var captureError: String? = null

        // 保存回调结果
        var capturedHardwareBuffer: HardwareBuffer? = null
        var capturedColorSpace: ColorSpace? = null
        var callbackError: String? = null
        val latch = CountDownLatch(1)

        try {
            // Step 1: 获取 displayId
            val displayId = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    service.display?.displayId ?: 0
                } else 0
            } catch (e: Exception) {
                diagLog("⚠️ S1 displayId异常: ${e.message}")
                0
            }
            diagLog("S1 displayId=$displayId")

            // Step 2: 创建 executor 和 callback
            val executor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        capturedHardwareBuffer = screenshot.hardwareBuffer
                        capturedColorSpace = screenshot.colorSpace
                        diagLog("S2 回调成功: hwBuf=${capturedHardwareBuffer != null} cs=${capturedColorSpace != null}")
                    } catch (e: Throwable) {
                        callbackError = "cb:${e.javaClass.simpleName}:${e.message}"
                        diagLog("S2 回调异常: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callbackError = "err=$errorCode"
                    diagLog("S2 回调失败: errorCode=$errorCode")
                    latch.countDown()
                }
            }

            // Step 3: 调用公开API takeScreenshot
            diagLog("S3 调用takeScreenshot...")
            service.takeScreenshot(displayId, executor, callback)
            diagLog("S3 takeScreenshot已调用, 等待回调...")

            // Step 4: 等待回调
            if (!latch.await(5, TimeUnit.SECONDS)) {
                captureError = "S4超时"
                diagLog("S4 latch.await超时5秒")
            } else {
                diagLog("S4 latch.await返回")
            }

            // Step 5: 检查回调结果
            if (captureError == null && callbackError != null) {
                captureError = callbackError
                diagLog("S5 回调错误: $callbackError")
            }

            // Step 6: 转换 HardwareBuffer → Bitmap
            if (captureError == null && capturedHardwareBuffer != null) {
                try {
                    val hwBuf = capturedHardwareBuffer!!
                    diagLog("S6 开始转换: ${hwBuf.width}x${hwBuf.height} fmt=${hwBuf.format} usage=${hwBuf.usage}")
                    resultBitmap = hardwareBufferToBitmap(hwBuf, capturedColorSpace)
                } catch (e: Throwable) {
                    diagLog("S6 转换逃逸异常: ${e.javaClass.simpleName}: ${e.message}")
                    lastDiagnostic = "S6EXC:${e.javaClass.simpleName}"
                }

                if (resultBitmap == null) {
                    // 安全获取format (可能异常)
                    val fmtInfo = try {
                        val hwBuf = capturedHardwareBuffer!!
                        "f${hwBuf.format} ${hwBuf.width}x${hwBuf.height}"
                    } catch (e: Throwable) {
                        "fmtErr:${e.javaClass.simpleName}"
                    }
                    captureError = "S6 $fmtInfo ${lastDiagnostic ?: "ALL_FAIL"}"
                    diagLog("S6 转换失败: $captureError")
                } else {
                    diagLog("S6 ✅ 截图成功: ${resultBitmap!!.width}x${resultBitmap!!.height}")
                }

                // 关闭 HardwareBuffer
                try { capturedHardwareBuffer?.close() } catch (e: Throwable) {
                    diagLog("S6 hwBuf.close异常: ${e.javaClass.simpleName}")
                }
            } else if (captureError == null && capturedHardwareBuffer == null) {
                captureError = "S5 hwBuf=null"
            }

            // ScreenshotResult 的 close() 在 compileSdk 不可用
            // HardwareBuffer 已经单独关闭, 不需要再关闭 ScreenshotResult

        } catch (e: SecurityException) {
            captureError = "请重开无障碍"
            diagLog("SecurityException: ${e.message}")
        } catch (e: Exception) {
            captureError = "${e.javaClass.simpleName}"
            diagLog("外层Exception: ${e.javaClass.simpleName}: ${e.message}")
            // 打印堆栈到日志
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                diagLog("堆栈:\n${sw.toString().take(500)}")
            } catch (_: Exception) {}
        }

        if (resultBitmap == null) {
            lastError = captureError ?: "未知失败"
            diagLog("❌ 最终失败: lastError=$lastError diag=$lastDiagnostic")
        }

        return resultBitmap
    }

    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer, colorSpace: ColorSpace?): Bitmap? {
        val width = hardwareBuffer.width
        val height = hardwareBuffer.height
        val format = hardwareBuffer.format
        val usage = hardwareBuffer.usage

        lastDiagnostic = "fmt=$format ${width}x${height} u=$usage"
        diagLog("hardwareBufferToBitmap: ${width}x${height} fmt=$format usage=$usage cs=${colorSpace != null}")

        bypassHiddenApiRestrictions()

        // 方案1: wrapHardwareBuffer (使用系统提供的ColorSpace)
        try {
            val cs = colorSpace ?: try {
                @Suppress("DEPRECATION")
                ColorSpace.get(ColorSpace.Named.SRGB)
            } catch (_: Exception) { null }
            diagLog("M1 wrapHardwareBuffer cs=${cs?.toString()?.take(30)}")
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, cs)
            if (wrapped != null) {
                val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                if (copy != null) {
                    diagLog("✅ M1 wrapHardwareBuffer成功")
                    lastDiagnostic = "OK(M1)"
                    return copy
                }
                diagLog("⚠️ M1 copy返回null")
            } else {
                diagLog("⚠️ M1 wrapHardwareBuffer返回null, fmt=$format")
            }
        } catch (e: UnsupportedOperationException) {
            diagLog("⚠️ M1 wrap不支持: fmt=$format msg=${e.message}")
        } catch (e: Exception) {
            diagLog("⚠️ M1 wrap异常: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案2: HardwareBuffer.lock() + Unsafe
        try {
            val bitmap = hardwareBufferLockRead(hardwareBuffer, width, height, format)
            if (bitmap != null) {
                diagLog("✅ M2 HardwareBuffer.lock成功")
                lastDiagnostic = "OK(M2)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ M2 HardwareBuffer.lock失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案3: Image.newInstance反射
        try {
            val bitmap = imageReflectionRead(hardwareBuffer, width, height, format)
            if (bitmap != null) {
                diagLog("✅ M3 Image反射成功")
                lastDiagnostic = "OK(M3)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ M3 Image反射失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 方案4: SurfaceControl.screenshot()
        try {
            val bitmap = surfaceControlScreenshot(width, height)
            if (bitmap != null) {
                diagLog("✅ M4 SurfaceControl成功")
                lastDiagnostic = "OK(M4)"
                return bitmap
            }
        } catch (e: Throwable) {
            diagLog("⚠️ M4 SurfaceControl失败: ${e.javaClass.simpleName}: ${e.message}")
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
                diagLog("M2 lock(API34)返回: ${lockResult?.javaClass?.name}")
            } catch (e: Exception) {
                diagLog("M2 lock(API34)失败: ${e.javaClass.simpleName}: ${e.message}")
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
                        diagLog("M2 lock(旧API)成功: nativePtr=$nativePtr")
                        break
                    }
                }
            } catch (e: Exception) {
                diagLog("M2 lock(旧API)失败: ${e.javaClass.simpleName}: ${e.message}")
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
                diagLog("M2 LockResult解析失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (nativePtr == 0L) {
            diagLog("M2 HardwareBuffer.lock()未获取到native指针")
            return null
        }

        try {
            val bitmap = readPixelsUnsafe(nativePtr, width, height, stride, format)
            try { for (m in HardwareBuffer::class.java.declaredMethods) { if (m.name == "unlock" && m.parameterCount == 0) { m.isAccessible = true; m.invoke(hardwareBuffer); break } } } catch (_: Exception) {}
            return bitmap
        } catch (e: Exception) {
            diagLog("M2 Unsafe读像素失败: ${e.javaClass.simpleName}: ${e.message}")
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
            diagLog("M2 首像素: [${b0.toInt() and 0xFF}, ${b1.toInt() and 0xFF}, ${b2.toInt() and 0xFF}, ${b3.toInt() and 0xFF}]")
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
            diagLog("M3 Image.newInstance(3参)失败: ${e.javaClass.simpleName}: ${e.message}")
            try {
                val m = imageClass.getDeclaredMethod("newInstance", HardwareBuffer::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(null, hardwareBuffer, 1)
            } catch (e2: Exception) {
                diagLog("M3 Image.newInstance(2参)也失败: ${e2.javaClass.simpleName}: ${e2.message}")
                null
            }
        } ?: return null

        try {
            val planes = imageClass.getMethod("getPlanes").invoke(image) as? Array<Any>
            if (planes == null || planes.isEmpty()) { diagLog("M3 Image.getPlanes返回空"); return null }
            val plane = planes[0]
            val planeClass = plane.javaClass
            val buffer = planeClass.getMethod("getBuffer").invoke(plane) as? java.nio.ByteBuffer
            if (buffer == null) { diagLog("M3 Plane.getBuffer返回null"); return null }
            val rowStride = planeClass.getMethod("getRowStride").invoke(plane) as Int
            val pixelStride = planeClass.getMethod("getPixelStride").invoke(plane) as Int
            diagLog("M3 planes: rowStride=$rowStride pixelStride=$pixelStride bufCap=${buffer.capacity()}")

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
            diagLog("M3 Image读取失败: ${e.javaClass.simpleName}: ${e.message}")
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
                    if (result is Bitmap) { diagLog("M4 SC(${paramTypes.size}参)成功"); return result }
                    diagLog("M4 SC(${paramTypes.size}参)返回: ${result?.javaClass?.simpleName}")
                } catch (_: Exception) { continue }
            }
        } catch (e: Exception) {
            diagLog("M4 SurfaceControl完全失败: ${e.message}")
        }
        return null
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
