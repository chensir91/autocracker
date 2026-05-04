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
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
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
import com.autoclicker.arknights.data.PresetSchemes
import com.autoclicker.arknights.data.SettingsManager
import com.autoclicker.arknights.databinding.ActivityMainBinding
import com.autoclicker.arknights.service.AutoClickAccessibilityService
import com.autoclicker.arknights.service.FloatingWindowService
import com.autoclicker.arknights.util.PermissionUtils
import com.google.android.material.tabs.TabLayout

/**
 * 主Activity
 * 负责初始化权限、启动服务、管理UI交互
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager
    
    private var floatingService: FloatingWindowService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FloatingWindowService.LocalBinder
            floatingService = binder.getService()
            isServiceBound = true
            setupServiceCallbacks()
            updateUIFromService()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            floatingService = null
            isServiceBound = false
        }
    }
    
    // 权限请求
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndRequestPermissions()
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
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
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
        
        FloatingWindowService.start(this)
        
        // 绑定服务
        Intent(this, FloatingWindowService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
        val presetSchemes = PresetSchemes.getAllPresets()
        val mySchemes = settingsManager.getAllSchemes()
        
        // 创建对话框
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        
        // 预设方案适配器
        val presetAdapter = PresetSchemeAdapter(presetSchemes) { scheme ->
            floatingService?.loadScheme(scheme)
            Toast.makeText(this@MainActivity, 
                "已加载预设方案: ${scheme.name}\n共${scheme.points.size}个步骤", 
                Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        // 我的方案适配器
        val myAdapter = SchemeAdapter(mySchemes) { scheme ->
            floatingService?.loadScheme(scheme)
            Toast.makeText(this@MainActivity, getString(R.string.scheme_load_success), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
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
    
    /**
     * 预设方案列表适配器
     */
    inner class PresetSchemeAdapter(
        private val schemes: List<ClickScheme>,
        private val onItemClick: (ClickScheme) -> Unit
    ) : RecyclerView.Adapter<PresetSchemeAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: android.widget.TextView = itemView.findViewById(R.id.tvSchemeName)
            val tvInfo: android.widget.TextView = itemView.findViewById(R.id.tvSchemeInfo)
            val btnDelete: android.widget.ImageButton = itemView.findViewById(R.id.btnDelete)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scheme, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scheme = schemes[position]
            holder.tvName.text = scheme.name
            holder.tvInfo.text = "${scheme.points.size}个步骤"
            
            // 预设方案不可删除，隐藏删除按钮
            holder.btnDelete.visibility = View.GONE
            
            holder.itemView.setOnClickListener {
                onItemClick(scheme)
            }
        }
        
        override fun getItemCount() = schemes.size
    }
    
    /**
     * 方案列表适配器
     */
    inner class SchemeAdapter(
        private val schemes: List<ClickScheme>,
        private val onItemClick: (ClickScheme) -> Unit
    ) : RecyclerView.Adapter<SchemeAdapter.ViewHolder>() {
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: android.widget.TextView = itemView.findViewById(R.id.tvSchemeName)
            val tvInfo: android.widget.TextView = itemView.findViewById(R.id.tvSchemeInfo)
            val btnDelete: android.widget.ImageButton = itemView.findViewById(R.id.btnDelete)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scheme, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scheme = schemes[position]
            holder.tvName.text = scheme.name
            holder.tvInfo.text = "${scheme.points.size}个点位"
            
            holder.itemView.setOnClickListener {
                onItemClick(scheme)
            }
            
            holder.btnDelete.setOnClickListener {
                settingsManager.deleteScheme(scheme.name)
                Toast.makeText(this@MainActivity, getString(R.string.scheme_delete_success), Toast.LENGTH_SHORT).show()
                showLoadDialog() // 刷新列表
            }
        }
        
        override fun getItemCount() = schemes.size
    }
}
