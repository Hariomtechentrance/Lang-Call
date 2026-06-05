package com.techentrance.languageapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.techentrance.languageapp.data.CallHistoryItem
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import com.techentrance.languageapp.databinding.ActivityCallHistoryBinding
import kotlinx.coroutines.launch

class CallHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallHistoryBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        session = SessionManager(this)
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.btnBack.setOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        binding.progressHistory.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getCallHistory(session.bearerToken)
                binding.progressHistory.visibility = View.GONE
                if (resp.isSuccessful && resp.body() != null) {
                    val items = resp.body()!!.history
                    if (items.isEmpty()) {
                        binding.layoutEmpty.visibility = View.VISIBLE
                    } else {
                        binding.rvHistory.adapter = HistoryAdapter(items)
                    }
                } else {
                    binding.layoutEmpty.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                binding.progressHistory.visibility = View.GONE
                binding.layoutEmpty.visibility = View.VISIBLE
            }
        }
    }

    private inner class HistoryAdapter(private val items: List<CallHistoryItem>) :
        RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvDirection: TextView = v.findViewById(R.id.tvDirection)
            val tvOtherName: TextView = v.findViewById(R.id.tvOtherName)
            val tvLanguages: TextView = v.findViewById(R.id.tvLanguages)
            val tvDate: TextView = v.findViewById(R.id.tvDate)
            val tvDuration: TextView = v.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_call_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            holder.tvDirection.text = if (item.direction == "outgoing") "📤" else "📥"
            holder.tvOtherName.text = item.other_name
            holder.tvLanguages.text = "${item.my_language.replaceFirstChar { it.uppercase() }} ↔ ${item.other_language.replaceFirstChar { it.uppercase() }}"
            holder.tvDate.text = formatDate(item.started_at)
            holder.tvDuration.text = formatDuration(item.duration_seconds)
        }

        private fun formatDuration(seconds: Int): String {
            if (seconds == 0) return "—"
            val mm = seconds / 60
            val ss = seconds % 60
            return "%02d:%02d".format(mm, ss)
        }

        private fun formatDate(iso: String): String {
            return try {
                // Simple: take date portion from ISO string (2024-01-15T10:30:00...)
                val part = iso.substringBefore('T')
                val time = iso.substringAfter('T').take(5)
                "$part  $time"
            } catch (_: Exception) {
                iso
            }
        }
    }
}
