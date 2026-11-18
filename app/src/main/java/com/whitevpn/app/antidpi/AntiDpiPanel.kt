package com.whitevpn.app.antidpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Controls for the selected Android apps, not an all-device VPN by default. */
@Composable
fun AntiDpiPanel(
    state: AntiDpiState,
    selection: AntiDpiStrategy,
    targets: Set<AntiDpiTarget>,
    dns: AntiDpiDns,
    blockQuic: Boolean,
    onSelect: (AntiDpiStrategy) -> Unit,
    onTargets: (Set<AntiDpiTarget>) -> Unit,
    onDns: (AntiDpiDns) -> Unit,
    onBlockQuic: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    vpnBusy: Boolean,
) {
    val running = state.phase == AntiDpiPhase.STARTING ||
        state.phase == AntiDpiPhase.ROUTING
    val editable = state.phase == AntiDpiPhase.STOPPED ||
        state.phase == AntiDpiPhase.FAILED
    var dnsMenu by remember { mutableStateOf(false) }

    Text("Anti-DPI • Telegram, YouTube, Instagram",
        fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Локальный обход DPI без Root. Обрабатываются только выбранные приложения; " +
            "остальной трафик телефона остаётся обычным.",
        color = Color(0xFFC7D4E8), fontSize = 13.sp
    )
    Spacer(Modifier.height(14.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF22334D), contentColor = Color(0xFFF5F8FF)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Выбери приложения", fontWeight = FontWeight.Bold)
            AntiDpiTarget.entries.forEach { target ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = target in targets,
                        onCheckedChange = { checked ->
                            if (editable) {
                                onTargets(if (checked) targets + target else targets - target)
                            }
                        },
                        enabled = editable
                    )
                    Text(target.title, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Состояние: " + when (state.phase) {
                    AntiDpiPhase.STOPPED -> "выключено"
                    AntiDpiPhase.STARTING -> "запускается"
                    AntiDpiPhase.ROUTING -> "локальный маршрут создан"
                    AntiDpiPhase.STOPPING -> "останавливается"
                    AntiDpiPhase.FAILED -> "ошибка"
                },
                fontWeight = FontWeight.SemiBold
            )
            Text(state.detail, fontSize = 12.sp, color = Color(0xFFC7D4E8))
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = if (running) onStop else onStart,
                enabled = state.phase != AntiDpiPhase.STOPPING &&
                    (running || (editable && !vpnBusy && targets.isNotEmpty())),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Остановить Anti-DPI" else "Запустить для выбранных приложений")
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Text("Стратегия", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    AntiDpiStrategy.entries.forEach { option ->
        OutlinedButton(
            onClick = { onSelect(option) },
            enabled = editable,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
            Text(
                (if (option == selection) "✓  " else "") + option.title +
                    " — " + option.details,
                fontSize = 12.sp
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Box {
        OutlinedButton(onClick = { dnsMenu = true }, enabled = editable) {
            Text("DNS: ${dns.title}", fontSize = 13.sp)
        }
        DropdownMenu(expanded = dnsMenu, onDismissRequest = { dnsMenu = false }) {
            AntiDpiDns.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        onDns(option)
                        dnsMenu = false
                    }
                )
            }
        }
    }
    Text(
        "По умолчанию используется DNS текущего Wi-Fi/оператора, " +
            "а не обязательный 1.1.1.1. Если DNS блокирует сервис, " +
            "попробуй другой вариант. Это не DoH.",
        color = Color(0xFFC7D4E8), fontSize = 12.sp
    )

    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = blockQuic,
            onCheckedChange = { onBlockQuic(it) },
            enabled = editable && AntiDpiTarget.YOUTUBE in targets
        )
        Text("YouTube: отключить QUIC (UDP/443) для перехода на TCP",
            fontSize = 12.sp)
    }
    Text(
        "Включи, если YouTube не загружает видео. Режим действует на ВСЕ выбранные " +
            "приложения: если одновременно выбран Telegram, это может нарушить звонки и видео. " +
            "Для проверки YouTube лучше временно оставить только его.",
        color = Color(0xFFFFD6A2), fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Встроенная проверка — это только TLS/HTTPS к главным доменам. " +
            "Она не подтверждает загрузку видео, Reels или соединение Telegram MTProto. " +
            "Если Telegram блокируется по IP, одной десинхронизации недостаточно: " +
            "понадобится отдельный работающий MTProto/SOCKS5-прокси.",
        color = Color(0xFFC7D4E8), fontSize = 12.sp
    )
}
