package com.autoclicker.arknights.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.ClickScheme
import com.autoclicker.arknights.data.OperationType
import com.autoclicker.arknights.data.PresetSchemes
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.databinding.ActivityMainBinding
import com.autoclicker.arknights.service.AutoClickAccessibilityService
import com.autoclicker.arknights.service.FloatingWindowService
import com.autoclicker.arknights.util.PermissionUtils
import com.google.android.material.tabs.TabLayout

/**
 * 主Activity v1.2.0
 * 负责初始化权限、启动服务、管理UI交互
 * 新增点位列表显示和编辑功能
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var pointAdapter: PointAdapter
    
    private var floatingService: FloatingWindowService? = null
    private var isServiceBound = false
    private var isStartingService = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingWindowService.LocalBinder
            floatingService = binder.getService()
            isServiceBound = true
            isStartingService = false
            setupServiceCallbacks()
            updateUIFromService()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            floatingService = null
            isServiceBound = false
            isStartingService = false
        }
    }
    
    // 权限请求
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 不在这里启动服务，让 onResume() 统一处理
        // 因为返回时 onResume 一定会被调用
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startFloatingService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        settingsManager = SettingsManager.getInstance(this)
        
        setupPointList()
        setupClickListeners()
        
        // 检查是否需要打开设置页面
        if (intent.getBooleanExtra("open_settings", false)) {
            openSettings()
        }
    }
    
    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
        updatePermissionHint()
        // 刷新设置
        floatingService?.refreshSettings()
        // 刷新点位列表
        refreshPointList()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
    
    /**
     * 设置点位列表
     */
    private fun setupPointList() {
        pointAdapter = PointAdapter(
            points = mutableListOf(),
            onEditClick = { position, point ->
                showEditPointDialog(position, point)
            },
            onDeleteClick = { position ->
                deletePoint(position)
            }
        )
        
        binding.rvPoints.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pointAdapter
        }
    }
    
    /**
     * 刷新点位列表
     */
    private fun refreshPointList() {
        val points = floatingService?.getRecordedPoints() ?: emptyList()
        
        if (points.isEmpty()) {
            binding.tvNoPointsHint.visibility = View.VISIBLE
            binding.rvPoints.visibility = View.GONE
        } else {
            binding.tvNoPointsHint.visibility = View.GONE
            binding.rvPoints.visibility = View.VISIBLE
            pointAdapter.updatePoints(points.toMutableList())
        }
    }
    
    /**
     * 显示编辑点位对话框
     */
    private fun showEditPointDialog(position: Int, point: ClickPoint) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_point, null)
        
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinnerType)
        val etX = dialogView.findViewById<EditText>(R.id.etX)
        val etY = dialogView.findViewById<EditText>(R.id.etY)
        val tvDurationLabel = dialogView.findViewById<TextView>(R.id.tvDurationLabel)
        val etDuration = dialogView.findViewById<EditText>(R.id.etDuration)
        
        // 设置操作类型选项
        val types = arrayOf("点击", "长按", "等待", "滑动", "长按拖动")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter
        
        // 设置初始值
        when (point.type) {
            OperationType.CLICK -> spinnerType.setSelection(0)
            OperationType.LONG_PRESS -> spinnerType.setSelection(1)
            OperationType.WAIT -> spinnerType.setSelection(2)
            OperationType.SWIPE -> spinnerType.setSelection(3)
            OperationType.LONG_PRESS_DRAG -> spinnerType.setSelection(4)
        }
        
        etX.setText(point.x.toInt().toString())
        etY.setText(point.y.toInt().toString())
        
        if (point.type != OperationType.CLICK) {
            tvDurationLabel.visibility = View.VISIBLE
            etDuration.visibility = View.VISIBLE
            etDuration.setText(point.duration.toString())
        }
        
        // 类型选择监听
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == 2) { // 等待
                    tvDurationLabel.visibility = View.VISIBLE
                    etDuration.visibility = View.VISIBLE
                } else {
                    tvDurationLabel.visibility = View.VISIBLE
                    etDuration.visibility = View.VISIBLE
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val type = when (spinnerType.selectedItemPosition) {
                    0 -> OperationType.CLICK
                    1 -> OperationType.LONG_PRESS
                    2 -> OperationType.WAIT
                    3 -> OperationType.SWIPE
                    4 -> OperationType.LONG_PRESS_DRAG
                    else -> OperationType.CLICK
                }
                
                val newX = etX.text.toString().toFloatOrNull() ?: point.x
                val newY = etY.text.toString().toFloatOrNull() ?: point.y
                val newDuration = etDuration.text.toString().toLongOrNull() ?: point.duration
                
                val newPoint = point.copy(
                    x = newX,
                    y = newY,
                    type = type,
                    duration = newDuration
                )
                
                floatingService?.let { service ->
                    val points = service.getRecordedPoints().toMutableList()
                    if (position < points.size) {
                        points[position] = newPoint
                        // 更新服务中的点位
                        service.loadScheme(ClickScheme("temp", points))
                    }
                }
                
                pointAdapter.updatePoint(position, newPoint)
                Toast.makeText(this, "点位已更新", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 删除点位
     */
    private fun deletePoint(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除点位")
            .setMessage("确定要删除这个点位吗？")
            .setPositiveButton("删除") { _, _ ->
                floatingService?.let { service ->
                    val points = service.getRecordedPoints().toMutableList()
                    if (position < points.size) {
                        points.removeAt(position)
                        // 重新编号
                        val renumbered = points.mapIndexed { index, point ->
                            point.copy(order = index + 1)
                        }
                        service.loadScheme(ClickScheme("temp", renumbered))
                    }
                }
                
                pointAdapter.removePoint(position)
                binding.tvPointInfo.text = getString(R.string.point_recorded, pointAdapter.itemCount)
                
                if (pointAdapter.itemCount == 0) {
                    binding.tvNoPointsHint.visibility = View.VISIBLE
                    binding.rvPoints.visibility = View.GONE
                }
                
                Toast.makeText(this, "点位已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 检查并请求权限
     */
    private fun checkAndRequestPermissions() {
        // 检查悬浮窗权限
        if (!PermissionUtils.canDrawOverlays(this)) {
            showOverlayPermissionHint()
            return
        }
        
        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        // 隐藏权限提示
        binding.permissionHint.visibility = View.GONE
        
        // 启动服务
        startFloatingService()
    }
    
    /**
     * 显示悬浮窗权限提示
     */
    private fun showOverlayPermissionHint() {
        binding.tvPermissionHint.text = getString(R.string.overlay_permission_hint)
        binding.btnGrantPermission.setOnClickListener {
            PermissionUtils.requestOverlayPermission(this)
        }
        binding.permissionHint.visibility = View.VISIBLE
    }
    
    /**
     * 更新权限提示状态
     */
    private fun updatePermissionHint() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            showOverlayPermissionHint()
            return
        }
        
        if (!PermissionUtils.isAccessibilityServiceEnabled(this)) {
            binding.tvPermissionHint.text = getString(R.string.accessibility_permission_hint)
            binding.btnGrantPermission.setOnClickListener {
                PermissionUtils.openAccessibilitySettings(this)
            }
            binding.permissionHint.visibility = View.VISIBLE
            return
        }
        
        binding.permissionHint.visibility = View.GONE
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingService() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            return
        }
        if (isStartingService) return
        if (floatingService != null) return
        isStartingService = true
        
        try {
            FloatingWindowService.start(this)
            Intent(this, FloatingWindowService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start service", e)
            isStartingService = false
        }
    }
    
    /**
     * 设置服务回调
     */
    private fun setupServiceCallbacks() {
        floatingService?.onStateChanged = { state ->
            runOnUiThread {
                updateUIFromState(state)
            }
        }
        
        floatingService?.onPointsChanged = { count ->
            runOnUiThread {
                binding.tvPointInfo.text = getString(R.string.point_recorded, count)
                refreshPointList()
            }
        }
    }
    
    /**
     * 从服务更新UI
     */
    private fun updateUIFromService() {
        floatingService?.let { service ->
            updateUIFromState(service.getState())
            binding.tvPointInfo.text = getString(R.string.point_recorded, service.getRecordedPoints().size)
            refreshPointList()
        }
    }
    
    /**
     * 根据状态更新UI
     */
    private fun updateUIFromState(state: FloatingWindowService.State) {
        when (state) {
            FloatingWindowService.State.IDLE -> {
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.tvStatus.setTextColor(getColor(R.color.status_idle))
                binding.btnStart.text = getString(R.string.btn_start)
                binding.btnStart.isEnabled = true
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.GONE
                binding.btnRecord.isEnabled = true
            }
            FloatingWindowService.State.RECORDING -> {
                binding.tvStatus.text = getString(R.string.status_recording)
                binding.tvStatus.setTextColor(getColor(R.color.status_recording))
                binding.btnStart.isEnabled = false
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.GONE
            }
            FloatingWindowService.State.RUNNING -> {
                binding.tvStatus.text = getString(R.string.status_running)
                binding.tvStatus.setTextColor(getColor(R.color.status_running))
                binding.btnStart.text = getString(R.string.btn_pause)
                binding.btnStart.isEnabled = true
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
                binding.btnRecord.isEnabled = false
            }
            FloatingWindowService.State.PAUSED -> {
                binding.tvStatus.text = getString(R.string.status_paused)
                binding.tvStatus.setTextColor(getColor(R.color.status_paused))
                binding.btnStart.text = getString(R.string.btn_start)
                binding.btnStart.isEnabled = true
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
                binding.btnRecord.isEnabled = false
            }
        }
    }
    
    /**
     * 设置点击监听
     */
    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            openSettings()
        }
        
        binding.btnRecord.setOnClickListener {
            floatingService?.toggleRecording()
        }
        
        binding.btnStart.setOnClickListener {
            floatingService?.toggleStartPause()
        }
        
        binding.btnPause.setOnClickListener {
            floatingService?.togglePause()
        }
        
        binding.btnStop.setOnClickListener {
            floatingService?.stop()
        }
        
        binding.btnSave.setOnClickListener {
            showSaveDialog()
        }
        
        binding.btnLoad.setOnClickListener {
            showLoadDialog()
        }
    }
    
    /**
     * 打开设置页面
     */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    
    /**
     * 显示保存方案对话框
     */
    private fun showSaveDialog() {
        val points = floatingService?.getRecordedPoints() ?: emptyList()
        if (points.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_points_recorded), Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_scheme, null)
        val etName = dialogView.findViewById<EditText>(R.id.etSchemeName)
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val scheme = ClickScheme(name, points)
                    settingsManager.saveScheme(scheme)
                    Toast.makeText(this, getString(R.string.scheme_save_success), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 显示加载方案对话框（支持预设方案和我的方案）
     */
    private fun showLoadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_load_scheme, null)
        val tabLayout = dialogView.findViewById<TabLayout>(R.id.tabLayout)
        val rvPresets = dialogView.findViewById<RecyclerView>(R.id.rvPresetSchemes)
        val rvSchemes = dialogView.findViewById<RecyclerView>(R.id.rvSchemes)
        val tvEmptyHint = dialogView.findViewById<android.widget.TextView>(R.id.tvEmptyHint)
        val tvPresetHint = dialogView.findViewById<android.widget.TextView>(R.id.tvPresetHint)
        
        // 获取预设方案和我的方案
        val (screenWidth, screenHeight) = settingsManager.getResolution()
        val presetSchemes = PresetSchemes.getAllPresets(screenWidth, screenHeight)
        val mySchemes = settingsManager.getAllSchemes()
        
        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        
        // 预设方案适配器
        val presetAdapter = PresetSchemeAdapter(presetSchemes) { scheme ->
            floatingService?.loadScheme(scheme)
            refreshPointList()
            Toast.makeText(this@MainActivity, 
                "已加载预设方案: ${scheme.name}\n共${scheme.points.size}个步骤", 
                Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        // 我的方案适配器
        val myAdapter = SchemeAdapter(
            schemes = mySchemes,
            onItemClick = { scheme ->
                floatingService?.loadScheme(scheme)
                refreshPointList()
                Toast.makeText(this@MainActivity, getString(R.string.scheme_load_success), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        )
        
        // 设置适配器
        rvPresets.layoutManager = LinearLayoutManager(this)
        rvPresets.adapter = presetAdapter
        rvSchemes.layoutManager = LinearLayoutManager(this)
        rvSchemes.adapter = myAdapter
        
        // 默认显示预设方案
        rvPresets.visibility = View.VISIBLE
        rvSchemes.visibility = View.GONE
        tvPresetHint.visibility = View.VISIBLE
        
        // Tab切换监听
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // 预设方案
                        rvPresets.visibility = View.VISIBLE
                        rvSchemes.visibility = View.GONE
                        tvPresetHint.visibility = View.VISIBLE
                        tvEmptyHint.visibility = View.GONE
                    }
                    1 -> {
                        // 我的方案
                        rvPresets.visibility = View.GONE
                        rvSchemes.visibility = View.VISIBLE
                        tvPresetHint.visibility = View.GONE
                        if (mySchemes.isEmpty()) {
                            tvEmptyHint.visibility = View.VISIBLE
                        } else {
                            tvEmptyHint.visibility = View.GONE
                        }
                    }
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        dialog.show()
    }
}
