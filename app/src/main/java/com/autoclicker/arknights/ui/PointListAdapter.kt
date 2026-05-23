package com.autoclicker.arknights.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
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
    private val onItemClick: (Int, ClickPoint) -> Unit,
    private val onShowClick: (Int, ClickPoint) -> Unit,
    private val onEditClick: (Int, ClickPoint) -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val onWaitDurationChange: (Int, ClickPoint, Long) -> Unit
) : RecyclerView.Adapter<PointListAdapter.ViewHolder>() {

    // 当前高亮的点位序号（-1表示无选中）
    var highlightedPosition: Int = -1

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvOrder: TextView = itemView.findViewById(R.id.tvPointOrder)
        val tvInfo: TextView = itemView.findViewById(R.id.tvPointInfo)
        val btnShow: ImageButton = itemView.findViewById(R.id.btnShowPoint)
        val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditPoint)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeletePoint)
        val waitDurationControls: LinearLayout = itemView.findViewById(R.id.waitDurationControls)
        val tvWaitDuration: TextView = itemView.findViewById(R.id.tvWaitDuration)
        val btnDecreaseWait: ImageButton = itemView.findViewById(R.id.btnDecreaseWait)
        val btnIncreaseWait: ImageButton = itemView.findViewById(R.id.btnIncreaseWait)
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

        // 调整/删除按钮只在选中时显示
        holder.btnEdit.visibility = if (isHighlighted) View.VISIBLE else View.GONE
        holder.btnDelete.visibility = if (isHighlighted) View.VISIBLE else View.GONE

        // WAIT类型选中时显示时长调整控件
        val isWaitPoint = point.type == OperationType.WAIT
        holder.waitDurationControls.visibility = if (isHighlighted && isWaitPoint) View.VISIBLE else View.GONE

        if (isHighlighted && isWaitPoint) {
            holder.tvWaitDuration.text = "${point.duration / 1000f}s"
        }

        // 点击item本身切换选中状态
        holder.itemView.setOnClickListener {
            val newHighlightPosition = if (highlightedPosition == position) -1 else position
            val previousPosition = highlightedPosition
            highlightedPosition = newHighlightPosition
            
            // 通知旧的和新的item更新
            if (previousPosition >= 0) {
                notifyItemChanged(previousPosition)
            }
            if (newHighlightPosition >= 0) {
                notifyItemChanged(newHighlightPosition)
            }
            
            // 通知外部选中状态变化
            if (newHighlightPosition >= 0) {
                onItemClick(newHighlightPosition, points[newHighlightPosition])
            } else {
                onItemClick(-1, point)
            }
        }

        // 显示按钮点击
        holder.btnShow.setOnClickListener {
            // 切换高亮状态
            val newHighlightPosition = if (highlightedPosition == position) -1 else position
            val previousPosition = highlightedPosition
            highlightedPosition = newHighlightPosition
            
            if (previousPosition >= 0) {
                notifyItemChanged(previousPosition)
            }
            if (newHighlightPosition >= 0) {
                notifyItemChanged(newHighlightPosition)
            }
            
            onShowClick(position, point)
        }

        // 编辑按钮点击
        holder.btnEdit.setOnClickListener {
            onEditClick(position, point)
        }

        // 删除按钮点击
        holder.btnDelete.setOnClickListener {
            onDeleteClick(position)
        }

        // 等待时长减少
        holder.btnDecreaseWait.setOnClickListener {
            val newDuration = (point.duration - 500).coerceAtLeast(500)
            if (newDuration != point.duration) {
                onWaitDurationChange(position, point, newDuration)
            }
        }

        // 等待时长增加
        holder.btnIncreaseWait.setOnClickListener {
            val newDuration = point.duration + 500
            onWaitDurationChange(position, point, newDuration)
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

    fun updatePointDuration(position: Int, newDuration: Long) {
        if (position in 0 until points.size) {
            points[position] = points[position].copy(duration = newDuration)
            notifyItemChanged(position)
        }
    }
}
