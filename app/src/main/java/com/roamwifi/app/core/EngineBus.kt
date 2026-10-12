package com.roamwifi.app.core

import kotlinx.coroutines.flow.MutableStateFlow

/** In-process channel between the UI and the roaming service (they share one process). */
object EngineBus {
    val state = MutableStateFlow(EngineState())

    /** Bumped by the UI whenever saved networks change so the engine reloads them. */
    val savedVersion = MutableStateFlow(0)

    /** SSID the user asked to connect to right now; the engine takes and clears it. */
    @Volatile
    var forceConnect: String? = null

    fun bumpSaved() {
        savedVersion.value = savedVersion.value + 1
    }
}
