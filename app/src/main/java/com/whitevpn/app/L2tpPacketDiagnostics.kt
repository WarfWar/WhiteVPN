package com.whitevpn.app

import com.arcansecurity.vpn.l2tpipsec.core.tunnel.Datagram
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.UdpSocketChannel
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.UdpSocketFactory
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Observes ONLY counts/ports. It never records packet payloads, PSKs,
 * usernames, IP addresses, passwords or keys. No diagnostic probes are sent.
 */
class L2tpPacketDiagnostics(
    private val delegate: UdpSocketFactory,
    private val peer: InetAddress,
    private val record: (outbound: Boolean, port: Int) -> Unit
) : UdpSocketFactory {
    override fun open(): UdpSocketChannel = object : UdpSocketChannel {
        private val socket = delegate.open()
        override val localAddress: InetAddress get() = socket.localAddress
        override val localPort: Int get() = socket.localPort

        override fun send(
            data: ByteArray, offset: Int, length: Int,
            destination: InetSocketAddress
        ) {
            socket.send(data, offset, length, destination)
            if (destination.address == peer && destination.port in listOf(500, 4500)) {
                record(true, destination.port)
            }
        }

        override fun receive(buffer: ByteArray, timeoutMs: Int): Datagram? {
            val packet = socket.receive(buffer, timeoutMs) ?: return null
            if (packet.source.address == peer && packet.source.port in listOf(500, 4500)) {
                record(false, packet.source.port)
            }
            return packet
        }

        override fun close() {
            socket.close()
        }
    }
}

data class L2tpPacketCounts(
    val sent500: Int = 0,
    val recv500: Int = 0,
    val sent4500: Int = 0,
    val recv4500: Int = 0
) {
    val totalSent get() = sent500 + sent4500
    val totalReceived get() = recv500 + recv4500

    fun record(outbound: Boolean, port: Int): L2tpPacketCounts = when {
        outbound && port == 500 -> copy(sent500 = (sent500 + 1).coerceAtMost(9999))
        !outbound && port == 500 -> copy(recv500 = (recv500 + 1).coerceAtMost(9999))
        outbound && port == 4500 -> copy(sent4500 = (sent4500 + 1).coerceAtMost(9999))
        !outbound && port == 4500 -> copy(recv4500 = (recv4500 + 1).coerceAtMost(9999))
        else -> this
    }

    fun diagnosis(): String = when {
        totalSent == 0 -> "IKE-запрос ещё не отправлен"
        totalReceived == 0 -> "От сервера пока нет UDP-ответа (проверьте сеть, UDP/500 и 4500)"
        else -> "Ответы UDP поступают, но согласование пока не завершено"
    }

    fun summary(): String =
        "UDP/500: отправлено $sent500, получено $recv500" +
            " • UDP/4500: отправлено $sent4500, получено $recv4500"
}
