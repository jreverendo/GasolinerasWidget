package com.tuapp.gasolineraswidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Programa chequeos adaptativos con AlarmManager.
 *
 * El intervalo depende del modo: reposo, conduccion con bateria o
 * conduccion con el dispositivo cargando.
 */
object WidgetUpdateScheduler {

    const val ACTION_REFRESH = "com.tuapp.gasolineraswidget.ACTION_REFRESH"

    private const val TAG = "WidgetUpdateScheduler"
    private const val REQUEST_REFRESH = 4001
    private const val INTERVALO_REPOSO_MS = 5L * 60L * 1000L
    private const val INTERVALO_CONDUCCION_BATERIA_MS = 4L * 60L * 1000L
    private const val INTERVALO_CONDUCCION_CARGA_MS = 60L * 1000L

    fun programar(context: Context) {
        val appContext = context.applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(appContext, GasolinerasWidgetProvider::class.java)
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendiente = pendingIntent(appContext)

        if (ids.isEmpty()) {
            alarmManager?.cancel(pendiente)
            pendiente.cancel()
            return
        }

        val estado = GasolineraRepository(appContext).obtenerEstadoDispositivo()
        val intervalo = when (estado.modo) {
            ModoActualizacion.REPOSO -> INTERVALO_REPOSO_MS
            ModoActualizacion.CONDUCIENDO_BATERIA -> INTERVALO_CONDUCCION_BATERIA_MS
            ModoActualizacion.CONDUCIENDO_CARGANDO -> INTERVALO_CONDUCCION_CARGA_MS
        }

        Log.d(TAG, "Modo " + estado.modo + ", proximo chequeo en " + intervalo + " ms")

        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalo,
            pendiente
        )
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
