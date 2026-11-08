package com.group33.medicationreminder.integration

import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.elder.photo.*
import com.group33.medicationreminder.auth.*
import com.group33.medicationreminder.core.session.PrivateSessionStore

class DebugChainApplication : Group33Application(), ElderPhotoDependenciesOwner {
    private val phoneConfigured get() = BuildConfig.PHONE_AUTH_BASE_URL.isNotBlank()
    private val commonPhoneStore by lazy {
        PrivateSessionStore(this, "debug-phone-trial:${BuildConfig.DEBUG_SERVER_URL.trimEnd('/')}", "debug-online-phone")
    }
    internal val repository by lazy {
        if (phoneConfigured) DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL,
            environment = "debug-phone-trial:${BuildConfig.DEBUG_SERVER_URL.trimEnd('/')}",
            sessionStore = commonPhoneStore, anonymousLoginAllowed = false)
        else DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL)
    }
    internal override val phoneAuth by lazy {
        if (!phoneConfigured) null
        else createFusionRepository(CommonPhoneSession(commonPhoneStore))
    }
    internal override val featureServices get() = repository.services
    override val elderPhotoDependencies: ElderPhotoDependencies
        get() = repository.services.let { ElderPhotoDependencies(it.photos, it.commands, it.scope) }
}
