package com.whitevpn.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.net.VpnProfileState
import android.os.Build

enum class Ikev2Status {
    UNSUPPORTED, DISCONNECTED, CONNECTING, CONNECTED, FAILED
}

/**
 * Android 13+ platform-managed IKEv2 with EAP-MSCHAPv2 credentials.
 * The system provisions/authenticates the tunnel; this class never
 * claims CONNECTED until VpnProfileState confirms it.
 */
class NativeIkev2Controller(private val application: Application) {
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            application.packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)

    private fun manager(): VpnManager {
        check(supported) { "IKEv2 not supported on this device" }
        return application.getSystemService(VpnManager::class.java)
            ?: error("No VpnManager service")
    }

    /** Null means consent was already granted. Nonnull intent must be launched by Activity. */
    fun provision(
        serverAddress: String, identity: String,
        username: String, password: String
    ): Intent? {
        check(supported)
        require(
            serverAddress.isNotBlank() &&
            serverAddress.length <= 253 &&
            ServerPing.validHost(serverAddress)
        )
        require(identity.isNotBlank() && identity.length <= 253 && '\n' !in identity)
        require(username.isNotBlank() && username.length <= 255 && '\n' !in username)
        require(password.isNotBlank() && password.length <= 1024 && '\n' !in password)
        val platformProfile = Ikev2VpnProfile.Builder(serverAddress.trim(), identity.trim())
            .setAuthUsernamePassword(username, password, null)
            .setBypassable(false)
            .build()
        return manager().provisionVpnProfile(platformProfile)
    }

    /** Request startup; the actual CONNECTED state is asynchronous. */
    fun start() {
        check(supported)
        manager().startProvisionedVpnProfileSession()
    }

    fun stop() {
        if (supported) manager().stopProvisionedVpnProfile()
    }

    fun status(): Ikev2Status {
        if (!supported) return Ikev2Status.UNSUPPORTED
        return try {
            when (manager().provisionedVpnProfileState?.state) {
                VpnProfileState.STATE_CONNECTED -> Ikev2Status.CONNECTED
                VpnProfileState.STATE_CONNECTING -> Ikev2Status.CONNECTING
                VpnProfileState.STATE_FAILED -> Ikev2Status.FAILED
                else -> Ikev2Status.DISCONNECTED
            }
        } catch (_: Exception) {
            Ikev2Status.FAILED
        }
    }
}
