package com.whitevpn.app.antidpi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AntiDpiPhase { STOPPED, STARTING, ROUTING, STOPPING, FAILED }

data class AntiDpiState(
    val phase: AntiDpiPhase = AntiDpiPhase.STOPPED,
    val detail: String = "Режим Anti-DPI выключен",
    val strategy: AntiDpiStrategy = AntiDpiStrategy.SPLIT,
)

object AntiDpiRuntime {
    private val internalState = MutableStateFlow(AntiDpiState())
    val state = internalState.asStateFlow()

    fun update(phase: AntiDpiPhase, detail: String, strategy: AntiDpiStrategy) {
        internalState.value = AntiDpiState(phase, detail.take(320), strategy)
    }
}

/** Cross-process status notifications: the native engine is isolated in :antidpi. */
class AntiDpiStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AntiDpiVpnService.ACTION_STATUS) return
        val phase = AntiDpiPhase.entries.firstOrNull {
            it.name == intent.getStringExtra(AntiDpiVpnService.EXTRA_PHASE)
        } ?: return
        val strategy = AntiDpiStrategy.fromId(
            intent.getStringExtra(AntiDpiVpnService.EXTRA_STRATEGY)
        )
        AntiDpiRuntime.update(
            phase,
            intent.getStringExtra(AntiDpiVpnService.EXTRA_DETAIL).orEmpty(),
            strategy
        )
    }
}

class AntiDpiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("antidpi_settings", Context.MODE_PRIVATE)

    fun strategy(): AntiDpiStrategy =
        AntiDpiStrategy.fromId(prefs.getString("strategy", AntiDpiStrategy.SPLIT.name))

    fun saveStrategy(value: AntiDpiStrategy) {
        prefs.edit().putString("strategy", value.name).apply()
    }

    fun targets(): Set<AntiDpiTarget> =
        AntiDpiTarget.fromNames(prefs.getStringSet("targets", null))

    fun saveTargets(value: Set<AntiDpiTarget>) {
        prefs.edit().putStringSet("targets", value.mapTo(mutableSetOf()) { it.name }).apply()
    }

    fun dns(): AntiDpiDns = AntiDpiDns.fromId(prefs.getString("dns", null))
    fun saveDns(value: AntiDpiDns) {
        prefs.edit().putString("dns", value.name).apply()
    }

    fun blockQuic(): Boolean = prefs.getBoolean("block_quic", false)
    fun saveBlockQuic(value: Boolean) {
        prefs.edit().putBoolean("block_quic", value).apply()
    }
}
