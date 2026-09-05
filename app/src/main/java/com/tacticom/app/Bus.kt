package com.tacticom.app

import kotlinx.coroutines.flow.MutableStateFlow

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val self: Boolean)

data class Session(
    val id: String,
    val name: String,
    val hostId: String,
    val hostName: String,
    val hostIp: String,
    val hostPort: Int,
    val locked: Boolean,
    val memberCount: Int
)

object Bus {
    val dark = MutableStateFlow(true)
    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    
    val currentSession = MutableStateFlow<Session?>(null)
    val sessionMembers = MutableStateFlow<List<String>>(emptyList())
    val isTransmitting = MutableStateFlow(false)
    val vu = MutableStateFlow(0f)
    val toastMsg = MutableStateFlow("")
}
