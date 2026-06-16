package com.autoclicker.arknights.data

/**
 * 一键肝舟 v2 - 设备配置
 * 所有坐标用百分比（相对屏幕宽高），运行时按实际分辨率转为绝对坐标
 * 基于10份录制数据交叉验证（K30 2400×1080 + iqoo z9t 2800×1260）
 * 
 * 坐标稳定性评级:
 * ✅ 跨设备差异<1% — 可直接用固定百分比
 * ⚠️ 差异1-5% — 可用但需留余量
 * ❌ 差异>5% — 必须搜索区域或OCR
 */
object DeviceConfig {
    
    // ============ 百分比坐标 ============
    
    /** 百分比坐标（0-100，相对屏幕宽/高） */
    data class PctCoord(val xPct: Float, val yPct: Float) {
        fun toAbs(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
            return (xPct * screenWidth / 100f).toInt() to (yPct * screenHeight / 100f).toInt()
        }
    }
    
    /** 百分比矩形区域（用于搜索） */
    data class PctRect(val leftPct: Float, val topPct: Float, val rightPct: Float, val bottomPct: Float) {
        fun toAbs(screenWidth: Int, screenHeight: Int): android.graphics.Rect {
            return android.graphics.Rect(
                (leftPct * screenWidth / 100f).toInt(),
                (topPct * screenHeight / 100f).toInt(),
                (rightPct * screenWidth / 100f).toInt(),
                (bottomPct * screenHeight / 100f).toInt()
            )
        }
    }
    
    // ============ 主界面底部导航 ============
    // ⚠️ 位置因主题略有差异，需OCR验证；以下为大致位置
    
    /** 基建 tab (底部导航最左) */
    val NAV_BASE = PctCoord(18f, 94f)
    
    /** 终端 tab (底部导航右起第二) */
    val NAV_TERMINAL = PctCoord(70f, 94f)
    
    /** 任务 tab (底部导航最右) */
    val NAV_MISSION = PctCoord(90f, 94f)
    
    // ============ 进游戏 ============
    
    /** START黄字搜索区域 (42-58%, 88-96%) — 菱形区域，收紧避免误识别加载条/背景文字 */
    val START_SEARCH_AREA = PctRect(42f, 88f, 58f, 96f)
    
    /** START点击位置 (50%, 91%) — 菱形中心偏上 */
    val START_CLICK = PctCoord(50f, 91f)
    
    /** 开始唤醒灰按钮搜索区域 (40-62%, 63-74%) — 按钮位置跨设备偏移，须搜索 */
    val WAKE_SEARCH_AREA = PctRect(40f, 63f, 62f, 74f)
    
    /** 开始唤醒点击位置 (51%, 70%) — 灰按钮中心 */
    val WAKE_CLICK = PctCoord(51f, 70f)
    
    // ============ 活动弹窗 ============
    
    /** 关闭弹窗×临时位置 (96%, 4%) — ⚠️Y%变化大，需OCR或搜索 */
    val POPUP_CLOSE = PctCoord(96f, 4f)
    
    /** 弹窗确认按钮（底部中央，如"确认"） */
    val POPUP_CONFIRM = PctCoord(50f, 85f)
    
    // ============ 基建 ============
    
    /** 基建收取识色点 (15%, 94%) ✅跨设备极稳 */
    val BASE_COLLECT_CHECK = PctCoord(15f, 94f)
    
    /** 基建收取点击位置（5连点区域，左下角） */
    val BASE_COLLECT_CLICK = PctCoord(15f, 94f)
    
    /** 基建内返回按钮 (6%, 5%) — ⚠️≠好友→主界面返回(18%,5%)！ */
    val BACK_BASE = PctCoord(6f, 5f)
    
    /** 好友→主界面返回按钮 (18%, 5%) — ⚠️≠基建内返回(6%,5%)！ */
    val BACK_TO_MAIN = PctCoord(18f, 5f)
    
    /** 基建内好友/访问入口 (右上区域) ⚠️需验证 */
    val BASE_VISIT_ENTRY = PctCoord(90f, 6f)
    
    /** 基建内信用交易所入口 ⚠️需验证 */
    val BASE_CREDIT_ENTRY = PctCoord(50f, 8f)
    
    // ============ 好友访问 ============
    
    /** 访问下位搜索区域 (85-97%, 88-97%) — X%波动大，须搜索 */
    val VISIT_NEXT_AREA = PctRect(85f, 88f, 97f, 97f)
    
    /** 确认访问好友 (68.1%, 24.0%) ✅极其稳定 */
    val VISIT_CONFIRM = PctCoord(68.1f, 24.0f)
    
    /** 进房间 (81.2%, 30.5%) ✅极其稳定 */
    val ROOM_ENTER = PctCoord(81.2f, 30.5f)
    
    // ============ 公招 ============
    
    /** 公招入口 (70.0%, 39.6%) 较稳定 ✅ */
    val RECRUIT_ENTRY = PctCoord(70.0f, 39.6f)
    
    /** 公招slot搜索区域 — 搜索蓝色按钮 */
    val RECRUIT_SLOT_AREA = PctRect(20f, 60f, 95f, 95f)
    
    /** 公招9h时间按钮 (86%, 42%) ⚠️需验证 */
    val RECRUIT_9H_BTN = PctCoord(86f, 42f)
    
    /** 公招确认按钮 (71.4%, 79.1%) ⚠️与信用确认购买坐标重叠！需状态机区分 */
    val RECRUIT_CONFIRM = PctCoord(71.4f, 79.1f)
    
    /** 公招右上×关闭 (97%, 4%) */
    val RECRUIT_CLOSE = PctCoord(97f, 4f)
    
    /** 公招SKIP动画按钮 (50%, 90%) ⚠️需验证 */
    val RECRUIT_SKIP = PctCoord(50f, 90f)
    
    // ============ 信用交易所 ============
    
    /** 收取信用搜索区域 (右上角) */
    val CREDIT_COLLECT_AREA = PctRect(85f, 1f, 95f, 8f)
    
    /** 信用确认购买 (70.4%, 80.1%) ✅✅✅跨设备极稳 ⚠️与公招9h确认重叠 */
    val CREDIT_BUY_CONFIRM = PctCoord(70.4f, 80.1f)
    
    /** 信用翻页搜索区域 — ⚠️跨设备差异大！K30(86%,66%) vs iqoo(81%,55%) ΔY=11% */
    val CREDIT_PAGE_AREA = PctRect(78f, 50f, 90f, 72f)
    
    /** 信用商品位置（上排4个） */
    val CREDIT_ITEMS = listOf(
        PctCoord(22f, 30f),  // 第1排第1个
        PctCoord(42f, 30f),  // 第1排第2个
        PctCoord(62f, 30f),  // 第1排第3个
        PctCoord(82f, 30f)   // 第1排第4个
    )
    
    /** 信用商品位置（下排2个） */
    val CREDIT_ITEMS_ROW2 = listOf(
        PctCoord(22f, 55f),  // 第2排第1个
        PctCoord(42f, 55f)   // 第2排第2个
    )
    
    // ============ 战斗 ============
    
    /** 终端→关卡选择区域（1-7大致位置） ⚠️需验证 */
    val BATTLE_1_7_POS = PctCoord(50f, 60f)
    
    /** 开始行动蓝按钮搜索区域（关卡选择界面） */
    val BATTLE_START_BLUE_AREA = PctRect(75f, 85f, 95f, 95f)
    
    /** 开始行动橙按钮搜索区域（编队界面） */
    val BATTLE_START_ORANGE_AREA = PctRect(75f, 85f, 95f, 95f)
    
    /** 行动结束白字搜索区域 (5-25%, 8-15%) */
    val BATTLE_END_SEARCH = PctRect(5f, 8f, 25f, 15f)
    
    /** 行动结束后点击继续（屏幕中央） */
    val BATTLE_CONTINUE = PctCoord(50f, 50f)
    
    // ============ 任务 ============
    
    /** "收集全部"蓝按钮搜索区域 */
    val MISSION_COLLECT_AREA = PctRect(70f, 82f, 95f, 95f)
    
    // ============ 识色规则 ============
    
    /**
     * RGB范围判断规则
     * 每个通道传入验证函数，如 { it > 220 } 表示 R>220
     */
    data class ColorRule(
        val name: String,
        val checkR: (Int) -> Boolean,
        val checkG: (Int) -> Boolean,
        val checkB: (Int) -> Boolean
    ) {
        fun check(r: Int, g: Int, b: Int): Boolean = checkR(r) && checkG(g) && checkB(b)
    }
    
    // ---- 进游戏 ----
    
    /** START黄字: R 220-249, G 180-229, B<80 — 排除纯黄加载条(R≈255,G≈255) */
    val COLOR_START_YELLOW = ColorRule("START黄字", { it in 220..249 }, { it in 180..229 }, { it < 80 })
    
    /** 开始唤醒灰按钮: R在45-135范围 */
    val COLOR_WAKE_GRAY = ColorRule("开始唤醒灰", { it in 45..135 }, { it in 45..135 }, { it in 45..135 })
    
    // ---- 基建 ----
    
    /** 基建/收信用 橙色收取: R>180, G<150 */
    val COLOR_COLLECT_ORANGE = ColorRule("橙色收取", { it > 180 }, { it < 150 }, { true })
    
    /** 基建/收信用 已收完灰色: R<150 */
    val COLOR_COLLECT_GRAY = ColorRule("已收灰", { it < 150 }, { true }, { true })
    
    // ---- 好友访问 ----
    
    /** 访问下位橙色按钮: R>200, G 80-140, B<80 */
    val COLOR_VISIT_NEXT = ColorRule("访问下位橙", { it > 200 }, { it in 80..140 }, { it < 80 })
    
    // ---- 公招 ----
    
    /** 公招空槽蓝+按钮: B>200, R<20, G>140 */
    val COLOR_RECRUIT_EMPTY = ColorRule("公招空槽蓝", { it < 20 }, { it > 140 }, { it > 200 })
    
    /** 公招聘用候选人蓝按钮: B>200, R<20, G>140 (颜色与空槽几乎一样！) */
    val COLOR_RECRUIT_FULL = ColorRule("公招已完成蓝", { it < 20 }, { it > 140 }, { it > 200 })
    
    /** 公招确认蓝按钮: B>200, R<100 */
    val COLOR_RECRUIT_CONFIRM = ColorRule("公招确认蓝", { it < 100 }, { true }, { it > 200 })
    
    // ---- 信用商店 ----
    
    /** 收取信用橙色按钮: R>180, G<130, B<50 (右上角) */
    val COLOR_CREDIT_COLLECT = ColorRule("收取信用橙", { it > 180 }, { it < 130 }, { it < 50 })
    
    /** 购买物品橙色按钮: R>200, G 80-140, B<50 */
    val COLOR_CREDIT_BUY = ColorRule("购买物品橙", { it > 200 }, { it in 80..140 }, { it < 50 })
    
    // ---- 战斗 ----
    
    /** 开始行动蓝按钮(关卡选择): R<40, G>100, B>150 */
    val COLOR_BATTLE_START_BLUE = ColorRule("开始行动蓝", { it < 40 }, { it > 100 }, { it > 150 })
    
    /** 开始行动橙按钮(编队): R>200, G>180, B<80 (黄橙色！) */
    val COLOR_BATTLE_START_ORANGE = ColorRule("开始行动橙", { it > 200 }, { it > 180 }, { it < 80 })
    
    /** 行动结束白字: R>250, G>250, B>250 */
    val COLOR_BATTLE_END_WHITE = ColorRule("行动结束白", { it > 250 }, { it > 250 }, { it > 250 })
    
    // ---- 任务 ----
    
    /** 任务收集全部蓝按钮: B>200, R<100 */
    val COLOR_MISSION_BLUE = ColorRule("任务收集蓝", { it < 100 }, { true }, { it > 200 })
}
