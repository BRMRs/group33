package com.group33.medicationreminder.integration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.group33.medicationreminder.core.data.contract.CaregiverElderContext
import com.group33.medicationreminder.core.data.contract.RepositoryScope
import org.junit.Assert.*
import org.junit.Test

class CaregiverEditorModelsTest {
    private val a = CaregiverElderContext(RepositoryScope("test", "installation", "caregiver"),
        1L, "relationship-a", "elder-a", "Elder A")
    private class Draft : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
    private fun draft(owner: ViewModelStoreOwner): Draft = ViewModelProvider(owner,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Draft() as T
        })["draft", Draft::class.java]

    @Test fun sameContextAndDisplayNameChangeRetainDraft() {
        val models = CaregiverEditorModels()
        models.select(a)
        val original = draft(models)
        assertFalse(models.select(a.copy(elderDisplayName = "Renamed")))
        assertSame(original, draft(models))
        assertFalse(original.cleared)
    }

    @Test fun switchingBackDoesNotRestorePreviousDraft() {
        val models = CaregiverEditorModels()
        models.select(a)
        val oldA = draft(models)
        models.select(a.copy(relationshipId = "relationship-b", elderId = "elder-b"))
        val oldB = draft(models)
        assertTrue(oldA.cleared)
        models.select(a)
        assertTrue(oldB.cleared)
        assertNotSame(oldA, draft(models))
    }

    @Test fun everyAuthorizationIdentityBoundaryDiscardsDraft() {
        val alternatives = listOf(a.copy(sessionGeneration = 2),
            a.copy(scope = a.scope.copy(userId = "another-account")),
            a.copy(scope = a.scope.copy(installationId = "another-installation")),
            a.copy(relationshipId = "new-relationship"), a.copy(elderId = "other-elder"), null)
        alternatives.forEach { next ->
            val models = CaregiverEditorModels()
            models.select(a)
            val old = draft(models)
            assertTrue(models.select(next))
            assertTrue(old.cleared)
            assertNotSame(old, draft(models))
        }
    }

    @Test fun activityStoreRetainsHolderUntilItIsCleared() {
        val activityStore = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val models = ViewModelProvider(owner)[CaregiverEditorModels::class.java]
        models.select(a)
        val old = draft(models)
        assertSame(models, ViewModelProvider(owner)[CaregiverEditorModels::class.java])
        assertFalse(old.cleared)
        activityStore.clear()
        assertTrue(old.cleared)
    }
}
