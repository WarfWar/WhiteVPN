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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val controller get() = (application as WhiteVpnApp).controller

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val current by controller.state.collectAsState()
            MaterialTheme(colorScheme = whiteVpnColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = whiteVpnColors.background,
                    contentColor = whiteVpnColors.onBackground
                ) {
                WhiteVpnScreen(
                    state = current,
                    onToggle = {
                        when (current.status) {
                            VpnStatus.CONNECTED -> controller.disconnect()
                            VpnStatus.DISCONNECTED -> requestVpnPermission()
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
    var generatedPublicKey by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var serverKey by remember { mutableStateOf("") }
    var localAddress by remember { mutableStateOf("10.0.0.2/32") }
    var dns by remember { mutableStateOf("1.1.1.1") }
    var generatorError by remember { mutableStateOf<String?>(null) }

    var pingRefresh by remember { mutableIntStateOf(0) }
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
            enabled = state.configured && !isBusy,
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

        Spacer(Modifier.height(24.dp))
        Text(
            "Другие протоколы",
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
                Text("L2TP/IPsec • устаревший протокол", fontWeight = FontWeight.Bold)
                Text(
                    "На Android 12–16 штатная поддержка L2TP/IPsec часто отсутствует. WhiteVPN не реализует L2TP: для новых серверов лучше IKEv2/IPsec.",
                    color = Color(0xFFC7D4E8),
                    fontSize = 13.sp
                )
                TextButton(onClick = onOpenLegacySettings) {
                    Text("Системные настройки VPN ↗")
                }
            }
        }

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
