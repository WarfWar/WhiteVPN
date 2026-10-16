package com.whitevpn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class L2tpConnectionPhase { IDLE, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

data class L2tpConnectionState(
    val phase: L2tpConnectionPhase = L2tpConnectionPhase.IDLE,
    val profileId: String? = null,
    val message: String = "Не подключено",
    val ipAddress: String? = null,
    val packetCounts: L2tpPacketCounts = L2tpPacketCounts()
)

/**
 * Only public network metadata, never keys or credentials.
 * The same UDP socket is used for both negotiation and encrypted payloads.
 */
object L2tpRuntime {
    private val mutable = MutableStateFlow(L2tpConnectionState())
    val state: StateFlow<L2tpConnectionState> = mutable.asStateFlow()

    fun begin(profileId: String, message: String) {
        mutable.value = L2tpConnectionState(
            phase = L2tpConnectionPhase.CONNECTING,
            profileId = profileId,
            message = message
        )
    }

    fun update(phase: L2tpConnectionPhase, id: String?, message: String, ip: String? = null) {
        mutable.update { before ->
            before.copy(phase = phase, profileId = id, message = message, ipAddress = ip)
        }
    }

    fun recordPacket(profileId: String, outbound: Boolean, port: Int) {
        mutable.update { current ->
            if (current.profileId != profileId ||
                current.phase != L2tpConnectionPhase.CONNECTING) current
            else current.copy(
                packetCounts = current.packetCounts.record(outbound, port)
            )
        }
    }
}
