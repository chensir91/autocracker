package com.autoclicker.arknights.data

import java.io.Serializable

/**
 * 操作类型枚举
 */
enum class OperationType {
    CLICK,      // 普通点击
    LONG_PRESS, // 长按
    WAIT,       // 等待
    SWIPE,      // 滑动
    LONG_PRESS_DRAG  // 长按拖动
}

/**
 * 表示屏幕上的一个点击点位
 * @param x X坐标
 * @param y Y坐标
 * @param order 顺序编号
 * @param type 操作类型（点击/长按/等待/滑动）
 * @param duration 时长参数（长按时为按住毫秒数，等待时为等待秒数*1000，滑动时为滑动毫秒数）
 * @param endX 滑动终点X坐标
 * @param endY 滑动终点Y坐标
 */
data class ClickPoint(
    val x: Float,
    val y: Float,
    val order: Int = 0,
    val type: OperationType = OperationType.CLICK,
    val duration: Long = 0,  // 长按时为毫秒数，等待时为毫秒数，滑动时为毫秒数
    val endX: Float = 0f,    // 滑动终点X
    val endY: Float = 0f     // 滑动终点Y
) : Serializable

/**
 * 录制方案，包含多个点位和配置信息
 * @param name 方案名称
 * @param points 点击点位列表
 * @param createdAt 创建时间
 */
data class ClickScheme(
    val name: String,
    val points: List<ClickPoint>,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * 应用设置 v1.3.0
 * @param minIntervalMs 最小点击间隔（毫秒）
 * @param maxIntervalMs 最大点击间隔（毫秒）
 * @param loopCount 循环次数，-1表示无限
 * @param offsetRange 随机偏移范围（像素）
 * @param pauseMinClicks 触发微停顿的最小点击次数
 * @param pauseMaxClicks 触发微停顿的最大点击次数
 * @param pauseMinDuration 微停顿最短时长（毫秒）
 * @param pauseMaxDuration 微停顿最长时长（毫秒）
 * @param longPressDuration 默认长按时长（毫秒）
 * @param waitDuration 默认等待时长（毫秒）
 * @param screenWidth 屏幕分辨率宽度
 * @param screenHeight 屏幕分辨率高度
 */
data class AppSettings(
    val minIntervalMs: Int = 100,
    val maxIntervalMs: Int = 200,
    val loopCount: Int = -1,
    // 安全性设置
    val offsetRange: Int = 8,           // 默认±8像素偏移
    val pauseMinClicks: Int = 20,       // 每20-50次停顿
    val pauseMaxClicks: Int = 50,
    val pauseMinDuration: Int = 1000,   // 停顿1-3秒
    val pauseMaxDuration: Int = 3000,
    // 操作类型默认值
    val longPressDuration: Int = 500,   // 默认长按500ms
    val waitDuration: Int = 1000,       // 默认等待1秒
    // 屏幕分辨率
    val screenWidth: Int = 2800,        // 默认2800x1260
    val screenHeight: Int = 1260
) : Serializable
