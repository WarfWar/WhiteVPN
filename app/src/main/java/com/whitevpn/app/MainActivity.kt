package com.whitevpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val controller get() = (application as WhiteVpnApp).controller
    private val nativeIkev2 get() = (application as WhiteVpnApp).nativeIkev2
    private val l2tpStore get() = (application as WhiteVpnApp).l2tpProfiles

    private var pendingL2tpId: String? = null
    private var pendingL2tpMode: L2tpCryptoMode = L2tpCryptoMode.STRONG
    private val l2tpPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingL2tpId
        val mode = pendingL2tpMode
        pendingL2tpId = null
        pendingL2tpMode = L2tpCryptoMode.STRONG
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            launchL2tp(id, mode)
        } else {
            controller.showMessage("Android не предоставил разрешение на L2TP/IPsec")
        }
    }

    private fun requestL2tp(profileId: String, mode: L2tpCryptoMode) {
        if (controller.state.value.status != VpnStatus.DISCONNECTED ||
            nativeIkev2.status() == Ikev2Status.CONNECTED ||
            nativeIkev2.status() == Ikev2Status.CONNECTING) {
            controller.showMessage("Сначала отключите WireGuard или IKEv2")
            return
        }
        val status = L2tpRuntime.state.value.phase
        if (status == L2tpConnectionPhase.CONNECTED ||
            status == L2tpConnectionPhase.CONNECTING) {
            controller.showMessage("Отключите предыдущую L2TP-сессию")
            return
        }
        try {
            val permission = VpnService.prepare(this)
            if (permission == null) launchL2tp(profileId, mode)
            else {
                pendingL2tpId = profileId
                pendingL2tpMode = mode
                l2tpPermission.launch(permission)
            }
        } catch (_: Exception) {
            controller.showMessage("Не удалось запросить разрешение Android для L2TP")
        }
    }

    private fun launchL2tp(profileId: String, mode: L2tpCryptoMode) {
        try {
            val request = Intent(this, L2tpTunnelService::class.java)
                .setAction(L2tpTunnelService.ACTION_CONNECT)
                .putExtra(L2tpTunnelService.EXTRA_PROFILE_ID, profileId)
                .putExtra(L2tpTunnelService.EXTRA_CRYPTO_MODE, mode.name)
            startForegroundService(request)
        } catch (_: Exception) {
            controller.showMessage("Не удалось запустить L2TP VPN-сервис")
        }
    }

    private fun stopL2tp() {
        try {
            startService(Intent(this, L2tpTunnelService::class.java)
                .setAction(L2tpTunnelService.ACTION_DISCONNECT))
        } catch (_: Exception) {
            controller.showMessage("Не удалось остановить L2TP VPN")
        }
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            controller.connect()
        } else {
            controller.showMessage("Android не разрешил запуск VPN")
        }
    }

    private val chooseConfig = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.use {
                    String(readLimited(it), StandardCharsets.UTF_8)
                } ?: error("File missing")
                controller.saveConfiguration(text)
            } catch (_: Exception) {
                controller.showMessage("Не удалось прочитать .conf (размер до 64 КБ)")
            }
        }
    }

    private val ikeConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                nativeIkev2.start()
                controller.showMessage("Запрос IKEv2 отправлен Android. Ожидаем состояние подключения.")
            } catch (_: Exception) {
                controller.showMessage("Не удалось запустить IKEv2/IPsec")
            }
        } else {
            controller.showMessage("Разрешение на IKEv2/IPsec отклонено")
        }
    }

    private val chooseOpenVpn = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val openProfile = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/x-openvpn-profile")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, "OpenVPN profile", uri)
                }
                startActivity(Intent.createChooser(openProfile, "Открыть .ovpn в VPN-клиенте"))
            } catch (_: Exception) {
                controller.showMessage(
                    "Не найден совместимый клиент OpenVPN. Установите OpenVPN for Android или OpenVPN Connect."
                )
            }
        }
    }

    private fun startIkev2(server: String, identity: String, user: String, password: String) {
        if (controller.state.value.status != VpnStatus.DISCONNECTED ||
            L2tpRuntime.state.value.phase == L2tpConnectionPhase.CONNECTED ||
            L2tpRuntime.state.value.phase == L2tpConnectionPhase.CONNECTING) {
            controller.showMessage("Сначала отключите WireGuard или L2TP")
            return
        }
        try {
            val intent = nativeIkev2.provision(server, identity, user, password)
            if (intent == null) {
                nativeIkev2.start()
            } else {
                ikeConsent.launch(intent)
            }
        } catch (_: Exception) {
            controller.showMessage("Ошибка IKEv2: проверьте адрес, логин, пароль и поддержку IPsec.")
        }
    }

    private fun stopIkev2() {
        try {
            nativeIkev2.stop()
        } catch (_: Exception) {
            controller.showMessage("Не удалось отключить IKEv2/IPsec")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val current by controller.state.collectAsState()
            val l2tpState by L2tpRuntime.state.collectAsState()
            var ikeStatus by remember { mutableStateOf(nativeIkev2.status()) }
            var l2tpEntries by remember {
                mutableStateOf(try {
                    l2tpStore.read()
                } catch (_: Exception) {
                    emptyList<L2tpProfile>()
                })
            }
            LaunchedEffect(Unit) {
                while (true) {
                    ikeStatus = nativeIkev2.status()
                    delay(2000)
                }
            }
            MaterialTheme(colorScheme = whiteVpnColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = whiteVpnColors.background,
                    contentColor = whiteVpnColors.onBackground
                ) {
                WhiteVpnScreen(
                    state = current,
                    ikeStatus = ikeStatus,
                    l2tpEntries = l2tpEntries,
                    l2tpState = l2tpState,
                    onConnectL2tp = ::requestL2tp,
                    onDisconnectL2tp = ::stopL2tp,
                    onSaveL2tp = { profile ->
                        try {
                            l2tpEntries = l2tpStore.upsert(profile)
                            controller.showMessage("L2TP/IPsec сервер сохранён")
                            true
                        } catch (_: Exception) {
                            controller.showMessage("Не удалось сохранить L2TP/IPsec. Проверьте все поля.")
                            false
                        }
                    },
                    onDeleteL2tp = { id ->
                        try {
                            l2tpEntries = l2tpStore.delete(id)
                            controller.showMessage("L2TP/IPsec сервер удалён")
                        } catch (_: Exception) {
                            controller.showMessage("Не удалось удалить L2TP/IPsec сервер")
                        }
                    },
                    onCopyL2tp = { value ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("VPN parameter", value)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            val extras = android.os.PersistableBundle()
                            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                            clip.description.extras = extras
                        }
                        clipboard.setPrimaryClip(clip)
                    },
                    onOpenL2tpClient = ::openL2tpClient,
                    onStartIkev2 = ::startIkev2,
                    onStopIkev2 = ::stopIkev2,
                    onToggle = {
                        when (current.status) {
                            VpnStatus.CONNECTED -> controller.disconnect()
                            VpnStatus.DISCONNECTED -> {
                                if (nativeIkev2.status() == Ikev2Status.CONNECTED ||
                                    nativeIkev2.status() == Ikev2Status.CONNECTING ||
                                    l2tpState.phase == L2tpConnectionPhase.CONNECTED ||
                                    l2tpState.phase == L2tpConnectionPhase.CONNECTING) {
                                    controller.showMessage("Сначала отключите IKEv2/IPsec")
                                } else {
                                    requestVpnPermission()
                                }
                            }
                            else -> Unit
                        }
                    },
                    onImport = { chooseConfig.launch(arrayOf("*/*")) },
                    onSave = controller::saveConfiguration,
                    onSelect = controller::selectProfile,
                    onDelete = controller::deleteProfile,
                    onOpenFreeServer = ::openFreeServer,
                    onImportOpenVpn = { chooseOpenVpn.launch(arrayOf("*/*")) },
                    onOpenLegacySettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } catch (_: Exception) {
                            controller.showMessage("Не удалось открыть настройки VPN")
                        }
                    },
                    onCopyPublicKey = { key ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WireGuard public key", key))
                    }
                )
                }
            }
        }
    }

    private fun openL2tpClient() {
        try {
            val packageName = "org.naruworks.l2tpvpn"
            val installed = packageManager.getLaunchIntentForPackage(packageName)
            if (installed != null) {
                startActivity(installed)
            } else {
                val url = "https://play.google.com/store/apps/details?id=" + packageName
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (_: Exception) {
            controller.showMessage("Не удалось открыть совместимый L2TP-клиент")
        }
    }

    private fun openFreeServer(server: FreeServer) {
        controller.showMessage(
            "На сайте VPNBook выберите сервер " + server.host +
                ", создайте личный профиль и импортируйте файл .conf в WhiteVPN."
        )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FreeServerCatalog.generatorUrl)))
        } catch (_: Exception) {
            controller.showMessage("Не удалось открыть сайт VPNBook в браузере")
        }
    }

    private fun requestVpnPermission() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) controller.connect()
        else vpnPermission.launch(permissionIntent)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val length = input.read(buffer)
            if (length < 0) break
            if (result.size() + length > 65536) error("Oversize configuration")
            result.write(buffer, 0, length)
        }
        return result.toByteArray()
    }
}

private val whiteVpnColors = darkColorScheme(
    primary = Color(0xFFF5F8FF),
    onPrimary = Color(0xFF121C2B),
    background = Color(0xFF101B2D),
    onBackground = Color(0xFFECF2FB),
    surface = Color(0xFF22334D),
    onSurface = Color(0xFFF5F8FF)
)

@Composable
private fun WhiteVpnScreen(
    state: VpnUiState,
    ikeStatus: Ikev2Status,
    l2tpEntries: List<L2tpProfile>,
    l2tpState: L2tpConnectionState,
    onConnectL2tp: (String, L2tpCryptoMode) -> Unit,
    onDisconnectL2tp: () -> Unit,
    onSaveL2tp: (L2tpProfile) -> Boolean,
    onDeleteL2tp: (String) -> Unit,
    onCopyL2tp: (String) -> Unit,
    onOpenL2tpClient: () -> Unit,
    onStartIkev2: (String, String, String, String) -> Unit,
    onStopIkev2: () -> Unit,
    onToggle: () -> Unit,
    onImport: () -> Unit,
    onSave: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenFreeServer: (FreeServer) -> Unit,
    onImportOpenVpn: () -> Unit,
    onOpenLegacySettings: () -> Unit,
    onCopyPublicKey: (String) -> Unit
) {
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showGenerator by remember { mutableStateOf(false) }
    var showIkev2Form by remember { mutableStateOf(false) }
    var activeProtocol by remember { mutableStateOf("WireGuard") }
    var l2tpSource by remember { mutableStateOf("Мои") }
    var softetherCompat by remember { mutableStateOf(false) }
    var selectedL2tpId by remember { mutableStateOf<String?>(null) }
    var catalogPingRefresh by remember { mutableIntStateOf(0) }
    val catalogPing = remember { mutableStateMapOf<String, Double?>() }
    var showL2tpForm by remember { mutableStateOf(false) }
    var l2tpEditingId by remember { mutableStateOf("") }
    var l2tpName by remember { mutableStateOf("") }
    var l2tpHost by remember { mutableStateOf("") }
    var l2tpUsername by remember { mutableStateOf("") }
    var l2tpPassword by remember { mutableStateOf("") }
    var l2tpPsk by remember { mutableStateOf("") }
    var l2tpError by remember { mutableStateOf<String?>(null) }
    var l2tpDetailId by remember { mutableStateOf<String?>(null) }
    var l2tpDeleteId by remember { mutableStateOf<String?>(null) }
    var vpnGateServers by remember { mutableStateOf(VpnGateL2tpCatalog.fallback) }
    var vpnGateRefresh by remember { mutableIntStateOf(0) }
    var vpnGateLoading by remember { mutableStateOf(false) }
    var vpnGateLive by remember { mutableStateOf(false) }
    var vpnGateExpanded by remember { mutableStateOf(false) }
    var vpnGateDisplayLimit by remember { mutableIntStateOf(40) }
    var vpnGateCountry by remember { mutableStateOf("Все") }
    var vpnGateMessage by remember {
        mutableStateOf("Резервный список: 16.10.2026 (3 адреса). " +
            "Для актуального каталога нажмите «Обновить»")
    }
    LaunchedEffect(vpnGateRefresh) {
        if (vpnGateRefresh == 0) return@LaunchedEffect
        vpnGateLoading = true
        try {
            val live = withContext(Dispatchers.IO) { VpnGateL2tpCatalog.fetch() }
            vpnGateServers = live
            vpnGateLive = true
            vpnGateDisplayLimit = 40
            vpnGateMessage = "Получено с VPN Gate: " + live.size + " L2TP/IPsec серверов"
        } catch (_: Exception) {
            vpnGateLive = false
            vpnGateMessage = "Не удалось загрузить VPN Gate. " +
                "Показан последний полученный или резервный список."
        } finally {
            vpnGateLoading = false
        }
    }

    var ipSpeedServers by remember { mutableStateOf(IpSpeedL2tpCatalog.snapshot) }
    var ipSpeedRefresh by remember { mutableIntStateOf(0) }
    var ipSpeedLoading by remember { mutableStateOf(false) }
    var ipSpeedLive by remember { mutableStateOf(false) }
    var ipSpeedMessage by remember {
        mutableStateOf("Резервный список от " + IpSpeedL2tpCatalog.snapshotUpdated)
    }
    var ipSpeedExpanded by remember { mutableStateOf(true) }
    var ipSpeedCountry by remember { mutableStateOf("Все") }

    LaunchedEffect(ipSpeedRefresh) {
        ipSpeedLoading = true
        try {
            val latest = withContext(Dispatchers.IO) { IpSpeedL2tpCatalog.fetch() }
            ipSpeedServers = latest
            ipSpeedLive = true
            ipSpeedMessage = "Данные загружены с IPSpeed • " + latest.size + " серверов"
        } catch (_: Exception) {
            ipSpeedLive = false
            ipSpeedMessage = "Не удалось обновить сайт. Показывается встроенный список от " +
                IpSpeedL2tpCatalog.snapshotUpdated
            if (ipSpeedServers.isEmpty()) ipSpeedServers = IpSpeedL2tpCatalog.snapshot
        } finally {
            ipSpeedLoading = false
        }
    }

    LaunchedEffect(activeProtocol, l2tpSource, catalogPingRefresh, ipSpeedServers, vpnGateServers) {
        if (activeProtocol != "L2TP" || l2tpSource == "Мои") return@LaunchedEffect
        // Limit probes for volatile public directories; never probe on the UI thread.
        val hosts = if (l2tpSource == "IPSpeed") {
            ipSpeedServers.map { it.ip }
        } else {
            vpnGateServers.map { it.host }
        }
        val limiter = Semaphore(4)
        coroutineScope {
            hosts.distinct().take(48).forEach { host ->
                if (!catalogPing.containsKey(host)) {
                    launch(Dispatchers.IO) {
                        val result = limiter.withPermit { ServerPing.probe(host) }
                        withContext(Dispatchers.Main) {
                            catalogPing[host] = result
                        }
                    }
                }
            }
        }
    }
    var l2tpPingRefresh by remember { mutableIntStateOf(0) }
    val l2tpPing = remember { mutableStateMapOf<String, Double?>() }
    LaunchedEffect(l2tpEntries, l2tpPingRefresh) {
        l2tpPing.clear()
        val limiter = Semaphore(3)
        coroutineScope {
            l2tpEntries.forEach { profile ->
                launch(Dispatchers.IO) {
                    val rtt = limiter.withPermit { ServerPing.probe(profile.host) }
                    withContext(Dispatchers.Main) { l2tpPing[profile.id] = rtt }
                }
            }
        }
    }
    var ikeServer by remember { mutableStateOf("") }
    var ikeIdentity by remember { mutableStateOf("") }
    var ikeUser by remember { mutableStateOf("") }
    var ikePassword by remember { mutableStateOf("") }
    var generatedPublicKey by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var serverKey by remember { mutableStateOf("") }
    var localAddress by remember { mutableStateOf("10.0.0.2/32") }
    var dns by remember { mutableStateOf("1.1.1.1") }
    var generatorError by remember { mutableStateOf<String?>(null) }

    var pingRefresh by remember { mutableIntStateOf(0) }
    var savedPingRefresh by remember { mutableIntStateOf(0) }
    val savedPing = remember { mutableStateMapOf<String, Double?>() }
    LaunchedEffect(savedPingRefresh, state.profiles) {
        savedPing.clear()
        val limiter = Semaphore(3)
        coroutineScope {
            state.profiles.forEach { profile ->
                val host = profile.endpointHost
                if (host != null) {
                    launch(Dispatchers.IO) {
                        val rtt = limiter.withPermit { ServerPing.probe(host) }
                        withContext(Dispatchers.Main) {
                            savedPing[profile.id] = rtt
                        }
                    }
                }
            }
        }
    }
    val pingResults = remember { mutableStateMapOf<String, Double?>() }
    LaunchedEffect(pingRefresh) {
        pingResults.clear()
        val limiter = Semaphore(3)
        coroutineScope {
            FreeServerCatalog.servers.forEach { server ->
                launch(Dispatchers.IO) {
                    val result = limiter.withPermit { ServerPing.probe(server.host) }
                    withContext(Dispatchers.Main) {
                        pingResults[server.host] = result
                    }
                }
            }
        }
    }
    val isBusy = state.status == VpnStatus.CONNECTING ||
        state.status == VpnStatus.DISCONNECTING
    val running = state.status == VpnStatus.CONNECTED

    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFF5F8FF), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("W", color = Color(0xFF121C2B), fontWeight = FontWeight.Black)
            }
            Text(
                "WhiteVPN",
                modifier = Modifier.padding(start = 12.dp),
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(20.dp))
        listOf(listOf("WireGuard", "L2TP"), listOf("IKEv2", "OpenVPN")).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                line.forEach { protocol ->
                    OutlinedButton(
                        onClick = { activeProtocol = protocol },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (activeProtocol == protocol) Color(0xFF477CA3)
                                else Color(0xFF22334D),
                            contentColor = Color(0xFFF5F8FF)
                        )
                    ) {
                        Text(protocol, fontWeight = if (activeProtocol == protocol)
                            FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (activeProtocol == "WireGuard") {
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier
                .background(
                    if (running) Color(0xFF193E37) else Color(0xFF1D2B40),
                    CircleShape
                )
                .padding(31.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .background(
                        if (running) Color(0xFF68E0B1) else Color(0xFFB7C6DE),
                        CircleShape
                    )
                    .padding(horizontal = 40.dp, vertical = 32.dp)
            ) {
                Text(
                    if (running) "✓" else "W",
                    color = Color(0xFF0C1422),
                    fontSize = 55.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            when (state.status) {
                VpnStatus.DISCONNECTED -> "Не подключено"
                VpnStatus.CONNECTING -> "Подключение..."
                VpnStatus.CONNECTED -> "Туннель запущен"
                VpnStatus.DISCONNECTING -> "Отключение..."
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                !state.configured -> "Добавьте конфигурацию WireGuard"
                running -> "Туннель активен. Доступность сервера не проверена."
                else -> "Выбран профиль: " + (state.activeName ?: "Нет")
            },
            color = Color(0xFFC7D4E8),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onToggle,
            enabled = state.configured && !isBusy &&
                ikeStatus != Ikev2Status.CONNECTED && ikeStatus != Ikev2Status.CONNECTING,
            modifier = Modifier.fillMaxWidth().height(62.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFEBB4B4) else Color.White,
                contentColor = Color(0xFF121C2B)
            )
        ) {
            Text(
                if (running) "Отключить VPN" else "Подключить VPN",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        if (state.message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                state.message,
                color = Color(0xFFF2B4B4),
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(30.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF22334D), contentColor = Color(0xFFF5F8FF))
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Конфигурация", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.configured) "Профиль сохранён и зашифрован"
                    else "Профиль ещё не загружен",
                    color = Color(0xFFC7D4E8),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status == VpnStatus.DISCONNECTED
                ) { Text("Импортировать .conf") }

                OutlinedButton(
                    onClick = { showGenerator = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status == VpnStatus.DISCONNECTED
                ) {
                    Text("Создать конфигурацию для своего сервера")
                }

                TextButton(
                    onClick = { showPasteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status == VpnStatus.DISCONNECTED
                ) {
                    Text("Вставить конфигурацию вручную")
                }
            }
        }

        Spacer(Modifier.height(25.dp))
        Text(
            "Мои подключения",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = { savedPingRefresh += 1 },
                enabled = state.profiles.any { it.endpointHost != null }
            ) { Text("↻ Проверить пинг") }
        }
        Text(
            "Задержка до Endpoint каждого сохранённого WireGuard-профиля (ICMP). Нет ответа не означает, что VPN не работает.",
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFC7D4E8),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        if (state.profiles.isEmpty()) {
            Text(
                "Пока нет сохранённых профилей. Выберите бесплатный сервер ниже и получите .conf на сайте провайдера.",
                color = Color(0xFFC7D4E8),
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.profiles.forEach { profile ->
            val selected = state.selectedId == profile.id
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(17.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF22334D), contentColor = Color(0xFFF5F8FF))
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        profile.provider + if (selected) " • Выбран" else "",
                        color = Color(0xFFC7D4E8),
                        fontSize = 12.sp
                    )
                    Text(
                        when {
                            profile.endpointHost == null -> "Пинг недоступен: нет Endpoint"
                            !savedPing.containsKey(profile.id) -> "Проверка пинга..."
                            else -> ServerPing.label(savedPing[profile.id])
                        } + (profile.endpointHost?.let { " • " + it } ?: ""),
                        fontSize = 13.sp,
                        color = if (savedPing[profile.id] != null) {
                            Color(0xFF8CF1C7)
                        } else {
                            Color(0xFFF0CE96)
                        },
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { onSelect(profile.id) },
                            enabled = !selected && state.status == VpnStatus.DISCONNECTED
                        ) { Text(if (selected) "Выбран" else "Выбрать") }
                        TextButton(
                            onClick = { pendingDeleteId = profile.id },
                            enabled = state.status == VpnStatus.DISCONNECTED
                        ) {
                            Text("Удалить", color = Color(0xFFF2B4B4))
                        }
                    }
                }
            }
        }

        }
        if (activeProtocol == "OpenVPN") {
        Spacer(Modifier.height(24.dp))
        Text(
            "OpenVPN",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 21.sp,
            color = Color(0xFFF5F8FF),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF22334D),
                contentColor = Color(0xFFF5F8FF)
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("OpenVPN • через совместимый клиент", fontWeight = FontWeight.Bold)
                Text(
                    "Файл .ovpn будет передан выбранному приложению OpenVPN. Встроенный WireGuard не запускает OpenVPN-туннели.",
                    color = Color(0xFFC7D4E8),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImportOpenVpn) {
                    Text("Открыть конфигурацию .ovpn")
                }
            }
        }
        }
        if (activeProtocol == "L2TP") {
        Spacer(Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF22334D),
                contentColor = Color(0xFFF5F8FF)
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("L2TP/IPsec PSK", fontWeight = FontWeight.Bold)
                val rankedL2tp = L2tpPingRanking.sortProfiles(l2tpEntries, l2tpPing)
                val selectedL2tp = l2tpEntries.firstOrNull { it.id == l2tpState.profileId }
                    ?: l2tpEntries.firstOrNull { it.id == selectedL2tpId }
                    ?: rankedL2tp.firstOrNull()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Выбран: " + (selectedL2tp?.name ?: "Добавьте сервер в «Мои»"),
                    color = Color(0xFFC7D4E8), fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { softetherCompat = !softetherCompat },
                    enabled = l2tpState.phase != L2tpConnectionPhase.CONNECTING &&
                        l2tpState.phase != L2tpConnectionPhase.CONNECTED &&
                        l2tpState.phase != L2tpConnectionPhase.DISCONNECTING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (softetherCompat) "✓ Совместимость SoftEther: ВКЛ"
                        else "Совместимость SoftEther: ВЫКЛ",
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (softetherCompat)
                        "ВНИМАНИЕ: AES-128/SHA-1/DH2 (1024 бит) — устаревшая криптография. " +
                            "Включайте только для проверки публичных SoftEther-серверов, " +
                            "не используйте для чувствительных данных."
                    else "Защищённый режим: AES-256/SHA-256/DH14. " +
                        "Многие публичные SoftEther-серверы его не поддерживают. " +
                        "Для них можно явно включить совместимость.",
                    color = Color(0xFFF0CE96), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 5.dp)
                )
                val busyL2tp = l2tpState.phase == L2tpConnectionPhase.CONNECTING ||
                    l2tpState.phase == L2tpConnectionPhase.CONNECTED ||
                    l2tpState.phase == L2tpConnectionPhase.DISCONNECTING
                Button(
                    onClick = {
                        if (busyL2tp) onDisconnectL2tp()
                        else selectedL2tp?.let {
                            onConnectL2tp(it.id, if (softetherCompat)
                                L2tpCryptoMode.SOFTETHER_LEGACY
                            else L2tpCryptoMode.STRONG)
                        }
                    },
                    enabled = if (busyL2tp) l2tpState.phase != L2tpConnectionPhase.DISCONNECTING
                        else selectedL2tp != null &&
                            state.status == VpnStatus.DISCONNECTED &&
                            ikeStatus != Ikev2Status.CONNECTED &&
                            ikeStatus != Ikev2Status.CONNECTING,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (busyL2tp) Color(0xFFEBB4B4)
                            else Color.White,
                        contentColor = Color(0xFF121C2B)
                    )
                ) {
                    Text(
                        if (busyL2tp) "Отключить L2TP / отменить"
                        else "Подключить L2TP",
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Встроенный L2TP: экспериментальный IKEv1/IPsec-движок " +
                        "без независимого криптоаудита. " +
                        "Не используйте публичные PSK для чувствительных данных.",
                    color = Color(0xFFF0CE96), fontSize = 12.sp
                )
                Text(
                    "Статус: " + l2tpState.message +
                        (l2tpState.ipAddress?.let { " • IP " + it } ?: ""),
                    color = when (l2tpState.phase) {
                        L2tpConnectionPhase.CONNECTED -> Color(0xFF8CF1C7)
                        L2tpConnectionPhase.FAILED -> Color(0xFFFFB7B7)
                        else -> Color(0xFFC7D4E8)
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(
                    "Добавляйте и сохраняйте адрес, логин, пароль и IPsec PSK. " +
                        "Подключение использует экспериментальный встроенный VPN-движок. " +
                        "Если он несовместим с сервером, можно открыть внешний клиент.",
                    color = Color(0xFFC7D4E8),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        l2tpEditingId = ""
                        l2tpName = ""
                        l2tpHost = ""
                        l2tpUsername = ""
                        l2tpPassword = ""
                        l2tpPsk = ""
                        l2tpError = null
                        showL2tpForm = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Добавить L2TP/IPsec сервер") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenLegacySettings) {
                        Text("Настройки Android ↗")
                    }
                    TextButton(onClick = onOpenL2tpClient) {
                        Text("L2TP-клиент ↗")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Мои", "IPSpeed", "VPN Gate").forEach { source ->
                        OutlinedButton(
                            onClick = { l2tpSource = source },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (l2tpSource == source)
                                    Color(0xFF477CA3) else Color(0xFF30415B),
                                contentColor = Color(0xFFF5F8FF)
                            )
                        ) { Text(source, fontSize = 12.sp) }
                    }
                }
                if (l2tpSource == "Мои") {
                if (l2tpEntries.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Сохранено: " + l2tpEntries.size,
                            color = Color(0xFFC7D4E8),
                            fontSize = 13.sp
                        )
                        TextButton(onClick = { l2tpPingRefresh += 1 }) {
                            Text("↻ Пинг")
                        }
                    }
                }
                L2tpPingRanking.sortProfiles(l2tpEntries, l2tpPing).forEach { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF30415B),
                            contentColor = Color(0xFFF5F8FF)
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(profile.host + " • L2TP/IPsec PSK",
                                color = Color(0xFFC7D4E8), fontSize = 13.sp)
                            Text(
                                if (l2tpPing.containsKey(profile.id)) {
                                    ServerPing.label(l2tpPing[profile.id])
                                } else {
                                    "Проверка пинга..."
                                },
                                color = if (l2tpPing[profile.id] != null) {
                                    Color(0xFF8CF1C7)
                                } else {
                                    Color(0xFFF0CE96)
                                },
                                fontSize = 12.sp
                            )
                            val active = l2tpState.profileId == profile.id
                            val busy = l2tpState.phase == L2tpConnectionPhase.CONNECTING ||
                                l2tpState.phase == L2tpConnectionPhase.DISCONNECTING
                            if (active && (l2tpState.phase == L2tpConnectionPhase.CONNECTED ||
                                l2tpState.phase == L2tpConnectionPhase.CONNECTING)) {
                                OutlinedButton(
                                    onClick = onDisconnectL2tp,
                                    enabled = l2tpState.phase != L2tpConnectionPhase.DISCONNECTING,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Отключить L2TP") }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        onConnectL2tp(
                                            profile.id,
                                            if (softetherCompat) L2tpCryptoMode.SOFTETHER_LEGACY
                                            else L2tpCryptoMode.STRONG
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy &&
                                        l2tpState.phase != L2tpConnectionPhase.CONNECTED &&
                                        state.status == VpnStatus.DISCONNECTED &&
                                        ikeStatus != Ikev2Status.CONNECTED &&
                                        ikeStatus != Ikev2Status.CONNECTING
                                ) { Text("Подключить L2TP (эксперимент)") }
                            }
                            TextButton(onClick = { selectedL2tpId = profile.id }) {
                                Text(if (selectedL2tp?.id == profile.id)
                                    "✓ Выбран" else "Выбрать сервер")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { l2tpDetailId = profile.id }) {
                                    Text("Параметры")
                                }
                                TextButton(onClick = {
                                    l2tpEditingId = profile.id
                                    l2tpName = profile.name
                                    l2tpHost = profile.host
                                    l2tpUsername = profile.username
                                    l2tpPassword = profile.password
                                    l2tpPsk = profile.preSharedKey
                                    l2tpError = null
                                    showL2tpForm = true
                                }) { Text("Изменить") }
                                TextButton(onClick = { l2tpDeleteId = profile.id }) {
                                    Text("Удалить", color = Color(0xFFFFB7B7))
                                }
                            }
                        }
                    }
                }
                if (l2tpEntries.isNotEmpty()) {
                    Text(
                        "Пинг проверяет ICMP, а не авторизацию L2TP. " +
                            "Пароли хранятся зашифрованными и не выводятся в карточках.",
                        color = Color(0xFFC7D4E8), fontSize = 12.sp
                    )
                }

                }
                if (l2tpSource == "IPSpeed") {
                Spacer(Modifier.height(18.dp))
                Text(
                    "IPSpeed • бесплатные L2TP/IPsec",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFFF5F8FF)
                )
                Text(
                    "Общие реквизиты провайдера: логин vpn • пароль vpn • IPsec PSK vpn. " +
                        "Общие ключи не подходят для конфиденциальных данных. " +
                        "Доступность каждого сервера не гарантируется.",
                    color = Color(0xFFC7D4E8),
                    fontSize = 12.sp
                )
                Text(
                    if (ipSpeedLoading) "Обновляем список..." else ipSpeedMessage,
                    color = if (ipSpeedLive) Color(0xFF8CF1C7) else Color(0xFFF0CE96),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { ipSpeedRefresh += 1 },
                        enabled = !ipSpeedLoading
                    ) { Text("↻ Обновить") }
                    OutlinedButton(onClick = { ipSpeedExpanded = !ipSpeedExpanded }) {
                        Text(if (ipSpeedExpanded) "Свернуть" else "Серверы (" + ipSpeedServers.size + ")")
                    }
                }
                if (ipSpeedExpanded) {
                    val hasUnsaved = ipSpeedServers.any { server ->
                        l2tpEntries.none { it.host == server.ip }
                    }
                    OutlinedButton(
                        onClick = {
                            ipSpeedServers.forEach { server ->
                                if (l2tpEntries.none { it.host == server.ip }) {
                                    onSaveL2tp(IpSpeedL2tpCatalog.toProfile(server))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasUnsaved
                    ) { Text("Добавить все новые серверы") }
                    Text(
                        "Встроенный пинг IPSpeed относится к их сети, не к твоему телефону. " +
                            "После добавления сервер появится в списке выше с ICMP-пингом телефона.",
                        color = Color(0xFFC7D4E8),
                        fontSize = 12.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("Все", "Хорватия", "Япония", "США").forEach { country ->
                            TextButton(onClick = { ipSpeedCountry = country }) {
                                Text(
                                    country,
                                    color = if (ipSpeedCountry == country) {
                                        Color(0xFF8CF1C7)
                                    } else {
                                        Color(0xFFC7D4E8)
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    ipSpeedServers.filter {
                        ipSpeedCountry == "Все" || it.country == ipSpeedCountry
                    }.sortedWith(compareBy<IpSpeedL2tpServer> {
                        L2tpPingRanking.group(it.ip, catalogPing)
                    }.thenBy { catalogPing[it.ip] ?: Double.MAX_VALUE }
                        .thenBy { it.ip }).forEach { server ->
                        val imported = l2tpEntries.any { it.host == server.ip }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF30415B),
                                contentColor = Color(0xFFF5F8FF)
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    server.flag + " " + server.country,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(server.ip, color = Color(0xFFC7D4E8), fontSize = 13.sp)
                                Text(
                                    if (catalogPing.containsKey(server.ip))
                                        ServerPing.label(catalogPing[server.ip])
                                    else "Проверка пинга...",
                                    color = if (catalogPing[server.ip] != null)
                                        Color(0xFF8CF1C7) else Color(0xFFF0CE96),
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Uptime (сайт): " + server.uptime +
                                        " • Пинг (сайт): " + server.publishedPing,
                                    color = Color(0xFFC7D4E8), fontSize = 11.sp
                                )
                                TextButton(
                                    onClick = {
                                        onSaveL2tp(IpSpeedL2tpCatalog.toProfile(server))
                                    },
                                    enabled = !imported
                                ) { Text(if (imported) "Сохранён" else "+ В мои L2TP") }
                            }
                        }
                    }
                }
                }
            }
        }

        if (l2tpSource == "VPN Gate") {
                Spacer(Modifier.height(18.dp))
                Text(
                    "VPN Gate • бесплатные L2TP/IPsec",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F8FF)
                )
                Text(
                    "Добровольческие серверы Университета Цукубы. Логин, пароль и PSK: vpn. " +
                        "Общий ключ не обеспечивает надёжную идентификацию сервера.",
                    color = Color(0xFFC7D4E8), fontSize = 12.sp
                )
                Text(
                    if (vpnGateLoading) "Получаем доступные L2TP-серверы..." else vpnGateMessage,
                    color = if (vpnGateLive) Color(0xFF8CF1C7) else Color(0xFFF0CE96),
                    fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vpnGateRefresh += 1 }, enabled = !vpnGateLoading) {
                        Text("↻ Обновить")
                    }
                    OutlinedButton(onClick = { vpnGateExpanded = !vpnGateExpanded }) {
                        Text(if (vpnGateExpanded) "Свернуть" else "Показать")
                    }
                }
                if (vpnGateExpanded) {
                    val countryList = listOf("Все") +
                        vpnGateServers.map { it.country }.distinct().take(12)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { vpnGateCountry = "Все" }) {
                            Text("Все", color = Color(0xFF8CF1C7))
                        }
                        Text(
                            "Фильтр по стране ниже • найдено " + vpnGateServers.size,
                            color = Color(0xFFC7D4E8), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                    countryList.filter { it != "Все" }.forEach { c ->
                        TextButton(onClick = {
                            vpnGateCountry = c
                            vpnGateDisplayLimit = 40
                        }) {
                            Text(
                                c,
                                color = if (vpnGateCountry == c) Color(0xFF8CF1C7)
                                else Color(0xFFC7D4E8)
                            )
                        }
                    }
                    val shown = vpnGateServers.filter {
                        vpnGateCountry == "Все" || it.country == vpnGateCountry
                    }.sortedWith(compareBy<VpnGateL2tpServer> {
                        L2tpPingRanking.group(it.host, catalogPing)
                    }.thenBy { catalogPing[it.host] ?: Double.MAX_VALUE }
                        .thenBy { it.host })
                    shown.take(vpnGateDisplayLimit).forEach { server ->
                        val saved = l2tpEntries.any { it.host == server.host }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(15.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF30415B),
                                contentColor = Color(0xFFF5F8FF)
                            )
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Text(server.flag + " " + server.country,
                                    fontWeight = FontWeight.SemiBold)
                                Text(server.host + " • " + server.uptime,
                                    color = Color(0xFFC7D4E8), fontSize = 13.sp)
                                Text(
                                    if (catalogPing.containsKey(server.host))
                                        ServerPing.label(catalogPing[server.host])
                                    else "Проверка пинга...",
                                    color = if (catalogPing[server.host] != null)
                                        Color(0xFF8CF1C7) else Color(0xFFF0CE96),
                                    fontSize = 12.sp
                                )
                                Text("Пинг VPN Gate: " + server.sitePing,
                                    color = Color(0xFFC7D4E8), fontSize = 12.sp)
                                TextButton(
                                    onClick = { onSaveL2tp(VpnGateL2tpCatalog.profile(server)) },
                                    enabled = !saved
                                ) { Text(if (saved) "Уже в моих" else "+ В мои L2TP") }
                            }
                        }
                    }
                    if (shown.size > vpnGateDisplayLimit) {
                        OutlinedButton(
                            onClick = { vpnGateDisplayLimit += 40 },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Показать ещё • " + (shown.size - vpnGateDisplayLimit)) }
                    }
                    Text(
                        "На VPN Gate отображаются только серверы со значком поддержки L2TP. " +
                            "Адрес может перестать работать в любой момент.",
                        color = Color(0xFFC7D4E8), fontSize = 12.sp
                    )
                }
        }
        }
        if (activeProtocol == "IKEv2") {
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF22334D),
                contentColor = Color(0xFFF5F8FF)
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("IKEv2/IPsec • встроенный Android", fontWeight = FontWeight.Bold)
                Text(
                    when (ikeStatus) {
                        Ikev2Status.UNSUPPORTED -> "IKEv2 через VpnManager недоступен на этом устройстве (нужен Android 13+ с IPsec tunnels)."
                        Ikev2Status.CONNECTED -> "Подключено: Android подтвердил состояние IKEv2."
                        Ikev2Status.CONNECTING -> "Android устанавливает IKEv2-соединение..."
                        Ikev2Status.FAILED -> "Ошибка IKEv2. Проверьте сервер, авторизацию и настройки Android."
                        Ikev2Status.DISCONNECTED -> "Для своего IKEv2-сервера: EAP (логин/пароль) и проверка сертификата системой Android."
                    },
                    color = Color(0xFFC7D4E8),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                if (ikeStatus == Ikev2Status.CONNECTED ||
                    ikeStatus == Ikev2Status.CONNECTING) {
                    OutlinedButton(onClick = onStopIkev2) {
                        Text("Отключить IKEv2")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showIkev2Form = true },
                        enabled = ikeStatus != Ikev2Status.UNSUPPORTED &&
                            state.status == VpnStatus.DISCONNECTED
                    ) { Text("Настроить и подключить IKEv2") }
                }
            }
        }

        }
        if (activeProtocol == "WireGuard") {
        Spacer(Modifier.height(24.dp))
        Text(
            "Бесплатные серверы",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "VPNBook • 10 открытых серверов в 5 странах. Для каждого подключения нужно сначала создать бесплатный личный WireGuard-профиль.",
            color = Color(0xFFC7D4E8),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = { pingRefresh += 1 }) {
                Text("↻ Проверить пинг")
            }
        }
        Text(
            "Пинг измеряется через ICMP, не через WireGuard. «Нет ответа» может означать блокировку ICMP, а не неисправность VPN.",
            color = Color(0xFFC7D4E8),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        FreeServerCatalog.servers.groupBy { it.country }.forEach { (country, servers) ->
            Text(
                country,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                color = Color(0xFFBECDE2),
                fontWeight = FontWeight.SemiBold
            )
            servers.forEach { server ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF22334D), contentColor = Color(0xFFF5F8FF))
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(server.flag + "  " + server.name, fontWeight = FontWeight.Bold)
                        Text(
                            server.host,
                            fontSize = 12.sp,
                            color = Color(0xFFC7D4E8)
                        )
                        Text(
                            if (pingResults.containsKey(server.host)) {
                                ServerPing.label(pingResults[server.host])
                            } else {
                                "Проверка пинга..."
                            },
                            color = if (pingResults[server.host] != null) {
                                Color(0xFF8CF1C7)
                            } else {
                                Color(0xFFF0CE96)
                            },
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        TextButton(onClick = { onOpenFreeServer(server) }) {
                            Text("Получить бесплатный профиль ↗")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "VPNBook: один профиль на одно устройство, срок действия — 7 дней. Провайдер хранит IP-адрес и время подключения до недели. Серверы сторонние, доступность не проверяется в реальном времени.",
            color = Color(0xFFC7D4E8),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "WhiteVPN • WireGuard • независимый клиент, не связанный с VPNBook",
            fontSize = 12.sp,
            color = Color(0xFFB7C6DA),
            textAlign = TextAlign.Center
        )
        }
    }

    if (showL2tpForm) {
        AlertDialog(
            onDismissRequest = {
                l2tpPassword = ""
                l2tpPsk = ""
                showL2tpForm = false
            },
            title = {
                Text(if (l2tpEditingId.isBlank()) "Добавить L2TP/IPsec"
                    else "Изменить L2TP/IPsec")
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "L2TP/IPsec с общим ключом (PSK). " +
                            "На Android 13+ для подключения понадобится отдельное приложение.",
                        color = Color(0xFFC7D4E8), fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = l2tpName,
                        onValueChange = { l2tpName = it.take(100) },
                        label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = l2tpHost,
                        onValueChange = { l2tpHost = it.take(253) },
                        label = { Text("Адрес сервера (домен или IP)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = l2tpUsername,
                        onValueChange = { l2tpUsername = it.take(255) },
                        label = { Text("Логин L2TP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = l2tpPassword,
                        onValueChange = { l2tpPassword = it.take(512) },
                        label = { Text("Пароль L2TP") },
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = l2tpPsk,
                        onValueChange = { l2tpPsk = it.take(512) },
                        label = { Text("IPsec PSK (общий ключ)") },
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (l2tpError != null) {
                        Text(l2tpError.orEmpty(), color = Color(0xFFFFB7B7))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val draft = L2tpProfile(
                        id = l2tpEditingId,
                        name = l2tpName,
                        host = l2tpHost,
                        username = l2tpUsername,
                        password = l2tpPassword,
                        preSharedKey = l2tpPsk
                    )
                    if (!L2tpProfileValidation.isValid(draft)) {
                        l2tpError = "Заполните все поля. Адрес должен быть доменом или IP, без порта."
                    } else if (onSaveL2tp(draft)) {
                        showL2tpForm = false
                        l2tpPassword = ""
                        l2tpPsk = ""
                        l2tpError = null
                    } else {
                        l2tpError = "Не удалось сохранить профиль"
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    l2tpPassword = ""
                    l2tpPsk = ""
                    showL2tpForm = false
                }) { Text("Отмена") }
            }
        )
    }

    val l2tpDetails = l2tpEntries.firstOrNull { it.id == l2tpDetailId }
    if (l2tpDetails != null) {
        AlertDialog(
            onDismissRequest = { l2tpDetailId = null },
            title = { Text("L2TP/IPsec: " + l2tpDetails.name) },
            text = {
                Column {
                    Text("Сервер: " + l2tpDetails.host)
                    Text("Логин: " + l2tpDetails.username)
                    Text("Пароль и PSK скрыты. Можно скопировать их отдельно.")
                    Text(
                        "Внимание: секреты в буфере обмена могут быть доступны " +
                            "другим приложениям. Очистите буфер после настройки.",
                        color = Color(0xFFF0CE96),
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = { onCopyL2tp(l2tpDetails.host) }) {
                        Text("Копировать адрес")
                    }
                    OutlinedButton(onClick = { onCopyL2tp(l2tpDetails.username) }) {
                        Text("Копировать логин")
                    }
                    OutlinedButton(onClick = { onCopyL2tp(l2tpDetails.password) }) {
                        Text("Копировать пароль")
                    }
                    OutlinedButton(onClick = { onCopyL2tp(l2tpDetails.preSharedKey) }) {
                        Text("Копировать IPsec PSK")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { l2tpDetailId = null }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (l2tpDeleteId != null) {
        AlertDialog(
            onDismissRequest = { l2tpDeleteId = null },
            title = { Text("Удалить L2TP/IPsec сервер?") },
            text = { Text("Сохранённые реквизиты будут удалены из WhiteVPN.") },
            confirmButton = {
                TextButton(onClick = {
                    l2tpDeleteId?.let(onDeleteL2tp)
                    l2tpDeleteId = null
                }) { Text("Удалить", color = Color(0xFFFFB7B7)) }
            },
            dismissButton = {
                TextButton(onClick = { l2tpDeleteId = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showIkev2Form) {
        AlertDialog(
            onDismissRequest = {
                ikePassword = ""
                showIkev2Form = false
            },
            title = { Text("IKEv2/IPsec — EAP") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Используйте данные своего IKEv2-сервера. При первом подключении Android попросит разрешение.",
                        fontSize = 13.sp, color = Color(0xFFC7D4E8)
                    )
                    OutlinedTextField(
                        value = ikeServer,
                        onValueChange = { ikeServer = it.take(253) },
                        label = { Text("VPN-сервер, например vpn.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ikeIdentity,
                        onValueChange = { ikeIdentity = it.take(253) },
                        label = { Text("IKEv2 ID сервера") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ikeUser,
                        onValueChange = { ikeUser = it.take(255) },
                        label = { Text("Имя пользователя") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ikePassword,
                        onValueChange = { ikePassword = it.take(1024) },
                        label = { Text("Пароль") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onStartIkev2(ikeServer, ikeIdentity, ikeUser, ikePassword)
                    ikePassword = ""
                    showIkev2Form = false
                }) { Text("Подключить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ikePassword = ""
                    showIkev2Form = false
                }) { Text("Отмена") }
            }
        )
    }

    if (generatedPublicKey != null) {
        AlertDialog(
            onDismissRequest = { generatedPublicKey = null },
            title = { Text("Профиль создан") },
            text = {
                Column {
                    Text(
                        "Добавьте этот ПУБЛИЧНЫЙ ключ в список разрешённых клиентов на своём WireGuard-сервере. Иначе подключение не заработает."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        generatedPublicKey.orEmpty(),
                        color = Color(0xFF8CF1C7),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    generatedPublicKey?.let(onCopyPublicKey)
                    generatedPublicKey = null
                }) { Text("Копировать публичный ключ") }
            },
            dismissButton = {
                TextButton(onClick = { generatedPublicKey = null }) { Text("Закрыть") }
            }
        )
    }

    if (showGenerator) {
        AlertDialog(
            onDismissRequest = { showGenerator = false },
            title = { Text("Локальный генератор WireGuard") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Для своего сервера: клиентские ключи создаются на телефоне. VPNBook требует выдавать конфигурации на своём сайте.",
                        color = Color(0xFFC7D4E8),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.take(255) },
                        label = { Text("Сервер и порт, например vpn.example.com:51820") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = serverKey,
                        onValueChange = { serverKey = it.take(255) },
                        label = { Text("Публичный ключ сервера") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = localAddress,
                        onValueChange = { localAddress = it.take(255) },
                        label = { Text("IP клиента, например 10.0.0.2/32") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dns,
                        onValueChange = { dns = it.take(255) },
                        label = { Text("DNS") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (generatorError != null) {
                        Text(generatorError.orEmpty(), color = Color(0xFFFFB4B4))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val generated = WireGuardProfileGenerator.generate(
                            endpoint, serverKey, localAddress, dns
                        )
                        if (onSave(generated.config)) {
                            generatedPublicKey = generated.clientPublicKey
                            showGenerator = false
                            generatorError = null
                        } else {
                            generatorError = "Не удалось сохранить профиль"
                        }
                    } catch (_: Exception) {
                        generatorError = "Проверьте ключ сервера, адрес, DNS и порт"
                    }
                }) { Text("Создать и сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showGenerator = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Удалить профиль?") },
            text = { Text("Конфигурация будет удалена из WhiteVPN. На сервере провайдера это действие ничего не меняет.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId?.let(onDelete)
                    pendingDeleteId = null
                }) { Text("Удалить", color = Color(0xFFF2B4B4)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Отмена") }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = {
                pastedText = ""
                showPasteDialog = false
            },
            title = { Text("Конфигурация WireGuard") },
            text = {
                Column {
                    Text(
                        "Вставьте текст .conf с разделами [Interface] и [Peer].",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { if (it.length <= 65536) pastedText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                        label = { Text("Конфигурация") },
                        minLines = 6,
                        maxLines = 12
                    )
                    if (state.message != null) {
                        Text(state.message, color = Color(0xFFF2B4B4), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onSave(pastedText)) {
                        pastedText = ""
                        showPasteDialog = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pastedText = ""
                    showPasteDialog = false
                }) { Text("Отмена") }
            }
        )
    }
}
