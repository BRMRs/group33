package com.group33.medicationreminder.caregiver

import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.*
import com.group33.shared.contract.*
import com.group33.shared.model.ApiError
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** Isolated UI reviewer route. Scenario values are never submitted to a server. */
class PairingRequestPreviewActivity : AppCompatActivity() {
    private val repository = PreviewPairingRepository()
    private val viewModel: PairingWorkflowViewModel by viewModels {
        PairingWorkflowViewModel.Factory(repository, PairingCommandContextProvider { kind, target ->
            CommandContext(UUID.randomUUID().toString(), RepositoryScope("preview", "preview", "caregiver-preview"), kind, target)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.group33_background))
        }
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.group33_background))
            navigationIcon = ContextCompat.getDrawable(this@PairingRequestPreviewActivity, R.drawable.ic_arrow_back_24)
            setNavigationIconTint(getColor(R.color.group33_text_primary))
            navigationContentDescription = getString(R.string.pairing_back)
            minimumHeight = (56 * resources.displayMetrics.density).toInt()
            setOnClickListener { finish() }
            setNavigationOnClickListener { finish() }
        }
        val banner = TextView(this).apply {
            setText(R.string.pairing_preview_banner)
            setTextAppearance(R.style.TextAppearance_Group33_Supporting)
            val pad = resources.getDimensionPixelSize(R.dimen.group33_screen_padding)
            setPadding(pad, 0, pad, 0)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(banner)
        root.addView(PairingRequestScreen(this, viewModel).view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(root)
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}

private class PreviewPairingRepository : PairingRepository {
    private val caregiver = User("caregiver-preview", "Caregiver", UserRole.CAREGIVER)
    private val requests = MutableStateFlow(Cached(emptyList<PairingRequestView>(), true, 1L, false, AccessState.AUTHORIZED))
    private var currentCode: String? = null

    override fun observeRequests() = requests
    override fun observeRelationships() = flowOf(Cached(emptyList<CareRelationshipView>(), true, 1L, false, AccessState.AUTHORIZED))

    override suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView> {
        if (code == "00000004") return OnlineCommandResult.Rejected(
            command.operationId,
            RepositoryError.Remote(ApiError("TOO_MANY_REQUESTS", "", true, "preview")),
        )
        if (code == "00000005") return OnlineCommandResult.AwaitingConfirmation(command.operationId)
        currentCode = code
        val pending = PairingRequestView(
            "preview-${UUID.randomUUID()}", "elder-preview", caregiver,
            PairingRequestStatus.PENDING, System.currentTimeMillis() + 600_000,
        )
        requests.value = requests.value.copy(value = listOf(pending))
        return OnlineCommandResult.Confirmed(command.operationId, pending, CacheState.APPLIED)
    }

    override suspend fun cancelRequest(requestId: String, command: CommandContext): OnlineCommandResult<Unit> {
        requests.value = requests.value.copy(value = requests.value.value.map { request ->
            if (request.id == requestId && request.status == PairingRequestStatus.PENDING) request.copy(
                status = PairingRequestStatus.CANCELLED,
                closedReason = PairingClosedReason.CAREGIVER_CANCELLED,
            ) else request
        })
        return OnlineCommandResult.Confirmed(command.operationId, Unit, CacheState.APPLIED)
    }

    override suspend fun refresh(): Outcome<Unit> {
        val status = when (currentCode) {
            "00000001" -> PairingRequestStatus.ACCEPTED
            "00000002" -> PairingRequestStatus.REJECTED
            "00000003" -> PairingRequestStatus.EXPIRED
            else -> null
        }
        if (status != null) {
            requests.value = requests.value.copy(value = requests.value.value.map { request ->
                if (request.status != PairingRequestStatus.PENDING) request else request.copy(
                    status = status,
                    relationshipId = if (status == PairingRequestStatus.ACCEPTED) "relationship-preview" else null,
                    closedReason = when (status) {
                        PairingRequestStatus.REJECTED -> PairingClosedReason.ELDER_REJECTED
                        PairingRequestStatus.EXPIRED -> PairingClosedReason.CODE_EXPIRED
                        else -> null
                    },
                )
            })
        }
        return Outcome.Success(Unit)
    }

    override suspend fun issueCode(command: CommandContext) = unavailable<PairingCodeView>(command)
    override suspend fun cancelCode(codeId: String, command: CommandContext) = unavailable<Unit>(command)
    override suspend fun respond(requestId: String, accept: Boolean, command: CommandContext) = unavailable<PairingDecisionResult>(command)
    override suspend fun revoke(relationshipId: String, command: CommandContext) = unavailable<CareRelationshipView>(command)
    private fun <T> unavailable(command: CommandContext): OnlineCommandResult<T> =
        OnlineCommandResult.NotSubmitted(command.operationId, RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE))
}
