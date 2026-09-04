package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)
data class SessionInfo(val id: String, val name: String, val locked: Boolean, val count: Int)

/** Global reactive state shared between the Service (writer) and the UI (reader). */
object Bus {
    val status = MutableStateFlow("STARTING")
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val lobby = MutableStateFlow<List<SessionInfo>>(emptyList())
    val connectedTo = MutableStateFlow<Peer?>(null)
    val inSession = MutableStateFlow<SessionInfo?>(null)
    val mySessionName = MutableStateFlow("")
    val presence = MutableStateFlow<List<String>>(emptyList())
    val transmitting = MutableStateFlow(false)
    val liveMode = MutableStateFlow(false)
    val earpiece = MutableStateFlow(false)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
}
