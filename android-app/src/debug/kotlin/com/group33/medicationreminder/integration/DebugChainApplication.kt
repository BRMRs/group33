package com.group33.medicationreminder.integration

import com.group33.medicationreminder.Group33Application
import com.group33.medicationreminder.BuildConfig
import com.group33.medicationreminder.elder.photo.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DebugChainApplication : Group33Application(), ElderPhotoDependenciesOwner {
    internal val repository by lazy { DebugChainRepository(this, BuildConfig.DEBUG_SERVER_URL) }
    internal override val elderDoses by lazy { DebugDoseReminders(this, repository) }

    override fun onCreate() {
        super.onCreate()
        elderDoses.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    internal override val featureServices get() = repository.services
    override val elderPhotoDependencies: ElderPhotoDependencies
        get() = repository.services.let { ElderPhotoDependencies(it.photos, it.commands, it.scope) }
}
