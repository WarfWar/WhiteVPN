package com.whitevpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.L2tpIpsecTunnel
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelErrorKind
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelInfo
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelListener
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.TunnelState
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.VpnConfig
import com.arcansecurity.vpn.l2tpipsec.platform.AndroidTunProvider
import com.arcansecurity.vpn.l2tpipsec.platform.AndroidUdpSocketFactory
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental IKEv1 / ESP / L2TP / PPP full-device VpnService.
 * The protocol stack is a pinned MIT-licensed third-party dependency, not a
 * fake button. "Connected" only appears after PPP and TUN establishment.
 *
 * Passwords/PSK are read from Android Keystore-encrypted local storage. No
 * secret is ever placed in Intents, external browser URLs or logs.
 */
class L2tpTunnelService : VpnService() {
    private val running = AtomicBoolean(false)
    private val sessionToken = AtomicInteger(0)
    @Volatile private var watchdog: Thread? = null
    @Volatile private var activeId: String? = null
    @Volatile private var tunnel: L2tpIpsecTunnel? = null
    @Volatile private var socketFactory: AndroidUdpSocketFactory? = null
    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var userStopped = false

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (id.isNullOrBlank()) {
                    L2tpRuntime.update(L2tpConnectionPhase.FAILED, null, "Не выбран L2TP-профиль")
                    stopSelf(startId)
                } else {
                    startForegroundNotification()
                    val mode = if (intent.getStringExtra(EXTRA_CRYPTO_MODE) ==
                        L2tpCryptoMode.SOFTETHER_LEGACY.name) {
                        L2tpCryptoMode.SOFTETHER_LEGACY
                    } else {
                        L2tpCryptoMode.STRONG
                    }
                    connect(id, mode)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "L2TP/IPsec VPN", NotificationManager.IMPORTANCE_LOW
        ))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, L2tpTunnelService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WhiteVPN • L2TP/IPsec")
            .setContentText("Экспериментальный VPN-туннель")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop
            ).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connect(profileId: String, cryptoMode: L2tpCryptoMode) {
        if (!running.compareAndSet(false, true)) return
        activeId = profileId
        userStopped = false
        val token = sessionToken.incrementAndGet()
        startWatchdog(token, profileId)
        L2tpRuntime.update(
            L2tpConnectionPhase.CONNECTING, profileId,
            if (cryptoMode == L2tpCryptoMode.SOFTETHER_LEGACY)
                "IKEv1: режим SoftEther (AES-128/SHA-1/DH2, сниженная защита)"
            else "IKEv1: стандартный защищённый режим (AES-256/SHA-256/DH14)"
        )

        worker = Thread({
            try {
                val profile = L2tpProfileStore(this).read()
                    .firstOrNull { it.id == profileId }
                    ?: throw IllegalStateException("Сохранённый сервер не найден")
                if (!L2tpProfileValidation.isValid(profile)) {
                    throw IllegalStateException("Параметры L2TP некорректны")
                }

                // Legacy algorithms are explicitly opt-in in the user interface.
                // Never silently downgrade on a failed IKE exchange.
                val config = L2tpCryptoProfiles.create(profile, cryptoMode)
                val target = InetAddress.getByName(profile.host)
                val factory = AndroidUdpSocketFactory(this, target)
                socketFactory = factory

                val listener = object : TunnelListener {
                    override fun onStateChanged(state: TunnelState, detail: String?) {
                        when (state) {
                            TunnelState.CONNECTED -> Unit
                            TunnelState.FAILED -> Unit
                            TunnelState.DISCONNECTING -> L2tpRuntime.update(
                                L2tpConnectionPhase.DISCONNECTING, profileId, "Отключение L2TP"
                            )
                            else -> {
                                if (!userStopped && L2tpRuntime.state.value.phase !=
                                    L2tpConnectionPhase.FAILED) {
                                    val phase = when (state) {
                                        TunnelState.IKE_PHASE1 ->
                                            "IKE Phase 1: ответ UDP/500 и согласование алгоритмов"
                                        TunnelState.IKE_PHASE2 ->
                                            "IKE Phase 2: ключи IPsec и UDP/4500"
                                        TunnelState.L2TP_TUNNEL ->
                                            "L2TP: создание туннеля"
                                        TunnelState.L2TP_SESSION ->
                                            "L2TP: создание сессии"
                                        TunnelState.PPP_NEGOTIATION ->
                                            "PPP: проверка логина и пароля"
                                        else -> "Этап: " + state.name
                                    }
                                    L2tpRuntime.update(
                                        L2tpConnectionPhase.CONNECTING, profileId, phase
                                    )
                                }
                            }
                        }
                    }

                    override fun onConnected(info: TunnelInfo) {
                        L2tpRuntime.update(
                            L2tpConnectionPhase.CONNECTED, profileId,
                            "IKEv1 + ESP + L2TP + PPP подключены",
                            info.assignedAddress
                        )
                    }

                    override fun onFailed(
                        kind: TunnelErrorKind, message: String, cause: Throwable?
                    ) {
                        if (!userStopped) L2tpRuntime.update(
                            L2tpConnectionPhase.FAILED, profileId,
                            when (kind) {
                                TunnelErrorKind.IKE_NO_RESPONSE ->
                                    "IKE: сервер не ответил. Возможны блокировка UDP/500/4500 или неподдерживаемая криптография."
                                TunnelErrorKind.IKE_PROPOSAL_REJECTED ->
                                    "IKE: сервер отверг параметры шифрования. Для SoftEther попробуйте режим совместимости."
                                TunnelErrorKind.IKE_AUTH_FAILED ->
                                    "IKE: ошибка проверки IPsec PSK."
                                TunnelErrorKind.PPP_AUTH_FAILED ->
                                    "PPP: сервер не принял логин или пароль."
                                else -> "L2TP: ошибка " + kind.name + ". " +
                                    message.take(150).replace(Regex("[\\r\\n]+"), " ")
                            }
                        )
                    }

                    override fun onDisconnected() {
                        val current = L2tpRuntime.state.value
                        if (current.phase != L2tpConnectionPhase.FAILED) {
                            L2tpRuntime.update(L2tpConnectionPhase.IDLE, null, "Отключено")
                        }
                    }
                }

                val instance = L2tpIpsecTunnel(
                    config = config,
                    socketFactory = factory,
                    tunProvider = AndroidTunProvider(this, { desc -> tunDescriptor = desc },
                        "WhiteVPN L2TP"),
                    listener = listener
                )
                tunnel = instance
                instance.run() // Blocks on the worker; callback reports real status.
            } catch (_: Exception) {
                if (!userStopped) L2tpRuntime.update(
                    L2tpConnectionPhase.FAILED, profileId,
                    "Не удалось запустить L2TP. Проверьте сеть, профиль и разрешение Android."
                )
            } finally {
                socketFactory?.closeAll()
                socketFactory = null
                runCatching { tunDescriptor?.close() }
                tunDescriptor = null
                tunnel = null
                activeId = null
                running.set(false)
                sessionToken.incrementAndGet()
                watchdog?.interrupt()
                watchdog = null
                if (userStopped || L2tpRuntime.state.value.phase == L2tpConnectionPhase.DISCONNECTING) {
                    L2tpRuntime.update(L2tpConnectionPhase.IDLE, null, "Отключено")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "whitevpn-l2tp").apply {
            isDaemon = true
            start()
        }
    }

    private fun startWatchdog(token: Int, profileId: String) {
        watchdog = Thread({
            try {
                Thread.sleep(CONNECTION_TIMEOUT_MS)
                if (token == sessionToken.get() && running.get() &&
                    L2tpRuntime.state.value.phase == L2tpConnectionPhase.CONNECTING) {
                    val phase = L2tpRuntime.state.value.message
                    L2tpRuntime.update(
                        L2tpConnectionPhase.FAILED, profileId,
                        "Таймаут 55 секунд на этапе «" + phase.take(70) +
                            "». Проверьте UDP 500/4500 и профиль шифрования."
                    )
                    tunnel?.stop()
                    socketFactory?.closeAll()
                    worker?.interrupt()
                    // Do not block the main thread. The worker owns cleanup.
                }
            } catch (_: InterruptedException) {
                // Normal cancellation after success or disconnect.
            }
        }, "whitevpn-l2tp-watchdog").apply { isDaemon = true; start() }
    }

    private fun disconnect() {
        userStopped = true
        // Cancel even during IKE negotiation, before the tunnel object exists.
        sessionToken.incrementAndGet()
        watchdog?.interrupt()
        L2tpRuntime.update(L2tpConnectionPhase.DISCONNECTING, activeId, "Останавливаем L2TP")
        tunnel?.stop()
        socketFactory?.closeAll()
        worker?.interrupt()
        if (!running.get()) {
            L2tpRuntime.update(L2tpConnectionPhase.IDLE, null, "Отключено")
            stopSelf()
        }
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        tunnel?.stop()
        socketFactory?.closeAll()
        runCatching { tunDescriptor?.close() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.whitevpn.app.L2TP_CONNECT"
        const val ACTION_DISCONNECT = "com.whitevpn.app.L2TP_DISCONNECT"
        const val EXTRA_PROFILE_ID = "l2tp_profile_id"
        const val EXTRA_CRYPTO_MODE = "l2tp_crypto_mode"
        private const val CHANNEL_ID = "whitevpn_l2tp_tunnel"
        private const val NOTIFICATION_ID = 601
        private const val CONNECTION_TIMEOUT_MS = 55_000L
    }
}
