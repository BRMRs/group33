package com.group33.medicationreminder.integration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext

/** Retains drafts across page/configuration changes, only while the authorized context is current. */
internal class CaregiverEditorModels : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var context: CaregiverElderContext? = null

    fun select(next: CaregiverElderContext?): Boolean {
        val changed = if (next == null) context != null else context?.isCurrentFor(next) != true
        if (changed) viewModelStore.clear()
        context = next
        return changed
    }

    override fun onCleared() { viewModelStore.clear() }
}
