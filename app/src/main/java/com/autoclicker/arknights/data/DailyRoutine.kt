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
 * 完整流程: 进游戏 → 清弹窗 → 基建收菜 → 好友访问 → 公招 → 信用商店 → 刷1-7 → 领任务
 * 线索交流暂不实现
 */
class DailyRoutine(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val TAG = "DailyRoutine"
        private const val MAX_FRIEND_VISITS = 12
        private const val MAX_RECRUIT_SLOTS = 4
        private const val MAX_CREDIT_BUYS = 6
        private const val MAX_POPUP_CLOSES = 10
    }
    
    // ============ 状态定义 ============
    
    enum class DailyState {
        WAIT_START,       // 等START黄字出现
        WAIT_WAKE,        // 等开始唤醒灰按钮出现
        CLEAR_POPUPS,     // 清活动弹窗
        MAIN_MENU,        // 主界面（OCR识别）
        BASE_COLLECT,     // 基建收取
        FRIEND_VISIT,     // 好友访问
        RECRUIT,          // 公招
        CREDIT_SHOP,      // 信用商店
        BATTLE_1_7,       // 刷1-7
        BATTLE_RESULT,    // 战斗结算
        MISSION,          // 领任务
        DONE              // 完成
    }
    
    // ============ 测试模块定义 ============
    
    enum class TestModule {
        ENTER_GAME,       // 进游戏
        CLEAR_POPUPS,     // 关弹窗
        BASE_COLLECT,     // 清基建
        FRIEND_VISIT,     // 好友访问
        RECRUIT,          // 公招
        CREDIT_SHOP,      // 信用商店
        BATTLE_1_7,       // 刷1-7
        MISSION           // 清任务
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
    
    private var runnerThread: Thread? = null
    
    // 回调
    var onStateChanged: ((DailyState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onAction: ((TestAction) -> Unit)? = null
    
    // ============ 工具方法 ============
    
    /** 百分比坐标转绝对坐标 */
    private fun Pct2Abs(coord: DeviceConfig.PctCoord): Pair<Int, Int> {
        return coord.toAbs(screenWidth, screenHeight)
    }
    
    /** 百分比矩形转绝对Rect */
    private fun Pct2Abs(rect: DeviceConfig.PctRect): android.graphics.Rect {
        return rect.toAbs(screenWidth, screenHeight)
    }
    
    /** 截图（带失败日志） */
    private fun screenshot(): Bitmap? {
        val bmp = ScreenshotHelper.captureScreen(service)
        if (bmp == null) {
            Log.w(TAG, "截图返回null，可能缺少截图权限")
        }
        return bmp
    }
    
    /** 点击（百分比坐标） */
    private fun click(coord: DeviceConfig.PctCoord) {
        val (x, y) = Pct2Abs(coord)
        log("点击 (${coord.xPct}%, ${coord.yPct}%) → ($x, $y)")
        onAction?.invoke(TestAction.Click(x, y))
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    /** 点击（绝对坐标） */
    private fun clickAbs(x: Int, y: Int) {
        log("点击 ($x, $y)")
        onAction?.invoke(TestAction.Click(x, y))
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    /** 等待毫秒（带UI回调） */
    private fun delay(ms: Long) {
        if (ms >= 1000) {
            onAction?.invoke(TestAction.Wait(ms / 1000f))
        }
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
    
    /** 检查暂停 */
    private fun checkPaused() {
        while (isPaused && isRunning) {
            Thread.sleep(200)
        }
    }
    
    /** 识色验证（单点，使用 ColorRule） */
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
    
    /** 区域搜索满足颜色规则的像素质心（解决偏左上问题） */
    private fun searchColorCentroid(bmp: Bitmap, rect: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val absRect = Pct2Abs(rect)
        return ScreenshotHelper.searchPixelCentroid(
            bmp, absRect.left, absRect.top, absRect.right, absRect.bottom,
            rule.checkR, rule.checkG, rule.checkB
        )
    }
    
    /** 等待识色条件满足（轮询截图直到颜色匹配） */
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
                    onAction?.invoke(TestAction.Wait(0f))
                } else if (screenshotFails % 10 == 0) {
                    log("⚠️ 截图持续失败 ($screenshotFails/$screenshotAttempts)")
                    onAction?.invoke(TestAction.Wait(0f))
                }
                delay(intervalMs)
                continue
            }
            // 先快速检查是否有匹配
            val quickFound = searchColor(bmp, area, rule)
            if (quickFound != null) {
                // 找到了！计算质心获取精确点击位置
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
        log("❌ ${rule.name} 区域搜索超时 ${timeoutMs}ms (截图${screenshotAttempts}次, 失败${screenshotFails}次)")
        return null
    }
    
    /** 单次截图检查区域颜色，返回质心坐标或null（不轮询） */
    private fun checkColorInArea(area: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val bmp = screenshot() ?: return null
        val result = searchColorCentroid(bmp, area, rule)
        bmp.recycle()
        return result
    }
    
    /** 日志 */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }
    
    // ============ 导航辅助 ============
    
    /** 从主界面进入基建 */
    private fun navigateToBase(): Boolean {
        log("导航: 点击基建tab")
        click(DeviceConfig.NAV_BASE)
        delay(2000)
        // 简单验证：检查收取按钮区域是否有橙色或灰色
        val bmp = screenshot()
        if (bmp != null) {
            val isOrange = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_ORANGE)
            val isGray = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_GRAY)
            bmp.recycle()
            if (isOrange || isGray) {
                log("✅ 已进入基建")
                return true
            }
        }
        log("⚠️ 未能确认进入基建，继续尝试")
        return true // 即使验证失败也继续
    }
    
    /** 从子界面返回基建总览 */
    private fun returnToBase(): Boolean {
        log("导航: 返回基建总览")
        click(DeviceConfig.BACK_BASE)
        delay(1500)
        return true
    }
    
    /** 确认已在主界面（简略版，后续加OCR） */
    private fun confirmMainMenu(): Boolean {
        delay(2000)
        log("✅ 主界面已到达（临时识别，后续加OCR）")
        return true
    }
    
    // ============ 状态处理 ============
    
    /**
     * ① 等待START黄字
     * - 搜索START菱形区域，找到后点击中心
     * - 下一状态: WAIT_WAKE
     */
    private fun handleWaitStart(): DailyState {
        log("=== 等待START界面 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_START))
        val startFound = waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)
        if (startFound == null) {
            onError?.invoke("等待START超时，请确认游戏已启动")
            onAction?.invoke(TestAction.Error("等待START超时"))
            return DailyState.DONE
        }
        delay(300)
        // 用质心坐标点击START
        clickAbs(startFound.first, startFound.second)
        delay(2000)
        return DailyState.WAIT_WAKE
    }
    
    /**
     * ② 等待开始唤醒灰按钮
     * - 区域搜索灰按钮，用质心坐标点击
     * - 兜底: 超时后点击固定位置
     * - 下一状态: CLEAR_POPUPS
     */
    private fun handleWaitWake(): DailyState {
        log("=== 等待开始唤醒 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_WAKE))
        
        val wakeFound = waitForColorInArea(DeviceConfig.WAKE_SEARCH_AREA, DeviceConfig.COLOR_WAKE_GRAY, timeoutMs = 25000)
        if (wakeFound != null) {
            log("✅ 开始唤醒灰按钮搜索成功，点击质心 (${wakeFound.first}, ${wakeFound.second})")
            delay(300)
            clickAbs(wakeFound.first, wakeFound.second)
            delay(2000)
            return DailyState.CLEAR_POPUPS
        }
        
        log("⚠️ 开始唤醒搜索超时，兜底点击固定位置")
        click(DeviceConfig.WAKE_CLICK)
        delay(2000)
        return DailyState.CLEAR_POPUPS
    }
    
    /**
     * ③ 清活动弹窗
     * - 循环点击右上×和底部确认，最多10次
     * - 下一状态: MAIN_MENU
     */
    private fun handleClearPopups(): DailyState {
        log("=== 清理活动弹窗 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CLEAR_POPUPS))
        
        delay(3000)
        
        for (i in 1..MAX_POPUP_CLOSES) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            // 交替点击右上×和底部确认（处理不同类型弹窗）
            click(DeviceConfig.POPUP_CLOSE)
            delay(500)
            click(DeviceConfig.POPUP_CONFIRM)
            delay(800)
        }
        
        return DailyState.MAIN_MENU
    }
    
    /**
     * ④ 主界面确认
     * - TODO: OCR识别底部导航栏
     * - 下一状态: BASE_COLLECT
     */
    private fun handleMainMenu(): DailyState {
        log("=== 主界面确认 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.MAIN_MENU))
        confirmMainMenu()
        return DailyState.BASE_COLLECT
    }
    
    /**
     * ⑤ 基建收取
     * - 进入基建 → 检查收取按钮颜色 → 橙色=5连点收取 / 灰色=已收跳过
     * - 完成后留在基建，下一模块从这里开始
     * - 下一状态: FRIEND_VISIT
     */
    private fun handleBaseCollect(): DailyState {
        log("=== 基建收取 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.BASE_COLLECT))
        
        // 进入基建
        navigateToBase()
        delay(1500)
        
        // 检查收取按钮：橙色=可收取，灰色=已收完
        val bmp = screenshot()
        if (bmp != null) {
            val isOrange = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_ORANGE)
            val isGray = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_GRAY)
            bmp.recycle()
            
            if (isOrange) {
                log("✅ 基建有可收取资源，5连点收取")
                for (i in 1..5) {
                    if (!isRunning) return DailyState.DONE
                    click(DeviceConfig.BASE_COLLECT_CLICK)
                    delay(1000)
                }
                log("✅ 基建收取完成")
            } else if (isGray) {
                log("✅ 基建已收完，跳过")
            } else {
                log("⚠️ 基建收取状态不确定，尝试收取")
                for (i in 1..5) {
                    if (!isRunning) return DailyState.DONE
                    click(DeviceConfig.BASE_COLLECT_CLICK)
                    delay(1000)
                }
            }
        } else {
            log("⚠️ 截图失败，跳过基建收取")
        }
        
        return DailyState.FRIEND_VISIT
    }
    
    /**
     * ⑥ 好友访问（基建访问，非线索交流）
     * - 在基建内点击好友访问入口 → 循环搜索"访问下位"橙按钮 → 点击 → 重复
     * - 访问完毕后返回基建总览
     * - 下一状态: RECRUIT
     */
    private fun handleFriendVisit(): DailyState {
        log("=== 好友访问 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.FRIEND_VISIT))
        
        // 点击好友访问入口
        click(DeviceConfig.BASE_VISIT_ENTRY)
        delay(2000)
        
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
                delay(2000) // 等访问动画
            } else {
                log("✅ 访问下位按钮未找到，已访问 $visitCount 位好友")
                break
            }
        }
        
        if (visitCount == 0) {
            log("⚠️ 未能访问任何好友，可能入口坐标不正确")
        }
        
        // 返回基建总览
        returnToBase()
        return DailyState.RECRUIT
    }
    
    /**
     * ⑦ 公招
     * - 在基建内点击公招入口 → 搜索蓝色按钮 → 空槽设9h / 完成槽领取
     * - 处理完后关闭公招界面，返回基建总览
     * - 下一状态: CREDIT_SHOP
     */
    private fun handleRecruit(): DailyState {
        log("=== 公招 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.RECRUIT))
        
        // 点击公招入口
        click(DeviceConfig.RECRUIT_ENTRY)
        delay(2000)
        
        var handledSlots = 0
        
        // 循环处理槽位（最多处理4个）
        for (round in 1..MAX_RECRUIT_SLOTS) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            // 先搜索"聘用候选人"蓝按钮（优先领取已完成的）
            val fullFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_FULL)
            if (fullFound != null) {
                log("✅ 发现已完成公招 → 点击领取 (${fullFound.first}, ${fullFound.second})")
                clickAbs(fullFound.first, fullFound.second)
                delay(2000) // 等领取动画
                // 点击SKIP或关闭
                click(DeviceConfig.RECRUIT_SKIP)
                delay(1500)
                handledSlots++
                continue
            }
            
            // 再搜索空槽位蓝+按钮
            val emptyFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_EMPTY)
            if (emptyFound != null) {
                log("✅ 发现空槽位 → 点击开始招募 (${emptyFound.first}, ${emptyFound.second})")
                clickAbs(emptyFound.first, emptyFound.second)
                delay(1500) // 等设置界面
                
                // 设9h
                click(DeviceConfig.RECRUIT_9H_BTN)
                delay(500)
                
                // 点确认
                click(DeviceConfig.RECRUIT_CONFIRM)
                delay(1500)
                handledSlots++
                continue
            }
            
            // 两种都没找到，说明所有槽位都在进行中
            log("✅ 无可处理的公招槽位（都在进行中或已满）")
            break
        }
        
        if (handledSlots > 0) {
            log("✅ 公招处理完成，共处理 $handledSlots 个槽位")
        } else {
            log("✅ 公招无需处理")
        }
        
        // 关闭公招界面
        click(DeviceConfig.RECRUIT_CLOSE)
        delay(1000)
        
        // 返回基建总览
        returnToBase()
        return DailyState.CREDIT_SHOP
    }
    
    /**
     * ⑧ 信用商店
     * - 在基建内点击信用交易所入口 → 收取信用 → 购买物品
     * - 完成后返回基建总览
     * - 下一状态: BATTLE_1_7
     */
    private fun handleCreditShop(): DailyState {
        log("=== 信用商店 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CREDIT_SHOP))
        
        // 点击信用交易所入口
        click(DeviceConfig.BASE_CREDIT_ENTRY)
        delay(2000)
        
        // 搜索"收取信用"橙色按钮（右上角）
        val collectFound = checkColorInArea(DeviceConfig.CREDIT_COLLECT_AREA, DeviceConfig.COLOR_CREDIT_COLLECT)
        if (collectFound != null) {
            log("✅ 找到收取信用按钮 → 点击 (${collectFound.first}, ${collectFound.second})")
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
            
            // 点击商品
            click(itemCoord)
            delay(800)
            
            // 搜索"购买物品"橙色确认按钮
            val buyFound = checkColorInArea(
                DeviceConfig.PctRect(60f, 70f, 85f, 90f),
                DeviceConfig.COLOR_CREDIT_BUY
            )
            if (buyFound != null) {
                log("✅ 购买确认 → 点击 (${buyFound.first}, ${buyFound.second})")
                clickAbs(buyFound.first, buyFound.second)
                buyCount++
                delay(1000)
            } else {
                // 可能是售罄，关闭弹窗
                log("⚠️ 未找到购买确认，可能已售罄")
                click(DeviceConfig.POPUP_CLOSE)
                delay(500)
            }
        }
        
        log("✅ 信用商店完成，购买 $buyCount 件物品")
        
        // 返回基建总览
        returnToBase()
        delay(1000)
        
        // 退出基建到主界面（点击终端tab，同时准备下一步）
        log("导航: 退出基建")
        // 返回主界面需要再点一次返回
        click(DeviceConfig.BACK_BASE)
        delay(1500)
        
        return DailyState.BATTLE_1_7
    }
    
    /**
     * ⑨ 刷1-7
     * - 从主界面点击终端tab → 导航到1-7 → 开始行动(蓝) → 编队确认(橙)
     * - 下一状态: BATTLE_RESULT
     */
    private fun handleBattle17(): DailyState {
        log("=== 刷1-7 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.BATTLE_1_7))
        
        // 点击终端tab
        click(DeviceConfig.NAV_TERMINAL)
        delay(2000)
        
        // TODO: 导航到1-7关卡（当前用简化方案）
        // 简化方案：假设终端界面显示上次关卡或可直接选择1-7
        // 点击1-7位置
        click(DeviceConfig.BATTLE_1_7_POS)
        delay(1500)
        
        // 搜索"开始行动"蓝色按钮
        val startBlue = waitForColorInArea(
            DeviceConfig.BATTLE_START_BLUE_AREA,
            DeviceConfig.COLOR_BATTLE_START_BLUE,
            timeoutMs = 10000
        )
        if (startBlue != null) {
            log("✅ 找到开始行动(蓝) → 点击 (${startBlue.first}, ${startBlue.second})")
            clickAbs(startBlue.first, startBlue.second)
            delay(2000)
        } else {
            log("⚠️ 未找到开始行动蓝按钮，尝试固定位置点击")
            click(DeviceConfig.BATTLE_START_BLUE_AREA.let { 
                PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
            delay(2000)
        }
        
        // 搜索"开始行动"橙色按钮（编队界面）
        val startOrange = waitForColorInArea(
            DeviceConfig.BATTLE_START_ORANGE_AREA,
            DeviceConfig.COLOR_BATTLE_START_ORANGE,
            timeoutMs = 10000
        )
        if (startOrange != null) {
            log("✅ 找到开始行动(橙) → 点击 (${startOrange.first}, ${startOrange.second})")
            clickAbs(startOrange.first, startOrange.second)
            delay(2000)
        } else {
            log("⚠️ 未找到开始行动橙按钮，尝试固定位置点击")
            click(DeviceConfig.BATTLE_START_ORANGE_AREA.let {
                PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
            delay(2000)
        }
        
        return DailyState.BATTLE_RESULT
    }
    
    /**
     * ⑩ 战斗结算
     * - 等待"OPERATION"白字出现 → 点击继续 → 返回主界面
     * - 下一状态: MISSION
     */
    private fun handleBattleResult(): DailyState {
        log("=== 等待战斗结算 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.BATTLE_RESULT))
        
        // 等待行动结束白字（最长等5分钟，1-7一般很快）
        val endFound = waitForColorInArea(
            DeviceConfig.BATTLE_END_SEARCH,
            DeviceConfig.COLOR_BATTLE_END_WHITE,
            timeoutMs = 300000
        )
        
        if (endFound != null) {
            log("✅ 检测到行动结束")
            delay(1000)
            // 点击继续
            click(DeviceConfig.BATTLE_CONTINUE)
            delay(2000)
        } else {
            log("⚠️ 等待战斗结算超时，尝试继续")
            click(DeviceConfig.BATTLE_CONTINUE)
            delay(2000)
        }
        
        return DailyState.MISSION
    }
    
    /**
     * ⑪ 领任务
     * - 点击任务tab → 搜索"收集全部"蓝按钮 → 点击
     * - 下一状态: DONE
     */
    private fun handleMission(): DailyState {
        log("=== 领任务 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.MISSION))
        
        // 点击任务tab
        click(DeviceConfig.NAV_MISSION)
        delay(2000)
        
        // 搜索"收集全部"蓝色按钮
        val collectFound = checkColorInArea(DeviceConfig.MISSION_COLLECT_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collectFound != null) {
            log("✅ 找到收集全部按钮 → 点击 (${collectFound.first}, ${collectFound.second})")
            clickAbs(collectFound.first, collectFound.second)
            delay(1500)
        } else {
            log("⚠️ 未找到收集全部按钮，可能已领取")
        }
        
        log("✅ 任务模块完成")
        return DailyState.DONE
    }
    
    // ============ 公开接口 ============
    
    /**
     * 启动一键肝舟（完整流程）
     */
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
                        DailyState.MAIN_MENU -> handleMainMenu()
                        DailyState.BASE_COLLECT -> handleBaseCollect()
                        DailyState.FRIEND_VISIT -> handleFriendVisit()
                        DailyState.RECRUIT -> handleRecruit()
                        DailyState.CREDIT_SHOP -> handleCreditShop()
                        DailyState.BATTLE_1_7 -> handleBattle17()
                        DailyState.BATTLE_RESULT -> handleBattleResult()
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
    
    /**
     * 启动单个测试模块
     */
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
    
    /** 进游戏模块: WAIT_START → WAIT_WAKE */
    private fun runEnterGameModule() {
        currentState = DailyState.WAIT_START
        onAction?.invoke(TestAction.StateChanged(currentState))
        
        log("=== [测试] 等待START界面 ===")
        val startCoord = waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)
        if (startCoord == null) {
            onError?.invoke("等待START超时")
            onAction?.invoke(TestAction.Error("等待START超时"))
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
            log("✅ 开始唤醒搜索成功，点击质心 (${wakeCoord.first}, ${wakeCoord.second})")
            delay(300)
            clickAbs(wakeCoord.first, wakeCoord.second)
        } else {
            log("⚠️ 开始唤醒搜索超时，兜底点击")
            click(DeviceConfig.WAKE_CLICK)
        }
        delay(2000)
        
        log("✅ 进游戏模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
    }
    
    /** 关弹窗模块 */
    private fun runClearPopupsModule() {
        currentState = DailyState.CLEAR_POPUPS
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 清理活动弹窗 ===")
        
        delay(1000)
        var closedCount = 0
        for (i in 1..MAX_POPUP_CLOSES) {
            if (!isRunning) break
            checkPaused()
            click(DeviceConfig.POPUP_CLOSE)
            closedCount++
            delay(500)
            click(DeviceConfig.POPUP_CONFIRM)
            delay(800)
        }
        
        log("✅ 关弹窗模块完成，共关闭 $closedCount 个弹窗")
        onAction?.invoke(TestAction.ModuleDone(TestModule.CLEAR_POPUPS))
    }
    
    /** 基建收取模块: 从主界面进基建 → 收取 → 停在基建 */
    private fun runBaseCollectModule() {
        currentState = DailyState.BASE_COLLECT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 基建收取 ===")
        
        // 尝试进入基建
        navigateToBase()
        delay(1500)
        
        // 检查收取按钮
        val bmp = screenshot()
        if (bmp != null) {
            val isOrange = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_ORANGE)
            val isGray = checkColor(bmp, DeviceConfig.BASE_COLLECT_CHECK, DeviceConfig.COLOR_COLLECT_GRAY)
            bmp.recycle()
            
            if (isOrange) {
                log("✅ 基建有可收取资源")
                for (i in 1..5) {
                    if (!isRunning) break
                    click(DeviceConfig.BASE_COLLECT_CLICK)
                    delay(1000)
                }
            } else if (isGray) {
                log("✅ 基建已收完")
            } else {
                log("⚠️ 收取状态不确定，尝试收取")
                for (i in 1..5) {
                    if (!isRunning) break
                    click(DeviceConfig.BASE_COLLECT_CLICK)
                    delay(1000)
                }
            }
        }
        
        log("✅ 基建收取模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.BASE_COLLECT))
    }
    
    /** 好友访问模块: 假设已在基建 → 访问 → 返回基建 */
    private fun runFriendVisitModule() {
        currentState = DailyState.FRIEND_VISIT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 好友访问（请先进入基建界面） ===")
        
        // 点击好友访问入口
        click(DeviceConfig.BASE_VISIT_ENTRY)
        delay(2000)
        
        var visitCount = 0
        for (i in 1..MAX_FRIEND_VISITS) {
            if (!isRunning) break
            checkPaused()
            
            val found = checkColorInArea(DeviceConfig.VISIT_NEXT_AREA, DeviceConfig.COLOR_VISIT_NEXT)
            if (found != null) {
                log("访问下位 → 点击 (${found.first}, ${found.second})")
                clickAbs(found.first, found.second)
                visitCount++
                delay(2000)
            } else {
                break
            }
        }
        
        log("✅ 好友访问模块完成，共访问 $visitCount 位")
        returnToBase()
        onAction?.invoke(TestAction.ModuleDone(TestModule.FRIEND_VISIT))
    }
    
    /** 公招模块: 假设已在基建 → 公招 → 返回基建 */
    private fun runRecruitModule() {
        currentState = DailyState.RECRUIT
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 公招（请先进入基建界面） ===")
        
        click(DeviceConfig.RECRUIT_ENTRY)
        delay(2000)
        
        var handledSlots = 0
        for (round in 1..MAX_RECRUIT_SLOTS) {
            if (!isRunning) break
            checkPaused()
            
            // 优先领取已完成的
            val fullFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_FULL)
            if (fullFound != null) {
                log("领取已完成公招 → 点击 (${fullFound.first}, ${fullFound.second})")
                clickAbs(fullFound.first, fullFound.second)
                delay(2000)
                click(DeviceConfig.RECRUIT_SKIP)
                delay(1500)
                handledSlots++
                continue
            }
            
            // 搜索空槽位
            val emptyFound = checkColorInArea(DeviceConfig.RECRUIT_SLOT_AREA, DeviceConfig.COLOR_RECRUIT_EMPTY)
            if (emptyFound != null) {
                log("开始招募 → 点击 (${emptyFound.first}, ${emptyFound.second})")
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
        
        log("✅ 公招模块完成，处理 $handledSlots 个槽位")
        click(DeviceConfig.RECRUIT_CLOSE)
        delay(1000)
        returnToBase()
        onAction?.invoke(TestAction.ModuleDone(TestModule.RECRUIT))
    }
    
    /** 信用商店模块: 假设已在基建 → 信用 → 返回基建 */
    private fun runCreditShopModule() {
        currentState = DailyState.CREDIT_SHOP
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 信用商店（请先进入基建界面） ===")
        
        click(DeviceConfig.BASE_CREDIT_ENTRY)
        delay(2000)
        
        // 收取信用
        val collectFound = checkColorInArea(DeviceConfig.CREDIT_COLLECT_AREA, DeviceConfig.COLOR_CREDIT_COLLECT)
        if (collectFound != null) {
            log("收取信用 → 点击 (${collectFound.first}, ${collectFound.second})")
            clickAbs(collectFound.first, collectFound.second)
            delay(1500)
        }
        
        // 购买物品
        var buyCount = 0
        val allItems = DeviceConfig.CREDIT_ITEMS + DeviceConfig.CREDIT_ITEMS_ROW2
        for (itemCoord in allItems) {
            if (!isRunning) break
            if (buyCount >= MAX_CREDIT_BUYS) break
            
            click(itemCoord)
            delay(800)
            
            val buyFound = checkColorInArea(
                DeviceConfig.PctRect(60f, 70f, 85f, 90f),
                DeviceConfig.COLOR_CREDIT_BUY
            )
            if (buyFound != null) {
                log("购买确认 → 点击 (${buyFound.first}, ${buyFound.second})")
                clickAbs(buyFound.first, buyFound.second)
                buyCount++
                delay(1000)
            } else {
                click(DeviceConfig.POPUP_CLOSE)
                delay(500)
            }
        }
        
        log("✅ 信用商店模块完成，购买 $buyCount 件")
        returnToBase()
        onAction?.invoke(TestAction.ModuleDone(TestModule.CREDIT_SHOP))
    }
    
    /** 刷1-7模块: 假设已在主界面 → 终端 → 1-7 → 战斗 */
    private fun runBattle17Module() {
        currentState = DailyState.BATTLE_1_7
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 刷1-7（请先回到主界面） ===")
        
        click(DeviceConfig.NAV_TERMINAL)
        delay(2000)
        
        // 点击1-7位置
        click(DeviceConfig.BATTLE_1_7_POS)
        delay(1500)
        
        // 开始行动(蓝)
        val startBlue = waitForColorInArea(
            DeviceConfig.BATTLE_START_BLUE_AREA,
            DeviceConfig.COLOR_BATTLE_START_BLUE,
            timeoutMs = 10000
        )
        if (startBlue != null) {
            clickAbs(startBlue.first, startBlue.second)
            delay(2000)
        } else {
            click(DeviceConfig.BATTLE_START_BLUE_AREA.let {
                PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
            delay(2000)
        }
        
        // 开始行动(橙)
        val startOrange = waitForColorInArea(
            DeviceConfig.BATTLE_START_ORANGE_AREA,
            DeviceConfig.COLOR_BATTLE_START_ORANGE,
            timeoutMs = 10000
        )
        if (startOrange != null) {
            clickAbs(startOrange.first, startOrange.second)
            delay(2000)
        } else {
            click(DeviceConfig.BATTLE_START_ORANGE_AREA.let {
                PctCoord((it.leftPct + it.rightPct) / 2, (it.topPct + it.bottomPct) / 2)
            })
            delay(2000)
        }
        
        // 等战斗结束
        log("等待战斗结束...")
        val endFound = waitForColorInArea(
            DeviceConfig.BATTLE_END_SEARCH,
            DeviceConfig.COLOR_BATTLE_END_WHITE,
            timeoutMs = 300000
        )
        if (endFound != null) {
            log("✅ 战斗结束")
            delay(1000)
            click(DeviceConfig.BATTLE_CONTINUE)
            delay(2000)
        }
        
        log("✅ 刷1-7模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.BATTLE_1_7))
    }
    
    /** 任务模块: 假设已在主界面 → 任务 → 收集 */
    private fun runMissionModule() {
        currentState = DailyState.MISSION
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 领任务（请先回到主界面） ===")
        
        click(DeviceConfig.NAV_MISSION)
        delay(2000)
        
        val collectFound = checkColorInArea(DeviceConfig.MISSION_COLLECT_AREA, DeviceConfig.COLOR_MISSION_BLUE)
        if (collectFound != null) {
            log("收集全部 → 点击 (${collectFound.first}, ${collectFound.second})")
            clickAbs(collectFound.first, collectFound.second)
            delay(1500)
        } else {
            log("⚠️ 未找到收集全部按钮")
        }
        
        log("✅ 任务模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.MISSION))
    }
    
    /**
     * 停止
     */
    fun stop() {
        isRunning = false
        isPaused = false
        runnerThread?.interrupt()
        runnerThread = null
        log("⛔ 已停止")
    }
    
    /**
     * 暂停/继续
     */
    fun togglePause() {
        isPaused = !isPaused
        log(if (isPaused) "⏸️ 已暂停" else "▶️ 已继续")
    }
}
