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
 * 当前实现: 进游戏部分 (WAIT_START → WAIT_WAKE → CLEAR_POPUPS → MAIN_MENU)
 * 后续逐步添加: 基建、好友、公招、信用、战斗、任务
 */
class DailyRoutine(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val TAG = "DailyRoutine"
    }
    
    // ============ 状态定义 ============
    
    enum class DailyState {
        WAIT_START,       // 等START黄字出现
        WAIT_WAKE,        // 等开始唤醒灰按钮出现
        CLEAR_POPUPS,     // 清活动弹窗
        MAIN_MENU,        // 主界面（OCR识别）
        // 以下待实现
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
        FRIEND_VISIT,     // 好友线索交流
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
    
    // 测试模式回调
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
    
    /** 区域搜索满足颜色规则的像素 */
    private fun searchColor(bmp: Bitmap, rect: DeviceConfig.PctRect, rule: DeviceConfig.ColorRule): Pair<Int, Int>? {
        val absRect = Pct2Abs(rect)
        return ScreenshotHelper.searchPixel(
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
                log("✅ ${rule.name} 识别成功 (${coord.xPct}%, ${coord.yPct}%)")
                onAction?.invoke(TestAction.Recognize(rule.name))
                return true
            }
            delay(intervalMs)
        }
        log("❌ ${rule.name} 等待超时 ${timeoutMs}ms")
        return false
    }
    
    /** 在区域内搜索颜色（轮询截图直到找到） */
    private fun waitForColorInArea(
        area: DeviceConfig.PctRect,
        rule: DeviceConfig.ColorRule,
        timeoutMs: Long = 30000,
        intervalMs: Long = 500
    ): Boolean {
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
                    onAction?.invoke(TestAction.Wait(0f))  // 触发状态栏更新
                } else if (screenshotFails % 10 == 0) {
                    log("⚠️ 截图持续失败 ($screenshotFails/$screenshotAttempts)")
                    onAction?.invoke(TestAction.Wait(0f))
                }
                delay(intervalMs)
                continue
            }
            val found = searchColor(bmp, area, rule)
            bmp.recycle()
            if (found != null) {
                log("✅ ${rule.name} 区域搜索成功 (${area.leftPct}-${area.rightPct}%, ${area.topPct}-${area.bottomPct}%) → (${found.first}, ${found.second})")
                onAction?.invoke(TestAction.Recognize(rule.name))
                return true
            }
            delay(intervalMs)
        }
        log("❌ ${rule.name} 区域搜索超时 ${timeoutMs}ms (截图${screenshotAttempts}次, 失败${screenshotFails}次)")
        return false
    }
    
    /** 日志 */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }
    
    // ============ 状态处理 ============
    
    /**
     * ① 等待START黄字
     * - 条件: (50%, 90%) R>220, G>180, B<80
     * - 操作: 点击START位置
     * - 下一状态: WAIT_WAKE
     */
    private fun handleWaitStart(): DailyState {
        log("=== 等待START界面 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_START))
        if (!waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)) {
            onError?.invoke("等待START超时，请确认游戏已启动")
            onAction?.invoke(TestAction.Error("等待START超时，请确认游戏已启动"))
            return DailyState.DONE
        }
        delay(300)
        click(DeviceConfig.START_CLICK)
        delay(2000)
        return DailyState.WAIT_WAKE
    }
    
    /**
     * ② 等待开始唤醒灰按钮
     * - 主方案: 区域搜索灰按钮 (类似START搜索)
     * - 备用: 固定点识色 (宽松条件: gray OR lightBg)
     * - 兜底: 超时后直接点击
     * - 下一状态: CLEAR_POPUPS
     */
    private fun handleWaitWake(): DailyState {
        log("=== 等待开始唤醒 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.WAIT_WAKE))
        
        // 主方案: 区域搜索灰按钮
        if (waitForColorInArea(DeviceConfig.WAKE_SEARCH_AREA, DeviceConfig.COLOR_WAKE_GRAY, timeoutMs = 20000)) {
            log("✅ 开始唤醒灰按钮区域搜索成功")
            delay(300)
            click(DeviceConfig.WAKE_CLICK)
            delay(2000)
            return DailyState.CLEAR_POPUPS
        }
        
        // 备用: 固定点识色 + 诊断日志
        log("⚠️ 区域搜索超时，尝试固定点识色...")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 8000L && isRunning) {
            checkPaused()
            val bmp = screenshot() ?: continue
            
            val isGray = checkColor(bmp, DeviceConfig.WAKE_CHECK, DeviceConfig.COLOR_WAKE_GRAY)
            val isLightBg = checkColor(bmp, DeviceConfig.WAKE_ASSIST, DeviceConfig.COLOR_WAKE_BG_LIGHT)
            
            // 诊断: 记录实际颜色值
            val (cx, cy) = Pct2Abs(DeviceConfig.WAKE_CHECK)
            val (ax, ay) = Pct2Abs(DeviceConfig.WAKE_ASSIST)
            if (cx in 0 until bmp.width && cy in 0 until bmp.height) {
                val cp = bmp.getPixel(cx, cy)
                log("🔍 WAKE_CHECK(${cx},${cy}): R=${android.graphics.Color.red(cp)} G=${android.graphics.Color.green(cp)} B=${android.graphics.Color.blue(cp)} gray=$isGray")
            }
            if (ax in 0 until bmp.width && ay in 0 until bmp.height) {
                val ap = bmp.getPixel(ax, ay)
                log("🔍 WAKE_ASSIST(${ax},${ay}): R=${android.graphics.Color.red(ap)} G=${android.graphics.Color.green(ap)} B=${android.graphics.Color.blue(ap)} light=$isLightBg")
            }
            
            bmp.recycle()
            
            if (isGray || isLightBg) {
                log("✅ 固定点识色命中 (gray=$isGray, lightBg=$isLightBg)")
                delay(300)
                click(DeviceConfig.WAKE_CLICK)
                delay(2000)
                return DailyState.CLEAR_POPUPS
            }
            delay(500)
        }
        
        // 兜底: 直接点击（按钮可能在那里但识色不准）
        log("⚠️ 开始唤醒超时，兜底点击")
        click(DeviceConfig.WAKE_CLICK)
        delay(2000)
        return DailyState.CLEAR_POPUPS
    }
    
    /**
     * ③ 清活动弹窗
     * - 临时方案: 循环点击右上角关闭区(96%, 4%)，最多8次
     * - 后续优化: OCR识别弹窗×按钮位置
     * - 下一状态: MAIN_MENU
     */
    private fun handleClearPopups(): DailyState {
        log("=== 清理活动弹窗 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.CLEAR_POPUPS))
        
        // 等一下让弹窗加载
        delay(3000)
        
        for (i in 1..8) {
            if (!isRunning) return DailyState.DONE
            checkPaused()
            
            // 截图看看当前画面，如果已经是主界面就停
            val bmp = screenshot() ?: continue
            
            // 简单判断: 如果主界面底部导航栏区域有亮色，可能是主界面
            // 这里用粗略判断，后续MAIN_MENU状态会精确验证
            // 暂时用固定次数关闭
            bmp.recycle()
            
            click(DeviceConfig.POPUP_CLOSE)
            delay(800)
        }
        
        return DailyState.MAIN_MENU
    }
    
    /**
     * ④ 主界面识别
     * - 主方案: OCR识别底部导航栏"基建"文字（主题因人而异）
     * - 备用: 识色（待定，需具体主题截图）
     * - 当前: 先用占位逻辑，等OCR集成后完善
     * - 下一状态: BASE_COLLECT (待实现)
     */
    private fun handleMainMenu(): DailyState {
        log("=== 主界面确认 ===")
        onAction?.invoke(TestAction.StateChanged(DailyState.MAIN_MENU))
        
        // TODO: OCR识别"基建"文字
        // 当前临时方案: 截图后简单检查是否有底部导航栏特征
        // 主界面底部导航栏应该在屏幕下方，先等几秒让界面稳定
        
        delay(2000)
        
        log("✅ 主界面已到达（临时识别，后续加OCR）")
        onDone?.invoke()
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
                        // 以下状态暂未实现，跳到DONE
                        DailyState.BASE_COLLECT -> {
                            log("⚠️ 基建收取尚未实现")
                            DailyState.DONE
                        }
                        DailyState.FRIEND_VISIT -> {
                            log("⚠️ 好友访问尚未实现")
                            DailyState.DONE
                        }
                        DailyState.RECRUIT -> {
                            log("⚠️ 公招尚未实现")
                            DailyState.DONE
                        }
                        DailyState.CREDIT_SHOP -> {
                            log("⚠️ 信用商店尚未实现")
                            DailyState.DONE
                        }
                        DailyState.BATTLE_1_7 -> {
                            log("⚠️ 刷1-7尚未实现")
                            DailyState.DONE
                        }
                        DailyState.BATTLE_RESULT -> {
                            log("⚠️ 战斗结算尚未实现")
                            DailyState.DONE
                        }
                        DailyState.MISSION -> {
                            log("⚠️ 任务尚未实现")
                            DailyState.DONE
                        }
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
     * @param module 要运行的测试模块
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
            
            // 截图权限预检：先尝试截一张，失败则直接报错
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
                    TestModule.BASE_COLLECT -> {
                        log("⚠️ 模块尚未实现: 清基建")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 清基建"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
                    TestModule.FRIEND_VISIT -> {
                        log("⚠️ 模块尚未实现: 好友线索交流")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 好友线索交流"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
                    TestModule.RECRUIT -> {
                        log("⚠️ 模块尚未实现: 公招")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 公招"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
                    TestModule.CREDIT_SHOP -> {
                        log("⚠️ 模块尚未实现: 信用商店")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 信用商店"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
                    TestModule.BATTLE_1_7 -> {
                        log("⚠️ 模块尚未实现: 刷1-7")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 刷1-7"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
                    TestModule.MISSION -> {
                        log("⚠️ 模块尚未实现: 清任务")
                        onAction?.invoke(TestAction.Error("模块尚未实现: 清任务"))
                        onAction?.invoke(TestAction.ModuleDone(module))
                    }
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
    
    /**
     * 运行进游戏模块（4个状态的完整流程）
     */
    private fun runEnterGameModule() {
        currentState = DailyState.WAIT_START
        onAction?.invoke(TestAction.StateChanged(currentState))
        
        // ① 等待START黄字（区域搜索，兼容不同设备Y偏移）
        log("=== [测试] 等待START界面 ===")
        if (!waitForColorInArea(DeviceConfig.START_SEARCH_AREA, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)) {
            onError?.invoke("等待START超时，请确认游戏已启动")
            onAction?.invoke(TestAction.Error("等待START超时，请确认游戏已启动"))
            onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
            return
        }
        delay(300)
        click(DeviceConfig.START_CLICK)
        delay(2000)
        
        // ② 等待开始唤醒灰按钮（主方案: 区域搜索，备用: 固定点，兜底: 直接点击）
        currentState = DailyState.WAIT_WAKE
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 等待开始唤醒 ===")
        
        var wakeFound = false
        // 主方案: 区域搜索
        if (waitForColorInArea(DeviceConfig.WAKE_SEARCH_AREA, DeviceConfig.COLOR_WAKE_GRAY, timeoutMs = 20000)) {
            log("✅ 开始唤醒区域搜索成功")
            wakeFound = true
        } else {
            // 备用: 固定点识色 (宽松条件)
            log("⚠️ 区域搜索超时，尝试固定点识色...")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 8000L && isRunning) {
                checkPaused()
                val bmp = screenshot() ?: continue
                
                val isGray = checkColor(bmp, DeviceConfig.WAKE_CHECK, DeviceConfig.COLOR_WAKE_GRAY)
                val isLightBg = checkColor(bmp, DeviceConfig.WAKE_ASSIST, DeviceConfig.COLOR_WAKE_BG_LIGHT)
                
                // 诊断日志
                val (cx, cy) = Pct2Abs(DeviceConfig.WAKE_CHECK)
                if (cx in 0 until bmp.width && cy in 0 until bmp.height) {
                    val cp = bmp.getPixel(cx, cy)
                    log("🔍 WAKE_CHECK(${cx},${cy}): R=${android.graphics.Color.red(cp)} G=${android.graphics.Color.green(cp)} B=${android.graphics.Color.blue(cp)} gray=$isGray")
                }
                
                bmp.recycle()
                
                if (isGray || isLightBg) {
                    log("✅ 固定点识色命中 (gray=$isGray, lightBg=$isLightBg)")
                    wakeFound = true
                    break
                }
                delay(500)
            }
        }
        
        if (!wakeFound) {
            log("⚠️ 开始唤醒超时，兜底点击")
        }
        delay(300)
        click(DeviceConfig.WAKE_CLICK)
        delay(2000)
        
        // ③ 清活动弹窗
        currentState = DailyState.CLEAR_POPUPS
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 清理活动弹窗 ===")
        
        delay(3000)
        for (i in 1..8) {
            if (!isRunning) {
                onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
                return
            }
            checkPaused()
            val bmp = screenshot() ?: continue
            bmp.recycle()
            click(DeviceConfig.POPUP_CLOSE)
            delay(800)
        }
        
        // ④ 主界面
        currentState = DailyState.MAIN_MENU
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 主界面确认 ===")
        delay(2000)
        
        log("✅ 进游戏模块完成")
        onAction?.invoke(TestAction.ModuleDone(TestModule.ENTER_GAME))
    }
    
    /**
     * 运行关弹窗模块
     */
    private fun runClearPopupsModule() {
        currentState = DailyState.CLEAR_POPUPS
        onAction?.invoke(TestAction.StateChanged(currentState))
        log("=== [测试] 清理活动弹窗 ===")
        
        delay(1000)
        
        var closedCount = 0
        for (i in 1..10) {
            if (!isRunning) break
            checkPaused()
            
            val bmp = screenshot() ?: continue
            bmp.recycle()
            
            click(DeviceConfig.POPUP_CLOSE)
            closedCount++
            log("关闭弹窗 #$closedCount")
            delay(800)
        }
        
        log("✅ 关弹窗模块完成，共关闭 $closedCount 个弹窗")
        onAction?.invoke(TestAction.ModuleDone(TestModule.CLEAR_POPUPS))
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
