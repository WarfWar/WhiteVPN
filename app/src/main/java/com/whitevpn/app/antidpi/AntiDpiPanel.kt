package com.whitevpn.app.antidpi

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small panel reusing the existing WhiteVPN UI; not a second VPN application. */
@Composable
fun AntiDpiPanel(
    state: AntiDpiState,
    selection: AntiDpiStrategy,
    onSelect: (AntiDpiStrategy) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    vpnBusy: Boolean,
) {
    Text("Anti-DPI • без Root", fontSize = 21.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Локальный движок ByeDPI + tun2socks. Нет внешнего VPN-сервера; IP не скрывается, " +
            "дополнительное шифрование не создаётся. Метод может не работать на вашей сети.",
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
            Text("Состояние: " + when (state.phase) {
                AntiDpiPhase.STOPPED -> "выключено"
                AntiDpiPhase.STARTING -> "запуск"
                AntiDpiPhase.ROUTING -> "маршрут работает"
                AntiDpiPhase.STOPPING -> "выключение"
                AntiDpiPhase.FAILED -> "ошибка"
            }, fontWeight = FontWeight.Bold)
            Text(state.detail, fontSize = 13.sp, color = Color(0xFFC7D4E8))
            Spacer(Modifier.height(12.dp))
            val connected = state.phase == AntiDpiPhase.STARTING ||
                state.phase == AntiDpiPhase.ROUTING
            Button(
                onClick = if (connected) onStop else onStart,
                enabled = state.phase != AntiDpiPhase.STOPPING &&
                    (connected || !vpnBusy),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connected) "Остановить Anti-DPI" else "Запустить Anti-DPI")
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("Стратегии обработки", fontWeight = FontWeight.SemiBold)
    Text(
        "Выбери профиль до подключения. Универсальной стратегии нет; " +
            "включение режима не подтверждает доступность Discord.",
        color = Color(0xFFC7D4E8), fontSize = 12.sp
    )
    Spacer(Modifier.height(8.dp))
    AntiDpiStrategy.entries.forEach { option ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onSelect(option) },
                enabled = state.phase == AntiDpiPhase.STOPPED ||
                    state.phase == AntiDpiPhase.FAILED,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    (if (option == selection) "✓  " else "") + option.title +
                        " — " + option.details,
                    fontSize = 12.sp
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Примечание: Android разрешает только один VPN-интерфейс одновременно. " +
            "Перед включением Anti-DPI отключи WireGuard, L2TP и IKEv2. " +
            "UDP/голосовые вызовы и IPv6 пока не подтверждены испытаниями.",
        color = Color(0xFFB7C6DA), fontSize = 12.sp
    )
}
