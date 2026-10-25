package com.group33.medicationreminder

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.group33.medicationreminder.core.data.contract.LanguageChoice
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.databinding.ActivitySharedLoginBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Shared visual entry. Phone authentication is wired in the next #10 delivery. */
class SharedLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySharedLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharedLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.languageButton.setOnClickListener { showLanguageChoices() }
        binding.oneTapButton.setOnClickListener { showNotConnected() }
        binding.smsButton.setOnClickListener { showNotConnected() }
    }

    private fun showLanguageChoices() {
        val choices = LanguageChoice.entries
        val labels = arrayOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_english),
            getString(R.string.language_chinese),
        )
        lifecycleScope.launch {
            val settings = (application as Group33Application).languageSettings
            val selected = choices.indexOf(settings.observeChoice().first())
            MaterialAlertDialogBuilder(this@SharedLoginActivity)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    dialog.dismiss()
                    lifecycleScope.launch {
                        when (settings.setChoice(choices[which])) {
                            is Outcome.Success -> Group33Application.applyLanguage(choices[which])
                            is Outcome.Failure -> Toast.makeText(
                                this@SharedLoginActivity,
                                R.string.language_save_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showNotConnected() {
        Toast.makeText(this, R.string.login_preview_only, Toast.LENGTH_LONG).show()
    }
}
