package com.group33.medicationreminder.caregiver

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverNavigationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val context = MutableStateFlow<CaregiverNavigationContext>(CaregiverNavigationContext.Unavailable)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun `navigation works without an Elder but exposes no Elder identity`() = runTest(dispatcher) {
        val model = model(); runCurrent()
        model.open(CaregiverDestination.PLANS)
        assertEquals(CaregiverDestination.PLANS, model.state.value.destination)
        assertNull(model.state.value.context as? CaregiverNavigationContext.ActiveElder)
        model.open(CaregiverDestination.RECORDS)
        assertEquals(CaregiverDestination.RECORDS, model.state.value.destination)
        model.open(CaregiverDestination.SETTINGS)
        assertEquals(CaregiverDestination.SETTINGS, model.state.value.destination)
    }

    @Test fun `switching and revoking an Elder immediately removes the old identity`() = runTest(dispatcher) {
        val model = model(); runCurrent()
        context.value = CaregiverNavigationContext.ActiveElder("A", "Elder A"); runCurrent()
        assertEquals("A", (model.state.value.context as CaregiverNavigationContext.ActiveElder).id)
        context.value = CaregiverNavigationContext.ActiveElder("B", "Elder B"); runCurrent()
        assertEquals("B", (model.state.value.context as CaregiverNavigationContext.ActiveElder).id)
        context.value = CaregiverNavigationContext.NoActiveElder; runCurrent()
        assertNull(model.state.value.context as? CaregiverNavigationContext.ActiveElder)
        context.value = CaregiverNavigationContext.Unavailable; runCurrent()
        assertNull(model.state.value.context as? CaregiverNavigationContext.ActiveElder)
    }

    private fun model() = CaregiverNavigationViewModel(CaregiverNavigationContextSource { context })
        .also { store.put("navigation", it) }
}
