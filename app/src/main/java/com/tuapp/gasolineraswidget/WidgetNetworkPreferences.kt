package com.tuapp.gasolineraswidget

import android.content.Context

/**
 * Guarda el tipo de red elegido para cada instancia del widget.
 */
object WidgetNetworkPreferences {

    const val TODAS = "TODAS"
    const val TRADICIONAL = "TRADICIONAL"
    const val LOWCOST = "LOWCOST"

    private const val PREFS_NAME = "gasolineras_widget_config"
    private const val KEY_PREFIX = "network_pref_"

    fun clavePreferencia(appWidgetId: Int): String = KEY_PREFIX + appWidgetId

    fun leer(context: Context, appWidgetId: Int): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val valor = prefs.getString(clavePreferencia(appWidgetId), TODAS) ?: TODAS
        return normalizarValor(valor)
    }

    fun guardar(context: Context, appWidgetId: Int, tipoRed: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(clavePreferencia(appWidgetId), normalizarValor(tipoRed))
            .commit()
    }

    fun eliminar(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(clavePreferencia(appWidgetId))
            .apply()
    }

    private fun normalizarValor(valor: String): String {
        return when (valor) {
            TRADICIONAL -> TRADICIONAL
            LOWCOST -> LOWCOST
            else -> TODAS
        }
    }
}
