package com.group33.medicationreminder.caregiver

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.group33.medicationreminder.core.data.contract.CaregiverDashboardRepository
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.LocalDateRange

/**
 * C3-owned composition point for the overview component. The caller supplies the active,
 * session-bound Elder context, its Dashboard Repository, and the date range; this class does not
 * select an identity, provide a default range, or install a Repository.
 */
class CaregiverOverviewHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val overview = CaregiverOverviewView(context)
    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var viewModel: CaregiverFeedbackViewModel? = null
    // Retain the model's trusted identity even while no active context is displayed. Clearing
    // the screen or changing display text must not discard that identity's revocation state.
    private var modelContext: CaregiverElderContext? = null
    private var boundContext: CaregiverElderContext? = null
    private var boundRepository: CaregiverDashboardRepository? = null
    private var boundRange: LocalDateRange? = null

    init {
        addView(overview, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    internal fun useParentScrolling() {
        check(viewModel == null)
        overview.useParentScrolling()
        overview.layoutParams = overview.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    /**
     * Bind to the current trusted context. Pass null while signed out, restoring a session, or
     * without an authorized current Elder; this synchronously clears the screen's private state.
     */
    @MainThread
    fun bind(
        owner: LifecycleOwner,
        repository: CaregiverDashboardRepository,
        elderContext: CaregiverElderContext?,
        range: LocalDateRange,
        onHistory: (elderId: String, range: LocalDateRange) -> Unit,
    ) {
        val currentViewModel = if (elderContext == null) {
            viewModel?.clearContext()
            boundContext = null
            boundRepository = repository
            boundRange = range
            viewModel
        } else {
            require(elderContext.elderId.isNotBlank())
            if (viewModel != null && modelContext?.isCurrentFor(elderContext) != true) {
                // A new session/relationship is a new authorization context. End the old
                // model's work before opening a fresh subscription; the Repository still
                // decides whether that new subscription is authorized.
                viewModel?.clearContext()
                storeOwner.viewModelStore.clear()
                viewModel = null
            }
            val isNewModel = viewModel == null
            val model = viewModel ?: ViewModelProvider(
                storeOwner,
                factory(repository, elderContext, range),
            )[CaregiverFeedbackViewModel::class.java].also {
                viewModel = it
            }

            // Include generation and relationship identity: an Elder ID can remain the same
            // across a new login or a different authorized relationship.
            if (!isNewModel &&
                (elderContext != boundContext || repository !== boundRepository || range != boundRange)
            ) {
                model.selectContext(repository, elderContext.scope, elderContext.elderId, range)
            }
            modelContext = elderContext
            boundContext = elderContext
            boundRepository = repository
            boundRange = range
            model
        }

        currentViewModel?.let { overview.bind(owner, it, onHistory) }
    }

    override fun onDetachedFromWindow() {
        viewModel?.clearContext()
        storeOwner.viewModelStore.clear()
        viewModel = null
        modelContext = null
        boundContext = null
        boundRepository = null
        boundRange = null
        super.onDetachedFromWindow()
    }

    private companion object {
        fun factory(
            repository: CaregiverDashboardRepository,
            elderContext: CaregiverElderContext,
            range: LocalDateRange,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass != CaregiverFeedbackViewModel::class.java) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                return CaregiverFeedbackViewModel(
                    repository = repository,
                    elderId = elderContext.elderId,
                    range = range,
                    scope = elderContext.scope,
                ) as T
            }
        }
    }
}
