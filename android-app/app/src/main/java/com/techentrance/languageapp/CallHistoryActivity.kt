package com.techentrance.languageapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
                        binding.rvHistory.adapter = HistoryAdapter(items.toMutableList())
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

    private fun blockUser(phone: String, name: String, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Block $name?")
            .setMessage("$name will no longer be able to call you on LangCall.")
            .setPositiveButton("Block") { _, _ ->
                lifecycleScope.launch {
                    try {
                        RetrofitClient.api.blockUser(session.bearerToken, phone)
                        Toast.makeText(this@CallHistoryActivity, "$name blocked", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (_: Exception) {
                        Toast.makeText(this@CallHistoryActivity, "Could not block user", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class HistoryAdapter(private val items: MutableList<CallHistoryItem>) :
        RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvDirection: TextView = v.findViewById(R.id.tvDirection)
            val tvOtherName: TextView = v.findViewById(R.id.tvOtherName)
            val tvLanguages: TextView = v.findViewById(R.id.tvLanguages)
            val tvDate: TextView = v.findViewById(R.id.tvDate)
            val tvDuration: TextView = v.findViewById(R.id.tvDuration)
            val btnBlock: TextView = v.findViewById(R.id.btnBlock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_call_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isMissed = item.status == "missed" || item.status == "timeout"

            // Direction icon based on status
            holder.tvDirection.text = when {
                isMissed -> "📵"
                item.direction == "outgoing" -> "📤"
                else -> "📥"
            }

            holder.tvOtherName.text = item.other_name
            holder.tvOtherName.setTextColor(
                if (isMissed) 0xFFFF5722.toInt() else 0xFFFFFFFF.toInt()
            )
            holder.tvLanguages.text =
                "${item.my_language.replaceFirstChar { it.uppercase() }} ↔ ${item.other_language.replaceFirstChar { it.uppercase() }}"
            holder.tvDate.text = formatDate(item.started_at)
            holder.tvDuration.text = when {
                isMissed -> "Missed"
                else -> formatDuration(item.duration_seconds)
            }
            holder.tvDuration.setTextColor(
                if (isMissed) 0xFFFF5722.toInt() else 0xFF4CAF50.toInt()
            )

            holder.btnBlock.setOnClickListener {
                // We need a phone number to block — use other_name as fallback label
                val pos = holder.adapterPosition
                blockUser(item.other_name, item.other_name) {
                    items.removeAt(pos)
                    notifyItemRemoved(pos)
                }
            }
        }

        private fun formatDuration(seconds: Int): String {
            if (seconds == 0) return "—"
            return "%02d:%02d".format(seconds / 60, seconds % 60)
        }

        private fun formatDate(iso: String): String {
            return try {
                "${iso.substringBefore('T')}  ${iso.substringAfter('T').take(5)}"
            } catch (_: Exception) { iso }
        }
    }
}
