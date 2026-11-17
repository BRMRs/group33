package com.group33.medicationreminder.integration

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.SharedLoginActivity
import com.group33.medicationreminder.caregiver.*
import com.group33.medicationreminder.core.data.contract.CaregiverContextState
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.elder.ElderHomeActivity
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One Activity-owned model feeds both #92's header and its real feature destinations. */
internal fun featureModel(activity: AppCompatActivity): ChainViewModel? {
    val services = (activity.application as Group33Application).featureServices ?: return null
    return ViewModelProvider(activity, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = ChainViewModel(services) as T
    })[ChainViewModel::class.java]
}

internal fun realCaregiverContextSource(activity: AppCompatActivity): CaregiverNavigationContextSource {
    val model = featureModel(activity) ?: return UnavailableCaregiverContextSource
    return CaregiverNavigationContextSource {
        model.state.map { state -> when (val current = state.context) {
            is CaregiverContextState.Active -> CaregiverNavigationContext.ActiveElder(current.context.elderId, current.context.elderDisplayName)
            CaregiverContextState.NoActiveElder, is CaregiverContextState.SelectElder -> CaregiverNavigationContext.NoActiveElder
            else -> CaregiverNavigationContext.Unavailable
        } }.distinctUntilChanged()
    }
}

internal fun openAuthenticatedHome(activity: AppCompatActivity, role: UserRole) {
    val target = if (role == UserRole.ELDER) ElderHomeActivity::class.java else CaregiverHomeActivity::class.java
    activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
}

/** Entry routing is common to all auth implementations; only the anonymous controls are Debug. */
internal fun routeSharedSession(activity: AppCompatActivity) {
    val auth = (activity.application as Group33Application).featureServices?.auth ?: return
    var routed = false
    activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
        auth.observeSession().collect { session ->
            if (!routed && session.readiness == AuthReadiness.READY && session.user != null) {
                routed = true; openAuthenticatedHome(activity, session.user.role)
            }
        }
    } }
}

internal fun openSharedLogin(activity: AppCompatActivity) {
    activity.startActivity(Intent(activity, SharedLoginActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
}
