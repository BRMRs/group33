package com.group33.medicationreminder.elder

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ActivityElderHomeBinding
import kotlinx.coroutines.launch

class ElderHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityElderHomeBinding
    private val viewModel: ElderHomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityElderHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.elderPhotoButton.setOnClickListener {
            startActivity(android.content.Intent(this, com.group33.medicationreminder.elder.photo.ElderPhotoActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.schedules.collect { schedules ->
                    binding.todayScheduleCount.text = resources.getQuantityString(
                        R.plurals.today_schedule_count,
                        schedules.size,
                        schedules.size,
                    )
                }
            }
        }
    }
}

