package com.group33.medicationreminder.caregiver

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.R
import com.group33.medicationreminder.databinding.ActivityCaregiverHomeBinding
import kotlinx.coroutines.launch

class CaregiverHomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCaregiverHomeBinding
    private val viewModel: CaregiverHomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addDemoScheduleButton.setOnClickListener {
            viewModel.addDemoSchedule()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.schedules.collect { schedules ->
                    binding.scheduleCount.text = resources.getQuantityString(
                        R.plurals.schedule_count,
                        schedules.size,
                        schedules.size,
                    )
                }
            }
        }
    }
}

