package com.example.parakeet06bv3

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
class TranslationAdapter(private val items: MutableList<TranslationItem>) :
    RecyclerView.Adapter<TranslationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEnglish: TextView = view.findViewById(R.id.tvEnglish)
        val tvKorean: TextView = view.findViewById(R.id.tvKorean)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_translation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvEnglish.text = item.englishText
        holder.tvKorean.text = item.koreanText

        when {
            item.isFinal && !item.isStreaming -> {
                holder.tvEnglish.setTextColor(Color.BLACK)
                holder.tvKorean.setTextColor(Color.DKGRAY)
                holder.tvStatus.visibility = View.GONE
                holder.itemView.setBackgroundColor(Color.parseColor("#F5F5F5"))
            }
            item.isFinal && item.isStreaming -> {
                holder.tvEnglish.setTextColor(Color.BLACK)
                holder.tvKorean.setTextColor(Color.parseColor("#2E7D32"))
                holder.tvStatus.text = "✍️ Qwen 3 스트리밍 중..."
                holder.tvStatus.visibility = View.VISIBLE
                holder.itemView.setBackgroundColor(Color.parseColor("#E8F5E9"))
            }
            else -> {
                holder.tvEnglish.setTextColor(Color.parseColor("#1976D2"))
                holder.tvKorean.setTextColor(Color.parseColor("#1976D2"))
                holder.tvStatus.text = "🎙️ ML Kit 번역 중..."
                holder.tvStatus.visibility = View.VISIBLE
                holder.itemView.setBackgroundColor(Color.parseColor("#E3F2FD"))
            }
        }
    }

    override fun getItemCount() = items.size

    fun updatePartial(eng: String, kor: String) {
        if (items.isEmpty() || items.last().isFinal) {
            items.add(TranslationItem(eng, kor, false, false))
            notifyItemInserted(items.size - 1)
        } else {
            items.last().englishText = eng
            items.last().koreanText = kor
            notifyItemChanged(items.size - 1)
        }
    }

    fun finalizeLastItem(eng: String, kor: String) {
        if (items.isNotEmpty() && !items.last().isFinal) {
            items.last().apply { englishText = eng; koreanText = kor; isFinal = true; isStreaming = true }
            notifyItemChanged(items.size - 1)
        } else {
            items.add(TranslationItem(eng, kor, true, true))
            notifyItemInserted(items.size - 1)
        }
    }

    fun updateStreamingText(position: Int, text: String) {
        if (position in items.indices) {
            items[position].koreanText = text
            notifyItemChanged(position)
        }
    }

    fun finishStreaming(position: Int) {
        if (position in items.indices) {
            items[position].isStreaming = false
            notifyItemChanged(position)
        }
    }

    fun clearItems() { items.clear(); notifyDataSetChanged() }
}