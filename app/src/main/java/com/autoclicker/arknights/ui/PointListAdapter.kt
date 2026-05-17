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
 * 悬浮窗点列表面板适配器
 */
class PointListAdapter(
    private val points: MutableList<ClickPoint>,
    private val onShowClick: (Int, ClickPoint) -> Unit,
    private val onEditClick: (Int, ClickPoint) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<PointListAdapter.ViewHolder>() {

    // 当前高亮的点位序号（-1表示只显示最后一个）
    var highlightedPosition: Int = -1

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvOrder: TextView = itemView.findViewById(R.id.tvPointOrder)
        val tvInfo: TextView = itemView.findViewById(R.id.tvPointInfo)
        val btnShow: ImageButton = itemView.findViewById(R.id.btnShowPoint)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditPoint)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeletePoint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_point_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = points[position]

        // 序号
        holder.tvOrder.text = point.order.toString()

        // 类型和坐标信息
        val typeStr = when (point.type) {
            OperationType.CLICK -> "点击"
            OperationType.LONG_PRESS -> "长按"
            OperationType.WAIT -> "等待"
            OperationType.SWIPE -> "滑动"
            OperationType.LONG_PRESS_DRAG -> "长按拖动"
            OperationType.WAIT_PIXEL -> "等待像素"
            OperationType.MULTI_CLICK -> "连续点击"
        }

        val infoText = if (point.type == OperationType.WAIT) {
            "${typeStr} ${point.duration / 1000f}s"
        } else {
            val coords = if (point.x > 0 || point.y > 0) {
                "(${point.x.toInt()}, ${point.y.toInt()})"
            } else {
                ""
            }
            "$typeStr $coords"
        }
        holder.tvInfo.text = infoText

        // 高亮当前选中
        val isHighlighted = position == highlightedPosition
        holder.itemView.setBackgroundColor(
            if (isHighlighted) 0x20FF9800.toInt() else 0x00000000
        )

        // 显示按钮状态
        holder.btnShow.alpha = if (isHighlighted) 1.0f else 0.6f

        // 按钮点击
        holder.btnShow.setOnClickListener {
            // 切换高亮状态
            highlightedPosition = if (highlightedPosition == position) -1 else position
            notifyDataSetChanged()
            onShowClick(position, point)
        }

        holder.btnEdit.setOnClickListener {
            onEditClick(position, point)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick(position)
        }
    }

    override fun getItemCount(): Int = points.size

    fun updatePoints(newPoints: List<ClickPoint>) {
        points.clear()
        points.addAll(newPoints)
        highlightedPosition = -1
        notifyDataSetChanged()
    }

    fun removePoint(position: Int) {
        if (position in 0 until points.size) {
            points.removeAt(position)
            if (highlightedPosition == position) {
                highlightedPosition = -1
            } else if (highlightedPosition > position) {
                highlightedPosition--
            }
            notifyDataSetChanged()
        }
    }
}
