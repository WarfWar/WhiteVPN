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

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class ProfileSummary(
    val id: String,
    val name: String,
    val provider: String,
    val endpointHost: String?
)
data class VpnUiState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val profiles: List<ProfileSummary> = emptyList(),
    val selectedId: String? = null,
    val message: String? = null
) {
    val configured get() = profiles.any { it.id == selectedId }
    val activeName get() = profiles.firstOrNull { it.id == selectedId }?.name
}

/** Tunnel UP means the interface is active, not a verified handshake. */
class VpnController(application: Application) {
    private val store = SecureConfigStore(application)
    private val backend by lazy { GoBackend(application.applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateInternal = MutableStateFlow(try {
        ui(store.read())
    } catch (_: Exception) {
        VpnUiState(message = "Не удалось прочитать сохранённые VPN-профили")
    })
    val state: StateFlow<VpnUiState> = stateInternal.asStateFlow()
    private val tunnel = object : Tunnel {
        override fun getName(): String = "WhiteVPN"
        override fun onStateChange(newState: Tunnel.State) {
            stateInternal.update {
                it.copy(status = if (newState == Tunnel.State.UP) VpnStatus.CONNECTED
                    else VpnStatus.DISCONNECTED)
            }
        }
    }

    private fun ui(saved: SavedProfiles, current: VpnUiState = VpnUiState()): VpnUiState =
        current.copy(
            profiles = saved.entries.map {
                ProfileSummary(it.id, it.name, it.provider,
                    VpnEndpoint.hostFromConfig(it.configuration))
            },
            selectedId = saved.selectedId, message = null
        )

    fun showMessage(message: String) {
        stateInternal.update { it.copy(message = message) }
    }

    fun saveConfiguration(raw: String): Boolean {
        if (state.value.status != VpnStatus.DISCONNECTED) {
            showMessage("Отключите VPN перед добавлением профиля")
            return false
        }
        return try {
            require(raw.isNotBlank() && raw.length <= 65536)
            val config = parse(raw)
            require(config.peers.isNotEmpty())
            val server = FreeServerCatalog.matchConfiguration(raw)
            val name = server?.let { s -> s.flag + " " + s.name } ?: "Мой WireGuard"
            val provider = if (server == null) "Личный" else "VPNBook"
            val saved = store.add(name, provider, config.toWgQuickString())
            stateInternal.update { ui(saved, it) }
            true
        } catch (_: Exception) {
            showMessage("Профиль недействителен или не удалось его сохранить")
            false
        }
    }

    fun selectProfile(id: String) {
        if (state.value.status != VpnStatus.DISCONNECTED) {
            showMessage("Сначала отключите VPN")
            return
        }
        try {
            val saved = store.select(id)
            stateInternal.update { ui(saved, it) }
        } catch (_: Exception) {
            showMessage("Не удалось выбрать профиль")
        }
    }

    fun deleteProfile(id: String) {
        if (state.value.status != VpnStatus.DISCONNECTED) {
            showMessage("Отключите VPN перед удалением")
            return
        }
        try {
            val saved = store.remove(id)
            stateInternal.update { ui(saved, it) }
        } catch (_: Exception) {
            showMessage("Не удалось удалить профиль")
        }
    }

    fun connect() {
        val current = state.value
        if (current.status != VpnStatus.DISCONNECTED || !current.configured) return
        val selected = current.selectedId ?: return
        stateInternal.update { it.copy(status = VpnStatus.CONNECTING, message = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val raw = store.read().entries.firstOrNull { it.id == selected }
                        ?.configuration ?: error("Missing active profile")
                    backend.setState(tunnel, Tunnel.State.UP, parse(raw))
                }
                stateInternal.update {
                    it.copy(status = if (result == Tunnel.State.UP) VpnStatus.CONNECTED
                        else VpnStatus.DISCONNECTED)
                }
            } catch (_: Exception) {
                stateInternal.update {
                    it.copy(status = VpnStatus.DISCONNECTED,
                        message = "Не удалось подключиться. Проверьте сервер и срок действия конфигурации.")
                }
            }
        }
    }

    fun disconnect() {
        if (state.value.status != VpnStatus.CONNECTED) return
        stateInternal.update { it.copy(status = VpnStatus.DISCONNECTING, message = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
                stateInternal.update { it.copy(status = VpnStatus.DISCONNECTED) }
            } catch (_: Exception) {
                stateInternal.update {
                    it.copy(status = VpnStatus.CONNECTED,
                        message = "Не удалось отключить VPN. Проверьте настройки Android.")
                }
            }
        }
    }

    private fun parse(raw: String): Config =
        Config.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
}
