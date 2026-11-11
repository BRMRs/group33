package com.group33.medicationreminder.caregiver

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.DoseEventRepository
import com.group33.medicationreminder.core.data.contract.LocalDateRange
import com.group33.shared.contract.UserView
import com.group33.shared.model.UserRole

/**
 * C3 composition point for read-only history. The caller supplies the trusted Elder context,
 * bound Repository, explicit date range, lifecycle owner, and FragmentManager.
 */
class CaregiverHistoryHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val history = CaregiverHistoryView(context)
    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var viewModel: CaregiverHistoryViewModel? = null
    // Preserve the model identity across a temporary context clear so a revoked identity cannot
    // be revived by metadata or repository/range rebinding.
    private var modelContext: CaregiverElderContext? = null
    private var boundContext: CaregiverElderContext? = null
    private var boundRepository: DoseEventRepository? = null
    private var boundRange: LocalDateRange? = null

    init {
        addView(history, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @MainThread
    fun bind(
        owner: LifecycleOwner,
        repository: DoseEventRepository,
        elderContext: CaregiverElderContext?,
        range: LocalDateRange,
        fragmentManager: FragmentManager,
    ) {
        val current = if (elderContext == null) {
            viewModel?.clearContext()
            boundContext = null
            boundRepository = repository
            boundRange = range
            viewModel
        } else {
            require(elderContext.elderId.isNotBlank())
            if (viewModel != null && modelContext?.isCurrentFor(elderContext) != true) {
                viewModel?.clearContext()
                storeOwner.viewModelStore.clear()
                viewModel = null
            }
            val isNewModel = viewModel == null
            val model = viewModel ?: ViewModelProvider(
                storeOwner, factory(repository, elderContext, range),
            )[CaregiverHistoryViewModel::class.java].also { viewModel = it }

            // Compare all trusted identity fields, including generation and relationship. Context
            // display-name changes are presentation-only, but are forwarded on a changed bind.
            if (!isNewModel && (elderContext != boundContext || repository !== boundRepository || range != boundRange)) {
                model.selectContext(repository, elderContext.scope, elder(elderContext), range)
            }
            modelContext = elderContext
            boundContext = elderContext
            boundRepository = repository
            boundRange = range
            model
        }
        current?.let { history.bind(owner, it, fragmentManager) }
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
        fun elder(context: CaregiverElderContext) = UserView(context.elderId, context.elderDisplayName, UserRole.ELDER)

        fun factory(
            repository: DoseEventRepository,
            context: CaregiverElderContext,
            range: LocalDateRange,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass != CaregiverHistoryViewModel::class.java) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                return CaregiverHistoryViewModel(repository, context.scope, elder(context), range) as T
            }
        }
    }
}
