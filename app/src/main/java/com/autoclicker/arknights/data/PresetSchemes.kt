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
     * 方案B: 领取邮件+任务奖励
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
     * 方案C: 信用商店购物
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
     * 方案D: 刷1-6（LS-6 物资筹备）
     * 从主界面开始，刷1-6关卡获取经验书和龙门币
     * 
     * 流程：
     * 1. 点击底部"作战"按钮进入选关界面
     * 2. 选择主题曲/物资筹备
     * 3. 选择LS-6关卡
     * 4. 点击"开始行动"
     * 5. 点击"代理指挥"启用自律
     * 6. 等待战斗结束（约45秒）
     * 7. 领取奖励结算
     * 8. 循环重复
     * 
     * 坐标说明（基于1280x720）：
     * - 作战按钮: 底部 (320, 680)
     * - 主题曲入口: 选关界面左侧
     * - 物资筹备: 主题曲下第一个章节
     * - LS-6: 章节内第6个关卡
     * - 开始行动: 右下角 (1100, 650)
     * - 代理指挥: 中间确认按钮 (640, 450)
     * - 结束/确认: 结算界面中心 (640, 400)
     */
    val farm16: ClickScheme
        get() = ClickScheme(
            name = "刷1-6",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击"作战"按钮进入选关界面
                click(320f, 680f, 1),
                // 等待关卡选择界面加载
                wait(2.0f),
                // 点击"主题曲"进入主题曲关卡
                click(200f, 200f, 2),
                wait(1.0f),
                // 点击"物资筹备"章节
                click(200f, 280f, 3),
                wait(1.0f),
                // 选择LS-6关卡（第6个关卡位置）
                click(640f, 550f, 4),
                // 等待关卡详情加载
                wait(1.5f),
                // 点击"开始行动"
                click(1100f, 650f, 5),
                // 等待行动确认界面
                wait(1.0f),
                // 点击"代理指挥"启用自律（如果是首次，可能需要滑动或点击特定位置）
                click(640f, 450f, 6),
                wait(0.5f),
                // 确认开始
                click(640f, 550f, 7),
                // 等待战斗结束（LS-6大约需要45秒）
                wait(50.0f),
                // 领取结算奖励
                click(640f, 400f, 8),
                wait(1.0f),
                // 点击继续/确定
                click(640f, 400f, 9),
                wait(1.0f)
            )
        )
    
    /**
     * 方案E: 好友线索交流
     * 从主界面开始，与好友进行线索交流
     * 
     * 流程：
     * 1. 点击底部"好友"按钮
     * 2. 等待好友界面加载
     * 3. 点击"线索交流"标签
     * 4. 点击"开始交流"发起交流
     * 5. 等待交流完成
     * 6. 领取奖励
     * 7. 返回主界面
     * 
     * 坐标说明（基于1280x720）：
     * - 好友按钮: 底部右侧 (1180, 680)
     * - 线索交流标签: 好友界面顶部 (400, 100)
     * - 开始交流按钮: 界面中心偏下 (640, 400)
     * - 领取奖励: 交流完成后弹出 (640, 400)
     * - 返回: 左上角 (50, 50)
     */
    val friendClue: ClickScheme
        get() = ClickScheme(
            name = "好友线索交流",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击"好友"按钮
                click(1180f, 680f, 1),
                // 等待好友界面加载
                wait(2.0f),
                // 点击"线索交流"标签
                click(400f, 100f, 2),
                // 等待线索交流界面加载
                wait(1.5f),
                // 点击"开始交流"按钮
                click(640f, 400f, 3),
                // 等待交流进行
                wait(2.0f),
                // 如果有确认对话框，点击确认
                click(640f, 400f, 4),
                wait(1.0f),
                // 点击"领取奖励"或确认获得线索
                click(640f, 350f, 5),
                wait(1.0f),
                // 如果需要，点击"发送线索"
                click(640f, 500f, 6),
                wait(1.0f),
                // 返回主界面
                click(50f, 50f, 7),
                wait(1.5f)
            )
        )
    
    /**
     * 方案F: 完整日常（综合方案）
     * 从主界面开始，执行所有日常任务
     * 按顺序执行：基建收菜 -> 领取奖励 -> 信用商店
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
                
                // ========== 领取邮件 ==========
                click(1200f, 50f, 4),  // 邮件
                wait(1.5f),
                click(640f, 400f, 5),  // 一键领取
                wait(2.0f),
                click(640f, 400f, 6),  // 确定
                wait(0.5f),
                click(50f, 50f, 7),  // 返回
                wait(1.5f),
                
                // ========== 领取任务 ==========
                click(1180f, 680f, 8),  // 任务
                wait(1.5f),
                click(640f, 500f, 9),  // 领取全部
                wait(2.0f),
                click(640f, 400f, 10),  // 确定
                wait(0.5f),
                click(50f, 50f, 11),  // 返回主界面
                wait(1.5f),
                
                // ========== 信用商店 ==========
                click(960f, 680f, 12),  // 商店
                wait(1.5f),
                click(150f, 300f, 13),  // 信用交易所
                wait(1.5f),
                click(1000f, 300f, 14),  // 购买
                wait(0.5f),
                click(640f, 400f, 15),  // 确认
                wait(0.5f),
                click(50f, 50f, 16),  // 返回
                wait(0.5f),
                click(50f, 50f, 17)  // 返回主界面
            )
        )
    
    /**
     * 获取所有预设方案列表
     */
    fun getAllPresets(): List<ClickScheme> {
        return listOf(
            baseCollect,
            collectRewards,
            creditStore,
            farm16,
            friendClue,
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
