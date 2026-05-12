package com.autoclicker.arknights.data

import android.graphics.Color

/**
 * 明日方舟日常预设方案 v2.0
 * 坐标基于 1280x720 源分辨率，根据目标分辨率动态缩放
 * 
 * 缩放说明：
 * - 源分辨率: 1280x720 (16:9)
 * - 目标分辨率: 2800x1260 (20:9) 或 2400x1080 (20:9)
 * - scaleX = targetWidth / 1280
 * - scaleY = targetHeight / 720
 * 
 * 新增特性：
 * - WAIT_PIXEL: 等待直到指定坐标像素颜色匹配（截图识别）
 * - MULTI_CLICK: 在同一位置连续点击多次
 * - 速度模式: fast(快速) / normal(标准)
 */
object PresetSchemes {
    
    // ============ 源分辨率基准 ============
    private const val SOURCE_WIDTH = 1280f
    private const val SOURCE_HEIGHT = 720f
    
    // ============ 辅助函数 ============
    
    /**
     * 将源坐标X转换为目标分辨率坐标
     */
    private fun scaleX(x: Float, targetWidth: Int) = x * (targetWidth / SOURCE_WIDTH)
    
    /**
     * 将源坐标Y转换为目标分辨率坐标
     */
    private fun scaleY(y: Float, targetHeight: Int) = y * (targetHeight / SOURCE_HEIGHT)
    
    /**
     * 创建等待点位
     */
    private fun wait(seconds: Float): ClickPoint {
        return ClickPoint(
            x = 0f,
            y = 0f,
            order = 0,
            type = OperationType.WAIT,
            duration = (seconds * 1000).toLong()
        )
    }
    
    /**
     * 创建点击点位
     * @param x 源坐标X（基于1280x720）
     * @param y 源坐标Y（基于1280x720）
     * @param order 点击顺序
     * @param targetWidth 目标分辨率宽度
     * @param targetHeight 目标分辨率高度
     */
    private fun click(x: Float, y: Float, order: Int, targetWidth: Int, targetHeight: Int): ClickPoint {
        return ClickPoint(
            x = scaleX(x, targetWidth),
            y = scaleY(y, targetHeight),
            order = order,
            type = OperationType.CLICK,
            duration = 0
        )
    }
    
    /**
     * 等待像素颜色匹配（截图识别）
     * @param x 源坐标X
     * @param y 源坐标Y
     * @param color 目标颜色 (ARGB)
     * @param order 顺序
     * @param targetWidth 目标分辨率宽度
     * @param targetHeight 目标分辨率高度
     * @param timeout 超时时间(ms)
     * @param interval 检查间隔(ms)
     * @param speedMultiplier 速度倍率 (fast=0.6, normal=1.0)
     */
    private fun waitPixel(x: Float, y: Float, color: Int, order: Int, 
                          targetWidth: Int, targetHeight: Int,
                          timeout: Long = 30000, interval: Long = 500,
                          speedMultiplier: Float = 1.0f): ClickPoint {
        return ClickPoint(
            x = scaleX(x, targetWidth),
            y = scaleY(y, targetHeight),
            order = order,
            type = OperationType.WAIT_PIXEL,
            targetColor = color,
            timeoutMs = (timeout * speedMultiplier).toLong(),
            checkIntervalMs = (interval * speedMultiplier).toLong(),
            colorTolerance = 30
        )
    }
    
    /**
     * 连续点击同一位置
     * @param x 源坐标X
     * @param y 源坐标Y
     * @param count 重复次数
     * @param order 顺序
     * @param targetWidth 目标分辨率宽度
     * @param targetHeight 目标分辨率高度
     * @param interval 重复间隔(ms)
     */
    private fun multiClick(x: Float, y: Float, count: Int, order: Int, 
                           targetWidth: Int, targetHeight: Int,
                           interval: Long = 300): ClickPoint {
        return ClickPoint(
            x = scaleX(x, targetWidth),
            y = scaleY(y, targetHeight),
            order = order,
            type = OperationType.MULTI_CLICK,
            repeatCount = count,
            repeatIntervalMs = interval
        )
    }
    
    // ============ 子方案 ============
    
    /**
     * 子方案信息
     */
    data class SubScheme(val id: String, val name: String, val desc: String)
    
    /**
     * 获取所有子方案列表
     * v2.0 简化为只有一个"完整日常"
     */
    fun getSubSchemes(): List<SubScheme> = listOf(
        SubScheme("daily", "完整日常", "自动执行全部日常")
    )
    
    /**
     * 根据勾选的子方案ID列表，拼接生成日常方案
     * v2.0 简化为直接加载完整日常
     */
    fun buildDailyScheme(selectedIds: List<String>, targetWidth: Int, targetHeight: Int, 
                         speedMode: String = "normal"): ClickScheme {
        val speedMultiplier = if (speedMode == "fast") 0.6f else 1.0f
        return getFullDaily(targetWidth, targetHeight, speedMultiplier)
    }
    
    /**
     * 完整日常流程 v4
     * 坐标基于1280x720，像素颜色为占位值（需在设备上校准）
     * 
     * 速度模式：
     * - fast: 检查间隔缩短到60%，超时缩短到60%
     * - normal: 使用默认参数
     * 
     * 注意：以下坐标和颜色都是占位值，需要在实际设备上校准
     */
    fun getFullDaily(tw: Int, th: Int, speedMultiplier: Float = 1.0f): ClickScheme {
        val steps = mutableListOf<ClickPoint>()
        var order = 1
        
        // ========== 一、启动游戏 ==========
        // 注：游戏启动需要用户在手机上手动点击图标
        // 等待游戏加载完成 - 检测主界面底部导航栏特征色
        // 占位色 #4A5568 (需校准)
        steps.add(waitPixel(640f, 695f, Color.parseColor("#4A5568"), order++, tw, th, 
            timeout = 90000, interval = 500, speedMultiplier = speedMultiplier))
        // 关闭公告弹窗（如有）
        steps.add(click(640f, 400f, order++, tw, th))  // 关闭公告按钮位置（需校准）
        steps.add(wait(0.5f))
        // 关闭活动弹窗（如有）
        steps.add(click(1200f, 50f, order++, tw, th))  // 关闭活动X位置（需校准）
        steps.add(wait(0.5f))
        
        // ========== 二、基建收菜 ==========
        // 点击基建入口
        steps.add(click(640f, 680f, order++, tw, th))
        // 等待基建加载 - 检测右上角蓝色一键收取图标
        // 占位色 #4FC3F7 (需校准)
        steps.add(waitPixel(1150f, 80f, Color.parseColor("#4FC3F7"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        // 点击右上角蓝色图标（一键收取）
        steps.add(click(1150f, 80f, order++, tw, th))
        steps.add(wait(1.0f))
        // 连点左下角5次（收取各个制造站/贸易站）
        steps.add(multiClick(100f, 650f, 5, order++, tw, th))
        steps.add(wait(1.0f))
        // 返回主界面
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(waitPixel(640f, 695f, Color.parseColor("#4A5568"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        
        // ========== 三、线索交流 ==========
        // 进会客室
        steps.add(click(900f, 400f, order++, tw, th))  // 会客室入口（需校准）
        // 占位色 #FF8A65 (需校准)
        steps.add(waitPixel(640f, 100f, Color.parseColor("#FF8A65"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        // 领取线索
        steps.add(click(640f, 500f, order++, tw, th))  // 领取线索按钮（需校准）
        steps.add(wait(0.5f))
        // 填入线索
        steps.add(click(640f, 400f, order++, tw, th))  // 填入位置（需校准）
        steps.add(wait(0.5f))
        // 开启交流
        steps.add(click(900f, 600f, order++, tw, th))  // 开启交流按钮（需校准）
        steps.add(wait(1.0f))
        // 去好友访问基建
        steps.add(click(1180f, 680f, order++, tw, th))  // 好友入口
        // 占位色 #E0E0E0 (需校准)
        steps.add(waitPixel(400f, 100f, Color.parseColor("#E0E0E0"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        // 依次访问10个好友
        for (i in 1..10) {
            steps.add(click(640f, 150f + (i - 1) * 55f, order++, tw, th))  // 好友列表位置（需校准）
            steps.add(wait(2.5f))  // 访问等待
            // 如果需要滚动，每5个下滑一次
            if (i == 5) {
                steps.add(ClickPoint(
                    x = scaleX(640f, tw), y = scaleY(300f, th),
                    endX = scaleX(640f, tw), endY = scaleY(150f, th),
                    order = order++, type = OperationType.SWIPE, duration = 300
                ))
                steps.add(wait(0.5f))
            }
        }
        // 返回会客室→主界面
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(wait(1.0f))
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(waitPixel(640f, 695f, Color.parseColor("#4A5568"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        
        // ========== 四、信用商店+公招 ==========
        // 点击商店
        steps.add(click(960f, 680f, order++, tw, th))
        // 占位色 #66BB6A (需校准)
        steps.add(waitPixel(150f, 300f, Color.parseColor("#66BB6A"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        // 信用交易所
        steps.add(click(150f, 300f, order++, tw, th))
        steps.add(wait(1.5f))
        // 买上面一排（5个物品）
        for (i in 1..5) {
            steps.add(click(200f + (i - 1) * 180f, 280f, order++, tw, th))  // 物品位置（需校准）
            steps.add(wait(0.3f))
            steps.add(click(640f, 400f, order++, tw, th))  // 确认购买
            steps.add(wait(0.5f))
        }
        // 买下面一排左边两个
        for (i in 1..2) {
            steps.add(click(200f + (i - 1) * 180f, 450f, order++, tw, th))  // 物品位置（需校准）
            steps.add(wait(0.3f))
            steps.add(click(640f, 400f, order++, tw, th))  // 确认购买
            steps.add(wait(0.5f))
        }
        // 点击公招（从商店进入）
        steps.add(click(640f, 180f, order++, tw, th))  // 公招入口（需校准）
        steps.add(wait(1.5f))
        // 公招流程（简化版：先不实现OCR，直接跳过选tag，后续迭代加OCR）
        // 点击第一个公招位
        steps.add(click(350f, 350f, order++, tw, th))  // 公招位1（需校准）
        steps.add(wait(1.0f))
        // 不选tag直接招（临时方案，OCR版本后续加）
        steps.add(click(960f, 600f, order++, tw, th))  // 开始招（需校准）
        steps.add(wait(0.5f))
        steps.add(click(640f, 450f, order++, tw, th))  // 确认
        steps.add(wait(0.5f))
        // 返回主界面
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(wait(0.5f))
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(waitPixel(640f, 695f, Color.parseColor("#4A5568"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        
        // ========== 五、刷1-7（3次）==========
        for (round in 1..3) {
            // 作战入口
            steps.add(click(320f, 680f, order++, tw, th))
            // 占位色 #B0BEC5 (需校准)
            steps.add(waitPixel(200f, 200f, Color.parseColor("#B0BEC5"), order++, tw, th,
                timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
            // 选择主线
            steps.add(click(200f, 200f, order++, tw, th))  // 主题曲入口（需校准）
            steps.add(wait(1.0f))
            // 选择1-7
            steps.add(click(800f, 500f, order++, tw, th))  // 1-7位置（需校准）
            steps.add(wait(1.5f))
            // 六倍代理指挥
            steps.add(click(640f, 350f, order++, tw, th))  // 代理指挥开关（需校准）
            steps.add(wait(0.3f))
            // 开始行动
            steps.add(click(1100f, 650f, order++, tw, th))
            steps.add(wait(0.5f))
            // 确认开始
            steps.add(click(640f, 550f, order++, tw, th))
            // 等待结算 - 检测结算界面
            // 占位色 #FFD54F (需校准)
            steps.add(waitPixel(640f, 300f, Color.parseColor("#FFD54F"), order++, tw, th,
                timeout = 120000, interval = 500, speedMultiplier = speedMultiplier))
            // 确认结算
            steps.add(click(640f, 400f, order++, tw, th))
            steps.add(wait(1.0f))
        }
        // 返回主界面
        steps.add(click(50f, 50f, order++, tw, th))
        steps.add(waitPixel(640f, 695f, Color.parseColor("#4A5568"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        
        // ========== 六、领取任务奖励（最后）==========
        steps.add(click(1180f, 680f, order++, tw, th))  // 任务入口
        // 占位色 #FF7043 (需校准)
        steps.add(waitPixel(640f, 500f, Color.parseColor("#FF7043"), order++, tw, th,
            timeout = 10000, interval = 500, speedMultiplier = speedMultiplier))
        // 领取全部
        steps.add(click(1100f, 100f, order++, tw, th))  // 领取全部按钮（需校准）
        steps.add(wait(1.5f))
        // 确认
        steps.add(click(640f, 400f, order++, tw, th))
        steps.add(wait(0.5f))
        // 返回主界面
        steps.add(click(50f, 50f, order++, tw, th))
        
        return ClickScheme(name = "完整日常", points = steps)
    }
}
