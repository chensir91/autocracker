package com.autoclicker.arknights.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.autoclicker.arknights.R
import com.autoclicker.arknights.data.ClickPoint
import com.autoclicker.arknights.data.OperationType

/**
 * 点位列表适配器
 */
class PointAdapter(
    private var points: MutableList<ClickPoint>,
    private val onEditClick: (Int, ClickPoint) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<PointAdapter.PointViewHolder>() {
    
    inner class PointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvOrder: TextView = itemView.findViewById(R.id.tvOrder)
        val tvType: TextView = itemView.findViewById(R.id.tvType)
        val tvCoords: TextView = itemView.findViewById(R.id.tvCoords)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PointViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_point, parent, false)
        return PointViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: PointViewHolder, position: Int) {
        val point = points[position]
        
        holder.tvOrder.text = point.order.toString()
        
        // 设置类型文字和颜色
        when (point.type) {
            OperationType.CLICK -> {
                holder.tvType.text = "点击"
                holder.tvType.setTextColor(holder.itemView.context.getColor(R.color.primary))
            }
            OperationType.LONG_PRESS -> {
                holder.tvType.text = "长按"
                holder.tvType.setTextColor(holder.itemView.context.getColor(R.color.status_running))
            }
            OperationType.WAIT -> {
                holder.tvType.text = "等待"
                holder.tvType.setTextColor(holder.itemView.context.getColor(R.color.accent))
            }
            OperationType.SWIPE -> {
                holder.tvType.text = "滑动"
                holder.tvType.setTextColor(0xFFCE93D8.toInt())  // 淡紫色
            }
            OperationType.LONG_PRESS_DRAG -> {
                holder.tvType.text = "长按拖动"
                holder.tvType.setTextColor(0xFFFFB74D.toInt())  // 淡橙色
            }
        }
        
        // 设置坐标
        when (point.type) {
            OperationType.WAIT -> {
                holder.tvCoords.text = "等待点"
            }
            OperationType.SWIPE -> {
                holder.tvCoords.text = "(${point.x.toInt()},${point.y.toInt()})→(${point.endX.toInt()},${point.endY.toInt()})"
            }
            OperationType.LONG_PRESS_DRAG -> {
                holder.tvCoords.text = "(${point.x.toInt()},${point.y.toInt()})→(${point.endX.toInt()},${point.endY.toInt()})"
            }
            else -> {
                holder.tvCoords.text = "X: ${point.x.toInt()}, Y: ${point.y.toInt()}"
            }
        }
        
        // 设置时长
        if (point.type != OperationType.CLICK && point.duration > 0) {
            holder.tvDuration.visibility = View.VISIBLE
            holder.tvDuration.text = "时长: ${point.duration}ms"
        } else {
            holder.tvDuration.visibility = View.GONE
        }
        
        holder.btnEdit.setOnClickListener {
            onEditClick(position, point)
        }
        
        holder.btnDelete.setOnClickListener {
            onDeleteClick(position)
        }
    }
    
    override fun getItemCount(): Int = points.size
    
    /**
     * 更新点位列表
     */
    fun updatePoints(newPoints: List<ClickPoint>) {
        points.clear()
        points.addAll(newPoints)
        notifyDataSetChanged()
    }
    
    /**
     * 删除点位
     */
    fun removePoint(position: Int) {
        if (position in 0 until points.size) {
            points.removeAt(position)
            // 重新编号
            points.forEachIndexed { index, point ->
                points[index] = point.copy(order = index + 1)
            }
            notifyDataSetChanged()
        }
    }
    
    /**
     * 更新点位
     */
    fun updatePoint(position: Int, point: ClickPoint) {
        if (position in 0 until points.size) {
            points[position] = point
            notifyItemChanged(position)
        }
    }
}
