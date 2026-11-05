package com.group33.medicationreminder.integration

import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.elder.photo.*

class DebugChainApplication : Group33Application(), ElderPhotoDependenciesOwner {
    internal val repository by lazy { DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL) }
    internal override val featureServices get() = repository.services
    override val elderPhotoDependencies: ElderPhotoDependencies
        get() = repository.services.let { ElderPhotoDependencies(it.photos, it.commands, it.scope) }
}
