package com.tuapp.gasolineraswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reajusta el intervalo cuando cambia el estado de carga.
 */
class PowerStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_POWER_CONNECTED ||
            action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            WidgetUpdateScheduler.programar(context)
        }
    }
}
