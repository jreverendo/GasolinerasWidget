package com.tuapp.gasolineraswidget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.util.Locale
import kotlin.math.abs

/**
 * Factoria de filas para el ListView del widget.
 *
 * onDataSetChanged() separa dos decisiones:
 * - Descargar el JSON solo si ha caducado su TTL segun bateria y conduccion.
 * - Recalcular distancias desde el JSON cacheado solo si toca por movimiento
 *   o por refresco manual. El hilo principal no realiza estas operaciones.
 */
class GasolineraRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val repository = GasolineraRepository(context.applicationContext)

    @Volatile
    private var estado: EstadoWidget

    init {
        val clave = WidgetFuelPreferences.leer(context, appWidgetId)
        estado = EstadoWidget(
            claveCombustible = clave,
            etiquetaCombustible = FuelOptions.porClave(clave).etiquetaCorta,
            gasolineras = emptyList(),
            precioMinimoVisible = 0.0,
            mediaProvincial = 0.0,
            litrosDeposito = WidgetDepositPreferences.leer(context),
            idMasCercana = "",
            idMasLejana = ""
        )
    }

    override fun onCreate() {
        // La cache se carga en onDataSetChanged, que corre en un hilo de binder.
    }

    override fun onDataSetChanged() {
        repository.procesarSolicitudUbicacionFresca()

        val clave = WidgetFuelPreferences.leer(context, appWidgetId)
        val filtroRed = WidgetNetworkPreferences.leer(context, appWidgetId)
        val litrosDeposito = WidgetDepositPreferences.leer(context)
        val estadoDispositivo = repository.obtenerEstadoDispositivo()
        val recalcular = repository.debeRecalcular(estadoDispositivo)
        val municipioActual = repository.obtenerMunicipioActual(
            estadoDispositivo.ubicacion
        )

        val resultado = repository.cargarListaParaMostrar(
            claveCombustible = clave,
            estado = estadoDispositivo,
            recalcular = recalcular,
            municipioActual = municipioActual,
            filtroRed = filtroRed
        )

        if (resultado.hayCache) {
            estado = EstadoWidget(
                claveCombustible = clave,
                etiquetaCombustible = FuelOptions.porClave(clave).etiquetaCorta,
                gasolineras = resultado.gasolineras,
                precioMinimoVisible = resultado.gasolineras
                    .minOfOrNull { it.precio } ?: 0.0,
                mediaProvincial = resultado.mediaProvincial,
                litrosDeposito = litrosDeposito,
                idMasCercana = resultado.idMasCercana,
                idMasLejana = resultado.idMasLejana
            )
        }

        val descargar = repository.debeDescargar(estadoDispositivo)
        Log.d(
            TAG,
            "Widget " + appWidgetId + " fuel " + clave +
                " red " + filtroRed +
                " modo " + estadoDispositivo.modo +
                " recalcular " + recalcular +
                " descargar " + descargar +
                " abiertas " + resultado.gasolineras.size
        )

        if (descargar) {
            repository.actualizarEnSegundoPlano { exitoso ->
                if (exitoso) {
                    GasolinerasWidgetProvider.notificarDatosActualizados(context)
                }
            }
        }

        WidgetUpdateScheduler.programar(context)
    }

    override fun getCount(): Int = estado.gasolineras.size

    override fun getViewAt(position: Int): RemoteViews {
        val actual = estado
        val views = RemoteViews(context.packageName, R.layout.item_gasolinera)

        if (position !in actual.gasolineras.indices) {
            return views
        }

        val gasolinera = actual.gasolineras[position]

        // La fila completa se tinta si su precio es el minimo de la lista
        // visible. Se usa una tolerancia para cubrir empates y errores de
        // coma flotante, y se resetea el fondo en todas las filas para que
        // el reciclaje del ListView no arrastre el color de otra posicion.
        val esPrecioMinimoVisible = abs(
            gasolinera.precio - actual.precioMinimoVisible
        ) <= TOLERANCIA_PRECIO_MINIMO
        val colorFondoFila = if (esPrecioMinimoVisible) {
            context.getColor(R.color.fila_mas_barata_bg)
        } else {
            context.getColor(R.color.fila_normal_bg)
        }
        views.setInt(R.id.item_root, "setBackgroundColor", colorFondoFila)

        views.setTextViewText(R.id.item_nombre, gasolinera.nombre)
        views.setTextViewText(
            R.id.item_distancia,
            context.getString(R.string.formato_distancia, gasolinera.distancia)
        )
        views.setTextViewText(R.id.tv_barras, textoAcceso(gasolinera.puntuacionAcceso))
        val colorBarras = when {
            gasolinera.id == actual.idMasCercana ->
                context.getColor(R.color.acceso_cercana)

            gasolinera.id == actual.idMasLejana ->
                context.getColor(R.color.acceso_lejana)

            else ->
                context.getColor(R.color.acceso_neutra)
        }
        views.setTextColor(R.id.tv_barras, colorBarras)
        views.setTextViewText(R.id.item_etiqueta, gasolinera.etiquetaRuta)
        views.setTextColor(
            R.id.item_etiqueta,
            if (gasolinera.aDesmano) {
                context.getColor(R.color.widget_route_warning)
            } else {
                context.getColor(R.color.widget_text_muted)
            }
        )
        views.setTextViewText(R.id.item_direccion, gasolinera.direccion)
        views.setTextViewText(R.id.item_combustible, actual.etiquetaCombustible)
        views.setTextViewText(
            R.id.item_precio,
            String.format(Locale.US, "%.3f", gasolinera.precio)
        )

        // Referencia unica para el ahorro del deposito y para el color de la
        // pastilla: media provincial de todas las estaciones validas del
        // carburante, no solo de las filas mostradas.
        val mediaProvincial = actual.mediaProvincial
        val deltaPorLitro = PrecioTramo.deltaPorLitro(
            gasolinera.precio,
            mediaProvincial
        )

        // deltaPorLitro > 0 cuando la estacion es mas cara que la media.
        // La diferencia de coste es negativa si ahorra y positiva si supone
        // un sobrecoste al llenar el deposito.
        val diferenciaCoste = PrecioTramo.diferenciaCoste(
            gasolinera.precio,
            mediaProvincial,
            actual.litrosDeposito
        )
        views.setTextViewText(
            R.id.tv_ahorro_deposito,
            PrecioTramo.textoDiferenciaCoste(context, diferenciaCoste)
        )
        views.setTextColor(
            R.id.tv_ahorro_deposito,
            PrecioTramo.colorDiferenciaCoste(context, diferenciaCoste)
        )

        val tramo = PrecioTramo.tramo(deltaPorLitro)
        views.setInt(R.id.item_precio, "setBackgroundResource", tramo.fondo)
        views.setTextColor(
            R.id.item_precio,
            context.getColor(tramo.colorTexto)
        )

        // La plantilla del widget apunta a NavigationDispatchActivity: las
        // coordenadas viajan como extras y la deteccion del navegador activo
        // se resuelve en el momento del toque, no al pintar la fila.
        val fillInIntent = Intent().apply {
            putExtra(NavigationHelper.EXTRA_LATITUD, gasolinera.latitud)
            putExtra(NavigationHelper.EXTRA_LONGITUD, gasolinera.longitud)
            putExtra(NavigationHelper.EXTRA_NOMBRE, gasolinera.nombre)
        }
        views.setOnClickFillInIntent(R.id.item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        estado = estado.copy(gasolineras = emptyList())
    }

    private fun textoAcceso(puntuacion: Int): String {
        return when (puntuacion) {
            5 -> " \u2582\u2583\u2585\u2586"
            4 -> " \u2582\u2583\u2585_"
            3 -> " \u2582\u2583__"
            2 -> " \u2582___"
            else -> " ____"
        }
    }

    private data class EstadoWidget(
        val claveCombustible: String,
        val etiquetaCombustible: String,
        val gasolineras: List<Gasolinera>,
        val precioMinimoVisible: Double,
        val mediaProvincial: Double,
        val litrosDeposito: Float,
        val idMasCercana: String,
        val idMasLejana: String
    )

    private companion object {
        const val TAG = "GasolineraWidget"
        const val TOLERANCIA_PRECIO_MINIMO = 1e-9
    }
}
