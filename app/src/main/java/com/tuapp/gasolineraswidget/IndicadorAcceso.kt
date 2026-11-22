package com.tuapp.gasolineraswidget

import android.content.Context

/**
 * Indicador de acceso compartido por el widget de escritorio y la capsula
 * flotante.
 *
 * Mantiene en un unico sitio los tres elementos que antes vivian en
 * GasolineraRemoteViewsFactory: el nivel de 0 a 4 barras que corresponde a
 * la puntuacion de acceso, el glifo de texto que usa la lista del widget y
 * el color de cercania, lejania o neutro.
 */
object IndicadorAcceso {

    /**
     * Drawable vectorial del indicador. El nivel es el mismo que el glifo
     * del widget de escritorio: 5 puntos pintan cuatro barras, 4 puntos
     * pintan tres y asi hasta la puntuacion 1, que no pinta ninguna.
     */
    fun drawable(puntuacionAcceso: Int): Int {
        return when (puntuacionAcceso) {
            5 -> R.drawable.ic_acceso_barras_5
            4 -> R.drawable.ic_acceso_barras_4
            3 -> R.drawable.ic_acceso_barras_3
            2 -> R.drawable.ic_acceso_barras_2
            else -> R.drawable.ic_acceso_barras_1
        }
    }

    /**
     * Glifo de texto del widget de escritorio. Se conservan los escapes
     * Unicode para que el fuente siga siendo ASCII.
     */
    fun texto(puntuacionAcceso: Int): String {
        return when (puntuacionAcceso) {
            5 -> " \u2582\u2583\u2585\u2586"
            4 -> " \u2582\u2583\u2585_"
            3 -> " \u2582\u2583__"
            2 -> " \u2582___"
            else -> " ____"
        }
    }

    /**
     * Color de las barras: azul para la estacion mas cercana, rojo para la
     * mas lejana y gris para el resto, igual que en el widget de escritorio.
     */
    fun color(
        context: Context,
        idGasolinera: String,
        idMasCercana: String,
        idMasLejana: String
    ): Int {
        return when (idGasolinera) {
            idMasCercana -> context.getColor(R.color.acceso_cercana)
            idMasLejana -> context.getColor(R.color.acceso_lejana)
            else -> context.getColor(R.color.acceso_neutra)
        }
    }
}
