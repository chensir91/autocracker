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
    
    // ============ 进游戏 ============
    
    /** START黄字搜索区域 (40-60%, 86-97%) — 菱形Y位置跨设备偏移大，须搜索 */
    val START_SEARCH_AREA = PctRect(40f, 86f, 60f, 97f)
    
    /** START点击位置 (50%, 91%) — 菱形中心偏上 */
    val START_CLICK = PctCoord(50f, 91f)
    
    /** 开始唤醒灰按钮搜索区域 (40-62%, 63-74%) — 按钮位置跨设备偏移，须搜索 */
    val WAKE_SEARCH_AREA = PctRect(40f, 63f, 62f, 74f)
    
    /** 开始唤醒灰按钮识色点 (51%, 67%) — 按钮中心偏上 */
    val WAKE_CHECK = PctCoord(51f, 67f)
    
    /** 开始唤醒辅助验证点 (51%, 63%) — 浅灰背景R>220 */
    val WAKE_ASSIST = PctCoord(51f, 63f)
    
    /** 开始唤醒点击位置 (51%, 67%) — 按钮中心 */
    val WAKE_CLICK = PctCoord(51f, 67f)
    
    // ============ 活动弹窗 ============
    
    /** 关闭弹窗×临时位置 (96%, 4%) — ⚠️Y%变化大，需OCR或搜索 */
    val POPUP_CLOSE = PctCoord(96f, 4f)
    
    // ============ 主界面 ============
    
    /** 基建入口（底部导航栏） — ⚠️主题因人而异，必须OCR定位 */
    val BASE_ENTRY = PctCoord(75f, 94f)
    
    // ============ 基建 ============
    
    /** 基建收取识色点 (15%, 94%) ✅跨设备极稳 */
    val BASE_COLLECT_CHECK = PctCoord(15f, 94f)
    
    /** 基建收取点击位置（5连点区域，左下角） */
    val BASE_COLLECT_CLICK = PctCoord(15f, 94f)
    
    /** 基建内返回按钮 (6%, 5%) — ⚠️≠好友→主界面返回(18%,5%)！ */
    val BACK_BASE = PctCoord(6f, 5f)
    
    /** 好友→主界面返回按钮 (18%, 5%) — ⚠️≠基建内返回(6%,5%)！ */
    val BACK_TO_MAIN = PctCoord(18f, 5f)
    
    // ============ 好友访问 ============
    
    /** 访问下位搜索区域 (88-95%, 84-92%) — X%波动大，须搜索 */
    val VISIT_NEXT_AREA = PctRect(86f, 82f, 97f, 94f)
    
    /** 确认访问好友 (68.1%, 24.0%) ✅极其稳定 */
    val VISIT_CONFIRM = PctCoord(68.1f, 24.0f)
    
    /** 进房间 (81.2%, 30.5%) ✅极其稳定 */
    val ROOM_ENTER = PctCoord(81.2f, 30.5f)
    
    // ============ 公招 ============
    
    /** 公招入口 (70.0%, 39.6%) 较稳定 ✅ */
    val RECRUIT_ENTRY = PctCoord(70.0f, 39.6f)
    
    /** 公招slot区域 (70-73%, 47-52%) */
    val RECRUIT_SLOT_AREA = PctRect(70f, 47f, 73f, 52f)
    
    /** 公招9h确认 (71.4%, 79.1%) ⚠️与信用确认购买坐标重叠！需状态机区分 */
    val RECRUIT_9H_CONFIRM = PctCoord(71.4f, 79.1f)
    
    /** 开始招租 (72.8%, 90.4%) */
    val RECRUIT_START = PctCoord(72.8f, 90.4f)
    
    /** 公招右上×关闭 (97%, 4%) */
    val RECRUIT_CLOSE = PctCoord(97f, 4f)
    
    // ============ 信用交易所 ============
    
    /** 收信用 (16.0%, 94.7%) — 与基建收取位置接近，橙色=有/灰色=已收 */
    val CREDIT_COLLECT = PctCoord(16.0f, 94.7f)
    
    /** 信用确认购买 (70.4%, 80.1%) ✅✅✅跨设备极稳 ⚠️与公招9h确认重叠 */
    val CREDIT_BUY_CONFIRM = PctCoord(70.4f, 80.1f)
    
    /** 信用翻页搜索区域 — ⚠️跨设备差异大！K30(86%,66%) vs iqoo(81%,55%) ΔY=11% */
    val CREDIT_PAGE_AREA = PctRect(78f, 50f, 90f, 72f)
    
    // ============ 战斗 ============
    
    /** 行动结束白字识色点 (9.8%, 11.1%) */
    val BATTLE_END_CHECK = PctCoord(9.8f, 11.1f)
    
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
    
    /** START黄字: R>220, G>180, B<80 */
    val COLOR_START_YELLOW = ColorRule("START黄字", { it > 220 }, { it > 180 }, { it < 80 })
    
    /** 开始唤醒灰按钮: R在45-135范围（≈85±50，加宽容差） */
    val COLOR_WAKE_GRAY = ColorRule("开始唤醒灰", { it in 45..135 }, { it in 45..135 }, { it in 45..135 })
    
    /** 开始唤醒辅助（浅灰背景）: R>220 */
    val COLOR_WAKE_BG_LIGHT = ColorRule("浅灰背景", { it > 200 }, { it > 200 }, { it > 200 })
    
    /** 基建/收信用 橙色收取: R>180, G<150 */
    val COLOR_COLLECT_ORANGE = ColorRule("橙色收取", { it > 180 }, { it < 150 }, { true })
    
    /** 基建/收信用 已收完灰色: R<150 */
    val COLOR_COLLECT_GRAY = ColorRule("已收灰", { it < 150 }, { true }, { true })
    
    /** 访问下位橙色按钮: R>200, 100<G<140, B<80 */
    val COLOR_VISIT_ORANGE = ColorRule("访问下位橙", { it > 200 }, { it in 100..140 }, { it < 80 })
    
    /** 行动结束白字: R>250, G>250, B>250 */
    val COLOR_BATTLE_END_WHITE = ColorRule("行动结束白", { it > 250 }, { it > 250 }, { it > 250 })
    
    /** 公招确认蓝按钮: B>200, R<100 */
    val COLOR_RECRUIT_BLUE = ColorRule("公招确认蓝", { it < 100 }, { true }, { it > 200 })
}
