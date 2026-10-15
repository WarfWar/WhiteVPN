package com.whitevpn.app

import android.app.Application

class WhiteVpnApp : Application() {
    val controller: VpnController by lazy { VpnController(this) }
    val nativeIkev2: NativeIkev2Controller by lazy { NativeIkev2Controller(this) }
}
