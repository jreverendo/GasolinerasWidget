package com.tuapp.gasolineraswidget

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias de la capsula flotante: posicion, filtro de red, criterio
 * protagonista, carburante y deposito. Se mantienen entre reinicios para
 * conservar el ultimo estado de uso en ruta.
 */
object FloatingWidgetPreferences {

    const val CRITERIO_BARATA = "BARATA"
    const val CRITERIO_CERCANA = "CERCANA"

    const val LITROS_POR_DEFECTO = 50f
    const val LITROS_MINIMOS = 1f
    const val LITROS_MAXIMOS = 200f

    private const val PREFS_NAME = "gasolineras_floating_prefs"
    private const val KEY_X = "floating_x"
    private const val KEY_Y = "floating_y"
    private const val KEY_RED = "floating_red"
    private const val KEY_CRITERIO = "floating_criterio"
    private const val KEY_CARBURANTE = "floating_carburante"
    private const val KEY_LITROS = "floating_litros_deposito"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun leerX(context: Context, defecto: Int): Int {
        return prefs(context).getInt(KEY_X, defecto)
    }

    fun leerY(context: Context, defecto: Int): Int {
        return prefs(context).getInt(KEY_Y, defecto)
    }

    fun guardarPosicion(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    fun leerRed(context: Context): String {
        val valor = prefs(context).getString(KEY_RED, null)
        return when (valor) {
            WidgetNetworkPreferences.TRADICIONAL -> WidgetNetworkPreferences.TRADICIONAL
            WidgetNetworkPreferences.LOWCOST -> WidgetNetworkPreferences.LOWCOST
            else -> WidgetNetworkPreferences.TODAS
        }
    }

    fun guardarRed(context: Context, red: String) {
        val normalizada = when (red) {
            WidgetNetworkPreferences.TRADICIONAL -> WidgetNetworkPreferences.TRADICIONAL
            WidgetNetworkPreferences.LOWCOST -> WidgetNetworkPreferences.LOWCOST
            else -> WidgetNetworkPreferences.TODAS
        }
        prefs(context).edit().putString(KEY_RED, normalizada).apply()
    }

    fun leerCriterio(context: Context): String {
        return if (prefs(context).getString(KEY_CRITERIO, null) == CRITERIO_CERCANA) {
            CRITERIO_CERCANA
        } else {
            CRITERIO_BARATA
        }
    }

    fun guardarCriterio(context: Context, criterio: String) {
        val normalizado = if (criterio == CRITERIO_CERCANA) {
            CRITERIO_CERCANA
        } else {
            CRITERIO_BARATA
        }
        prefs(context).edit().putString(KEY_CRITERIO, normalizado).apply()
    }

    fun leerCarburante(context: Context): String {
        val valor = prefs(context).getString(KEY_CARBURANTE, null)
        if (valor != null && FuelOptions.opciones.any { it.claveJson == valor }) {
            return valor
        }
        return FuelOptions.GASOLINA_95
    }

    fun guardarCarburante(context: Context, clave: String) {
        val normalizada = if (FuelOptions.opciones.any { it.claveJson == clave }) {
            clave
        } else {
            FuelOptions.GASOLINA_95
        }
        prefs(context).edit().putString(KEY_CARBURANTE, normalizada).apply()
    }

    fun leerLitrosDeposito(context: Context): Float {
        val litros = prefs(context).getFloat(KEY_LITROS, LITROS_POR_DEFECTO)
        return if (litros.isFinite() &&
            litros >= LITROS_MINIMOS &&
            litros <= LITROS_MAXIMOS
        ) {
            litros
        } else {
            LITROS_POR_DEFECTO
        }
    }

    fun guardarLitrosDeposito(context: Context, litros: Float) {
        if (!litros.isFinite() ||
            litros < LITROS_MINIMOS ||
            litros > LITROS_MAXIMOS
        ) {
            return
        }
        prefs(context).edit().putFloat(KEY_LITROS, litros).apply()
    }
}
