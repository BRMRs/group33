package com.group33.medicationreminder.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.mobile.auth.gatewayauth.Constant

/** Opens the carrier agreement requested by the SDK in the system browser. */
class FusionProtocolActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(Constant.PROTOCOL_WEB_VIEW_URL)?.let(Uri::parse)
        if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        finish()
    }
}
