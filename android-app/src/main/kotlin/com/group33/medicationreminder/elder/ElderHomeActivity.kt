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
    private var content: com.group33.medicationreminder.integration.OnlineFeatureContent? = null
    private lateinit var binding: ActivityElderHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityElderHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.elderPhotoButton.setOnClickListener {
            startActivity(android.content.Intent(this, com.group33.medicationreminder.elder.photo.ElderPhotoActivity::class.java))
        }
        content = com.group33.medicationreminder.integration.OnlineFeatureContent.install(this,
            binding.featureContent, com.group33.shared.model.UserRole.ELDER)
        if (content != null) {
            binding.todayScheduleCount.visibility = android.view.View.GONE
            binding.elderHomeHint.visibility = android.view.View.GONE
            return
        }

        // A missing authenticated provider is unavailable, never a fake empty medication list.
        binding.todayScheduleCount.setText(R.string.chain_signed_out)
        binding.elderHomeHint.visibility = android.view.View.GONE
        binding.elderPhotoButton.isEnabled = false
    }
    override fun onResume() {
        super.onResume()
        com.group33.medicationreminder.integration.featureModel(this)?.let {
            if (it.state.value.session.readiness == com.group33.medicationreminder.core.data.contract.AuthReadiness.READY) it.refresh(reportStatus = false)
        }
    }
    override fun onDestroy() { content?.close(); super.onDestroy() }
}

