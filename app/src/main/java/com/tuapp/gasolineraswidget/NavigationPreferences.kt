package com.tuapp.gasolineraswidget

import android.content.Context
import android.content.SharedPreferences

/**
 * Navegador GPS preferido por el usuario.
 *
 * Se guarda una decision logica y no un paquete concreto: Sygic se resuelve
 * despues al paquete que este instalado (aura o truck). El valor por defecto
 * es Automatico, que delega la deteccion en NavigationHelper.
 */
object NavigationPreferences {

    const val AUTO = "AUTO"
    const val PREGUNTAR = "PREGUNTAR"
    const val GOOGLE_MAPS = "GOOGLE_MAPS"
    const val WAZE = "WAZE"
    const val SYGIC = "SYGIC"

    private const val PREFS_NAME = "gasolineras_navigation_prefs"
    private const val KEY_NAVEGADOR = "nav_navegador"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun leer(context: Context): String {
        return normalizar(prefs(context).getString(KEY_NAVEGADOR, AUTO))
    }

    fun guardar(context: Context, valor: String) {
        prefs(context).edit().putString(KEY_NAVEGADOR, normalizar(valor)).apply()
    }

    private fun normalizar(valor: String?): String {
        return when (valor) {
            PREGUNTAR -> PREGUNTAR
            GOOGLE_MAPS -> GOOGLE_MAPS
            WAZE -> WAZE
            SYGIC -> SYGIC
            else -> AUTO
        }
    }
}
