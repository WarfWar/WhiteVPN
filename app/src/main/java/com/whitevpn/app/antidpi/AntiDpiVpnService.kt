package com.whitevpn.app.antidpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import com.whitevpn.app.MainActivity
import com.whitevpn.app.R
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Local, no-root TUN -> HEV -> ByeDPI SOCKS5 route.
 * NOT a remote VPN; does not encrypt traffic again or conceal the public IP.
 */
class AntiDpiVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.whitevpn.app.antidpi.START"
        const val ACTION_STOP = "com.whitevpn.app.antidpi.STOP"
        const val ACTION_STATUS = "com.whitevpn.app.antidpi.STATUS"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_DNS = "dns"
        const val EXTRA_BLOCK_QUIC = "block_quic"
        private const val CHANNEL = "whitevpn_antidpi"
        private const val NOTIFICATION_ID = 202
        private const val SOCKS_PORT = 1080
    }

    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var strategy = AntiDpiStrategy.SPLIT
    @Volatile private var targets: Set<AntiDpiTarget> = AntiDpiTarget.entries.toSet()
    @Volatile private var dns = AntiDpiDns.SYSTEM
    @Volatile private var blockQuic = false
    @Volatile private var proxyThread: Thread? = null
    @Volatile private var tunnelThread: Thread? = null
    @Volatile private var bridgeThread: Thread? = null
    @Volatile private var hevPeerFd = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (active.compareAndSet(false, true)) {
                    strategy = AntiDpiStrategy.fromId(intent.getStringExtra(EXTRA_STRATEGY))
                    targets = AntiDpiTarget.fromNames(intent.getStringArrayListExtra(EXTRA_TARGETS))
                    dns = AntiDpiDns.fromId(intent.getStringExtra(EXTRA_DNS))
                    blockQuic = intent.getBooleanExtra(EXTRA_BLOCK_QUIC, false) &&
                        AntiDpiTarget.YOUTUBE in targets
                    postNotification()
                    report(AntiDpiPhase.STARTING, "Проверяем локальный SOCKS5")
                    thread(name = "WhiteVPN-AntiDPI-Start") { begin() }
                }
            }
            ACTION_STOP -> stopEngine("Отключено пользователем")
            else -> if (!active.get()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopEngine("Android отозвал VPN-разрешение")
        super.onRevoke()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = super.onBind(intent)

    private fun postNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "WhiteVPN Anti-DPI", NotificationManager.IMPORTANCE_LOW
        ))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AntiDpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WhiteVPN • Telegram / YouTube / Instagram")
            .setContentText("Локальный Anti-DPI, не удалённый VPN")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop
            ).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun begin() {
        try {
            check(targets.isNotEmpty()) { "Выбери хотя бы одно приложение" }

            proxyThread = thread(name = "WhiteVPN-ByeDPI") {
                try {
                    val result = AntiDpiNative.proxyStart(strategy.arguments())
                    if (!stopping.get()) failure("ByeDPI завершился с кодом $result")
                } catch (e: Throwable) {
                    if (!stopping.get()) failure("Сбой ByeDPI: ${e.javaClass.simpleName}")
                }
            }

            val deadline = SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!stopping.get() && SystemClock.elapsedRealtime() < deadline) {
                if (socksResponds()) { ready = true; break }
                Thread.sleep(100)
            }
            check(!stopping.get()) { "Запуск отменён" }
            check(ready) { "SOCKS5 не ответил за 10 секунд" }

            val builder = Builder()
                .setSession("WhiteVPN Anti-DPI")
                .setMtu(1500)
                .addAddress("10.123.0.2", 32)
                .addRoute("0.0.0.0", 0)

            // Without an IPv6 route, let Android use its ordinary IPv6 network:
            // this prevents accidental IPv6 blackholes, but IPv6 is NOT processed.
            builder.allowFamily(OsConstants.AF_INET6)

            // Only explicitly chosen, installed packages are intercepted.
            // WhiteVPN itself must never be in this allowlist to avoid a socket loop.
            val installed = mutableListOf<String>()
            for (packageId in AntiDpiTarget.packagesOf(targets)) {
                try {
                    builder.addAllowedApplication(packageId)
                    installed += packageId
                } catch (_: PackageManager.NameNotFoundException) {
                    // Alternative Telegram or YouTube clients may not be installed.
                }
            }
            check(installed.isNotEmpty()) {
                "Не найдено выбранных приложений: установи Telegram, YouTube или Instagram"
            }

            // Previous build forced 1.1.1.1 for everyone: if that resolver is
            // filtered, no app can load. Prefer the active network's own IPv4 DNS.
            val dnsAddress = dns.address ?: systemIpv4Dns()
            if (dnsAddress != null) builder.addDnsServer(dnsAddress)

            val descriptor = builder.establish() ?: error("Android не создал VPN-интерфейс")
            tun = descriptor
            if (stopping.get()) { descriptor.close(); return }

            var tunnelFd = descriptor.fd
            if (blockQuic) {
                // Optional for YouTube: drop QUIC/UDP 443 and allow HTTP/3 clients
                // to fall back to TCP. This can affect Telegram voice calls if both
                // are selected; the UI warns explicitly.
                val fds = AntiDpiNative.openPacketBridge()
                check(fds.size == 2 && fds.all { it >= 0 }) {
                    "Не удалось создать пакетный мост для QUIC"
                }
                hevPeerFd = fds[0]
                tunnelFd = hevPeerFd
                bridgeThread = thread(name = "WhiteVPN-QuicFallback") {
                    try {
                        val result = AntiDpiNative.runPacketBridge(descriptor.fd, fds[1], true)
                        if (!stopping.get()) failure("Обработка UDP/443 прервана: $result")
                    } catch (e: Throwable) {
                        if (!stopping.get()) failure("Сбой QUIC-фильтра: ${e.javaClass.simpleName}")
                    }
                }
            }

            val config = """
                tunnel:
                  mtu: 1500
                misc:
                  task-stack-size: 81920
                  log-level: warn
                socks5:
                  address: 127.0.0.1
                  port: $SOCKS_PORT
                  udp: udp
            """.trimIndent() + "\n"

            tunnelThread = thread(name = "WhiteVPN-HEV") {
                try {
                    val result = AntiDpiNative.tunnelStart(config, tunnelFd)
                    if (!stopping.get()) failure("tun2socks завершился с кодом $result")
                } catch (e: Throwable) {
                    if (!stopping.get()) failure("Сбой tun2socks: ${e.javaClass.simpleName}")
                }
            }
            Thread.sleep(350)
            if (!stopping.get() && tunnelThread?.isAlive == true &&
                (!blockQuic || bridgeThread?.isAlive == true)) {
                val list = targets.joinToString(", ") { it.title }
                report(AntiDpiPhase.ROUTING,
                    "Маршрут для $list запущен. Проверяем HTTPS через SOCKS5...")
                thread(name = "WhiteVPN-HTTPS-check") { probeServices() }
            }
        } catch (e: Throwable) {
            if (!stopping.get()) failure(e.message ?: "Не удалось запустить Anti-DPI")
        }
    }

    private fun systemIpv4Dns(): String? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = cm.activeNetwork
        cm.getLinkProperties(activeNetwork)?.dnsServers
            ?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    private fun socksResponds(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 400)
            socket.soTimeout = 400
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val response = socket.getInputStream()
            response.read() == 5 && response.read() == 0
        }
    } catch (_: Exception) { false }

    /**
     * HTTPS handshake through the *actual ByeDPI SOCKS5 proxy*. It tests the
     * selected website's TLS endpoint, not the native app's full functionality
     * (MTProto Telegram, googlevideo CDN and Instagram media are separate).
     */
    private fun probeServices() {
        val checks = mutableListOf<String>()
        for (target in AntiDpiTarget.entries) {
            if (target !in targets || stopping.get()) continue
            val ok = try {
                val proxy = Proxy(Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", SOCKS_PORT))
                Socket(proxy).use { connection ->
                    connection.connect(
                        InetSocketAddress.createUnresolved(target.probeHost, 443), 4500
                    )
                    connection.soTimeout = 4500
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                        connection, target.probeHost, 443, true
                    ) as SSLSocket
                    ssl.use {
                        it.soTimeout = 4500
                        it.sslParameters = it.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                        it.startHandshake()
                    }
                }
                true
            } catch (_: Exception) { false }
            checks += target.title + if (ok) " HTTPS ✓" else " HTTPS ✗"
        }
        if (!stopping.get()) {
            report(AntiDpiPhase.ROUTING,
                checks.joinToString(" • ") +
                    ". Проверено только HTTPS главных доменов, не работа приложений.")
        }
    }

    private fun failure(detail: String) {
        report(AntiDpiPhase.FAILED, detail)
        stopEngine(detail, reportStop = false)
    }

    private fun stopEngine(detail: String, reportStop: Boolean = true) {
        if (!stopping.compareAndSet(false, true)) return
        if (reportStop) report(AntiDpiPhase.STOPPING, "Останавливаем Anti-DPI")
        thread(name = "WhiteVPN-AntiDPI-Stop") {
            try { AntiDpiNative.stopPacketBridge() } catch (_: Throwable) {}
            try { AntiDpiNative.tunnelStop() } catch (_: Throwable) {}
            try { AntiDpiNative.proxyStop() } catch (_: Throwable) {}
            try { tun?.close() } catch (_: Throwable) {}
            try { tunnelThread?.join(950) } catch (_: InterruptedException) {}
            try { bridgeThread?.join(950) } catch (_: InterruptedException) {}
            try { proxyThread?.join(950) } catch (_: InterruptedException) {}
            val fd = hevPeerFd
            if (fd >= 0) try { AntiDpiNative.closePacketFd(fd) } catch (_: Throwable) {}
            if (reportStop) report(AntiDpiPhase.STOPPED, detail)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Only terminate the isolated :antidpi process, never MainActivity.
            Process.killProcess(Process.myPid())
        }
    }

    private fun report(phase: AntiDpiPhase, detail: String) {
        sendBroadcast(Intent(this, AntiDpiStatusReceiver::class.java).apply {
            action = ACTION_STATUS
            putExtra(EXTRA_PHASE, phase.name)
            putExtra(EXTRA_DETAIL, detail.take(320))
            putExtra(EXTRA_STRATEGY, strategy.name)
        })
    }
}
