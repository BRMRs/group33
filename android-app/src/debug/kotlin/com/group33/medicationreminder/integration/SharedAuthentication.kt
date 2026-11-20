package com.group33.medicationreminder.integration

import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.model.UserRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private data class LoginState(val busy: Boolean = false, val message: Int = R.string.chain_ready)
private class AnonymousLoginModel(private val auth: AuthRepository) : ViewModel() {
    private val mutable = MutableStateFlow(LoginState())
    val state = mutable.asStateFlow()
    fun login(role: UserRole, name: String) {
        if (mutable.value.busy) return
        mutable.value = LoginState(true)
        viewModelScope.launch {
            try {
                val result = auth.login(role, name)
                val error = ((result as? Outcome.Failure)?.error as? RepositoryError.Remote)?.error?.code
                mutable.value = LoginState(message = when {
                    result is Outcome.Success -> R.string.chain_signed_in
                    error == "INSTALLATION_ROLE_CONFLICT" -> R.string.chain_fixed_role
                    else -> R.string.chain_failed
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutable.value = LoginState(message = R.string.chain_failed) }
        }
    }
}

/** The only anonymous login controls, attached inside #59's existing shared entry in Debug. */
internal fun installSharedAuthentication(activity: AppCompatActivity, host: ViewGroup) {
    // A configured phone build has one identity source; it cannot fall back to an anonymous actor.
    if ((activity.application as Group33Application).phoneAuth != null) return
    val services = checkNotNull((activity.application as Group33Application).featureServices)
    val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = AnonymousLoginModel(services.auth) as T
    })[AnonymousLoginModel::class.java]
    val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; host.addView(this) }
    fun text(resource: Int) = TextView(activity).apply { setText(resource); textSize = 18f; column.addView(this) }
    text(R.string.chain_anonymous_login)
    text(R.string.chain_boundary)
    text(R.string.chain_server).text = activity.getString(R.string.chain_server, BuildConfig.DEBUG_SERVER_URL)
    val name = EditText(activity).apply { setHint(R.string.chain_name); minHeight = 56.dp(activity); column.addView(this) }
    fun button(role: UserRole, label: Int, fallback: Int) = com.google.android.material.button.MaterialButton(activity).apply {
        setText(label); minHeight = 56.dp(activity); column.addView(this)
        setOnClickListener { model.login(role, name.text.toString().ifBlank { activity.getString(fallback) }) }
    }
    val elder = button(UserRole.ELDER, R.string.chain_login_elder, R.string.chain_demo_elder)
    val caregiver = button(UserRole.CAREGIVER, R.string.chain_login_caregiver, R.string.chain_demo_caregiver)
    val status = text(R.string.chain_ready)
    activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { model.state.collect { state ->
            elder.isEnabled = !state.busy; caregiver.isEnabled = !state.busy
            status.setText(if (state.busy) R.string.chain_working else state.message)
        } }
    } }
}
private fun Int.dp(activity: AppCompatActivity) = (this * activity.resources.displayMetrics.density).toInt()
