package com.jarvis.agent

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(val text: String, val fromUser: Boolean)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<ChatMessage>()

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tv.text = (if (m.fromUser) "You: " else "Jarvis: ") + m.text
        holder.tv.gravity = if (m.fromUser) Gravity.END else Gravity.START
    }

    override fun getItemCount() = items.size

    fun add(m: ChatMessage) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }
}
