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
                    connect(id)
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

    private fun connect(profileId: String) {
        if (!running.compareAndSet(false, true)) return
        activeId = profileId
        userStopped = false
        L2tpRuntime.update(L2tpConnectionPhase.CONNECTING, profileId, "Открываем IKEv1/IPsec")

        worker = Thread({
            try {
                val profile = L2tpProfileStore(this).read()
                    .firstOrNull { it.id == profileId }
                    ?: throw IllegalStateException("Сохранённый сервер не найден")
                if (!L2tpProfileValidation.isValid(profile)) {
                    throw IllegalStateException("Параметры L2TP некорректны")
                }

                // Default strong proposal. Older VPN Gate servers may reject it;
                // we do not silently downgrade cryptography to MD5/DH2.
                val config = VpnConfig(
                    serverHost = profile.host,
                    username = profile.username,
                    password = profile.password,
                    presharedKey = profile.preSharedKey
                )
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
                            else -> L2tpRuntime.update(
                                L2tpConnectionPhase.CONNECTING, profileId,
                                "Этап: " + state.name
                            )
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
                            "Ошибка L2TP: " + kind.name +
                                ". Проверьте PSK, PPP, IKE-параметры и доступность сервера."
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "whitevpn-l2tp").apply {
            isDaemon = true
            start()
        }
    }

    private fun disconnect() {
        userStopped = true
        L2tpRuntime.update(L2tpConnectionPhase.DISCONNECTING, activeId, "Отключение")
        tunnel?.stop()
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
        private const val CHANNEL_ID = "whitevpn_l2tp_tunnel"
        private const val NOTIFICATION_ID = 601
    }
}
