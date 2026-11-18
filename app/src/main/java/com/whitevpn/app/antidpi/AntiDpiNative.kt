package com.whitevpn.app.antidpi

/**
 * JNI entry points to pinned MIT-licensed ByeDPI + HEV native sources.
 * Proxy native state is process-global, so the service runs in :antidpi.
 */
internal object AntiDpiNative {
    init { System.loadLibrary("whitedpi") }
    external fun proxyStart(args: Array<String>): Int
    external fun proxyStop(): Int
    external fun tunnelStart(config: String, fd: Int): Int
    external fun tunnelStop()

    /** AF_UNIX SOCK_DGRAM pair for optional QUIC fallback filtering. */
    external fun openPacketBridge(): IntArray
    /** Copy IP datagrams between the VPN TUN and the HEV socket end. */
    external fun runPacketBridge(tunFd: Int, bridgeFd: Int, blockUdp443: Boolean): Int
    external fun stopPacketBridge()
    external fun closePacketFd(fd: Int)
}
