package com.whitevpn.app

import android.app.Activity
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val current by controller.state.collectAsState()
            MaterialTheme(colorScheme = whiteVpnColors) {
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
                    onSave = controller::saveConfiguration
                )
            }
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
    background = Color(0xFF0C1422),
    onBackground = Color(0xFFECF2FB),
    surface = Color(0xFF182438),
    onSurface = Color(0xFFECF2FB)
)

@Composable
private fun WhiteVpnScreen(
    state: VpnUiState,
    onToggle: () -> Unit,
    onImport: () -> Unit,
    onSave: (String) -> Boolean
) {
    var showPasteDialog by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    val isBusy = state.status == VpnStatus.CONNECTING ||
        state.status == VpnStatus.DISCONNECTING
    val running = state.status == VpnStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
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

        Spacer(Modifier.height(54.dp))
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
                else -> "Конфигурация готова к подключению"
            },
            color = Color(0xFF9AABC3),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )

        Spacer(Modifier.height(34.dp))
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF172237))
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Конфигурация", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.configured) "Профиль сохранён и зашифрован"
                    else "Профиль ещё не загружен",
                    color = Color(0xFF9AABC3),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status == VpnStatus.DISCONNECTED
                ) { Text("Импортировать .conf") }

                TextButton(
                    onClick = { showPasteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status == VpnStatus.DISCONNECTED
                ) {
                    Text("Вставить конфигурацию вручную")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "WhiteVPN • WireGuard\nНужен собственный сервер или профиль провайдера",
            fontSize = 12.sp,
            color = Color(0xFF8293AD),
            textAlign = TextAlign.Center
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
