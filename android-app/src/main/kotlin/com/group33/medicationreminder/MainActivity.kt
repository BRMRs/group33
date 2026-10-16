package com.group33.medicationreminder

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.group33.medicationreminder.caregiver.CaregiverHomeActivity
import com.group33.medicationreminder.databinding.ActivityMainBinding
import com.group33.medicationreminder.elder.ElderHomeActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.elderButton.setOnClickListener {
            startActivity(Intent(this, ElderHomeActivity::class.java))
        }
        binding.caregiverButton.setOnClickListener {
            startActivity(Intent(this, CaregiverHomeActivity::class.java))
        }
    }
}

