package com.autoclicker.arknights.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.OperationType
import com.autoclicker.arknights.service.FloatingWindowService
import java.util.Calendar

/**
 * 服务对话框Activity - 用于在Android 14+上安全地显示对话框
 * 解决Service中直接弹Dialog导致的BadTokenException问题
 */
class ServiceDialogActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_DIALOG_TYPE = "dialog_type"
        const val TYPE_SCHEDULED = "scheduled"
        const val TYPE_LONG_PRESS = "long_press"
        const val TYPE_WAIT = "wait"
        const val EXTRA_POINT_X = "point_x"
        const val EXTRA_POINT_Y = "point_y"
        const val EXTRA_ORDER = "order"
        const val EXTRA_DEFAULT_DURATION = "default_duration"
        
        // Action constants for Service
        const val ACTION_SET_SCHEDULED = "SET_SCHEDULED_START"
        const val ACTION_ADD_POINT = "ADD_RECORDED_POINT"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent.getStringExtra(EXTRA_DIALOG_TYPE)) {
            TYPE_SCHEDULED -> showScheduledDialog()
            TYPE_LONG_PRESS -> showLongPressDialog()
            TYPE_WAIT -> showWaitDialog()
            else -> finish()
        }
    }
    
    /**
     * 显示时间选择对话框（定时启动）
     */
    private fun showScheduledDialog() {
        val calendar = Calendar.getInstance()
        val timePicker = TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val serviceIntent = Intent(this, FloatingWindowService::class.java).apply {
                    action = ACTION_SET_SCHEDULED
                    putExtra("hourOfDay", hourOfDay)
                    putExtra("minute", minute)
                }
                startService(serviceIntent)
                finish()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        timePicker.setTitle("设置定时启动")
        timePicker.setOnCancelListener { finish() }
        timePicker.show()
    }
    
    /**
     * 显示长按时长设置对话框
     */
    private fun showLongPressDialog() {
        val x = intent.getFloatExtra(EXTRA_POINT_X, 0f)
        val y = intent.getFloatExtra(EXTRA_POINT_Y, 0f)
        val defaultDuration = intent.getLongExtra(EXTRA_DEFAULT_DURATION, 500L)
        
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_long_press, null)
        
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekLongPressDuration)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvLongPressDuration)
        
        seekBar.max = 20
        seekBar.progress = (defaultDuration / 100).toInt()
        tvDuration.text = "${seekBar.progress * 100}ms"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = (progress + 1) * 100
                tvDuration.text = "${duration}ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle("设置长按时长")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val duration = (seekBar.progress + 1) * 100L
                val serviceIntent = Intent(this, FloatingWindowService::class.java).apply {
                    action = ACTION_ADD_POINT
                    putExtra("type", OperationType.LONG_PRESS.name)
                    putExtra("x", x)
                    putExtra("y", y)
                    putExtra("duration", duration)
                }
                startService(serviceIntent)
                finish()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
    
    /**
     * 显示等待时长设置对话框
     */
    private fun showWaitDialog() {
        val defaultDuration = intent.getLongExtra(EXTRA_DEFAULT_DURATION, 1000L)
        
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_wait, null)
        
        val seekBar = dialogView.findViewById<SeekBar>(R.id.seekWaitDuration)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvWaitDuration)
        
        seekBar.max = 30
        seekBar.progress = (defaultDuration / 100).toInt()
        tvDuration.text = "${seekBar.progress * 100}ms"
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = (progress + 1) * 100
                tvDuration.text = "${duration}ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        AlertDialog.Builder(this)
            .setTitle("设置等待时长")
            .setView(dialogView)
            .setPositiveButton("添加等待") { _, _ ->
                val duration = (seekBar.progress + 1) * 100L
                val serviceIntent = Intent(this, FloatingWindowService::class.java).apply {
                    action = ACTION_ADD_POINT
                    putExtra("type", OperationType.WAIT.name)
                    putExtra("duration", duration)
                }
                startService(serviceIntent)
                finish()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
