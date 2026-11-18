package com.tuapp.gasolineraswidget

import android.content.Context

/**
 * Guarda el combustible elegido para cada instancia del widget.
 */
object WidgetFuelPreferences {

    private const val PREFS_NAME = "gasolineras_widget_config"
    private const val KEY_PREFIX = "fuel_pref_"

    fun clavePreferencia(appWidgetId: Int): String = KEY_PREFIX + appWidgetId

    fun leer(context: Context, appWidgetId: Int): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(
            clavePreferencia(appWidgetId),
            FuelOptions.porDefecto.claveJson
        ) ?: FuelOptions.porDefecto.claveJson
    }

    fun guardar(context: Context, appWidgetId: Int, claveCombustible: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(clavePreferencia(appWidgetId), claveCombustible)
            .commit()
    }

    fun eliminar(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(clavePreferencia(appWidgetId))
            .apply()
    }
}
