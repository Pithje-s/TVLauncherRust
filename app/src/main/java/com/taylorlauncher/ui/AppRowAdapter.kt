package com.taylorlauncher.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.taylorlauncher.R
import com.taylorlauncher.core.Tween
import com.taylorlauncher.model.LauncherItem

class AppRowAdapter(
    private val itemPresenter: LauncherItemPresenter,
    private val onClick: (LauncherItem) -> Unit
) : RecyclerView.Adapter<AppRowAdapter.Holder>() {

    private val items = mutableListOf<LauncherItem>()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view)

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    fun submit(newItems: List<LauncherItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun append(newItems: List<LauncherItem>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    fun size() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(itemPresenter.createTileCell(parent.context, null, null))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        itemPresenter.bindTileCell(holder.itemView, item.packageName, item.title)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(holder: Holder) {
        Tween.resetScale(holder.itemView.findViewById(R.id.tile_frame))
    }

    override fun getItemCount() = items.size
}
