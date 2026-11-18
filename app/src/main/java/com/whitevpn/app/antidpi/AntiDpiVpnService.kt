package com.whitevpn.app.antidpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.whitevpn.app.MainActivity
import com.whitevpn.app.R
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * No-root local VPN capture -> hev tun2socks -> 127.0.0.1 ByeDPI SOCKS.
 * This is traffic re-routing, NOT a remote VPN or encryption layer.
 * No keys, traffic payloads, or destination hostnames are logged by this service.
 */
class AntiDpiVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.whitevpn.app.antidpi.START"
        const val ACTION_STOP = "com.whitevpn.app.antidpi.STOP"
        const val ACTION_STATUS = "com.whitevpn.app.antidpi.STATUS"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_DETAIL = "detail"
        private const val CHANNEL = "whitevpn_antidpi"
        private const val NOTIFICATION_ID = 202
        private const val SOCKS_PORT = 1080
    }

    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var strategy = AntiDpiStrategy.SPLIT
    @Volatile private var proxyThread: Thread? = null
    @Volatile private var tunnelThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (active.compareAndSet(false, true)) {
                    strategy = AntiDpiStrategy.fromId(intent.getStringExtra(EXTRA_STRATEGY))
                    postNotification()
                    report(AntiDpiPhase.STARTING, "Запускается локальный SOCKS5-прокси")
                    thread(name = "WhiteVPN-AntiDPI-Start") { begin() }
                }
            }
            ACTION_STOP -> stopEngine("Отключено пользователем")
            else -> if (!active.get()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopEngine("Android отозвал разрешение VPN")
        super.onRevoke()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = super.onBind(intent)

    private fun postNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "WhiteVPN Anti-DPI", NotificationManager.IMPORTANCE_LOW)
        )
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
            .setContentTitle("WhiteVPN • Anti-DPI")
            .setContentText("Локальная обработка без удалённого VPN-сервера")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stop
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
            proxyThread = thread(name = "WhiteVPN-ByeDPI") {
                try {
                    val code = AntiDpiNative.proxyStart(strategy.arguments())
                    if (!stopping.get()) failure("ByeDPI завершился: код $code")
                } catch (e: Throwable) {
                    if (!stopping.get()) failure("Ошибка нативного ByeDPI: ${e.javaClass.simpleName}")
                }
            }

            val deadline = SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!stopping.get() && SystemClock.elapsedRealtime() < deadline) {
                if (socksResponds()) { ready = true; break }
                Thread.sleep(100)
            }
            check(!stopping.get()) { "Запуск отменён" }
            check(ready) { "ByeDPI SOCKS5 не запустился за 10 секунд" }

            val descriptor = Builder()
                .setSession("WhiteVPN Anti-DPI")
                .setMtu(1500)
                .addAddress("10.123.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName) // prevents self-capture loop
                .establish() ?: error("Android не создал VPN-интерфейс")
            tun = descriptor
            if (stopping.get()) { descriptor.close(); return }

            val config = """
                tunnel:
                  mtu: 8500
                misc:
                  task-stack-size: 81920
                socks5:
                  address: 127.0.0.1
                  port: $SOCKS_PORT
                  udp: udp
            """.trimIndent() + "\n"
            tunnelThread = thread(name = "WhiteVPN-HEV") {
                try {
                    val code = AntiDpiNative.tunnelStart(config, descriptor.fd)
                    if (!stopping.get()) failure("tun2socks завершился: код $code")
                } catch (e: Throwable) {
                    if (!stopping.get()) failure("Ошибка tun2socks: ${e.javaClass.simpleName}")
                }
            }
            Thread.sleep(250)
            if (!stopping.get() && tunnelThread?.isAlive == true) {
                report(AntiDpiPhase.ROUTING, "Локальный маршрут запущен. Доступность сайтов не проверена")
            }
        } catch (e: Throwable) {
            if (!stopping.get()) failure(e.message ?: "Не удалось запустить Anti-DPI")
        }
    }

    private fun socksResponds(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 250)
            socket.soTimeout = 250
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val response = socket.getInputStream()
            response.read() == 5 && response.read() == 0
        }
    } catch (_: Exception) { false }

    private fun failure(detail: String) {
        report(AntiDpiPhase.FAILED, detail)
        stopEngine(detail, reportStop = false)
    }

    private fun stopEngine(detail: String, reportStop: Boolean = true) {
        if (!stopping.compareAndSet(false, true)) return
        if (reportStop) report(AntiDpiPhase.STOPPING, "Остановка Anti-DPI")
        thread(name = "WhiteVPN-AntiDPI-Stop") {
            try { AntiDpiNative.tunnelStop() } catch (_: Throwable) {}
            try { AntiDpiNative.proxyStop() } catch (_: Throwable) {}
            try { tun?.close() } catch (_: Throwable) {}
            try { tunnelThread?.join(900) } catch (_: InterruptedException) {}
            try { proxyThread?.join(900) } catch (_: InterruptedException) {}
            if (reportStop) report(AntiDpiPhase.STOPPED, detail)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // The pinned native ByeDPI core is not safe to restart in the same process.
            // Never kill the main app: service is declared with android:process=":antidpi".
            Process.killProcess(Process.myPid())
        }
    }

    private fun report(phase: AntiDpiPhase, detail: String) {
        sendBroadcast(Intent(this, AntiDpiStatusReceiver::class.java).apply {
            action = ACTION_STATUS
            putExtra(EXTRA_PHASE, phase.name)
            putExtra(EXTRA_DETAIL, detail.take(200))
            putExtra(EXTRA_STRATEGY, strategy.name)
        })
    }
}
