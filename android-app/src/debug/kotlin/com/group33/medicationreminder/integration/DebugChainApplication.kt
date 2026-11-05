package com.group33.medicationreminder.integration

import android.app.Application
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.elder.photo.*

class DebugChainApplication : Application(), ElderPhotoDependenciesOwner {
    internal val repository by lazy { DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL) }
    override val elderPhotoDependencies: ElderPhotoDependencies
        get() = repository.services.let { ElderPhotoDependencies(it.photos, it.commands, it.scope) }
}
