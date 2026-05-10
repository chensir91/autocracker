package com.autoclicker.arknights.data

/**
 * 明日方舟日常预设方案
 * 坐标基于 1280x720 源分辨率，根据目标分辨率动态缩放
 * 
 * 缩放说明：
 * - 源分辨率: 1280x720 (16:9)
 * - 目标分辨率: 2800x1260 (20:9) 或 2400x1080 (20:9)
 * - scaleX = targetWidth / 1280
 * - scaleY = targetHeight / 720
 * 
 * 支持的分辨率：
 * - 2800x1260: scaleX = 2.1875, scaleY = 1.75
 * - 2400x1080: scaleX = 1.875, scaleY = 1.5
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
    fun getBaseCollect(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "基建收菜",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击基建按钮进入基建界面
                click(640f, 680f, 1, targetWidth, targetHeight),
                // 等待基建界面加载
                wait(2.0f),
                // 点击"办事"一键领取（如果界面有一键领取功能）
                click(1100f, 650f, 2, targetWidth, targetHeight),
                // 等待处理
                wait(1.5f),
                // 点击第一个贸易站
                click(300f, 400f, 3, targetWidth, targetHeight),
                wait(0.5f),
                // 领取订单
                click(640f, 360f, 4, targetWidth, targetHeight),
                wait(0.5f),
                // 返回基建主界面
                click(50f, 50f, 5, targetWidth, targetHeight),
                wait(0.5f),
                // 点击制造站
                click(640f, 400f, 6, targetWidth, targetHeight),
                wait(0.5f),
                // 领取产物
                click(640f, 360f, 7, targetWidth, targetHeight),
                wait(0.5f),
                // 返回基建主界面
                click(50f, 50f, 8, targetWidth, targetHeight),
                wait(0.5f),
                // 如果有无人机可用，点击加速
                click(1150f, 200f, 9, targetWidth, targetHeight),
                wait(0.5f),
                // 确认加速
                click(640f, 400f, 10, targetWidth, targetHeight),
                wait(1.0f),
                // 返回主界面
                click(50f, 50f, 11, targetWidth, targetHeight),
                wait(1.5f),
                // 再次点击返回确保退出基建
                click(50f, 50f, 12, targetWidth, targetHeight)
            )
        )
    }
    
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
    fun getCollectRewards(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "领取邮件+任务",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击邮件图标
                click(1200f, 50f, 1, targetWidth, targetHeight),
                // 等待邮件列表加载
                wait(1.5f),
                // 点击一键领取
                click(640f, 400f, 2, targetWidth, targetHeight),
                // 等待领取动画
                wait(2.0f),
                // 点击确定（如果有确认对话框）
                click(640f, 400f, 3, targetWidth, targetHeight),
                wait(0.5f),
                // 关闭邮件界面
                click(50f, 50f, 4, targetWidth, targetHeight),
                // 等待返回主界面
                wait(1.5f),
                // 点击任务入口
                click(1180f, 680f, 5, targetWidth, targetHeight),
                // 等待任务界面加载
                wait(1.5f),
                // 点击领取全部奖励
                click(640f, 500f, 6, targetWidth, targetHeight),
                // 等待领取动画
                wait(2.0f),
                // 点击确定（如果有确认对话框）
                click(640f, 400f, 7, targetWidth, targetHeight),
                wait(0.5f),
                // 返回主界面
                click(50f, 50f, 8, targetWidth, targetHeight)
            )
        )
    }
    
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
    fun getCreditStore(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "信用商店",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击商店入口
                click(960f, 680f, 1, targetWidth, targetHeight),
                // 等待商店界面加载
                wait(1.5f),
                // 点击信用交易所
                click(150f, 300f, 2, targetWidth, targetHeight),
                // 等待信用商店加载
                wait(1.5f),
                // 点击购买第一个商品
                click(1000f, 300f, 3, targetWidth, targetHeight),
                // 等待购买确认
                wait(0.5f),
                // 确认购买
                click(640f, 400f, 4, targetWidth, targetHeight),
                wait(0.5f),
                // 再次购买（如果还有）
                click(1000f, 380f, 5, targetWidth, targetHeight),
                wait(0.5f),
                click(640f, 400f, 6, targetWidth, targetHeight),
                wait(0.5f),
                // 再次购买
                click(1000f, 460f, 7, targetWidth, targetHeight),
                wait(0.5f),
                click(640f, 400f, 8, targetWidth, targetHeight),
                wait(0.5f),
                // 返回主界面
                click(50f, 50f, 9, targetWidth, targetHeight),
                wait(0.5f),
                click(50f, 50f, 10, targetWidth, targetHeight)
            )
        )
    }
    
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
    fun getFarm16(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "刷1-6",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击"作战"按钮进入选关界面
                click(320f, 680f, 1, targetWidth, targetHeight),
                // 等待关卡选择界面加载
                wait(2.0f),
                // 点击"主题曲"进入主题曲关卡
                click(200f, 200f, 2, targetWidth, targetHeight),
                wait(1.0f),
                // 点击"物资筹备"章节
                click(200f, 280f, 3, targetWidth, targetHeight),
                wait(1.0f),
                // 选择LS-6关卡（第6个关卡位置）
                click(640f, 550f, 4, targetWidth, targetHeight),
                // 等待关卡详情加载
                wait(1.5f),
                // 点击"开始行动"
                click(1100f, 650f, 5, targetWidth, targetHeight),
                // 等待行动确认界面
                wait(1.0f),
                // 点击"代理指挥"启用自律（如果是首次，可能需要滑动或点击特定位置）
                click(640f, 450f, 6, targetWidth, targetHeight),
                wait(0.5f),
                // 确认开始
                click(640f, 550f, 7, targetWidth, targetHeight),
                // 等待战斗结束（LS-6大约需要45秒）
                wait(50.0f),
                // 领取结算奖励
                click(640f, 400f, 8, targetWidth, targetHeight),
                wait(1.0f),
                // 点击继续/确定
                click(640f, 400f, 9, targetWidth, targetHeight),
                wait(1.0f)
            )
        )
    }
    
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
    fun getFriendClue(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "好友线索交流",
            points = listOf(
                // 等待主界面加载
                wait(1.5f),
                // 点击"好友"按钮
                click(1180f, 680f, 1, targetWidth, targetHeight),
                // 等待好友界面加载
                wait(2.0f),
                // 点击"线索交流"标签
                click(400f, 100f, 2, targetWidth, targetHeight),
                // 等待线索交流界面加载
                wait(1.5f),
                // 点击"开始交流"按钮
                click(640f, 400f, 3, targetWidth, targetHeight),
                // 等待交流进行
                wait(2.0f),
                // 如果有确认对话框，点击确认
                click(640f, 400f, 4, targetWidth, targetHeight),
                wait(1.0f),
                // 点击"领取奖励"或确认获得线索
                click(640f, 350f, 5, targetWidth, targetHeight),
                wait(1.0f),
                // 如果需要，点击"发送线索"
                click(640f, 500f, 6, targetWidth, targetHeight),
                wait(1.0f),
                // 返回主界面
                click(50f, 50f, 7, targetWidth, targetHeight),
                wait(1.5f)
            )
        )
    }
    
    /**
     * 方案F: 完整日常（综合方案）
     * 从主界面开始，执行所有日常任务
     * 按顺序执行：基建收菜 -> 领取奖励 -> 信用商店
     * 
     * 注意：这是一个较长的方案，建议设置足够的循环间隔
     */
    fun getDailyComplete(targetWidth: Int, targetHeight: Int): ClickScheme {
        return ClickScheme(
            name = "完整日常",
            points = listOf(
                // ========== 基建收菜 ==========
                wait(1.5f),
                click(640f, 680f, 1, targetWidth, targetHeight),  // 进入基建
                wait(2.0f),
                click(1100f, 650f, 2, targetWidth, targetHeight),  // 办事/一键领取
                wait(1.5f),
                click(50f, 50f, 3, targetWidth, targetHeight),  // 返回
                wait(1.5f),
                
                // ========== 领取邮件 ==========
                click(1200f, 50f, 4, targetWidth, targetHeight),  // 邮件
                wait(1.5f),
                click(640f, 400f, 5, targetWidth, targetHeight),  // 一键领取
                wait(2.0f),
                click(640f, 400f, 6, targetWidth, targetHeight),  // 确定
                wait(0.5f),
                click(50f, 50f, 7, targetWidth, targetHeight),  // 返回
                wait(1.5f),
                
                // ========== 领取任务 ==========
                click(1180f, 680f, 8, targetWidth, targetHeight),  // 任务
                wait(1.5f),
                click(640f, 500f, 9, targetWidth, targetHeight),  // 领取全部
                wait(2.0f),
                click(640f, 400f, 10, targetWidth, targetHeight),  // 确定
                wait(0.5f),
                click(50f, 50f, 11, targetWidth, targetHeight),  // 返回主界面
                wait(1.5f),
                
                // ========== 信用商店 ==========
                click(960f, 680f, 12, targetWidth, targetHeight),  // 商店
                wait(1.5f),
                click(150f, 300f, 13, targetWidth, targetHeight),  // 信用交易所
                wait(1.5f),
                click(1000f, 300f, 14, targetWidth, targetHeight),  // 购买
                wait(0.5f),
                click(640f, 400f, 15, targetWidth, targetHeight),  // 确认
                wait(0.5f),
                click(50f, 50f, 16, targetWidth, targetHeight),  // 返回
                wait(0.5f),
                click(50f, 50f, 17, targetWidth, targetHeight)  // 返回主界面
            )
        )
    }
    
    /**
     * 获取所有预设方案列表
     * @param targetWidth 目标分辨率宽度
     * @param targetHeight 目标分辨率高度
     */
    fun getAllPresets(targetWidth: Int, targetHeight: Int): List<ClickScheme> {
        return listOf(
            getBaseCollect(targetWidth, targetHeight),
            getCollectRewards(targetWidth, targetHeight),
            getCreditStore(targetWidth, targetHeight),
            getFarm16(targetWidth, targetHeight),
            getFriendClue(targetWidth, targetHeight),
            getDailyComplete(targetWidth, targetHeight)
        )
    }
    
    /**
     * 根据名称获取预设方案
     * @param name 方案名称
     * @param targetWidth 目标分辨率宽度
     * @param targetHeight 目标分辨率高度
     */
    fun getPresetByName(name: String, targetWidth: Int, targetHeight: Int): ClickScheme? {
        return getAllPresets(targetWidth, targetHeight).find { it.name == name }
    }
}
