package com.tuapp.gasolineraswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recibe el chequeo periodico programado con AlarmManager.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WidgetUpdateScheduler.ACTION_REFRESH) return

        GasolinerasWidgetProvider.notificarDatosActualizados(context)
        WidgetUpdateScheduler.programar(context)
    }
}
