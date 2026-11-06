package com.group33.medicationreminder.caregiver

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.core.data.contract.LocalFailureCode
import com.group33.medicationreminder.core.data.contract.OnlineCommandKind
import com.group33.medicationreminder.databinding.ViewPairingRequestBinding
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** C1 pairing page. Its caller supplies the reviewed Repository and trusted command scope. */
class PairingRequestScreen(
    private val activity: AppCompatActivity,
    private val viewModel: PairingWorkflowViewModel,
) {
    private val binding = ViewPairingRequestBinding.inflate(activity.layoutInflater)
    val view: View = binding.root

    init {
        val maximumWidth = activity.resources.getDimensionPixelSize(R.dimen.group33_content_max_width)
        val outerPadding = activity.resources.getDimensionPixelSize(R.dimen.group33_screen_padding) * 2
        binding.pairingContent.layoutParams = FrameLayout.LayoutParams(
            minOf(activity.resources.displayMetrics.widthPixels, maximumWidth + outerPadding),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL,
        )
        binding.root.requestFocus()
        ViewCompat.setAccessibilityHeading(binding.pairingTitle, true)
        binding.pairingNoticeCard.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        binding.pairingCodeInput.doAfterTextChanged { viewModel.setCode(it?.toString().orEmpty()) }
        binding.pairingSubmitButton.setOnClickListener { submit() }
        binding.pairingCodeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        binding.pairingRefreshButton.setOnClickListener { viewModel.refresh() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun submit() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.pairingCodeInput.windowToken, 0)
        binding.pairingCodeInput.clearFocus()
        viewModel.submitCode()
    }

    private fun render(state: PairingWorkflowState) {
        if (binding.pairingCodeInput.text?.toString() != state.code) {
            binding.pairingCodeInput.setText(state.code)
            binding.pairingCodeInput.setSelection(state.code.length)
        }
        binding.pairingSubmitButton.isEnabled = !state.busy
        binding.pairingRefreshButton.isEnabled = !state.busy
        binding.pairingCodeLayout.error = if (state.notice == PairingNotice.InvalidCode) {
            activity.getString(R.string.pairing_notice_invalid_body)
        } else null

        val notice = noticeResources(state.notice, state.lastCommandKind)
        binding.pairingNoticeCard.visibility = if (notice == null) View.GONE else View.VISIBLE
        notice?.let { (title, body) ->
            binding.pairingNoticeTitle.setText(title)
            binding.pairingNoticeBody.setText(body)
        }

        binding.pairingEmptyMessage.visibility = if (state.requests.isEmpty()) View.VISIBLE else View.GONE
        binding.pairingRequestsContainer.removeAllViews()
        state.requests.forEach { request -> addRequest(request, state.busy) }
    }

    private fun addRequest(request: PairingRequestView, busy: Boolean) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val card = MaterialCardView(activity).apply {
            radius = (12 * activity.resources.displayMetrics.density)
            cardElevation = 0f
            setCardBackgroundColor(activity.getColor(R.color.group33_surface))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val status = TextView(activity).apply {
            text = activity.getString(statusResource(request.status))
            setTextAppearance(R.style.TextAppearance_Group33_Body)
            contentDescription = text
        }
        content.addView(status)
        if (request.status == PairingRequestStatus.PENDING) {
            content.addView(MaterialButton(activity).apply {
                setText(R.string.pairing_cancel)
                isEnabled = !busy
                minHeight = activity.resources.getDimensionPixelSize(R.dimen.group33_action_min_height)
                setOnClickListener { viewModel.cancel(request.id) }
            })
        }
        card.addView(content)
        binding.pairingRequestsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = padding })
    }

    private fun noticeResources(notice: PairingNotice?, kind: OnlineCommandKind?): Pair<Int, Int>? = when (notice) {
        null -> null
        PairingNotice.InvalidCode -> R.string.pairing_notice_invalid_title to R.string.pairing_notice_invalid_body
        PairingNotice.SessionUnavailable -> R.string.pairing_notice_session_title to R.string.pairing_notice_session_body
        PairingNotice.CommandConfirmed -> if (kind == OnlineCommandKind.CANCEL_PAIRING_REQUEST) {
            R.string.pairing_notice_cancelled_title to R.string.pairing_notice_cancelled_body
        } else R.string.pairing_notice_pending_title to R.string.pairing_notice_pending_body
        PairingNotice.ResultUnknown -> R.string.pairing_notice_unknown_title to R.string.pairing_notice_unknown_body
        PairingNotice.RefreshFailed -> R.string.pairing_notice_refresh_failed_title to R.string.pairing_notice_refresh_failed_body
        PairingNotice.Refreshed -> R.string.pairing_notice_refresh_title to R.string.pairing_notice_refresh_body
        is PairingNotice.Rejected -> when (notice.error.code) {
            "TOO_MANY_REQUESTS", "RATE_LIMITED" -> R.string.pairing_notice_rate_title to R.string.pairing_notice_rate_body
            else -> R.string.pairing_notice_rejected_title to R.string.pairing_notice_rejected_body
        }
        is PairingNotice.NotSubmitted -> when (notice.code) {
            LocalFailureCode.OFFLINE -> R.string.pairing_notice_offline_title to R.string.pairing_notice_offline_body
            LocalFailureCode.SESSION_NOT_READY -> R.string.pairing_notice_session_title to R.string.pairing_notice_session_body
            else -> R.string.pairing_notice_unavailable_title to R.string.pairing_notice_unavailable_body
        }
    }

    private fun statusResource(status: PairingRequestStatus): Int = when (status) {
        PairingRequestStatus.PENDING -> R.string.pairing_status_pending
        PairingRequestStatus.ACCEPTED -> R.string.pairing_status_accepted
        PairingRequestStatus.REJECTED -> R.string.pairing_status_rejected
        PairingRequestStatus.CANCELLED -> R.string.pairing_status_cancelled
        PairingRequestStatus.EXPIRED -> R.string.pairing_status_expired
    }
}
