package com.autoclicker.arknights.data

/**
 * 明日方舟日常预设方案
 * 坐标基于 2800x1260 分辨率（20:9比例）
 * 可能需要根据实际游戏界面微调
 * 
 * 缩放说明：
 * - 源分辨率: 1280x720 (16:9)
 * - 目标分辨率: 2800x1260 (20:9)
 * - scaleX = 2800 / 1280 = 2.1875
 * - scaleY = 1260 / 720 = 1.75
 * 
 * 注意：2800x1260 比标准 16:9 更宽，游戏可能在中间区域渲染，
 * 两侧可能有黑边。坐标已考虑游戏内容区域居中显示的情况。
 */
object PresetSchemes {
    
    // ============ 缩放比例 ============
    private const val SCALE_X = 2.1875f
    private const val SCALE_Y = 1.75f
    
    // ============ 辅助函数 ============
    
    /**
     * 将 1280x720 坐标转换为 2800x1260 坐标
     */
    private fun scaleX(x: Float) = x * SCALE_X
    private fun scaleY(y: Float) = y * SCALE_Y
    
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
     */
    private fun click(x: Float, y: Float, order: Int): ClickPoint {
        return ClickPoint(
            x = scaleX(x),
            y = scaleY(y),
            order = order,
            type = OperationType.CLICK,
            duration = 0
        )
    }
    
    // ============ 预设方案 ============
    
    /**
     * 方案A: 基建收菜
     * 从主界面开始，一键收取基建产出
     * 
     * 流程：
     * 1. 等待主界面加载
     * 2. 点击"基建"按钮进入基建
     * 3. 等待基建界面加载
     * 4. 点击"办事"（一键领取）
     * 5. 等待处理
     * 6. 返回主界面
     * 
     * 坐标说明（基于1280x720）：
     * - 基建入口: 底部中间 (640, 680)
     * - 办事/一键领取按钮: 基建界面右下角 (1150, 620)
     * - 返回按钮: 左上角 (50, 50)
     */
    val baseCollect: ClickScheme
        get() = ClickScheme(
            name = "基建收菜",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击基建按钮进入基建界面
                click(640f, 680f, 1),
                // 等待基建界面加载
                wait(2.0f),
                // 点击"办事"一键领取（如果界面有一键领取功能）
                click(1100f, 650f, 2),
                // 等待处理
                wait(1.5f),
                // 点击第一个贸易站
                click(300f, 400f, 3),
                wait(0.5f),
                // 领取订单
                click(640f, 360f, 4),
                wait(0.5f),
                // 返回基建主界面
                click(50f, 50f, 5),
                wait(0.5f),
                // 点击制造站
                click(640f, 400f, 6),
                wait(0.5f),
                // 领取产物
                click(640f, 360f, 7),
                wait(0.5f),
                // 返回基建主界面
                click(50f, 50f, 8),
                wait(0.5f),
                // 如果有无人机可用，点击加速
                click(1150f, 200f, 9),
                wait(0.5f),
                // 确认加速
                click(640f, 400f, 10),
                wait(1.0f),
                // 返回主界面
                click(50f, 50f, 11),
                wait(1.5f),
                // 再次点击返回确保退出基建
                click(50f, 50f, 12)
            )
        )
    
    /**
     * 方案B: 公招
     * 从主界面开始，进行公开招募
     * 
     * 流程：
     * 1. 点击"公开招募"入口
     * 2. 等待加载
     * 3. 点击第一个招募位
     * 4. 等待标签出现
     * 5. 选择高价值标签组合（资深干员+输出等）
     * 6. 点击确认
     * 
     * 坐标说明（基于1280x720）：
     * - 公招入口: 主界面左侧 (120, 200)
     * - 第一个招募位: 公招界面左上方 (200, 250)
     * - 标签区域: 中间位置 (640, 300~500)
     * - 确认按钮: 右下角 (1100, 650)
     */
    val recruit: ClickScheme
        get() = ClickScheme(
            name = "公招",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击公开招募入口
                click(120f, 200f, 1),
                // 等待公招界面加载
                wait(2.0f),
                // 点击第一个招募位
                click(200f, 250f, 2),
                // 等待标签出现
                wait(1.5f),
                // 选择第一个标签（高稀有度标签位置）
                click(400f, 280f, 3),
                wait(0.3f),
                // 选择第二个标签
                click(640f, 280f, 4),
                wait(0.3f),
                // 选择第三个标签
                click(880f, 280f, 5),
                wait(0.3f),
                // 确认招募
                click(1100f, 650f, 6),
                // 等待招募完成
                wait(2.0f),
                // 关闭招募结果
                click(640f, 400f, 7),
                wait(1.0f),
                // 返回主界面
                click(50f, 50f, 8)
            )
        )
    
    /**
     * 方案C: 领取邮件+任务奖励
     * 从主界面开始，领取所有邮件和任务奖励
     * 
     * 流程：
     * 1. 点击邮件图标
     * 2. 点击一键领取
     * 3. 关闭邮件界面
     * 4. 点击任务入口
     * 5. 点击领取全部
     * 6. 返回主界面
     * 
     * 坐标说明（基于1280x720）：
     * - 邮件图标: 右上角 (1200, 50)
     * - 一键领取: 邮件界面中部 (640, 400)
     * - 任务入口: 底部右侧 (1180, 680)
     * - 领取全部: 任务界面 (640, 500)
     */
    val collectRewards: ClickScheme
        get() = ClickScheme(
            name = "领取邮件+任务",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击邮件图标
                click(1200f, 50f, 1),
                // 等待邮件列表加载
                wait(1.5f),
                // 点击一键领取
                click(640f, 400f, 2),
                // 等待领取动画
                wait(2.0f),
                // 点击确定（如果有确认对话框）
                click(640f, 400f, 3),
                wait(0.5f),
                // 关闭邮件界面
                click(50f, 50f, 4),
                // 等待返回主界面
                wait(1.5f),
                // 点击任务入口
                click(1180f, 680f, 5),
                // 等待任务界面加载
                wait(1.5f),
                // 点击领取全部奖励
                click(640f, 500f, 6),
                // 等待领取动画
                wait(2.0f),
                // 点击确定（如果有确认对话框）
                click(640f, 400f, 7),
                wait(0.5f),
                // 返回主界面
                click(50f, 50f, 8)
            )
        )
    
    /**
     * 方案D: 信用商店购物
     * 从主界面开始，在信用商店购买打折物品
     * 
     * 流程：
     * 1. 点击商店入口
     * 2. 点击信用交易所
     * 3. 购买第一个打折物品
     * 4. 返回
     * 
     * 坐标说明（基于1280x720）：
     * - 商店入口: 底部 (960, 680)
     * - 信用交易所: 商店界面左侧 (150, 300)
     * - 购买按钮: 商品右侧 (1000, 300~500)
     * - 返回: 左上角 (50, 50)
     */
    val creditStore: ClickScheme
        get() = ClickScheme(
            name = "信用商店",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击商店入口
                click(960f, 680f, 1),
                // 等待商店界面加载
                wait(1.5f),
                // 点击信用交易所
                click(150f, 300f, 2),
                // 等待信用商店加载
                wait(1.5f),
                // 点击购买第一个商品
                click(1000f, 300f, 3),
                // 等待购买确认
                wait(0.5f),
                // 确认购买
                click(640f, 400f, 4),
                wait(0.5f),
                // 再次购买（如果还有）
                click(1000f, 380f, 5),
                wait(0.5f),
                click(640f, 400f, 6),
                wait(0.5f),
                // 再次购买
                click(1000f, 460f, 7),
                wait(0.5f),
                click(640f, 400f, 8),
                wait(0.5f),
                // 返回主界面
                click(50f, 50f, 9),
                wait(0.5f),
                click(50f, 50f, 10)
            )
        )
    
    /**
     * 方案E: 完整日常（综合方案）
     * 从主界面开始，执行所有日常任务
     * 按顺序执行：基建收菜 -> 公招 -> 领取奖励 -> 信用商店
     * 
     * 注意：这是一个较长的方案，建议设置足够的循环间隔
     */
    val dailyComplete: ClickScheme
        get() = ClickScheme(
            name = "完整日常",
            points = listOf(
                // ========== 基建收菜 ==========
                wait(1.5f),
                click(640f, 680f, 1),  // 进入基建
                wait(2.0f),
                click(1100f, 650f, 2),  // 办事/一键领取
                wait(1.5f),
                click(50f, 50f, 3),  // 返回
                wait(1.5f),
                
                // ========== 公招 ==========
                click(120f, 200f, 4),  // 公开招募
                wait(2.0f),
                click(200f, 250f, 5),  // 第一个招募位
                wait(1.5f),
                click(400f, 280f, 6),  // 选择标签
                wait(0.3f),
                click(640f, 280f, 7),
                wait(0.3f),
                click(880f, 280f, 8),
                wait(0.3f),
                click(1100f, 650f, 9),  // 确认
                wait(2.0f),
                click(640f, 400f, 10),  // 关闭结果
                wait(1.0f),
                click(50f, 50f, 11),  // 返回主界面
                wait(1.5f),
                
                // ========== 领取邮件 ==========
                click(1200f, 50f, 12),  // 邮件
                wait(1.5f),
                click(640f, 400f, 13),  // 一键领取
                wait(2.0f),
                click(640f, 400f, 14),  // 确定
                wait(0.5f),
                click(50f, 50f, 15),  // 返回
                wait(1.5f),
                
                // ========== 领取任务 ==========
                click(1180f, 680f, 16),  // 任务
                wait(1.5f),
                click(640f, 500f, 17),  // 领取全部
                wait(2.0f),
                click(640f, 400f, 18),  // 确定
                wait(0.5f),
                click(50f, 50f, 19),  // 返回主界面
                wait(1.5f),
                
                // ========== 信用商店 ==========
                click(960f, 680f, 20),  // 商店
                wait(1.5f),
                click(150f, 300f, 21),  // 信用交易所
                wait(1.5f),
                click(1000f, 300f, 22),  // 购买
                wait(0.5f),
                click(640f, 400f, 23),  // 确认
                wait(0.5f),
                click(50f, 50f, 24),  // 返回
                wait(0.5f),
                click(50f, 50f, 25)  // 返回主界面
            )
        )
    
    /**
     * 获取所有预设方案列表
     */
    fun getAllPresets(): List<ClickScheme> {
        return listOf(
            baseCollect,
            recruit,
            collectRewards,
            creditStore,
            dailyComplete
        )
    }
    
    /**
     * 根据名称获取预设方案
     */
    fun getPresetByName(name: String): ClickScheme? {
        return getAllPresets().find { it.name == name }
    }
}
