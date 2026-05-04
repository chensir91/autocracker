package com.autoclicker.arknights.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickScheme

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
        val btnLoad: ImageButton = itemView.findViewById(R.id.btnLoadScheme)
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
            name.contains("作战") -> "自动刷取理智药"
            name.contains("公招") -> "自动公招+刷新"
            name.contains("签到") -> "自动完成每日签到"
            name.contains("好友") -> "自动领取友情点"
            name.contains("领取") -> "自动领取月卡/邮件"
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
        val btnLoad: ImageButton = itemView.findViewById(R.id.btnLoadScheme)
        val btnDelete: ImageButton? = itemView.findViewById(R.id.btnDeleteScheme)
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
