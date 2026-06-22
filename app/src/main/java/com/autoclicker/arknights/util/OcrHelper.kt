package com.autoclicker.arknights.util

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * OCR辅助工具 — 基于ML Kit中文识别
 *
 * 核心能力：
 * - recognizeText(): 识别整张截图的所有文字
 * - findText(): 搜索目标文字，返回bounding box
 * - findTextCenter(): 搜索目标文字，返回正中心坐标
 *
 * 坐标说明：
 * - 所有返回坐标均为bitmap坐标（截图像素坐标）
 * - 如需转为屏幕点击坐标，调用方需用bmpToScreen()变换
 */
object OcrHelper {
    private const val TAG = "OcrHelper"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions())
    }

    /** OCR识别结果：文字+边界框 */
    data class TextBlock(
        val text: String,
        val boundingBox: Rect,
        val confidence: Float = 1f
    )

    /**
     * 识别bitmap中所有文字
     * @param bmp 截图bitmap
     * @return 文字块列表（行级别+元素级别，元素级别更精确）
     */
    fun recognizeText(bmp: Bitmap): List<TextBlock> {
        val image = InputImage.fromBitmap(bmp, 0)
        val result = try {
            Tasks.await(recognizer.process(image), 10000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "OCR识别失败: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }

        val blocks = mutableListOf<TextBlock>()

        for (textBlock in result.textBlocks) {
            for (line in textBlock.lines) {
                val lineBox = line.boundingBox
                if (lineBox != null) {
                    blocks.add(TextBlock(line.text, lineBox))
                }
                // 元素级别更精确（中文OCR通常一行一个元素，但拆开更准）
                for (element in line.elements) {
                    val elBox = element.boundingBox
                    if (elBox != null) {
                        blocks.add(TextBlock(element.text, elBox))
                    }
                }
            }
        }

        return blocks
    }

    /**
     * 在bitmap中搜索目标文字
     * @param bmp 截图bitmap
     * @param targetText 目标文字（支持部分匹配，如搜"开始唤醒"能匹配到"开始唤醒"这行）
     * @param searchArea 搜索区域（bitmap坐标），null则搜全图
     * @return 匹配到的文字边界框（bitmap坐标），未找到返回null
     */
    fun findText(bmp: Bitmap, targetText: String, searchArea: Rect? = null): Rect? {
        val blocks = recognizeText(bmp)

        // 优先精确匹配，再模糊匹配
        var bestMatch: TextBlock? = null
        var bestScore = -1

        for (block in blocks) {
            if (!block.text.contains(targetText)) continue

            // 检查是否在搜索区域内
            if (searchArea != null && !Rect.intersects(block.boundingBox, searchArea)) {
                continue
            }

            // 匹配得分：文字越短越精确（"开始唤醒"在"点击开始唤醒"中匹配得分高于在"点击开始唤醒按钮"中）
            val score = targetText.length.toFloat() / block.text.length
            if (score > bestScore) {
                bestScore = score
                bestMatch = block
            }
        }

        return bestMatch?.boundingBox
    }

    /**
     * 在bitmap中搜索目标文字，返回文字正中心坐标（bitmap坐标）
     * @param bmp 截图bitmap
     * @param targetText 目标文字
     * @param searchArea 搜索区域（bitmap坐标），null则搜全图
     * @return 文字正中心(bitmap坐标)，未找到返回null
     */
    fun findTextCenter(bmp: Bitmap, targetText: String, searchArea: Rect? = null): Pair<Int, Int>? {
        val rect = findText(bmp, targetText, searchArea) ?: return null
        val cx = rect.left + rect.width() / 2
        val cy = rect.top + rect.height() / 2
        Log.d(TAG, "✅ 找到'$targetText' bbox=(${rect.left},${rect.top},${rect.right},${rect.bottom}) center=($cx,$cy)")
        return cx to cy
    }

    /**
     * 识别并返回所有文字（调试用）
     */
    fun dumpAllText(bmp: Bitmap): String {
        val blocks = recognizeText(bmp)
        val sb = StringBuilder()
        sb.append("OCR共识别${blocks.size}个文字块:\n")
        for ((i, block) in blocks.withIndex()) {
            val box = block.boundingBox
            sb.append("  [$i] '${block.text}' @ (${box.left},${box.top})-(${box.right},${box.bottom})\n")
        }
        return sb.toString()
    }
}
