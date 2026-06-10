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
    
    // ============ 工具方法 ============
    
    /** 百分比坐标转绝对坐标 */
    private fun Pct2Abs(coord: DeviceConfig.PctCoord): Pair<Int, Int> {
        return coord.toAbs(screenWidth, screenHeight)
    }
    
    /** 百分比矩形转绝对Rect */
    private fun Pct2Abs(rect: DeviceConfig.PctRect): android.graphics.Rect {
        return rect.toAbs(screenWidth, screenHeight)
    }
    
    /** 截图 */
    private fun screenshot(): Bitmap? {
        return ScreenshotHelper.captureScreen(service)
    }
    
    /** 点击（百分比坐标） */
    private fun click(coord: DeviceConfig.PctCoord) {
        val (x, y) = Pct2Abs(coord)
        log("点击 (${coord.xPct}%, ${coord.yPct}%) → ($x, $y)")
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    /** 点击（绝对坐标） */
    private fun clickAbs(x: Int, y: Int) {
        ClickUtils.click(service, x.toFloat(), y.toFloat())
    }
    
    /** 等待毫秒 */
    private fun delay(ms: Long) {
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
                return true
            }
            delay(intervalMs)
        }
        log("❌ ${rule.name} 等待超时 ${timeoutMs}ms")
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
        if (!waitForColor(DeviceConfig.START_CHECK, DeviceConfig.COLOR_START_YELLOW, timeoutMs = 60000)) {
            onError?.invoke("等待START超时，请确认游戏已启动")
            return DailyState.DONE
        }
        delay(300)
        click(DeviceConfig.START_CLICK)
        delay(1000)
        return DailyState.WAIT_WAKE
    }
    
    /**
     * ② 等待开始唤醒灰按钮
     * - 条件: (51%, 70%) R/G/B ≈ 85±30，辅助验证 (51%, 66%) R>200（浅灰背景）
     * - 操作: 点击开始唤醒
     * - 下一状态: CLEAR_POPUPS
     */
    private fun handleWaitWake(): DailyState {
        log("=== 等待开始唤醒 ===")
        
        val startTime = System.currentTimeMillis()
        val timeoutMs = 15000L
        
        while (System.currentTimeMillis() - startTime < timeoutMs && isRunning) {
            checkPaused()
            val bmp = screenshot() ?: continue
            
            // 主判断: 灰按钮
            val isGray = checkColor(bmp, DeviceConfig.WAKE_CHECK, DeviceConfig.COLOR_WAKE_GRAY)
            // 辅助判断: 浅灰背景（与按钮形成跳变）
            val isLightBg = checkColor(bmp, DeviceConfig.WAKE_ASSIST, DeviceConfig.COLOR_WAKE_BG_LIGHT)
            
            bmp.recycle()
            
            if (isGray && isLightBg) {
                log("✅ 开始唤醒灰按钮识别成功")
                delay(300)
                click(DeviceConfig.WAKE_CLICK)
                delay(2000)
                return DailyState.CLEAR_POPUPS
            }
            delay(500)
        }
        
        // 灰按钮可能不存在（已自动进入），直接尝试下一状态
        log("⚠️ 开始唤醒超时，可能已自动进入，继续清理弹窗")
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
     * 启动一键肝舟
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
            } finally {
                isRunning = false
            }
        }, "DailyRoutine")
        
        runnerThread?.start()
    }
    
    /**
     * 停止
     */
    fun stop() {
        isRunning = false
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
