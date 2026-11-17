package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A trusted session/relationship adapter supplies this; a screen never invents an authorized Elder. */
sealed interface CaregiverNavigationContext {
    data object Unavailable : CaregiverNavigationContext
    data object NoActiveElder : CaregiverNavigationContext
    data class ActiveElder(val id: String, val displayName: String) : CaregiverNavigationContext {
        init { require(id.isNotBlank() && displayName.isNotBlank()) }
    }
}

fun interface CaregiverNavigationContextSource {
    fun observe(): Flow<CaregiverNavigationContext>
}

object UnavailableCaregiverContextSource : CaregiverNavigationContextSource {
    override fun observe(): Flow<CaregiverNavigationContext> = flowOf(CaregiverNavigationContext.Unavailable)
}

enum class CaregiverDestination { PLANS, RECORDS, SETTINGS }

data class CaregiverNavigationState(
    val context: CaregiverNavigationContext = CaregiverNavigationContext.Unavailable,
    val destination: CaregiverDestination = CaregiverDestination.PLANS,
)

class CaregiverNavigationViewModel(source: CaregiverNavigationContextSource) : ViewModel() {
    private val mutableState = MutableStateFlow(CaregiverNavigationState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            source.observe().collect { context ->
                // Replacing the whole context removes the previous Elder immediately on sign-out or revocation.
                mutableState.update { it.copy(context = context) }
            }
        }
    }

    fun open(destination: CaregiverDestination) {
        mutableState.update { it.copy(destination = destination) }
    }

    class Factory(private val source: CaregiverNavigationContextSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CaregiverNavigationViewModel(source) as T
    }
}
