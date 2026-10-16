package com.whitevpn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class L2tpConnectionPhase { IDLE, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

data class L2tpConnectionState(
    val phase: L2tpConnectionPhase = L2tpConnectionPhase.IDLE,
    val profileId: String? = null,
    val message: String = "Не подключено",
    val ipAddress: String? = null
)

/** Display status from the real protocol stack, never inferred from ICMP ping. */
object L2tpRuntime {
    private val mutable = MutableStateFlow(L2tpConnectionState())
    val state: StateFlow<L2tpConnectionState> = mutable.asStateFlow()
    fun update(phase: L2tpConnectionPhase, id: String?, message: String, ip: String? = null) {
        mutable.value = L2tpConnectionState(phase, id, message, ip)
    }
}
