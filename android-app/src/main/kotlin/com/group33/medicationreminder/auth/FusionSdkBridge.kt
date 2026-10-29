package com.group33.medicationreminder.auth

import android.app.Activity
import com.alicom.fusion.auth.AlicomFusionAuthCallBack
import com.alicom.fusion.auth.AlicomFusionAuthUICallBack
import com.alicom.fusion.auth.AlicomFusionBusiness
import com.alicom.fusion.auth.AlicomFusionLog
import com.alicom.fusion.auth.HalfWayVerifyResult
import com.alicom.fusion.auth.error.AlicomFusionEvent
import com.alicom.fusion.auth.numberauth.FusionNumberAuthModel
import com.alicom.fusion.auth.smsauth.AlicomFusionVerifyCodeView
import com.alicom.fusion.auth.token.AlicomFusionAuthToken
import com.alicom.fusion.auth.upsms.AlicomFusionUpSMSView
import com.group33.medicationreminder.R
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-scoped adapter. Call start only after the user has accepted the app's privacy policy. */
internal class FusionSdkBridge(private val activity: Activity, private val listener: Listener) {
    interface Listener {
        /** Must return a previously fetched fresh SDK token without blocking the UI thread. */
        fun refreshSdkToken(): String?

        /** Send the token to the app server. Call completion only after the server verifies it. */
        fun verifyOnServer(token: String, completion: (Boolean) -> Unit)

        fun finished(verified: Boolean)
    }

    private val business = AlicomFusionBusiness()
    private val started = AtomicBoolean(false)
    private val sceneStarted = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val verifying = AtomicBoolean(false)
    @Volatile private var initialized = false
    @Volatile private var verified = false

    fun start(schemeCode: String, token: String) {
        require(schemeCode.isNotBlank() && token.isNotBlank())
        check(started.compareAndSet(false, true))
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
                finish(false)
            }

            override fun onVerifySuccess(token: String, nodeName: String, event: AlicomFusionEvent) {
                if (!verifying.compareAndSet(false, true)) return
                listener.verifyOnServer(token) { accepted ->
                    activity.runOnUiThread {
                        if (!finished.get()) {
                            verified = accepted
                            if (!accepted) verifying.set(false)
                            business.continueSceneWithTemplateId(SCENE, accepted)
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
                activity.runOnUiThread {
                    if (!finished.get()) business.continueSceneWithTemplateId(SCENE, false)
                }
            }

            override fun onTemplateFinish(event: AlicomFusionEvent) = finish(verified)
            override fun onAuthEvent(event: AlicomFusionEvent) = Unit
            override fun onGetPhoneNumberForVerification(nodeId: String, event: AlicomFusionEvent): String = ""
            // This is a temporary interruption; Alibaba says the scene cannot be stopped here.
            override fun onVerifyInterrupt(event: AlicomFusionEvent) = Unit
        })
    }

    fun close() = finish(false)

    private fun finish(success: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        activity.runOnUiThread {
            if (initialized && sceneStarted.get()) runCatching { business.stopSceneWithTemplateId(SCENE) }
            if (initialized) runCatching { business.destory() }
            listener.finished(success)
        }
    }

    private fun authToken(value: String) = AlicomFusionAuthToken().apply { setAuthToken(value) }

    private val smsSwitch = object : AlicomFusionAuthUICallBack {
        override fun onPhoneNumberVerifyUICustomView(templateId: String, nodeId: String, config: FusionNumberAuthModel) {
            config.builder.setSwitchAccHidden(false).setSwitchAccText(activity.getString(R.string.login_sms))
            config.setSwitchLoginBack { config.otherPhoneLogin() }
        }

        override fun onSMSCodeVerifyUICustomView(
            templateId: String, nodeId: String, autoInput: Boolean, view: AlicomFusionVerifyCodeView,
        ) = Unit

        override fun onSMSSendVerifyUICustomView(
            templateId: String, nodeId: String, view: AlicomFusionUpSMSView, phone: String, code: String,
        ) = Unit
    }

    private companion object { const val SCENE = "100001" }
}
