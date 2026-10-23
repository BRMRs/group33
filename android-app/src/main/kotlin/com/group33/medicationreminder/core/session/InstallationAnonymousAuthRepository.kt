package com.group33.medicationreminder.core.session

import android.content.Context
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.AuthRepository
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.medicationreminder.core.data.contract.RepositoryError
import com.group33.medicationreminder.core.data.contract.SessionView
import com.group33.shared.contract.UserView
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preview anonymous installation login for Elder entry.
 * Persists a random installation UUID locally; does not call HTTP or store JWTs in UI state.
 * Real `/api/v1/auth/anonymous` + private token storage remains S1/C1 work.
 */
class InstallationAnonymousAuthRepository(
    context: Context,
) : AuthRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sessionState = MutableStateFlow(restoreSession())

    override fun observeSession(): Flow<SessionView> = sessionState.asStateFlow()

    override suspend fun login(role: UserRole, displayName: String): Outcome<UserView> {
        val trimmedName = displayName.trim().ifEmpty { return Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT)) }
        val existingRole = prefs.getString(KEY_ROLE, null)
        if (existingRole != null && existingRole != role.name) {
            return Outcome.Failure(RepositoryError.Local(LocalFailureCode.INVALID_INPUT))
        }
        val installationId = installationId()
        prefs.edit()
            .putString(KEY_ROLE, role.name)
            .putString(KEY_DISPLAY_NAME, trimmedName)
            .apply()
        val user = User(
            id = "install:$installationId",
            displayName = trimmedName,
            role = role,
        )
        val ready = SessionView(AuthReadiness.READY, user)
        sessionState.value = ready
        return Outcome.Success(user)
    }

    private fun installationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    private fun restoreSession(): SessionView {
        val roleName = prefs.getString(KEY_ROLE, null) ?: return SessionView(AuthReadiness.SIGNED_OUT, null)
        val role = runCatching { UserRole.valueOf(roleName) }.getOrNull()
            ?: return SessionView(AuthReadiness.SIGNED_OUT, null)
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: return SessionView(AuthReadiness.SIGNED_OUT, null)
        val user = User(id = "install:${installationId()}", displayName = displayName, role = role)
        return SessionView(AuthReadiness.READY, user)
    }

    companion object {
        private const val PREFS = "group33_anonymous_installation"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_ROLE = "role"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
