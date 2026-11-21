package com.taylorlauncher.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.taylorlauncher.R
import com.taylorlauncher.core.Tween

class AllAppsOverlayAdapter(
    private val apps: List<Pair<String, String>>,
    private val itemPresenter: LauncherItemPresenter,
    private val onOpen: (Pair<String, String>) -> Unit,
    private val onKey: (Int, Int, KeyEvent) -> Boolean
) : RecyclerView.Adapter<AllAppsOverlayAdapter.Holder>() {

    inner class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val cell = itemPresenter.createTileCell(parent.context, null, null)
        val holder = Holder(cell)
        cell.setOnKeyListener { _, keyCode, event ->
            onKey(holder.bindingAdapterPosition, keyCode, event)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        itemPresenter.bindTileCell(holder.itemView, app.first, app.second)
        holder.itemView.setOnClickListener { onOpen(app) }
    }

    override fun onViewRecycled(holder: Holder) {
        Tween.resetScale(holder.itemView.findViewById(R.id.tile_frame))
    }

    override fun getItemCount() = apps.size
}
