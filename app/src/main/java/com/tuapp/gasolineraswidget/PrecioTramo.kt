package com.tuapp.gasolineraswidget

import android.content.Context
import java.util.Locale
import kotlin.math.abs

/**
 * Escala unica de 6 tramos de color y calculo del ahorro del deposito.
 *
 * La usan tanto el widget de escritorio como la capsula flotante para que
 * el color de la pastilla y el ahorro en euros sean identicos en ambos.
 * La referencia siempre es la media provincial del carburante.
 */
object PrecioTramo {

    /**
     * Fondo y color de texto de cada tramo de la pastilla de precio.
     * Solo el amarillo usa texto oscuro para mantener el contraste.
     */
    enum class Tramo(
        val fondo: Int,
        val colorTexto: Int
    ) {
        VERDE_BRILLANTE(
            R.drawable.bg_precio_verde_brillante,
            R.color.precio_texto_claro
        ),
        VERDE(R.drawable.bg_precio_verde, R.color.precio_texto_claro),
        NEGRO(R.drawable.bg_precio_negro, R.color.precio_texto_claro),
        AMARILLO(R.drawable.bg_precio_amarillo, R.color.precio_texto_oscuro),
        NARANJA(R.drawable.bg_precio_naranja, R.color.precio_texto_claro),
        ROJO(R.drawable.bg_precio_rojo, R.color.precio_texto_claro)
    }

    /**
     * Diferencia por litro entre la estacion y la media provincial.
     * Es negativa cuando la estacion es mas barata que la media.
     */
    fun deltaPorLitro(precio: Double, mediaProvincial: Double): Double {
        return if (mediaProvincial > 0.0) {
            precio - mediaProvincial
        } else {
            0.0
        }
    }

    /**
     * Coste del deposito respecto a la media provincial.
     * Es negativo si supone un ahorro y positivo si supone un sobrecoste.
     */
    fun diferenciaCoste(
        precio: Double,
        mediaProvincial: Double,
        litrosDeposito: Float
    ): Double {
        if (mediaProvincial <= 0.0 || litrosDeposito <= 0f) return 0.0
        return deltaPorLitro(precio, mediaProvincial) * litrosDeposito.toDouble()
    }

    /**
     * Devuelve el tramo de color de la pastilla segun el delta exacto
     * (precio de la estacion menos media provincial) en euros por litro.
     */
    fun tramo(deltaPorLitro: Double): Tramo {
        // Los umbrales se comparan con un margen minimo para absorber el
        // error de coma flotante justo en los limites (0.03, 0.06, 0.10).
        return when {
            deltaPorLitro <= -UMBRAL_MUY_BARATA + EPSILON_DELTA ->
                Tramo.VERDE_BRILLANTE

            deltaPorLitro <= -UMBRAL_BARATA + EPSILON_DELTA ->
                Tramo.VERDE

            deltaPorLitro < UMBRAL_BARATA - EPSILON_DELTA ->
                Tramo.NEGRO

            deltaPorLitro < UMBRAL_AMARILLO - EPSILON_DELTA ->
                Tramo.AMARILLO

            deltaPorLitro <= UMBRAL_NARANJA + EPSILON_DELTA ->
                Tramo.NARANJA

            else ->
                Tramo.ROJO
        }
    }

    fun textoDiferenciaCoste(context: Context, diferenciaCoste: Double): String {
        if (abs(diferenciaCoste) < UMBRAL_IMPACTO) {
            return context.getString(R.string.deposito_neutro)
        }
        return String.format(Locale.US, "%+.2f eur", diferenciaCoste)
    }

    fun colorDiferenciaCoste(context: Context, diferenciaCoste: Double): Int {
        return when {
            diferenciaCoste < -UMBRAL_IMPACTO ->
                context.getColor(R.color.deposito_ahorro)

            diferenciaCoste > UMBRAL_IMPACTO ->
                context.getColor(R.color.deposito_sobrecoste)

            else ->
                context.getColor(R.color.deposito_neutro)
        }
    }

    private const val UMBRAL_MUY_BARATA = 0.10
    private const val UMBRAL_BARATA = 0.03
    private const val UMBRAL_AMARILLO = 0.06
    private const val UMBRAL_NARANJA = 0.10
    private const val EPSILON_DELTA = 1e-9
    private const val UMBRAL_IMPACTO = 0.005
}
