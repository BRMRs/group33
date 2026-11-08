package com.group33.medicationreminder.auth

import android.app.Activity
import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import com.alicom.fusion.auth.AlicomFusionAuthCallBack
import com.alicom.fusion.auth.AlicomFusionAuthUICallBack
import com.alicom.fusion.auth.AlicomFusionBusiness
import com.alicom.fusion.auth.AlicomFusionLog
import com.alicom.fusion.auth.HalfWayVerifyResult
import com.alicom.fusion.auth.error.AlicomFusionEvent
import com.alicom.fusion.auth.numberauth.FusionNumberAuthModel
import com.alicom.fusion.auth.numberauth.NumberAuthUtil
import com.alicom.fusion.auth.smsauth.AlicomFusionVerifyCodeView
import com.alicom.fusion.auth.token.AlicomFusionAuthToken
import com.alicom.fusion.auth.upsms.AlicomFusionUpSMSView
import com.group33.medicationreminder.R
import com.group33.medicationreminder.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-scoped adapter. Call start only after the user has accepted the app's privacy policy. */
internal class FusionSdkBridge(
    private val activity: Activity,
    private val preferSms: Boolean,
    private val smsPhone: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Must return a previously fetched fresh SDK token without blocking the UI thread. */
        fun refreshSdkToken(): String?

        /** Send the token to the app server. Call completion only after the server verifies it. */
        fun verifyOnServer(token: String, completion: (Boolean, PhoneAuthError?) -> Unit)

        fun finished(verified: Boolean, error: PhoneAuthError?)
    }

    private val business = AlicomFusionBusiness()
    private val started = AtomicBoolean(false)
    private val sceneStarted = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val verifying = AtomicBoolean(false)
    private val smsSwitchStarted = AtomicBoolean(false)
    private val smsSwitchScheduled = AtomicBoolean(false)
    private val smsProofPageShown = AtomicBoolean(false)
    private var authPage: Activity? = null
    private var smsPage: Activity? = null
    private var upSmsPage: Activity? = null
    private var pendingSmsModel: FusionNumberAuthModel? = null
    private val smsPageObserver = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(screen: Activity) {
            if (screen.javaClass.name == "com.alicom.fusion.auth.smsauth.FusionSmsActivity") {
                trace("provider SMS page opened")
                smsPage = screen
                smsProofPageShown.set(true)
                lastFailure = null
            }
            if (screen.javaClass.name == "com.alicom.fusion.auth.upsms.AlicomFusionUpSmsActivity") {
                trace("provider upstream SMS page opened")
                // Alibaba may require this second SMS proof after rate limiting.
                // Its successful token must enter the same app sign-in flow.
                upSmsPage = screen
                smsProofPageShown.set(true)
            }
            if (screen === activity && smsProofPageShown.get() && !finished.get()) {
                trace("returned to app login from provider")
                screen.window.decorView.postDelayed({
                    // Provider pages can remain created (and isFinishing=false)
                    // after the user returns to this activity. Their stale
                    // references must not leave the login spinner running.
                    if (!finished.get() && !verifying.get() &&
                        !screen.isFinishing && !screen.isDestroyed &&
                        screen.hasWindowFocus()
                    ) {
                        lastFailure = PhoneAuthError.CANCELLED
                        finish(false)
                    }
                }, 1000)
            }
            if (screen.javaClass.name == "com.alicom.fusion.auth.numberauth.FusionNumberAuthActivity" ||
                screen.javaClass.name == "com.mobile.auth.gatewayauth.LoginAuthActivity"
            ) {
                authPage = screen
                openSmsIfReady()
            }
        }
        override fun onActivityPaused(screen: Activity) {
            if (authPage === screen) authPage = null
        }
        override fun onActivityCreated(screen: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(screen: Activity) = Unit
        override fun onActivityStopped(screen: Activity) = Unit
        override fun onActivitySaveInstanceState(screen: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(screen: Activity) {
            if (smsPage === screen) smsPage = null
            if (upSmsPage === screen) upSmsPage = null
        }
    }
    @Volatile private var initialized = false
    @Volatile private var verified = false
    @Volatile private var lastFailure: PhoneAuthError? = null

    private fun trace(stage: String) {
        if (BuildConfig.DEBUG) Log.i("Group33Auth", stage)
    }

    fun start(schemeCode: String, token: String) {
        require(schemeCode.isNotBlank() && token.isNotBlank())
        check(started.compareAndSet(false, true))
        if (preferSms) activity.application.registerActivityLifecycleCallbacks(smsPageObserver)
        AlicomFusionLog.setLogEnable(false)
        AlicomFusionBusiness.useSDKSupplyUMSDK(true, "group33")
        business.initWithToken(activity.applicationContext, schemeCode, authToken(token))
        initialized = true
        AlicomFusionLog.setLogEnable(false)
        business.setAlicomFusionAuthCallBack(object : AlicomFusionAuthCallBack {
            override fun onSDKTokenUpdate(): AlicomFusionAuthToken? =
                listener.refreshSdkToken()?.takeIf { it.isNotBlank() }?.let(::authToken)

            override fun onSDKTokenAuthSuccess() {
                if (sceneStarted.compareAndSet(false, true)) {
                    activity.runOnUiThread {
                        if (!finished.get()) business.startSceneWithTemplateId(activity, SCENE, smsSwitch)
                    }
                }
            }

            override fun onSDKTokenAuthFailure(token: AlicomFusionAuthToken?, event: AlicomFusionEvent?) {
                lastFailure = providerError(event)
                finish(false)
            }

            override fun onVerifySuccess(token: String, nodeName: String, event: AlicomFusionEvent) {
                trace("provider proof received; smsPageShown=${smsProofPageShown.get()}")
                if (preferSms && !smsProofPageShown.get()) {
                    activity.runOnUiThread {
                        if (!finished.get()) business.continueSceneWithTemplateId(SCENE, false)
                    }
                    return
                }
                if (!verifying.compareAndSet(false, true)) return
                listener.verifyOnServer(token) { accepted, serverError ->
                    activity.runOnUiThread {
                        if (!finished.get()) {
                            trace("server proof result; accepted=$accepted; error=${serverError?.name ?: "none"}")
                            verified = accepted
                            if (preferSms) {
                                // The SDK has already checked the SMS proof. Do not
                                // resume its fallback chain after our server rejects
                                // the token: that reopens the cached-number page.
                                if (!accepted) lastFailure = serverError ?: PhoneAuthError.INVALID_CODE
                                finish(accepted)
                            } else {
                                verifying.set(false)
                                business.continueSceneWithTemplateId(SCENE, accepted)
                            }
                        }
                    }
                }
            }

            override fun onHalfWayVerifySuccess(
                nodeName: String, token: String, event: AlicomFusionEvent, result: HalfWayVerifyResult,
            ) {
                // The login scene does not use account-change intermediate verification.
                result.verifyResult(false)
            }

            override fun onVerifyFailed(event: AlicomFusionEvent, nodeName: String) {
                trace("provider proof failed; code=${event.errorCode}; smsPageShown=${smsProofPageShown.get()}")
                activity.runOnUiThread {
                    if (finished.get()) return@runOnUiThread
                    lastFailure = providerError(event)
                    if (preferSms && !smsProofPageShown.get()) {
                        // The carrier node failed before SMS started. Advance once
                        // to the user's requested SMS method.
                        business.continueSceneWithTemplateId(SCENE, false)
                    } else if (preferSms) {
                        // Retrying the failed SMS node here can reopen the same
                        // rate-limit page with its cached phone number forever.
                        finish(false)
                    }
                    else finish(false)
                }
            }

            override fun onTemplateFinish(event: AlicomFusionEvent) {
                trace("provider scene ended; verifying=${verifying.get()}")
                // The SDK can close its last page while our server is still
                // exchanging the proof token for a verified phone number.
                // Keep that result alive so a successful SMS reaches role selection.
                if (verifying.get()) {
                    return
                }
                if (!verified && lastFailure == null) lastFailure = providerError(event)
                finish(verified)
            }
            override fun onAuthEvent(event: AlicomFusionEvent) {
                if (event.errorCode in setOf("510001", "510004", "610001", "610004", "600005", "600007", "600008")) {
                    trace("provider event; code=${event.errorCode}")
                }
                if (preferSms && !verifying.get() &&
                    event.errorCode in setOf("510001", "510004", "610001", "610004")) {
                    lastFailure = PhoneAuthError.CANCELLED
                    finish(false)
                }
                if (!preferSms && event.errorCode == "400007") {
                    lastFailure = providerError(event)
                    finish(false)
                }
            }
            override fun onGetPhoneNumberForVerification(nodeId: String, event: AlicomFusionEvent): String = smsPhone
            // This is a temporary interruption; Alibaba says the scene cannot be stopped here.
            override fun onVerifyInterrupt(event: AlicomFusionEvent) {
                if (!preferSms) {
                    lastFailure = providerError(event)
                    finish(false)
                }
            }
        })
    }

    fun close() = finish(false)

    private fun finish(success: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        activity.runOnUiThread {
            if (preferSms) activity.application.unregisterActivityLifecycleCallbacks(smsPageObserver)
            pendingSmsModel = null
            authPage = null
            if (preferSms) smsPage?.finish()
            if (preferSms) upSmsPage?.finish()
            smsPage = null
            upSmsPage = null
            if (initialized && sceneStarted.get()) runCatching { business.stopSceneWithTemplateId(SCENE) }
            if (initialized) runCatching { business.destory() }
            listener.finished(success, if (success) null else lastFailure ?: if (preferSms) PhoneAuthError.UNKNOWN else PhoneAuthError.ONE_TAP_UNAVAILABLE)
        }
    }

    private fun authToken(value: String) = AlicomFusionAuthToken().apply { setAuthToken(value) }

    private fun providerError(event: AlicomFusionEvent?): PhoneAuthError {
        val codes = listOfNotNull(event?.errorCode, event?.innerCode)
        return when {
            "1600007" in codes -> PhoneAuthError.NO_SIM
            codes.any { it in setOf("1600008", "10101", "410004", "410003") } -> PhoneAuthError.CELLULAR_UNAVAILABLE
            codes.any { it in setOf("500009", "1600014", "700003", "2206", "20101") } -> PhoneAuthError.TOO_MANY_REQUESTS
            codes.any { it in setOf("1600015", "10100", "10103", "100003", "100004", "500", "410000") } -> PhoneAuthError.NETWORK
            else -> if (preferSms) PhoneAuthError.UNKNOWN else PhoneAuthError.ONE_TAP_UNAVAILABLE
        }
    }

    private fun openSmsIfReady() {
        if (!preferSms || finished.get() || smsSwitchStarted.get()) return
        val page = authPage ?: return
        val model = pendingSmsModel ?: return
        if (!smsSwitchScheduled.compareAndSet(false, true)) return
        fun attempt(remaining: Int) {
            page.window.decorView.postDelayed({
                if (finished.get() || authPage !== page) {
                    smsSwitchScheduled.set(false)
                    return@postDelayed
                }
                if (NumberAuthUtil.getInstance().loginCallBack != null) {
                    if (smsSwitchStarted.compareAndSet(false, true)) model.otherPhoneLogin()
                    smsSwitchScheduled.set(false)
                } else if (remaining > 0) {
                    attempt(remaining - 1)
                } else {
                    smsSwitchScheduled.set(false)
                }
            }, 100)
        }
        attempt(20)
    }

    private val smsSwitch = object : AlicomFusionAuthUICallBack {
        override fun onPhoneNumberVerifyUICustomView(templateId: String, nodeId: String, config: FusionNumberAuthModel) {
            // Keep the app's two entry points distinct: one tap stays on carrier
            // authorization; only the explicit SMS button enters code verification.
            config.builder.setSwitchAccHidden(!preferSms).setSwitchAccText(activity.getString(R.string.login_sms))
            if (preferSms) config.setSwitchLoginBack { config.otherPhoneLogin() }
            if (preferSms) activity.runOnUiThread {
                pendingSmsModel = config
                openSmsIfReady()
            }
        }

        override fun onSMSCodeVerifyUICustomView(
            templateId: String, nodeId: String, autoInput: Boolean, view: AlicomFusionVerifyCodeView,
        ) {
            if (preferSms) smsProofPageShown.set(true)
            // Style the provider's single SMS flow without replacing its controls or
            // resend timer. The SDK remains responsible for sending and checking codes.
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density + 0.5f).toInt()
            fun rounded(fill: Int, outline: Int? = null) = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(20).toFloat()
                if (outline != null) setStroke(dp(1), outline)
            }
            val action = activity.getColor(R.color.group33_action)
            val primary = activity.getColor(R.color.group33_text_primary)
            view.rootRl?.setBackgroundColor(android.graphics.Color.rgb(253, 249, 255))
            view.titleContentTV?.apply {
                textSize = 24f
                setTextColor(primary)
            }
            view.sendVerifyCodeView?.apply {
                sendSmsHint?.apply { textSize = 20f; setTextColor(primary) }
                sendSmsCode?.apply {
                    textSize = 24f
                    setTextColor(action)
                    gravity = Gravity.CENTER
                    minHeight = dp(80)
                }
                listOfNotNull(firstCode, secondCode, thirdCode, fourCode, fivthCode, sixthCode)
                    .forEach { it.textSize = 24f; it.minHeight = dp(64) }
            }

            val input = view.inputView ?: return
            val root = input.inputNumberRootRL ?: return
            // The SDK's default tablet layout stretches these controls almost edge
            // to edge. Keep them readable and centered on tablets, while still
            // allowing them to use the available width on smaller phones.
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val compactWidth = minOf(dp(360), (screenWidth - dp(48)).coerceAtLeast(dp(280)))
            root.post {
                (root.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                    params.width = compactWidth
                    params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                    root.layoutParams = params
                }
                val numberRowId = activity.resources.getIdentifier(
                    "fusion_getsms_inputnumrl", "id", activity.packageName,
                )
                if (numberRowId != 0) {
                    root.findViewById<View>(numberRowId)?.layoutParams?.let { params ->
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        root.findViewById<View>(numberRowId)?.layoutParams = params
                    }
                }
                val phoneField = input.inputNumberET
                val sendButton = input.inputNumberRequestCodeTV
                if (phoneField != null && sendButton != null) {
                    root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            view: View, left: Int, top: Int, right: Int, bottom: Int,
                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                        ) {
                            root.removeOnLayoutChangeListener(this)
                            val parent = sendButton.parent as? ViewGroup ?: return
                            val params = sendButton.layoutParams as? RelativeLayout.LayoutParams ?: return
                            phoneField.post {
                                val fieldBounds = Rect(0, 0, phoneField.width, phoneField.height)
                                parent.offsetDescendantRectToMyCoords(phoneField, fieldBounds)
                                val fieldStart = fieldBounds.left
                                val fieldEnd = fieldBounds.right
                                params.width = fieldEnd - fieldStart
                                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                params.leftMargin = (fieldStart - parent.paddingLeft).coerceAtLeast(0)
                                params.rightMargin = (parent.width - parent.paddingRight - fieldEnd).coerceAtLeast(0)
                                params.removeRule(RelativeLayout.CENTER_HORIZONTAL)
                                params.removeRule(RelativeLayout.CENTER_IN_PARENT)
                                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE)
                                sendButton.layoutParams = params
                            }
                        }
                    })
                }
            }

            input.inputNumberET?.apply {
                textSize = 24f
                hint = activity.getString(R.string.login_phone_hint)
                minHeight = dp(80)
                layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(primary)
                setHintTextColor(activity.getColor(R.color.group33_text_secondary))
                background = rounded(android.graphics.Color.TRANSPARENT, activity.getColor(R.color.group33_text_secondary))
                setPadding(dp(16), 0, dp(16), 0)
                if (preferSms) {
                    // The user entered this number once on our fresh entry page.
                    // Replace any number retained by an earlier SDK scene.
                    setText(smsPhone)
                    post { if (text?.toString() != smsPhone) setText(smsPhone) }
                }
            }
            val countryCodeId = activity.resources.getIdentifier(
                "fusion_getsms_numbeare", "id", activity.packageName,
            )
            if (countryCodeId != 0) {
                input.inputNumberRootRL?.findViewById<TextView>(countryCodeId)?.apply {
                    textSize = 24f
                    minHeight = dp(80)
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                }
            }
            input.inputNumberRequestCodeTV?.apply {
                textSize = 24f
                minHeight = dp(80)
                layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                isFocusable = true
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = text
                setTextColor(android.graphics.Color.WHITE)
                background = rounded(action)
                setPadding(dp(20), 0, dp(20), 0)
            }
            input.privacyRL?.apply {
                visibility = View.VISIBLE
                val privacyTextId = activity.resources.getIdentifier(
                    "fusion_getsms_privacytext", "id", activity.packageName,
                )
                if (privacyTextId != 0) {
                    findViewById<TextView>(privacyTextId)?.apply {
                    textSize = 18f
                        gravity = Gravity.CENTER_VERTICAL
                    }
                }
                (layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                    params.bottomMargin = dp(12)
                    layoutParams = params
                }
            }
            input.agreePrivacy?.apply {
                minWidth = dp(52)
                minHeight = dp(52)
            }
        }

        override fun onSMSSendVerifyUICustomView(
            templateId: String, nodeId: String, view: AlicomFusionUpSMSView, phone: String, code: String,
        ) {
            if (preferSms) smsProofPageShown.set(true)
        }
    }

    private companion object { const val SCENE = "100001" }
}
