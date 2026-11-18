package com.tuapp.gasolineraswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restaura el calendario de chequeos tras un reinicio o una actualizacion.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            WidgetUpdateScheduler.programar(context)
        }
    }
}
