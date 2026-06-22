package com.autoclicker.arknights.data

/**
 * 一键肝舟 v2 - 设备配置
 * 所有坐标用百分比（相对屏幕宽高），运行时按实际分辨率转为绝对坐标
 * 基于30+张截图交叉验证（K30 2400×1080 + iqoo z9t 2800×1260）
 *
 * 坐标稳定性评级:
 * ✅ 跨设备差异<1% — 可直接用固定百分比
 * ⚠️ 差异1-5% 或仅基于单设备截图 — 可用但需留余量
 * ❌ 差异>5% 或主题相关 — 必须搜索区域或OCR
 *
 * v3.13: 全面重写导航流程，新增线索交流/1-7循环/任务双页
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
    
    // ============ 顶部导航栏（各子界面通用） ============
    // 从公招截图验证：返回箭头 | 首页(小房子) | 编队 | 干员 | 档案 | 终端 | 基建 | 公开招募 | 干员寻访 | 采购中心
    
    /** 返回箭头 (左上角) — 点它回上一层 */
    val NAV_BACK = PctCoord(3f, 4f)
    
    /** 小房子/首页 (导航栏第二个) — 点它出导航栏或回主界面 */
    val NAV_HOME = PctCoord(10f, 4f)
    
    /** 导航栏-公开招募 (中偏右) ⚠️位置因屏幕宽度可能偏移 */
    val NAV_BAR_RECRUIT = PctCoord(68f, 4f)
    
    /** 导航栏-终端 ⚠️ */
    val NAV_BAR_TERMINAL = PctCoord(50f, 4f)
    
    /** 导航栏-基建 ⚠️ */
    val NAV_BAR_BASE = PctCoord(58f, 4f)
    
    /** 导航栏-采购中心(含信用商店) ⚠️ */
    val NAV_BAR_SHOP = PctCoord(88f, 4f)
    
    // ============ 主界面模块（右侧排列，主题相关） ============
    // ❌ 主界面布局因主题变化大，以下坐标仅为估算，需OCR最终替代
    
    /** 主界面-基建模块 (右下区域) ❌需OCR */
    val MAIN_BASE = PctCoord(83f, 85f)
    
    /** 主界面-基建搜索区域（OCR搜索"基建"文字） ✅截图验证 (74.8-79.7%, 81.9-87.7%) +余量 */
    val MAIN_BASE_SEARCH_AREA = PctRect(70f, 78f, 85f, 92f)
    
    /** 主界面-任务模块 (中下方) ❌需OCR */
    val MAIN_MISSION = PctCoord(83f, 78f)
    
    /** 主界面-采购中心/信用商店 (中偏右) ❌需OCR */
    val MAIN_SHOP = PctCoord(83f, 55f)
    
    /** 主界面-终端 (右上区域) ❌需OCR */
    val MAIN_TERMINAL = PctCoord(83f, 20f)
    
    // ============ 进游戏 ============
    
    /** START黄字搜索区域 (42-58%, 88-96%) — 菱形区域 */
    val START_SEARCH_AREA = PctRect(42f, 88f, 58f, 96f)
    
    /** START点击位置 (50%, 91%) */
    val START_CLICK = PctCoord(50f, 91f)
    
    /** 开始唤醒灰按钮搜索区域 — 屏幕中偏左区域，覆盖不同设备按钮位置 */
    val WAKE_SEARCH_AREA = PctRect(30f, 25f, 70f, 75f)
    
    /** 开始唤醒点击位置 (45%, 38%) — 兜底位置，优先搜索 */
    val WAKE_CLICK = PctCoord(45f, 38f)
    
    // ============ 清弹窗 ============
    
    /** 今日配给弹窗-黄色徽章搜索区域 (屏幕中央) ✅截图验证 */
    val DAILY_RATION_BADGE_AREA = PctRect(35f, 35f, 65f, 65f)
    
    /** 今日配给弹窗-确认按钮(底部中心黑色圆形) ✅截图验证 */
    val DAILY_RATION_CONFIRM = PctCoord(50f, 82f)
    
    /** 弹窗X按钮搜索区域 — 右上角灰色X圆形按钮 ✅截图验证 (85.2-86.7%,7.9-11.1%) +余量 */
    val POPUP_X_AREA = PctRect(78f, 3f, 95f, 18f)
    
    /** 弹窗深色背景检测区域（用于验证X按钮确实在弹窗上） */
    val POPUP_DARK_CHECK_AREA = PctRect(20f, 30f, 80f, 70f)
    
    /** 弹窗确认按钮搜索区域（底部中心，今日配给等无X按钮弹窗的确认按钮） */
    val POPUP_CONFIRM_SEARCH_AREA = PctRect(35f, 82f, 65f, 95f)
    
    /** 弹窗确认按钮兜底点击位置 */
    val POPUP_CONFIRM_FALLBACK = PctCoord(50f, 88f)
    
    // ============ 基建 ============
    
    /** 蓝底铃铛搜索区域 (右上角 NOTIFICATION) ✅截图验证 */
    val BASE_BELL_AREA = PctRect(82f, 1f, 96f, 8f)
    
    /** 底部功能栏"可收获"按钮 (第二项) ⚠️需OCR确认 */
    val BASE_HARVEST = PctCoord(20f, 95f)
    
    /** 会客室入口 (基建网格中，控制中枢右侧) ⚠️截图估算 */
    val BASE_MEETING_ROOM = PctCoord(76f, 27f)
    
    /** 基建内返回按钮 (6%, 5%) */
    val BACK_BASE = PctCoord(6f, 5f)
    
    // ============ 会客室/线索交流 ============
    
    /** 线索传递图标 (左上) ⚠️ */
    val MEETING_CLUE_TRANSFER = PctCoord(10f, 5f)
    
    /** 好友按钮 (左下区域) ⚠️ */
    val MEETING_FRIEND = PctCoord(10f, 90f)
    
    // ============ 好友访问 ============
    
    /** 访问下位搜索区域 (85-97%, 88-97%) ✅跨设备稳定 */
    val VISIT_NEXT_AREA = PctRect(85f, 88f, 97f, 97f)
    
    // ============ 公招 ============
    
    /** 公招入口 — 从导航栏直接点 */
    // (使用 NAV_BAR_RECRUIT)
    
    /** 公招slot搜索区域 */
    val RECRUIT_SLOT_AREA = PctRect(20f, 40f, 95f, 95f)
    
    /** 公招9h时间按钮 ⚠️需验证 */
    val RECRUIT_9H_BTN = PctCoord(86f, 42f)
    
    /** 公招确认按钮 ⚠️与信用确认购买坐标重叠！需状态机区分 */
    val RECRUIT_CONFIRM = PctCoord(71.4f, 79.1f)
    
    /** 公招SKIP动画按钮 ⚠️需验证 */
    val RECRUIT_SKIP = PctCoord(50f, 90f)
    
    // ============ 信用交易所 ============
    
    /** 收取信用搜索区域 (右上角) */
    val CREDIT_COLLECT_AREA = PctRect(85f, 1f, 95f, 8f)
    
    /** 信用确认购买 ✅✅✅跨设备极稳 ⚠️与公招9h确认重叠 */
    val CREDIT_BUY_CONFIRM = PctCoord(70.4f, 80.1f)
    
    /** 信用商品位置（上排4个） */
    val CREDIT_ITEMS = listOf(
        PctCoord(22f, 30f),
        PctCoord(42f, 30f),
        PctCoord(62f, 30f),
        PctCoord(82f, 30f)
    )
    
    /** 信用商品位置（下排2个） */
    val CREDIT_ITEMS_ROW2 = listOf(
        PctCoord(22f, 55f),
        PctCoord(42f, 55f)
    )
    
    /** 购买确认搜索区域 */
    val CREDIT_BUY_AREA = PctRect(60f, 70f, 85f, 90f)
    
    // ============ 终端→1-7 ============
    
    /** 终端底部导航-主题曲tab (左下第二个) ✅截图验证 */
    val TERM_THEME_TAB = PctCoord(22f, 92f)
    
    /** 终端底部导航-终端tab (最左) ✅ */
    val TERM_TERMINAL_TAB = PctCoord(7f, 92f)
    
    /** 黑暗时代下卡片 (三张卡片的中间/偏右) ⚠️截图估算 */
    val DARK_AGE_BELOW = PctCoord(72f, 43f)
    
    /** 前往章节按钮 (右下角) ⚠️截图估算 */
    val GO_TO_CHAPTER = PctCoord(91f, 85f)
    
    /** 1-7关卡节点 (关卡地图中部) ⚠️截图估算 */
    val STAGE_1_7 = PctCoord(65f, 31f)
    
    /** 开始行动蓝按钮搜索区域（关卡选择界面右下） ✅截图验证 */
    val BATTLE_START_BLUE_AREA = PctRect(83f, 82f, 98f, 96f)
    
    /** 开始行动橙按钮搜索区域（编队界面右下） ✅截图验证 */
    val BATTLE_START_ORANGE_AREA = PctRect(60f, 70f, 98f, 96f)
    
    /** 行动结束白字搜索区域 (左上方) ✅截图验证 */
    val BATTLE_END_AREA = PctRect(4f, 8f, 25f, 20f)
    
    /** 战斗结束后点击继续（屏幕中央） */
    val BATTLE_CONTINUE = PctCoord(50f, 50f)
    
    // ============ 任务 ============
    
    /** 任务模块(主界面中下方) ❌需OCR */
    val MISSION_MODULE = PctCoord(83f, 78f)
    
    /** "收集全部"蓝按钮搜索区域 ✅截图验证 */
    val COLLECT_ALL_AREA = PctRect(72f, 5f, 92f, 18f)
    
    /** 周常任务tab ⚠️截图估算 */
    val WEEKLY_TAB = PctCoord(30f, 5f)
    
    // ============ 识色规则 ============
    
    data class ColorRule(
        val name: String,
        val checkR: (Int) -> Boolean,
        val checkG: (Int) -> Boolean,
        val checkB: (Int) -> Boolean
    ) {
        fun check(r: Int, g: Int, b: Int): Boolean = checkR(r) && checkG(g) && checkB(b)
    }
    
    // ---- 进游戏 ----
    
    /** START黄字: R 220-249, G 180-229, B<80 */
    val COLOR_START_YELLOW = ColorRule("START黄字", { it in 220..249 }, { it in 180..229 }, { it < 80 })
    
    /** 开始唤醒灰按钮: R:82-115, G:78-108, B:78-108 — 收紧范围避免匹配背景渐变和UI线条 */
    val COLOR_WAKE_GRAY = ColorRule("开始唤醒灰", { it in 82..115 }, { it in 78..108 }, { it in 78..108 })
    
    // ---- 清弹窗 ----
    
    /** 今日配给黄色徽章(寻访凭证): R>200, G>150, B<80 ✅截图验证 */
    val COLOR_DAILY_RATION_YELLOW = ColorRule("今日配给黄", { it > 200 }, { it > 150 }, { it < 80 })
    
    /** 弹窗X灰色按钮: R/G/B均在110-200，且差异小(灰色) — ✅截图验证 RGB≈(150-170) 需配合密度检测 */
    val COLOR_POPUP_X = ColorRule("弹窗X灰", { it in 110..200 }, { it in 110..200 }, { it in 110..200 })
    
    /** 确认按钮白色对勾: R>220, G>220, B>220 (今日配给等弹窗底部的确认按钮) */
    val COLOR_CONFIRM_WHITE = ColorRule("确认按钮白", { it > 220 }, { it > 220 }, { it > 220 })
    
    // ---- 基建 ----
    
    /** 蓝底铃铛: B>200, R<100, G>130 ✅截图验证 */
    val COLOR_BASE_BELL = ColorRule("蓝底铃铛", { it < 100 }, { it > 130 }, { it > 200 })
    
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
    
    /** 公招聘用候选人蓝按钮: B>200, R<20, G>140 */
    val COLOR_RECRUIT_FULL = ColorRule("公招已完成蓝", { it < 20 }, { it > 140 }, { it > 200 })
    
    /** 公招确认蓝按钮: B>200, R<100 */
    val COLOR_RECRUIT_CONFIRM = ColorRule("公招确认蓝", { it < 100 }, { true }, { it > 200 })
    
    // ---- 信用商店 ----
    
    /** 收取信用橙色按钮: R>180, G<130, B<50 */
    val COLOR_CREDIT_COLLECT = ColorRule("收取信用橙", { it > 180 }, { it < 130 }, { it < 50 })
    
    /** 购买物品橙色按钮: R>200, G 80-140, B<50 */
    val COLOR_CREDIT_BUY = ColorRule("购买物品橙", { it > 200 }, { it in 80..140 }, { it < 50 })
    
    // ---- 战斗 ----
    
    /** 开始行动蓝按钮(关卡选择): R<40, G>100, B>150 ✅截图验证 */
    val COLOR_BATTLE_START_BLUE = ColorRule("开始行动蓝", { it < 40 }, { it > 100 }, { it > 150 })
    
    /** 开始行动橙按钮(编队): R>200, G>180, B<80 ✅截图验证 */
    val COLOR_BATTLE_START_ORANGE = ColorRule("开始行动橙", { it > 200 }, { it > 180 }, { it < 80 })
    
    /** 行动结束白字: R>250, G>250, B>250 ✅截图验证 */
    val COLOR_BATTLE_END_WHITE = ColorRule("行动结束白", { it > 250 }, { it > 250 }, { it > 250 })
    
    // ---- 任务 ----
    
    /** 任务收集全部蓝按钮: B>200, R<100 ✅截图验证 */
    val COLOR_MISSION_BLUE = ColorRule("任务收集蓝", { it < 100 }, { true }, { it > 200 })
}
