package com.whitevpn.app.antidpi

/**
 * JNI entry points to the pinned MIT native sources.
 * The proxy has process-wide state: service runs in a dedicated :antidpi process.
 */
internal object AntiDpiNative {
    init { System.loadLibrary("whitedpi") }
    external fun proxyStart(args: Array<String>): Int
    external fun proxyStop(): Int
    external fun tunnelStart(config: String, fd: Int): Int
    external fun tunnelStop()
}
