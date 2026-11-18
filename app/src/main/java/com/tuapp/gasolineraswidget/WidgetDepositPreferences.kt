package com.tuapp.gasolineraswidget

import android.content.Context

/**
 * Guarda la capacidad en litros del deposito del vehiculo.
 *
 * Es una preferencia global (no por instancia) porque el coche es el mismo
 * para todos los widgets. Se usa para estimar el ahorro total al repostar.
 */
object WidgetDepositPreferences {

    const val KEY_LITROS_DEPOSITO = "pref_litros_deposito"
    const val LITROS_POR_DEFECTO = 50f
    const val LITROS_MINIMOS = 1f
    const val LITROS_MAXIMOS = 200f

    private const val PREFS_NAME = "gasolineras_widget_config"

    fun leer(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val litros = prefs.getFloat(KEY_LITROS_DEPOSITO, LITROS_POR_DEFECTO)
        return if (litros.isFinite() && litros >= LITROS_MINIMOS) {
            litros
        } else {
            LITROS_POR_DEFECTO
        }
    }

    fun guardar(context: Context, litros: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LITROS_DEPOSITO, litros)
            .commit()
    }
}
