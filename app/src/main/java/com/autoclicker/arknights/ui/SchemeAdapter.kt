package com.autoclicker.arknights.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickScheme
import com.autoclicker.arknights.data.OperationType

/**
 * 预设方案适配器
 */
class PresetSchemeAdapter(
    private val schemes: List<ClickScheme>,
    private val onItemClick: (ClickScheme) -> Unit
) : RecyclerView.Adapter<PresetSchemeAdapter.SchemeViewHolder>() {
    
    inner class SchemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvSchemeName)
        val tvDesc: TextView = itemView.findViewById(R.id.tvSchemeDesc)
        val tvPoints: TextView = itemView.findViewById(R.id.tvSchemePoints)
        val tvHint: TextView = itemView.findViewById(R.id.tvSchemeHint)
        val btnLoad: ImageButton = itemView.findViewById(R.id.btnLoadScheme)
        val btnToggleDetail: ImageButton = itemView.findViewById(R.id.btnToggleDetail)
        val layoutSteps: LinearLayout = itemView.findViewById(R.id.layoutSteps)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SchemeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scheme, parent, false)
        return SchemeViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: SchemeViewHolder, position: Int) {
        val scheme = schemes[position]
        
        holder.tvName.text = scheme.name
        holder.tvDesc.text = getSchemeDescription(scheme.name)
        holder.tvPoints.text = "${scheme.points.size} 个步骤"
        
        // 显示使用提示
        if (scheme.description.isNotEmpty()) {
            holder.tvHint.text = scheme.description
            holder.tvHint.visibility = View.VISIBLE
        } else {
            holder.tvHint.visibility = View.GONE
        }
        
        // 步骤详情默认隐藏
        holder.layoutSteps.removeAllViews()
        holder.layoutSteps.visibility = View.GONE
        
        // 展开/折叠状态
        var isExpanded = false
        
        holder.btnToggleDetail.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                // 构建步骤列表
                holder.layoutSteps.removeAllViews()
                scheme.points.forEachIndexed { index, point ->
                    val stepText = when (point.type) {
                        OperationType.WAIT -> "⏱ 等待 ${point.duration / 1000}秒"
                        OperationType.LONG_PRESS -> "👆 长按 (${point.x.toInt()}, ${point.y.toInt()}) ${point.duration}ms"
                        OperationType.CLICK -> "👆 点击 (${point.x.toInt()}, ${point.y.toInt()})"
                    }
                    val label = if (point.label.isNotEmpty()) point.label else stepText
                    
                    val tv = android.widget.TextView(holder.itemView.context).apply {
                        text = "${index + 1}. $label"
                        textSize = 11f
                        setTextColor(context.getColor(android.R.color.darker_gray))
                        setPadding(4, 4, 4, 4)
                    }
                    holder.layoutSteps.addView(tv)
                }
                holder.layoutSteps.visibility = View.VISIBLE
            } else {
                holder.layoutSteps.visibility = View.GONE
            }
        }
        
        holder.btnLoad.setOnClickListener {
            onItemClick(scheme)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(scheme)
        }
    }
    
    override fun getItemCount(): Int = schemes.size
    
    private fun getSchemeDescription(name: String): String {
        return when {
            name.contains("收菜") -> "一键收取基建产出"
            name.contains("邮件") -> "领取邮件和任务奖励"
            name.contains("信用") -> "购买信用交易所打折物品"
            name.contains("1-6") -> "刷LS-6物资筹备"
            name.contains("好友") -> "与好友交流线索"
            name.contains("日常") -> "综合：基建+邮件+任务+信用商店"
            else -> "预设方案"
        }
    }
}

/**
 * 我的方案适配器
 */
class SchemeAdapter(
    private var schemes: List<ClickScheme>,
    private val onItemClick: (ClickScheme) -> Unit,
    private val onDeleteClick: ((ClickScheme) -> Unit)? = null
) : RecyclerView.Adapter<SchemeAdapter.SchemeViewHolder>() {
    
    inner class SchemeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvSchemeName)
        val tvDesc: TextView = itemView.findViewById(R.id.tvSchemeDesc)
        val tvPoints: TextView = itemView.findViewById(R.id.tvSchemePoints)
        val tvHint: TextView = itemView.findViewById(R.id.tvSchemeHint)
        val btnLoad: ImageButton = itemView.findViewById(R.id.btnLoadScheme)
        val btnDelete: ImageButton? = itemView.findViewById(R.id.btnDeleteScheme)
        val btnToggleDetail: ImageButton = itemView.findViewById(R.id.btnToggleDetail)
        val layoutSteps: LinearLayout = itemView.findViewById(R.id.layoutSteps)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SchemeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scheme, parent, false)
        return SchemeViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: SchemeViewHolder, position: Int) {
        val scheme = schemes[position]
        
        holder.tvName.text = scheme.name
        
        // 格式化创建时间
        val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val dateStr = dateFormat.format(java.util.Date(scheme.createdAt))
        holder.tvDesc.text = dateStr
        
        holder.tvPoints.text = "${scheme.points.size} 个步骤"
        
        // 显示使用提示
        if (scheme.description.isNotEmpty()) {
            holder.tvHint.text = scheme.description
            holder.tvHint.visibility = View.VISIBLE
        } else {
            holder.tvHint.visibility = View.GONE
        }
        
        // 步骤详情默认隐藏
        holder.layoutSteps.removeAllViews()
        holder.layoutSteps.visibility = View.GONE
        
        // 展开/折叠状态
        var isExpanded = false
        
        holder.btnToggleDetail.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                // 构建步骤列表
                holder.layoutSteps.removeAllViews()
                scheme.points.forEachIndexed { index, point ->
                    val stepText = when (point.type) {
                        OperationType.WAIT -> "⏱ 等待 ${point.duration / 1000}秒"
                        OperationType.LONG_PRESS -> "👆 长按 (${point.x.toInt()}, ${point.y.toInt()}) ${point.duration}ms"
                        OperationType.CLICK -> "👆 点击 (${point.x.toInt()}, ${point.y.toInt()})"
                    }
                    val label = if (point.label.isNotEmpty()) point.label else stepText
                    
                    val tv = android.widget.TextView(holder.itemView.context).apply {
                        text = "${index + 1}. $label"
                        textSize = 11f
                        setTextColor(context.getColor(android.R.color.darker_gray))
                        setPadding(4, 4, 4, 4)
                    }
                    holder.layoutSteps.addView(tv)
                }
                holder.layoutSteps.visibility = View.VISIBLE
            } else {
                holder.layoutSteps.visibility = View.GONE
            }
        }
        
        holder.btnLoad.setOnClickListener {
            onItemClick(scheme)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(scheme)
        }
        
        holder.btnDelete?.setOnClickListener {
            onDeleteClick?.invoke(scheme)
        }
        
        // 如果没有删除回调，隐藏删除按钮
        if (onDeleteClick == null) {
            holder.btnDelete?.visibility = View.GONE
        }
    }
    
    override fun getItemCount(): Int = schemes.size
    
    /**
     * 更新方案列表
     */
    fun updateSchemes(newSchemes: List<ClickScheme>) {
        schemes = newSchemes
        notifyDataSetChanged()
    }
}
