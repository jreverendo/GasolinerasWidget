package com.tuapp.gasolineraswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proveedor del widget de gasolineras.
 *
 * Cada instancia usa una URI de servicio distinta para que su
 * RemoteViewsFactory pueda leer el appWidgetId y el combustible correctos.
 */
class GasolinerasWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        actualizarWidget(context, appWidgetManager, appWidgetIds)
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action != ACTION_TOGGLE_RED &&
            intent.action != ACTION_REFRESH_WIDGET &&
            intent.action != ACTION_FORCE_DOWNLOAD
        ) {
            return
        }

        val appWidgetId = intent.getIntExtra(
            EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        if (intent.action == ACTION_FORCE_DOWNLOAD) {
            descargarPreciosForzado(context, appWidgetId)
            return
        }

        if (intent.action == ACTION_REFRESH_WIDGET) {
            val repository = GasolineraRepository(context)
            repository.solicitarUbicacionFresca()
            repository.solicitarRefrescoManual()

            AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(
                appWidgetId,
                R.id.lv_gasolineras
            )
            return
        }

        val actual = WidgetNetworkPreferences.leer(context, appWidgetId)
        val siguiente = siguienteFiltroRed(actual)
        WidgetNetworkPreferences.guardar(context, appWidgetId, siguiente)

        val views = RemoteViews(context.packageName, R.layout.gasolinera_widget)
        views.setTextViewText(R.id.tv_toggle_red, textoFiltroRed(context, siguiente))
        views.setOnClickPendingIntent(
            R.id.tv_toggle_red,
            crearPendingIntentToggle(context, appWidgetId)
        )

        val appWidgetManager = AppWidgetManager.getInstance(context)
        appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
        appWidgetManager.notifyAppWidgetViewDataChanged(
            appWidgetId,
            R.id.lv_gasolineras
        )
    }

    /**
     * Fuerza una descarga del JSON ignorando la cache local. Mientras dura
     * la peticion HTTP se mantiene vivo el proceso con goAsync. Al terminar
     * se pinta la hora del sistema y se repinta la lista con los precios
     * nuevos; si falla, se conserva la cache anterior.
     */
    @Suppress("DEPRECATION")
    private fun descargarPreciosForzado(context: Context, appWidgetId: Int) {
        val resultado = goAsync()
        val appContext = context.applicationContext

        GasolineraRepository(appContext).actualizarEnSegundoPlano(
            forceDownload = true
        ) { actualizado ->
            try {
                if (actualizado) {
                    val ahora = System.currentTimeMillis()
                    val appWidgetManager = AppWidgetManager.getInstance(appContext)
                    val views = RemoteViews(
                        appContext.packageName,
                        R.layout.gasolinera_widget
                    )
                    views.setTextViewText(
                        R.id.tv_last_update,
                        textoUltimaActualizacion(appContext, ahora)
                    )
                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                    appWidgetManager.notifyAppWidgetViewDataChanged(
                        appWidgetId,
                        R.id.lv_gasolineras
                    )
                }
            } finally {
                resultado.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            WidgetFuelPreferences.eliminar(context, appWidgetId)
            WidgetNetworkPreferences.eliminar(context, appWidgetId)
        }
        WidgetUpdateScheduler.programar(context)
    }

    companion object {

        @Suppress("DEPRECATION")
        fun actualizarWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val plantillaNavegacion = crearPendingIntentNavegacion(context)
            val textoHora = textoUltimaActualizacion(
                context,
                GasolineraRepository(context).obtenerTimestampCache()
            )

            appWidgetIds.forEach { appWidgetId ->
                val views = RemoteViews(context.packageName, R.layout.gasolinera_widget)
                views.setEmptyView(R.id.lv_gasolineras, R.id.widget_empty)
                views.setRemoteAdapter(
                    R.id.lv_gasolineras,
                    intentServicio(context, appWidgetId)
                )
                views.setPendingIntentTemplate(
                    R.id.lv_gasolineras,
                    plantillaNavegacion
                )
                views.setTextViewText(R.id.tv_last_update, textoHora)
                views.setTextViewText(
                    R.id.tv_toggle_red,
                    textoFiltroRed(
                        context,
                        WidgetNetworkPreferences.leer(context, appWidgetId)
                    )
                )
                views.setOnClickPendingIntent(
                    R.id.tv_toggle_red,
                    crearPendingIntentToggle(context, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_refresh,
                    crearPendingIntentRefresh(context, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_force_download,
                    crearPendingIntentForceDownload(context, appWidgetId)
                )

                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(
                    appWidgetId,
                    R.id.lv_gasolineras
                )
            }

            WidgetUpdateScheduler.programar(context)
        }

        /**
         * Invalida los adaptadores de todos los widgets para que vuelvan a
         * cargar la cache persistente despues de una descarga correcta.
         */
        @Suppress("DEPRECATION")
        fun notificarDatosActualizados(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componente = ComponentName(context, GasolinerasWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componente)

            ids.forEach { appWidgetId ->
                appWidgetManager.notifyAppWidgetViewDataChanged(
                    appWidgetId,
                    R.id.lv_gasolineras
                )
            }
        }

        private fun crearPendingIntentNavegacion(context: Context): PendingIntent {
            // Plantilla explicita y mutable: el fill-in de cada fila solo
            // anade las coordenadas de la gasolinera pulsada y la deteccion
            // del navegador se hace al tocar, no al pintar el widget.
            val intent = Intent(context, NavigationDispatchActivity::class.java).apply {
                action = ACTION_NAVEGACION_WIDGET
            }

            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }

            return PendingIntent.getActivity(
                context,
                REQUEST_NAVEGACION,
                intent,
                flags
            )
        }

        /**
         * Formatea el instante de la cache como dia/mes y hora local, por
         * ejemplo "14/11 11:30". El patron es fijo para que el ancho del
         * texto no dependa del formato de 12 o 24 horas del dispositivo.
         */
        private fun textoUltimaActualizacion(context: Context, timestamp: Long): String {
            if (timestamp <= 0L) {
                return context.getString(R.string.widget_last_update_unknown)
            }
            val formato = SimpleDateFormat(PATRON_FECHA_HORA, Locale.getDefault())
            val fechaHora = formato.format(Date(timestamp))
            return context.getString(R.string.widget_last_update_format, fechaHora)
        }

        private fun textoFiltroRed(context: Context, filtro: String): String {
            return when (filtro) {
                WidgetNetworkPreferences.TRADICIONAL ->
                    context.getString(R.string.widget_toggle_red_tradicional)

                WidgetNetworkPreferences.LOWCOST ->
                    context.getString(R.string.widget_toggle_red_lowcost)

                else ->
                    context.getString(R.string.widget_toggle_red_todas)
            }
        }

        private fun siguienteFiltroRed(actual: String): String {
            return when (actual) {
                WidgetNetworkPreferences.TODAS -> WidgetNetworkPreferences.TRADICIONAL
                WidgetNetworkPreferences.TRADICIONAL -> WidgetNetworkPreferences.LOWCOST
                else -> WidgetNetworkPreferences.TODAS
            }
        }

        private fun crearPendingIntentToggle(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, GasolinerasWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_RED
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }

            return PendingIntent.getBroadcast(
                context,
                REQUEST_TOGGLE_BASE + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun crearPendingIntentRefresh(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, GasolinerasWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }

            return PendingIntent.getBroadcast(
                context,
                REQUEST_REFRESH_BASE + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun crearPendingIntentForceDownload(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, GasolinerasWidgetProvider::class.java).apply {
                action = ACTION_FORCE_DOWNLOAD
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }

            return PendingIntent.getBroadcast(
                context,
                REQUEST_FORCE_DOWNLOAD_BASE + appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun intentServicio(context: Context, appWidgetId: Int): Intent {
            return Intent(context, GasolineraWidgetService::class.java).apply {
                data = Uri.parse("gasolineraswidget://widget/$appWidgetId")
                putExtra(GasolineraWidgetService.EXTRA_WIDGET_ID, appWidgetId)
            }
        }

        const val ACTION_TOGGLE_RED = "com.tuapp.gasolineraswidget.ACTION_TOGGLE_RED"
        const val ACTION_REFRESH_WIDGET =
            "com.tuapp.gasolineraswidget.ACTION_REFRESH_WIDGET"
        const val ACTION_FORCE_DOWNLOAD =
            "com.tuapp.gasolineraswidget.ACTION_FORCE_DOWNLOAD"
        const val ACTION_NAVEGACION_WIDGET =
            "com.tuapp.gasolineraswidget.ACTION_NAVEGACION_WIDGET"

        private const val PATRON_FECHA_HORA = "dd/MM HH:mm"
        private const val EXTRA_WIDGET_ID = "widget_id"
        private const val REQUEST_NAVEGACION = 2001
        private const val REQUEST_TOGGLE_BASE = 10000
        private const val REQUEST_REFRESH_BASE = 20000
        private const val REQUEST_FORCE_DOWNLOAD_BASE = 30000
    }
}
