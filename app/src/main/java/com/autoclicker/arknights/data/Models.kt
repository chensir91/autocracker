package com.autoclicker.arknights.data

import java.io.Serializable

/**
 * 表示屏幕上的一个点击点位
 * @param x X坐标
 * @param y Y坐标
 * @param order 顺序编号
 */
data class ClickPoint(
    val x: Float,
    val y: Float,
    val order: Int = 0
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
 * 应用设置
 * @param minIntervalMs 最小点击间隔（毫秒）
 * @param maxIntervalMs 最大点击间隔（毫秒）
 * @param loopCount 循环次数，-1表示无限
 */
data class AppSettings(
    val minIntervalMs: Int = 100,
    val maxIntervalMs: Int = 200,
    val loopCount: Int = -1
) : Serializable
