package com.group33.medicationreminder

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.lifecycleScope
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.databinding.ActivitySharedLoginBinding
import kotlinx.coroutines.launch

internal fun nextLanguageChoice(displayedLanguage: String): LanguageChoice =
    if (displayedLanguage == "zh") LanguageChoice.ENGLISH else LanguageChoice.SIMPLIFIED_CHINESE

/** Shared visual entry. Phone authentication is wired in the next #10 delivery. */
class SharedLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySharedLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val displayedLanguage = ConfigurationCompat.getLocales(resources.configuration)[0]?.language.orEmpty()
        binding.languageButton.setText(
            if (displayedLanguage == "zh") R.string.language_chinese else R.string.language_english,
        )
        binding.languageButton.contentDescription = getString(R.string.language_switch_action)
        binding.languageButton.setOnClickListener { switchLanguage(displayedLanguage) }
        binding.oneTapButton.setOnClickListener { showNotConnected() }
        binding.smsButton.setOnClickListener { showNotConnected() }
    }

    private fun switchLanguage(displayedLanguage: String) {
        val target = nextLanguageChoice(displayedLanguage)
        binding.languageButton.isEnabled = false
        lifecycleScope.launch {
            val settings = (application as Group33Application).languageSettings
            when (settings.setChoice(target)) {
                is Outcome.Success -> Group33Application.applyLanguage(target)
                is Outcome.Failure -> {
                    binding.languageButton.isEnabled = true
                    Toast.makeText(this@SharedLoginActivity, R.string.language_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showNotConnected() {
        Toast.makeText(this, R.string.login_preview_only, Toast.LENGTH_LONG).show()
    }
}
