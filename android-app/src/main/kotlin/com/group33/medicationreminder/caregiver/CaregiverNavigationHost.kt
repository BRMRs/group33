package com.group33.medicationreminder.caregiver

import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

/** Public host contract for C2/C3 destination views mounted inside the shared caregiver shell. */
interface CaregiverNavigationHost : LifecycleOwner {
    val navigationState: StateFlow<CaregiverNavigationState>
    val fragmentManager: FragmentManager

    /** True for the Debug-only sample route; consumers must not treat its elder as authorized. */
    val isLocalNavigationPreview: Boolean

    fun mountDestinationContent(destination: CaregiverDestination, content: View)
    fun clearDestinationContent(destination: CaregiverDestination)
    fun navigateTo(destination: CaregiverDestination)
}
