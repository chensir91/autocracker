package com.autoclicker.arknights.ui

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.AppSettings
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.databinding.ActivitySettingsBinding

/**
 * 设置页面 v1.1.0
 * 用于配置点击间隔、循环次数、安全性设置和定时启动
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    
    // 当前设置值
    private var currentMinInterval = 10
    private var currentMaxInterval = 20
    private var currentLoopCount = -1
    
    // 安全性设置
    private var currentOffsetRange = 8
    private var currentPauseMinClicks = 20
    private var currentPauseMaxClicks = 50
    private var currentPauseMinDuration = 1000
    private var currentPauseMaxDuration = 3000
    
    // 操作类型默认值
    private var currentLongPressDuration = 500
    private var currentWaitDuration = 1000
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager.getInstance(this)
        
        loadCurrentSettings()
        setupListeners()
    }
    
    /**
     * 加载当前设置
     */
    private fun loadCurrentSettings() {
        val settings = settingsManager.getSettings()
        
        // 转换为厘秒单位
        currentMinInterval = settings.minIntervalMs / 10
        currentMaxInterval = settings.maxIntervalMs / 10
        currentLoopCount = settings.loopCount
        
        // 安全性设置
        currentOffsetRange = settings.offsetRange
        currentPauseMinClicks = settings.pauseMinClicks
        currentPauseMaxClicks = settings.pauseMaxClicks
        currentPauseMinDuration = settings.pauseMinDuration
        currentPauseMaxDuration = settings.pauseMaxDuration
        
        // 操作类型默认值
        currentLongPressDuration = settings.longPressDuration
        currentWaitDuration = settings.waitDuration
        
        updateSeekBarDisplay()
        updateLoopCountDisplay()
        updateSecuritySettingsDisplay()
        updateOperationSettingsDisplay()
    }
    
    /**
     * 设置监听器
     */
    private fun setupListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // 最小间隔滑动条
        binding.seekMinInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > currentMaxInterval) {
                    seekBar?.progress = currentMaxInterval
                    return
                }
                currentMinInterval = progress
                updateSeekBarDisplay()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 最大间隔滑动条
        binding.seekMaxInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < currentMinInterval) {
                    seekBar?.progress = currentMinInterval
                    return
                }
                currentMaxInterval = progress
                updateSeekBarDisplay()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 无限循环开关
        binding.switchInfiniteLoop.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.layoutFixedLoopCount.visibility = View.GONE
                currentLoopCount = -1
            } else {
                binding.layoutFixedLoopCount.visibility = View.VISIBLE
                if (binding.etLoopCount.text.toString().isEmpty()) {
                    binding.etLoopCount.setText("10")
                }
                currentLoopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 10
            }
        }
        
        // 循环次数输入
        binding.etLoopCount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                currentLoopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 10
                if (currentLoopCount <= 0) {
                    currentLoopCount = 10
                    binding.etLoopCount.setText("10")
                }
            }
        }
        
        // ===== 安全性设置 =====
        
        // 偏移范围滑动条
        binding.seekOffsetRange.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentOffsetRange = progress
                updateSecuritySettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 停顿频率最小值
        binding.seekPauseMinClicks.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > currentPauseMaxClicks) {
                    seekBar?.progress = currentPauseMaxClicks
                    return
                }
                currentPauseMinClicks = progress
                updateSecuritySettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 停顿频率最大值
        binding.seekPauseMaxClicks.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < currentPauseMinClicks) {
                    seekBar?.progress = currentPauseMinClicks
                    return
                }
                currentPauseMaxClicks = progress
                updateSecuritySettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 停顿时长最小值
        binding.seekPauseMinDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > currentPauseMaxDuration / 100) {
                    seekBar?.progress = currentPauseMaxDuration / 100
                    return
                }
                currentPauseMinDuration = progress * 100
                updateSecuritySettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 停顿时长最大值
        binding.seekPauseMaxDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < currentPauseMinDuration / 100) {
                    seekBar?.progress = currentPauseMinDuration / 100
                    return
                }
                currentPauseMaxDuration = progress * 100
                updateSecuritySettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // ===== 操作类型默认值 =====
        
        // 长按时长
        binding.seekLongPressDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLongPressDuration = (progress + 1) * 100
                updateOperationSettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 等待时长
        binding.seekWaitDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentWaitDuration = (progress + 1) * 100
                updateOperationSettingsDisplay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 保存按钮
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }
    
    /**
     * 更新滑动条显示
     */
    private fun updateSeekBarDisplay() {
        binding.seekMinInterval.progress = currentMinInterval
        binding.seekMaxInterval.progress = currentMaxInterval
        
        val minSeconds = currentMinInterval / 10f
        val maxSeconds = currentMaxInterval / 10f
        
        binding.tvMinInterval.text = String.format("%.1fs", minSeconds)
        binding.tvMaxInterval.text = String.format("%.1fs", maxSeconds)
    }
    
    /**
     * 更新循环次数显示
     */
    private fun updateLoopCountDisplay() {
        if (currentLoopCount < 0) {
            binding.switchInfiniteLoop.isChecked = true
            binding.layoutFixedLoopCount.visibility = View.GONE
        } else {
            binding.switchInfiniteLoop.isChecked = false
            binding.layoutFixedLoopCount.visibility = View.VISIBLE
            binding.etLoopCount.setText(currentLoopCount.toString())
        }
    }
    
    /**
     * 更新安全性设置显示
     */
    private fun updateSecuritySettingsDisplay() {
        binding.seekOffsetRange.progress = currentOffsetRange
        binding.tvOffsetRange.text = "±${currentOffsetRange}px"
        
        binding.seekPauseMinClicks.progress = currentPauseMinClicks
        binding.seekPauseMaxClicks.progress = currentPauseMaxClicks
        binding.tvPauseClicksRange.text = "${currentPauseMinClicks}-${currentPauseMaxClicks}次"
        
        binding.seekPauseMinDuration.progress = currentPauseMinDuration / 100
        binding.seekPauseMaxDuration.progress = currentPauseMaxDuration / 100
        binding.tvPauseDurationRange.text = "${currentPauseMinDuration / 1000}-${currentPauseMaxDuration / 1000}秒"
    }
    
    /**
     * 更新操作类型默认值显示
     */
    private fun updateOperationSettingsDisplay() {
        binding.seekLongPressDuration.progress = currentLongPressDuration / 100 - 1
        binding.tvLongPressDuration.text = "${currentLongPressDuration}ms"
        
        binding.seekWaitDuration.progress = currentWaitDuration / 100 - 1
        binding.tvWaitDuration.text = "${currentWaitDuration}ms"
    }
    
    /**
     * 保存设置
     */
    private fun saveSettings() {
        // 验证输入
        if (currentMinInterval > currentMaxInterval) {
            Toast.makeText(this, "最小间隔不能大于最大间隔", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (currentMinInterval < 1) {
            currentMinInterval = 1
        }
        
        val loopCount = if (binding.switchInfiniteLoop.isChecked) {
            -1
        } else {
            binding.etLoopCount.text.toString().toIntOrNull() ?: 10
        }
        
        val settings = AppSettings(
            minIntervalMs = currentMinInterval * 10,
            maxIntervalMs = currentMaxInterval * 10,
            loopCount = loopCount,
            // 安全性设置
            offsetRange = currentOffsetRange,
            pauseMinClicks = currentPauseMinClicks,
            pauseMaxClicks = currentPauseMaxClicks,
            pauseMinDuration = currentPauseMinDuration,
            pauseMaxDuration = currentPauseMaxDuration,
            // 操作类型默认值
            longPressDuration = currentLongPressDuration,
            waitDuration = currentWaitDuration
        )
        
        settingsManager.saveSettings(settings)
        
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
