package com.techentrance.languageapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.techentrance.languageapp.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        Triple("🌏", "Break Language Barriers", "Call anyone across India and speak in your own language — they hear it translated instantly in theirs."),
        Triple("🎤", "You Speak, They Hear", "Speak Hindi, they speak Tamil — both understand each other perfectly. No typing. No delays. Just talk."),
        Triple("🔒", "Safe & Private", "Your calls are end-to-end secured. Accounts are protected against hacking with automatic lockout and encrypted storage."),
        Triple("📞", "Super Easy to Use", "Just enter their phone number, tap Call, and start talking. Both people need LangCall installed — that's it!"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        binding.viewPager.adapter = OnboardingAdapter()
        setupDots()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                val isLast = position == pages.size - 1
                binding.btnNext.text = if (isLast) "Get Started 🚀" else "Next →"
                binding.btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
            }
        })

        binding.btnNext.setOnClickListener {
            if (binding.viewPager.currentItem < pages.size - 1) {
                binding.viewPager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        getSharedPreferences("langcall_app", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setupDots() {
        repeat(pages.size) { i ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 12).also {
                    it.setMargins(6, 0, 6, 0)
                }
                background = ContextCompat.getDrawable(this@OnboardingActivity,
                    if (i == 0) android.R.drawable.btn_radio else android.R.drawable.btn_default_small)
            }
            binding.dotsLayout.addView(dot)
        }
    }

    private fun updateDots(selected: Int) {
        for (i in 0 until binding.dotsLayout.childCount) {
            val dot = binding.dotsLayout.getChildAt(i)
            dot.alpha = if (i == selected) 1f else 0.4f
        }
    }

    private inner class OnboardingAdapter : RecyclerView.Adapter<OnboardingAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvEmoji: TextView = v.findViewById(R.id.tvEmoji)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvDesc: TextView = v.findViewById(R.id.tvDesc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
        )

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (emoji, title, desc) = pages[position]
            holder.tvEmoji.text = emoji
            holder.tvTitle.text = title
            holder.tvDesc.text = desc
        }
    }
}
