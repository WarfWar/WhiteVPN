package com.whitevpn.app

import android.app.Application
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VpnStatus {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
}

data class VpnUiState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val configured: Boolean = false,
    val message: String? = null
)

/**
 * Process-scoped WireGuard backend survives Activity recreation.
 * UP reports local tunnel state, not confirmation of a remote handshake.
 */
class VpnController(application: Application) {
    private val store = SecureConfigStore(application)
    private val backend by lazy { GoBackend(application.applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableState = MutableStateFlow(
        VpnUiState(configured = store.exists())
    )
    val state: StateFlow<VpnUiState> = mutableState.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = "WhiteVPN"

        override fun onStateChange(newState: Tunnel.State) {
            mutableState.update { old ->
                old.copy(
                    status = if (newState == Tunnel.State.UP) {
                        VpnStatus.CONNECTED
                    } else {
                        VpnStatus.DISCONNECTED
                    }
                )
            }
        }
    }

    fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    fun saveConfiguration(text: String): Boolean {
        if (state.value.status != VpnStatus.DISCONNECTED) {
            showMessage("Отключите VPN перед заменой конфигурации")
            return false
        }
        return try {
            require(text.isNotBlank() && text.length <= 65536)
            val config = parse(text)
            require(config.peers.isNotEmpty())
            store.save(config.toWgQuickString())
            mutableState.update { it.copy(configured = true, message = null) }
            true
        } catch (_: Exception) {
            showMessage("Неверная конфигурация WireGuard или ошибка её сохранения")
            false
        }
    }

    fun connect() {
        if (!state.value.configured || state.value.status != VpnStatus.DISCONNECTED) return
        mutableState.update { it.copy(status = VpnStatus.CONNECTING, message = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val profile = store.load() ?: error("Missing configuration")
                    backend.setState(tunnel, Tunnel.State.UP, parse(profile))
                }
                mutableState.update {
                    it.copy(
                        status = if (result == Tunnel.State.UP) {
                            VpnStatus.CONNECTED
                        } else {
                            VpnStatus.DISCONNECTED
                        }
                    )
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = VpnStatus.DISCONNECTED,
                        message = "Туннель не запущен. Проверьте конфигурацию, сервер и разрешение Android."
                    )
                }
            }
        }
    }

    fun disconnect() {
        if (state.value.status != VpnStatus.CONNECTED) return
        mutableState.update { it.copy(status = VpnStatus.DISCONNECTING, message = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
                mutableState.update { it.copy(status = VpnStatus.DISCONNECTED) }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        status = VpnStatus.CONNECTED,
                        message = "Ошибка отключения. Проверьте состояние VPN в настройках Android."
                    )
                }
            }
        }
    }

    private fun parse(text: String): Config =
        Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
}
