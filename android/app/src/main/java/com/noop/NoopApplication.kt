package com.noop

import android.app.Application
import com.noop.data.AppLog

/**
 * Application entry point.
 *
 * NOOP is a fully on-device WHOOP companion: it connects to the strap over BLE and
 * persists everything locally via Room. There is no network layer.
 *
 * This class is intentionally thin. The BLE client ([com.noop.ble.WhoopBleClient]) and
 * the data layer ([com.noop.data.WhoopRepository]) are owned and held by the
 * [com.noop.ui.AppViewModel], scoped to the Activity, so they live exactly as long as
 * the UI that drives them. The only process-wide setup is the diagnostic log.
 */
class NoopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
