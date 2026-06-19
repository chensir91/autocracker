package com.autoclicker.arknights.data

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import com.autoclicker.arknights.util.ClickUtils
import com.autoclicker.arknights.util.ScreenshotHelper

/**
 * 一键肝舟 v2 - 状态机日常 Runner
 *
 * 核心思路: 点击 → 截图 → 识色/OCR确认 → 下一步
 * 每个状态有: 验证条件(是否到了这个状态) + 操作(点哪里) + 下一状态
 *
 * v3.13 完整流程:
 * 进游戏 → 清弹窗 → 基建(铃铛→可收获→会客室) → 线索交流→好友 →
 * 好友访问 → 小房子→公开招募 → 公招 → 箭头→信用商店 →
 * 信用商店 → 箭头→终端 → 刷1-7循环 → 小房子→首页→任务 → 领任务 → 结束
 */
class DailyRoutine(
    private val service: AccessibilityService,
    screenWidth: Int,
    screenHeight: Int
) {
    // ⚠️ displayMetrics 可能返回竖屏分辨率，但游戏横屏运行
    // 首次截图时用bitmap实际尺寸覆盖，确保百分比坐标准确
    @Volatile private var screenWidth: Int = screenWidth
    @Volatile private var screenHeight: Int = screenHeight
    @Volatile private var screenDimVerified: Boolean = false
    companion object {
        private const val TAG = "DailyRoutine"
        private const val MAX_FRIEND_VISITS = 12
        private const val MAX_RECRUIT_SLOTS = 4
        private const val MAX_CREDIT_BUYS = 6
        private const val MAX_POPUP_CLOSES = 10
        private const val BATTLE_WAIT_SEC = 70       // 战斗开始后等待秒数
        private const val BATTLE_CHECK_INTERVAL = 5000L  // 检查战斗结束的间隔(ms)
        private const val DEFAULT_BATTLE_COUNT = 1    // 默认刷1-7次数
    }
    
    // ============ 状态定义 ============
    
    enum class DailyState {
        WAIT_START,       // 等START黄字出现
        WAIT_WAKE,        // 等开始唤醒灰按钮出现
        CLEAR_POPUPS,     // 清活动弹窗（中间偏右上找X）
        BASE_COLLECT,     // 基建收取（铃铛→可收获→会客室）
        CLUE_EXCHANGE,    // 线索交流（简化版，最后点好友进访问）
        FRIEND_VISIT,     // 好友访问（访问下位循环→小房子→公开招募）
        RECRUIT,          // 公招（处理槽位→箭头回主界面→信用商店）
        CREDIT_SHOP,      // 信用商店（购买→箭头回主界面→终端）
        BATTLE_1_7,       // 刷1-7（终端→主题曲→黑暗时代下→1-7循环→小房子→首页）
        MISSION,          // 领任务（日常×2→周常×2→箭头返回）
        DONE              // 完成
    }
    
    // ============ 测试模块定义 ============
    
    enum class TestModule {
        ENTER_GAME,       // 进游戏
        CLEAR_POPUPS,     // 关弹窗
        BASE_COLLECT,     // 清基建
        CLUE_EXCHANGE,    // 线索交流(简化)
        FRIEND_VISIT,     // 好友访问
        RECRUIT,          // 公招
        CREDIT_SHOP,      // 信用商店
        BATTLE_1_7,       // 刷1-7
        MISSION           // 领任务
    }
    
    // ============ 测试动作回调 ============
    
    sealed class TestAction {
        data class Click(val x: Int, val y: Int) : TestAction()
        data class Wait(val seconds: Float) : TestAction()
        data class Recognize(val stateName: String) : TestAction()
        data class StateChanged(val state: DailyState) : TestAction()
        data class ModuleDone(val module: TestModule) : TestAction()
        data class Error(val msg: String) : TestAction()
    }
    
    // ============ 运行状态 ============
    
    @Volatile var isRunning = false
        private set
    @Volatile var isPaused = false
        private set
    @Volatile var currentState: DailyState = DailyState.WAIT_START
        private set
    
    /** 刷1-7次数（可外部设置） */
    @Volatile var battleCount: Int = DEFAULT_BATTLE_COUNT
    
    private var runnerThread: Thread? = null
    
    // 回调
    var onStateChanged: ((DailyState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAction: ((TestAction) -> Unit)? = null
    
    // ============ 工具方法 ============
    
    private fun Pct2Abs(coord: DeviceConfig.PctCoord): Pair<Int, Int> {
        return coord.toAbs(screenWidth, screenHeight)
    }
    
    private fun Pct2Abs(rect: DeviceConfig.PctRect): android.graphics.Rect {
        return rect.toAbs(screenWidth, screenHeight)
    }
    
    private fun screenshot(): Bitmap? {
        val bmp = ScreenshotHelper.captureScreen(service)
        if (bmp == null) {
            Log.w(TAG, "截图返回null，可能缺少截图权限")
            return null
        }
        // 首次截图：用bitmap实际尺寸校准screenWidth/screenHeight
        // 解决displayMetrics返回竖屏分辨率但游戏横屏运行的bug
        if (!screenDimVerified) {
            val bmpW = bmp.width
            val bmpH = bmp.height
            if (bmpW != screenWidth || bmpH != screenHeight) {
                Log.w(TAG, "⚠️ 屏幕尺寸校准: displayMetrics(${screenWidth}×${screenHeight}) → 截图实际(${bmpW}×${bmpH})")
                log("📐 屏幕尺寸校准: ${screenWidth}×${screenHeight} → ${bmpW}×${bmpH}")
                screenWidth = bmpW
                screenHeight = bmpH
            } else {
                Log.d(TAG, "✅ 屏幕尺寸一致: ${screenWidth}×${screenHeight}")
            }
            screenDimVerified = true
        }
        return bmp
    }
    
    private fun click(coord: DeviceConfig.PctCoord) {
        val (x, y) = Pct2Abs(coord)
        log("点击 (${coord.xPct}%, ${coord.yPct}%) → ($x, $y)")
        onAction?.invoke(TestAction.Click(x, y))
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    private fun clickAbs(x: Int, y: Int) {
        log("点击 ($x, $y)")
        onAction?.invoke(TestAction.Click(x, y))
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    private fun delay(ms: Long) {
        if (ms >= 1000) {
            onAction?.invoke(TestAction.Wait(ms / 1000f))
        }
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
    
    private fun checkPaused() {
        while (isPaused && isRunning) {
            Thread.sleep(200)
        }
    }
    
    private fun checkColor(bmp: Bitmap, coord: DeviceConfig.PctCoord, rule: DeviceConfig.ColorRule): Boolean {
        val (x, y) = Pct2Abs(coord)
        return ScreenshotHelper.checkPixelRange(bmp, x, y, rule.checkR, rule.checkG, rule.checkB)
    }
    
    /** 区域搜索满足颜色规则的像素（返回第一个匹配） */
    private fun searchColor(bmp: Bitmap, rect: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val absRect = Pct2Abs(rect)
        return ScreenshotHelper.searchPixel(
            bmp, absRect.left, absRect.top, absRect.right, absRect.bottom,
            rule.checkR, rule.checkG, rule.checkB
        )
    }
    
    /** 区域搜索满足颜色规则的像素质心 */
    private fun searchColorCentroid(bmp: Bitmap, rect: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val absRect = Pct2Abs(rect)
        return ScreenshotHelper.searchPixelCentroid(
            bmp, absRect.left, absRect.top, absRect.right, absRect.bottom,
            rule.checkR, rule.checkG, rule.checkB
        )
    }
    
    /** 在区域内搜索颜色（轮询截图直到找到），返回质心坐标或null */
    private fun waitForColorInArea(
        area: DeviceConfig.PctRect,
        rule: DeviceConfig.ColorRule,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500
    ): Pair<Int, Int>? {
        val startTime = System.currentTimeMillis()
        var screenshotAttempts = 0
        var screenshotFails = 0
        while (System.currentTimeMillis() - startTime < timeoutMs && isRunning) {
            checkPaused()
            screenshotAttempts++
            val bmp = screenshot()
            if (bmp == null) {
                screenshotFails++
                if (screenshotFails <= 3) {
                    log("⚠️ 截图失败 (#$screenshotFails)")
                } else if (screenshotFails % 10 == 0) {
                    log("⚠️ 截图持续失败 ($screenshotFails/$screenshotAttempts)")
                }
                delay(intervalMs)
                continue
            }
            val quickFound = searchColor(bmp, area, rule)
            if (quickFound != null) {
                val centroid = searchColorCentroid(bmp, area, rule)
                bmp.recycle()
                val result = centroid ?: quickFound
                log("✅ ${rule.name} 区域搜索成功 → 质心(${result.first}, ${result.second})")
                onAction?.invoke(TestAction.Recognize(rule.name))
                return result
            }
            bmp.recycle()
            delay(intervalMs)
        }
        log("❌ ${rule.name} 区域搜索超时 ${timeoutMs}ms")
        return null
    }
    
    /** 单次截图检查区域颜色，返回质心坐标或null */
    private fun checkColorInArea(area: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val bmp = screenshot() ?: return null
        val result = searchColorCentroid(bmp, area, rule)
        bmp.recycle()
        return result
    }
    
    /** 等待识色条件满足（单点轮询） */
    private fun waitForColor(
        coord: DeviceConfig.PctCoord,
        rule: DeviceConfig.ColorRule,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs && isRunning) {
            checkPaused()
            val bmp = screenshot() ?: continue
            val matched = checkColor(bmp, coord, rule)
            bmp.recycle()
            if (matched) {
                log("✅ ${rule.name} 识别成功")
                onAction?.invoke(TestAction.Recognize(rule.name))
                return true
            }
            delay(intervalMs)
        }
        log("❌ ${rule.name} 等待超时 ${timeoutMs}ms")
        return false
    }
    
    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }
    
    // ============ 状态处理 ============
    
    /**
     * ① 等待START黄字 → 点击 → 下一状态: WAIT_WAKE
     */
    private fun handleWaitStart(): DailyState {
        log("=== 等待START界面 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_START))
        
        val startFound = waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)
        if (startFound == null) {
            onError?.invoke("等待START超时，请确认游戏已启动")
            return DailyState.DONE
        }
        delay(300)
        clickAbs(startFound.first, startFound.second)
        delay(2000)
        return DailyState.WAIT_WAKE
    }
    
    /**
     * ② 等待开始唤醒灰按钮 → 点击 → 下一状态: CLEAR_POPUPS
     */
    private fun handleWaitWake(): DailyState {
        log("=== 等待开始唤醒 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_WAKE))
        
        val wakeFound = waitForColorInArea(DeviceConfig.WAKE_SEARCH_AREA, DeviceConfig.COLOR_WAKE_GRAY, timeoutMs = 25000)
        if (wakeFound != null) {
            log("✅ 开始唤醒搜索成功，点击质心")
            delay(300)
            clickAbs(wakeFound.first, wakeFound.second)
        } else {
            log("⚠️ 开始唤醒搜索超时，兜底点击")
            click(DeviceConfig.WAKE_CLICK)
        }
        delay(2000)
        return DailyState.CLEAR_POPUPS
    }
    
    /**
     * ③ 清弹窗 — 先识别今日配给→再搜X按钮→确认弹窗背景才点击
     * 新策略(v3.16):
     * 1. 搜索今日配给黄色徽章 → 点确认按钮关闭
     * 2. 搜索X按钮(灰色) + 验证弹窗深色背景存在(防止误点主界面UI)
     * 3. 都没找到 → 弹窗已清完
     * → 下一状态: BASE_COLLECT
     */
    private fun handleClearPopups(): DailyState {
        log("=== 清理活动弹窗 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CLEAR_POPUPS))
        
        delay(3000) // 等弹窗完全加载
        
        for (i in 1..MAX_POPUP_CLOSES) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            // === 第一步：识别今日配给弹窗（黄色徽章） ===
            val yellowFound = checkColorInArea(DeviceConfig.DAILY_RATION_BADGE_AREA, DeviceConfig.COLOR_DAILY_RATION_YELLOW)
            if (yellowFound != null) {
                log("✅ 识别到今日配给弹窗 → 点确认按钮")
                click(DeviceConfig.DAILY_RATION_CONFIRM)
                delay(1500)
                continue
            }
            
            // === 第二步：搜索X按钮 + 弹窗背景验证 ===
            val xFound = searchPopupX()
            if (xFound != null) {
                log("✅ 找到弹窗X → 点击 (${xFound.first}, ${xFound.second})")
                clickAbs(xFound.first, xFound.second)
                delay(1000)
                continue
            }
            
            // === 没找到任何弹窗 → 清完 ===
            log("✅ 弹窗已清除完毕")
            break
        }
        
        // 进入基建
        return DailyState.BASE_COLLECT
    }
    
    /**
     * 搜索弹窗X按钮 — 带弹窗背景验证
     * 先找灰色像素候选，再验证弹窗深色背景存在，防止误点主界面UI
     */
    private fun searchPopupX(): Pair<Int, Int>? {
        val bmp = screenshot() ?: return null
        
        // 在X按钮搜索区域找灰色像素
        val absRect = Pct2Abs(DeviceConfig.POPUP_X_AREA)
        val firstMatch = ScreenshotHelper.searchPixel(
            bmp, absRect.left, absRect.top, absRect.right, absRect.bottom,
            DeviceConfig.COLOR_POPUP_X.checkR, DeviceConfig.COLOR_POPUP_X.checkG, DeviceConfig.COLOR_POPUP_X.checkB
        )
        
        if (firstMatch == null) {
            bmp.recycle()
            return null
        }
        
        // 找到灰色像素 → 计算质心
        val centroid = ScreenshotHelper.searchPixelCentroid(
            bmp, absRect.left, absRect.top, absRect.right, absRect.bottom,
            DeviceConfig.COLOR_POPUP_X.checkR, DeviceConfig.COLOR_POPUP_X.checkG, DeviceConfig.COLOR_POPUP_X.checkB
        )
        val candidate = centroid ?: firstMatch
        
        // === 关键验证：检查弹窗深色背景是否存在 ===
        // 弹窗覆盖时，屏幕中央区域应该是深色的(弹窗背景)，而不是正常游戏界面
        val darkCheckRect = Pct2Abs(DeviceConfig.POPUP_DARK_CHECK_AREA)
        var darkCount = 0
        var totalChecked = 0
        // 采样检查：每隔20像素检查一个点
        for (y in darkCheckRect.top until darkCheckRect.bottom step 20) {
            for (x in darkCheckRect.left until darkCheckRect.right step 20) {
                if (x < bmp.width && y < bmp.height) {
                    val pixel = bmp.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    val brightness = (r + g + b) / 3
                    if (brightness < 60) {
                        darkCount++
                    }
                    totalChecked++
                }
            }
        }
        bmp.recycle()
        
        val darkRatio = if (totalChecked > 0) darkCount.toFloat() / totalChecked else 0f
        log("  X候选(${candidate.first},${candidate.second}) 深色背景比例: ${"%.0f".format(darkRatio * 100)}% ($darkCount/$totalChecked)")
        
        // 弹窗覆盖时中央区域至少30%是深色的；主界面中央区域有游戏画面，深色比例很低
        if (darkRatio >= 0.3f) {
            log("  ✅ 弹窗背景验证通过")
            return candidate
        } else {
            log("  ❌ 弹窗背景验证失败（可能是主界面），跳过")
            return null
        }
    }
    
    /**
     * ④ 基建收取 — 识蓝底铃铛→点→识"可收获"→5连点→点会客室
     * → 下一状态: CLUE_EXCHANGE
     */
    private fun handleBaseCollect(): DailyState {
        log("=== 基建收取 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.BASE_COLLECT))
        
        // 点击主界面基建模块进入基建
        click(DeviceConfig.MAIN_BASE)
        delay(2000)
        
        // 识别右上角蓝底铃铛并点击
        val bellFound = checkColorInArea(DeviceConfig.BASE_BELL_AREA, DeviceConfig.COLOR_BASE_BELL)
        if (bellFound != null) {
            log("✅ 找到蓝底铃铛 → 点击")
            clickAbs(bellFound.first, bellFound.second)
            delay(1500)
        } else {
            log("⚠️ 未找到蓝底铃铛，尝试固定位置点击")
            click(DeviceConfig.BASE_BELL_AREA.let {
                DeviceConfig.PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
            delay(1500)
        }
        
        // 点"可收获"按钮5次（间隔1s）
        log("点击\"可收获\"5次")
        for (i in 1..5) {
            if (!isRunning) return DailyState.DONE
            click(DeviceConfig.BASE_HARVEST)
            delay(1000)
        }
        
        // 点会客室进入线索交流
        log("点击会客室")
        click(DeviceConfig.BASE_MEETING_ROOM)
        delay(2000)
        
        return DailyState.CLUE_EXCHANGE
    }
    
    /**
     * ⑤ 线索交流（简化版）— 进入会客室后直接点好友进访问
     * TODO: 完整线索交流逻辑待用户提供截图后补充
     * → 下一状态: FRIEND_VISIT
     */
    private fun handleClueExchange(): DailyState {
        log("=== 线索交流（简化版）===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CLUE_EXCHANGE))
        
        // 简化版：在会客室界面直接点好友进入访问
        // TODO: 完整流程应包含线索传递、参与线索交流等操作
        log("⚠️ 线索交流为简化版，直接点好友进访问")
        delay(1000)
        
        click(DeviceConfig.MEETING_FRIEND)
        delay(2000)
        
        return DailyState.FRIEND_VISIT
    }
    
    /**
     * ⑥ 好友访问 — 循环搜索"访问下位"橙按钮→完成后点小房子→点公开招募
     * → 下一状态: RECRUIT
     */
    private fun handleFriendVisit(): DailyState {
        log("=== 好友访问 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.FRIEND_VISIT))
        
        // 循环搜索"访问下位"橙按钮
        var visitCount = 0
        for (i in 1..MAX_FRIEND_VISITS) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            val found = checkColorInArea(DeviceConfig.VISIT_NEXT_AREA, DeviceConfig.COLOR_VISIT_NEXT)
            if (found != null) {
                log("✅ 找到访问下位 → 点击 (${found.first}, ${found.second})")
                clickAbs(found.first, found.second)
                visitCount++
                delay(2000)
            } else {
                log("✅ 访问下位按钮未找到，已访问 $visitCount 位好友")
                break
            }
        }
        
        // 点左上角小房子 → 出导航栏
        log("点小房子回导航")
        click(DeviceConfig.NAV_HOME)
        delay(1500)
        
        // 点导航栏中上方"公开招募"
        log("点公开招募进公招")
        click(DeviceConfig.NAV_BAR_RECRUIT)
        delay(2000)
        
        return DailyState.RECRUIT
    }
    
    /**
     * ⑦ 公招 — 优先领取已完成→处理空槽设9h→箭头回主界面→点信用商店
     * → 下一状态: CREDIT_SHOP
     */
    private fun handleRecruit(): DailyState {
        log("=== 公招 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.RECRUIT))
        
        var handledSlots = 0
        
        for (round in 1..MAX_RECRUIT_SLOTS) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            // 先搜索"聘用候选人"蓝按钮（优先领取已完成的）
            val fullFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_FULL)
            if (fullFound != null) {
                log("✅ 发现已完成公招 → 点击领取")
                clickAbs(fullFound.first, fullFound.second)
                delay(2000)
                click(DeviceConfig.RECRUIT_SKIP)
                delay(1500)
                handledSlots++
                continue
            }
            
            // 再搜索空槽位蓝+按钮
            val emptyFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_EMPTY)
            if (emptyFound != null) {
                log("✅ 发现空槽位 → 点击开始招募")
                clickAbs(emptyFound.first, emptyFound.second)
                delay(1500)
                click(DeviceConfig.RECRUIT_9H_BTN)
                delay(500)
                click(DeviceConfig.RECRUIT_CONFIRM)
                delay(1500)
                handledSlots++
                continue
            }
            
            log("✅ 无可处理的公招槽位")
            break
        }
        
        log("✅ 公招处理完成，共处理 $handledSlots 个槽位")
        
        // 点左上角箭头回主界面
        log("点箭头回主界面")
        click(DeviceConfig.NAV_BACK)
        delay(1500)
        
        // 点信用商店（采购中心）
        log("点采购中心/信用商店")
        click(DeviceConfig.MAIN_SHOP)
        delay(2000)
        
        // 如果进了采购中心，还需要点信用商店
        // TODO: 需要确认采购中心内信用商店的位置
        
        return DailyState.CREDIT_SHOP
    }
    
    /**
     * ⑧ 信用商店 — 收信用→购买物品→箭头回主界面→点终端
     * → 下一状态: BATTLE_1_7
     */
    private fun handleCreditShop(): DailyState {
        log("=== 信用商店 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CREDIT_SHOP))
        
        // 搜索"收取信用"橙色按钮
        val collectFound = checkColorInArea(DeviceConfig.CREDIT_COLLECT_AREA, DeviceConfig.COLOR_CREDIT_COLLECT)
        if (collectFound != null) {
            log("✅ 找到收取信用按钮 → 点击")
            clickAbs(collectFound.first, collectFound.second)
            delay(1500)
        } else {
            log("⚠️ 未找到收取信用按钮，可能已收取")
        }
        
        // 购买物品（上排4个 + 下排2个）
        var buyCount = 0
        val allItems = DeviceConfig.CREDIT_ITEMS + DeviceConfig.CREDIT_ITEMS_ROW2
        
        for (itemCoord in allItems) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            if (buyCount >= MAX_CREDIT_BUYS) break
            
            click(itemCoord)
            delay(800)
            
            val buyFound = checkColorInArea(DeviceConfig.CREDIT_BUY_AREA, DeviceConfig.COLOR_CREDIT_BUY)
            if (buyFound != null) {
                log("✅ 购买确认 → 点击")
                clickAbs(buyFound.first, buyFound.second)
                buyCount++
                delay(1000)
            } else {
                log("⚠️ 未找到购买确认，可能已售罄")
                // 关闭可能的弹窗
                click(DeviceConfig.NAV_BACK)
                delay(500)
            }
        }
        
        log("✅ 信用商店完成，购买 $buyCount 件物品")
        
        // 点左上角箭头回主界面
        log("点箭头回主界面")
        click(DeviceConfig.NAV_BACK)
        delay(1500)
        
        // 点右上角终端
        log("点终端进1-7")
        click(DeviceConfig.MAIN_TERMINAL)
        delay(2000)
        
        return DailyState.BATTLE_1_7
    }
    
    /**
     * ⑨ 刷1-7 — 终端→主题曲→黑暗时代下→前往章节→[循环]→小房子→首页
     * 循环: 点1-7→开始行动蓝→开始行动橙→等70s→每5s检查行动结束→点继续→回到地图
     * → 下一状态: MISSION
     */
    private fun handleBattle17(): DailyState {
        log("=== 刷1-7 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.BATTLE_1_7))
        
        // 终端界面：点击左下角第二个按钮（主题曲tab）
        log("点击主题曲tab")
        click(DeviceConfig.TERM_THEME_TAB)
        delay(2000)
        
        // 点击偏右的"黑暗时代下"卡片
        log("点击黑暗时代下")
        click(DeviceConfig.DARK_AGE_BELOW)
        delay(2000)
        
        // 点击右下角"前往章节"
        log("点击前往章节")
        click(DeviceConfig.GO_TO_CHAPTER)
        delay(2000)
        
        // ===== 刷1-7循环 =====
        for (battle in 1..battleCount) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            log("--- 刷1-7 第 $battle/$battleCount 次 ---")
            
            // 点屏幕正中1-7关卡节点
            click(DeviceConfig.STAGE_1_7)
            delay(1500)
            
            // 点右下角"开始行动"蓝按钮
            val startBlue = waitForColorInArea(
                DeviceConfig.BATTLE_START_BLUE_AREA,
                DeviceConfig.COLOR_BATTLE_START_BLUE,
                timeoutMs = 10000
            )
            if (startBlue != null) {
                log("✅ 找到开始行动(蓝) → 点击")
                clickAbs(startBlue.first, startBlue.second)
            } else {
                log("⚠️ 未找到开始行动蓝，尝试固定位置")
                click(DeviceConfig.BATTLE_START_BLUE_AREA.let {
                    DeviceConfig.PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
                })
            }
            delay(2000)
            
            // 点中偏右下"开始行动"橙按钮（编队界面）
            val startOrange = waitForColorInArea(
                DeviceConfig.BATTLE_START_ORANGE_AREA,
                DeviceConfig.COLOR_BATTLE_START_ORANGE,
                timeoutMs = 10000
            )
            if (startOrange != null) {
                log("✅ 找到开始行动(橙) → 点击")
                clickAbs(startOrange.first, startOrange.second)
            } else {
                log("⚠️ 未找到开始行动橙，尝试固定位置")
                click(DeviceConfig.BATTLE_START_ORANGE_AREA.let {
                    DeviceConfig.PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
                })
            }
            delay(2000)
            
            // 等待战斗开始后等70秒
            log("等待战斗进行 ${BATTLE_WAIT_SEC}s...")
            delay(BATTLE_WAIT_SEC * 1000L)
            
            // 每隔5s识别左上方"行动结束"白字
            var battleEnded = false
            val battleTimeout = 300000L // 最长等5分钟
            val battleStart = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - battleStart < battleTimeout && isRunning) {
                checkPaused()
                val endFound = checkColorInArea(DeviceConfig.BATTLE_END_AREA, DeviceConfig.COLOR_BATTLE_END_WHITE)
                if (endFound != null) {
                    log("✅ 检测到行动结束")
                    battleEnded = true
                    break
                }
                delay(BATTLE_CHECK_INTERVAL)
            }
            
            if (battleEnded) {
                delay(1000)
                // 点屏幕正中继续
                click(DeviceConfig.BATTLE_CONTINUE)
                delay(2000)
            } else {
                log("⚠️ 等待战斗结算超时，尝试继续")
                click(DeviceConfig.BATTLE_CONTINUE)
                delay(2000)
            }
            
            // 回到关卡地图，准备下一次
            if (battle < battleCount) {
                delay(1000)
            }
        }
        
        // 退出：点左上角小房子
        log("点小房子回导航")
        click(DeviceConfig.NAV_HOME)
        delay(1500)
        
        // 点首页回主界面
        log("点首页回主页")
        click(DeviceConfig.NAV_HOME)
        delay(1500)
        
        return DailyState.MISSION
    }
    
    /**
     * ⑩ 领任务 — 识中下方任务→点→日常收集×2→周常→收集×2→箭头返回
     * → 下一状态: DONE
     */
    private fun handleMission(): DailyState {
        log("=== 领任务 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.MISSION))
        
        // 识别主界面中下方任务模块并点击
        log("点击任务模块")
        click(DeviceConfig.MISSION_MODULE)
        delay(2000)
        
        // 日常任务页 — 点右上方"收集全部"2下（间隔1s）
        val collectFound1 = checkColorInArea(DeviceConfig.COLLECT_ALL_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collectFound1 != null) {
            log("✅ 日常-找到收集全部 → 点击")
            clickAbs(collectFound1.first, collectFound1.second)
            delay(1000)
            // 第二下
            clickAbs(collectFound1.first, collectFound1.second)
            delay(1500)
        } else {
            log("⚠️ 日常-未找到收集全部")
        }
        
        // 切换到周常任务tab
        log("切换到周常任务")
        click(DeviceConfig.WEEKLY_TAB)
        delay(1500)
        
        // 周常任务页 — 点右上方"收集全部"2下（间隔1s）
        val collectFound2 = checkColorInArea(DeviceConfig.COLLECT_ALL_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collectFound2 != null) {
            log("✅ 周常-找到收集全部 → 点击")
            clickAbs(collectFound2.first, collectFound2.second)
            delay(1000)
            clickAbs(collectFound2.first, collectFound2.second)
            delay(1500)
        } else {
            log("⚠️ 周常-未找到收集全部")
        }
        
        // 点左上角箭头返回主界面
        log("点箭头返回主页")
        click(DeviceConfig.NAV_BACK)
        delay(1000)
        
        log("✅ 任务模块完成")
        return DailyState.DONE
    }
    
    // ============ 公开接口 ============
    
    fun start() {
        if (isRunning) return
        
        if (!ScreenshotHelper.isSupported) {
            onError?.invoke("当前系统版本不支持截图（需Android 11+）")
            return
        }
        
        isRunning = true
        isPaused = false
        currentState = DailyState.WAIT_START
        
        runnerThread = Thread({
            log("🚀 一键肝舟启动！")
            
            // 截图权限预检
            log("📷 预检截图权限...")
            val testBmp = screenshot()
            if (testBmp == null) {
                val detail = ScreenshotHelper.lastError ?: "未知"
                val diag = ScreenshotHelper.lastDiagnostic ?: ""
                log("❌ 截图失败: $detail | $diag")
                val userMsg = when {
                    detail.contains("err=4") || detail.contains("请重开无障碍") -> "[v${ScreenshotHelper.VERSION}] 截图权限未生效，请关闭再重新开启无障碍服务"
                    else -> "[v${ScreenshotHelper.VERSION}] $detail"
                }
                onError?.invoke(userMsg)
                isRunning = false
                return@Thread
            } else {
                log("✅ 截图成功 (${testBmp.width}x${testBmp.height})")
                testBmp.recycle()
            }
            
            onStateChanged?.invoke(currentState)
            
            try {
                while (isRunning && currentState != DailyState.DONE) {
                    checkPaused()
                    
                    val nextState = when (currentState) {
                        DailyState.WAIT_START -> handleWaitStart()
                        DailyState.WAIT_WAKE -> handleWaitWake()
                        DailyState.CLEAR_POPUPS -> handleClearPopups()
                        DailyState.BASE_COLLECT -> handleBaseCollect()
                        DailyState.CLUE_EXCHANGE -> handleClueExchange()
                        DailyState.FRIEND_VISIT -> handleFriendVisit()
                        DailyState.RECRUIT -> handleRecruit()
                        DailyState.CREDIT_SHOP -> handleCreditShop()
                        DailyState.BATTLE_1_7 -> handleBattle17()
                        DailyState.MISSION -> handleMission()
                        DailyState.DONE -> DailyState.DONE
                    }
                    
                    currentState = nextState
                    onStateChanged?.invoke(currentState)
                }
                
                if (currentState == DailyState.DONE) {
                    log("🏁 一键肝舟完成！")
                    onDone?.invoke()
                }
            } catch (e: InterruptedException) {
                log("⛔ 一键肝舟被中断")
            } catch (e: Exception) {
                Log.e(TAG, "DailyRoutine error", e)
                onError?.invoke("运行出错: ${e.message}")
                onAction?.invoke(TestAction.Error("运行出错: ${e.message}"))
            } finally {
                isRunning = false
            }
        }, "DailyRoutine")
        
        runnerThread?.start()
    }
    
    fun startModule(module: TestModule) {
        if (isRunning) return
        
        if (!ScreenshotHelper.isSupported) {
            onError?.invoke("当前系统版本不支持截图（需Android 11+）")
            onAction?.invoke(TestAction.Error("当前系统版本不支持截图（需Android 11+）"))
            return
        }
        
        isRunning = true
        isPaused = false
        
        runnerThread = Thread({
            log("🚀 测试模块启动: $module")
            
            log("📷 预检截图权限...")
            val testBmp = screenshot()
            if (testBmp == null) {
                val detail = ScreenshotHelper.lastError ?: "未知"
                val userMsg = when {
                    detail.contains("err=4") || detail.contains("请重开无障碍") -> "[v${ScreenshotHelper.VERSION}] 截图权限未生效，请关闭再重新开启无障碍服务"
                    else -> "[v${ScreenshotHelper.VERSION}] $detail"
                }
                onError?.invoke(userMsg)
                onAction?.invoke(TestAction.Error(userMsg))
                onAction?.invoke(TestAction.ModuleDone(module))
                isRunning = false
                return@Thread
            } else {
                log("✅ 截图成功 (${testBmp.width}x${testBmp.height})")
                testBmp.recycle()
            }
            
            try {
                when (module) {
                    TestModule.ENTER_GAME -> runEnterGameModule()
                    TestModule.CLEAR_POPUPS -> runClearPopupsModule()
                    TestModule.BASE_COLLECT -> runBaseCollectModule()
                    TestModule.CLUE_EXCHANGE -> runClueExchangeModule()
                    TestModule.FRIEND_VISIT -> runFriendVisitModule()
                    TestModule.RECRUIT -> runRecruitModule()
                    TestModule.CREDIT_SHOP -> runCreditShopModule()
                    TestModule.BATTLE_1_7 -> runBattle17Module()
                    TestModule.MISSION -> runMissionModule()
                }
            } catch (e: InterruptedException) {
                log("⛔ 测试模块被中断: $module")
            } catch (e: Exception) {
                Log.e(TAG, "TestModule error: $module", e)
                onError?.invoke("运行出错: ${e.message}")
                onAction?.invoke(TestAction.Error("运行出错: ${e.message}"))
            } finally {
                isRunning = false
                log("🏁 测试模块完成: $module")
            }
        }, "TestModule-$module")
        
        runnerThread?.start()
    }
    
    // ============ 测试模块实现 ============
    
    private fun runEnterGameModule() {
        currentState = DailyState.WAIT_START
        onAction?.invoke(TestAction.StateChanged(currentState))
        
        log("=== [测试] 等待START界面 ===")
        val startCoord = waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)
        if (startCoord == null) {
            onError?.invoke("等待START超时")
            onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
            return
        }
        delay(300)
        clickAbs(startCoord.first, startCoord.second)
        delay(2000)
        
        currentState = DailyState.WAIT_WAKE
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 等待开始唤醒 ===")
        
        val wakeCoord = waitForColorInArea(DeviceConfig.WAKE_SEARCH_AREA, DeviceConfig.COLOR_WAKE_GRAY, timeoutMs = 25000)
        if (wakeCoord != null) {
            delay(300)
            clickAbs(wakeCoord.first, wakeCoord.second)
        } else {
            click(DeviceConfig.WAKE_CLICK)
        }
        delay(2000)
        
        log("✅ 进游戏模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
    }
    
    private fun runClearPopupsModule() {
        currentState = DailyState.CLEAR_POPUPS
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 清理活动弹窗 ===")
        
        delay(1000)
        for (i in 1..MAX_POPUP_CLOSES) {
            if (!isRunning) break
            checkPaused()
            
            // 1. 今日配给弹窗
            val yellowFound = checkColorInArea(DeviceConfig.DAILY_RATION_BADGE_AREA, DeviceConfig.COLOR_DAILY_RATION_YELLOW)
            if (yellowFound != null) {
                log("识别到今日配给 → 点确认")
                click(DeviceConfig.DAILY_RATION_CONFIRM)
                delay(1500)
                continue
            }
            
            // 2. X按钮 + 背景验证
            val xFound = searchPopupX()
            if (xFound != null) {
                log("找到弹窗X → 点击")
                clickAbs(xFound.first, xFound.second)
                delay(1000)
                continue
            }
            
            log("✅ 弹窗已清除")
            break
        }
        
        log("✅ 关弹窗模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.CLEAR_POPUPS))
    }
    
    private fun runBaseCollectModule() {
        currentState = DailyState.BASE_COLLECT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 基建收取（请先在主界面） ===")
        
        click(DeviceConfig.MAIN_BASE)
        delay(2000)
        
        val bellFound = checkColorInArea(DeviceConfig.BASE_BELL_AREA, DeviceConfig.COLOR_BASE_BELL)
        if (bellFound != null) {
            log("找到铃铛 → 点击")
            clickAbs(bellFound.first, bellFound.second)
            delay(1500)
        }
        
        log("点击\"可收获\"5次")
        for (i in 1..5) {
            if (!isRunning) break
            click(DeviceConfig.BASE_HARVEST)
            delay(1000)
        }
        
        log("点击会客室")
        click(DeviceConfig.BASE_MEETING_ROOM)
        delay(2000)
        
        log("✅ 基建收取模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.BASE_COLLECT))
    }
    
    private fun runClueExchangeModule() {
        currentState = DailyState.CLUE_EXCHANGE
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 线索交流-简化版（请在会客室界面） ===")
        
        delay(1000)
        click(DeviceConfig.MEETING_FRIEND)
        delay(2000)
        
        log("✅ 线索交流模块完成(简化版)")
        onAction?.invoke(TestAction.ModuleDone(TestModule.CLUE_EXCHANGE))
    }
    
    private fun runFriendVisitModule() {
        currentState = DailyState.FRIEND_VISIT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 好友访问（请在会客室/好友界面） ===")
        
        var visitCount = 0
        for (i in 1..MAX_FRIEND_VISITS) {
            if (!isRunning) break
            checkPaused()
            val found = checkColorInArea(DeviceConfig.VISIT_NEXT_AREA, DeviceConfig.COLOR_VISIT_NEXT)
            if (found != null) {
                log("访问下位 → 点击")
                clickAbs(found.first, found.second)
                visitCount++
                delay(2000)
            } else {
                break
            }
        }
        
        log("✅ 好友访问完成，共访问 $visitCount 位")
        
        // 点小房子→公开招募
        click(DeviceConfig.NAV_HOME)
        delay(1500)
        click(DeviceConfig.NAV_BAR_RECRUIT)
        delay(2000)
        
        log("✅ 好友访问模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.FRIEND_VISIT))
    }
    
    private fun runRecruitModule() {
        currentState = DailyState.RECRUIT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 公招（请在公招界面） ===")
        
        var handledSlots = 0
        for (round in 1..MAX_RECRUIT_SLOTS) {
            if (!isRunning) break
            checkPaused()
            
            val fullFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_FULL)
            if (fullFound != null) {
                log("领取已完成公招 → 点击")
                clickAbs(fullFound.first, fullFound.second)
                delay(2000)
                click(DeviceConfig.RECRUIT_SKIP)
                delay(1500)
                handledSlots++
                continue
            }
            
            val emptyFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_EMPTY)
            if (emptyFound != null) {
                log("开始招募 → 点击")
                clickAbs(emptyFound.first, emptyFound.second)
                delay(1500)
                click(DeviceConfig.RECRUIT_9H_BTN)
                delay(500)
                click(DeviceConfig.RECRUIT_CONFIRM)
                delay(1500)
                handledSlots++
                continue
            }
            break
        }
        
        log("✅ 公招处理完成，处理 $handledSlots 个槽位")
        
        click(DeviceConfig.NAV_BACK)
        delay(1500)
        click(DeviceConfig.MAIN_SHOP)
        delay(2000)
        
        log("✅ 公招模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.RECRUIT))
    }
    
    private fun runCreditShopModule() {
        currentState = DailyState.CREDIT_SHOP
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 信用商店（请在信用商店界面） ===")
        
        val collectFound = checkColorInArea(DeviceConfig.CREDIT_COLLECT_AREA, DeviceConfig.COLOR_CREDIT_COLLECT)
        if (collectFound != null) {
            log("收取信用 → 点击")
            clickAbs(collectFound.first, collectFound.second)
            delay(1500)
        }
        
        var buyCount = 0
        val allItems = DeviceConfig.CREDIT_ITEMS + DeviceConfig.CREDIT_ITEMS_ROW2
        for (itemCoord in allItems) {
            if (!isRunning) break
            if (buyCount >= MAX_CREDIT_BUYS) break
            
            click(itemCoord)
            delay(800)
            
            val buyFound = checkColorInArea(DeviceConfig.CREDIT_BUY_AREA, DeviceConfig.COLOR_CREDIT_BUY)
            if (buyFound != null) {
                log("购买确认 → 点击")
                clickAbs(buyFound.first, buyFound.second)
                buyCount++
                delay(1000)
            } else {
                click(DeviceConfig.NAV_BACK)
                delay(500)
            }
        }
        
        log("✅ 信用商店完成，购买 $buyCount 件")
        
        click(DeviceConfig.NAV_BACK)
        delay(1500)
        click(DeviceConfig.MAIN_TERMINAL)
        delay(2000)
        
        log("✅ 信用商店模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.CREDIT_SHOP))
    }
    
    private fun runBattle17Module() {
        currentState = DailyState.BATTLE_1_7
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 刷1-7（请在终端界面） ===")
        
        click(DeviceConfig.TERM_THEME_TAB)
        delay(2000)
        click(DeviceConfig.DARK_AGE_BELOW)
        delay(2000)
        click(DeviceConfig.GO_TO_CHAPTER)
        delay(2000)
        
        // 单次战斗
        click(DeviceConfig.STAGE_1_7)
        delay(1500)
        
        val startBlue = waitForColorInArea(
            DeviceConfig.BATTLE_START_BLUE_AREA, DeviceConfig.COLOR_BATTLE_START_BLUE, timeoutMs = 10000)
        if (startBlue != null) {
            clickAbs(startBlue.first, startBlue.second)
        } else {
            click(DeviceConfig.BATTLE_START_BLUE_AREA.let {
                DeviceConfig.PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
        }
        delay(2000)
        
        val startOrange = waitForColorInArea(
            DeviceConfig.BATTLE_START_ORANGE_AREA, DeviceConfig.COLOR_BATTLE_START_ORANGE, timeoutMs = 10000)
        if (startOrange != null) {
            clickAbs(startOrange.first, startOrange.second)
        } else {
            click(DeviceConfig.BATTLE_START_ORANGE_AREA.let {
                DeviceConfig.PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
        }
        delay(2000)
        
        log("等待战斗结束...")
        delay(BATTLE_WAIT_SEC * 1000L)
        
        val battleStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - battleStart < 300000 && isRunning) {
            checkPaused()
            val endFound = checkColorInArea(DeviceConfig.BATTLE_END_AREA, DeviceConfig.COLOR_BATTLE_END_WHITE)
            if (endFound != null) {
                log("✅ 战斗结束")
                delay(1000)
                click(DeviceConfig.BATTLE_CONTINUE)
                delay(2000)
                break
            }
            delay(BATTLE_CHECK_INTERVAL)
        }
        
        log("✅ 刷1-7模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.BATTLE_1_7))
    }
    
    private fun runMissionModule() {
        currentState = DailyState.MISSION
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 领任务（请在主界面） ===")
        
        click(DeviceConfig.MISSION_MODULE)
        delay(2000)
        
        // 日常收集×2
        val collect1 = checkColorInArea(DeviceConfig.COLLECT_ALL_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collect1 != null) {
            log("日常收集全部 → 点击2下")
            clickAbs(collect1.first, collect1.second)
            delay(1000)
            clickAbs(collect1.first, collect1.second)
            delay(1500)
        }
        
        // 切周常
        click(DeviceConfig.WEEKLY_TAB)
        delay(1500)
        
        // 周常收集×2
        val collect2 = checkColorInArea(DeviceConfig.COLLECT_ALL_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collect2 != null) {
            log("周常收集全部 → 点击2下")
            clickAbs(collect2.first, collect2.second)
            delay(1000)
            clickAbs(collect2.first, collect2.second)
            delay(1500)
        }
        
        click(DeviceConfig.NAV_BACK)
        delay(1000)
        
        log("✅ 任务模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.MISSION))
    }
    
    fun stop() {
        isRunning = false
        isPaused = false
        runnerThread?.interrupt()
        runnerThread = null
        log("⛔ 已停止")
    }
    
    fun togglePause() {
        isPaused = !isPaused
        log(if (isPaused) "⏸️ 已暂停" else "▶️ 已继续")
    }
}
