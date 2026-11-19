package com.tuapp.gasolineraswidget

import android.location.Location
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detector del modo ruta y rumbo de la marcha.
 *
 * Mantiene una ventana de las ultimas muestras de velocidad y activa el modo
 * ruta solo cuando el vehiculo circula de forma sostenida por encima de
 * 55 km/h. La desactivacion es mas perezosa (por debajo de 40 km/h) para no
 * parpadear al detenerse en un peaje o en una retencion. En ambos casos se
 * tolera que hasta un 20 por ciento de las muestras no cumplan el umbral.
 *
 * El rumbo se toma de la ubicacion cuando el GPS lo aporta, y si no se
 * calcula geometricamente entre dos lecturas consecutivas. Con el rumbo y
 * la posicion actual se puede saber si una gasolinera queda por delante o
 * ya ha sido rebasada.
 */
class RouteDirectionHelper {

    private class Muestra(val tiempoMs: Long, val velocidadMps: Double)

    private val muestras = ArrayDeque<Muestra>()

    private var inicioObservacionMs = 0L
    private var ultimoTiempoMs = 0L
    private var ultimaLatitud = 0.0
    private var ultimaLongitud = 0.0
    private var hayReferencia = false

    private var enRuta = false
    private var rumboGrados: Float? = null

    /** Modo ruta activo tras aplicar la histeresis temporal. */
    val modoRuta: Boolean
        get() = enRuta

    /** Rumbo fiable actual en grados (0 = norte), o null si no se conoce. */
    val rumbo: Float?
        get() = rumboGrados

    /**
     * Procesa una lectura GPS. Devuelve true solo cuando el modo ruta ha
     * cambiado, para que el servicio pueda refrescar la capsula una vez.
     */
    fun registrar(
        latitud: Double,
        longitud: Double,
        velocidadMps: Float?,
        bearingGrados: Float?,
        tiempoMs: Long
    ): Boolean {
        if (!latitud.isFinite() || !longitud.isFinite()) return false

        val tiempo = if (tiempoMs > 0L) tiempoMs else System.currentTimeMillis()
        if (tiempo <= ultimoTiempoMs) return false
        if (inicioObservacionMs == 0L) inicioObservacionMs = tiempo

        val velocidad = estimarVelocidad(latitud, longitud, velocidadMps, tiempo)
        actualizarRumbo(latitud, longitud, bearingGrados, velocidad)

        ultimaLatitud = latitud
        ultimaLongitud = longitud
        ultimoTiempoMs = tiempo
        hayReferencia = true

        muestras.addLast(Muestra(tiempo, velocidad))
        while (muestras.isNotEmpty() &&
            tiempo - muestras.first().tiempoMs > VENTANA_MS
        ) {
            muestras.removeFirst()
        }

        val anterior = enRuta
        if (!enRuta) {
            if (ventanaCumple(tiempo, VELOCIDAD_RUTA_MPS, porEncima = true)) {
                enRuta = true
            }
        } else {
            if (ventanaCumple(tiempo, VELOCIDAD_CIUDAD_MPS, porEncima = false)) {
                enRuta = false
            }
        }
        return anterior != enRuta
    }

    /**
     * Usa la velocidad del GPS y, cuando no llega o viene a cero, la estima
     * entre dos lecturas consecutivas con sus marcas de tiempo.
     */
    private fun estimarVelocidad(
        latitud: Double,
        longitud: Double,
        velocidadMps: Float?,
        tiempoMs: Long
    ): Double {
        val declarada = velocidadMps
            ?.takeIf { it.isFinite() }
            ?.toDouble()
            ?.coerceAtLeast(0.0)
            ?: 0.0
        if (declarada > VELOCIDAD_MINIMA_CALCULO_MPS) return declarada
        if (!hayReferencia) return declarada

        val deltaMs = tiempoMs - ultimoTiempoMs
        if (deltaMs <= 0L || deltaMs > VENTANA_MS) return declarada

        val distancia = distanciaMetros(
            ultimaLatitud,
            ultimaLongitud,
            latitud,
            longitud
        )
        val segundos = deltaMs / 1000.0
        return distancia / segundos
    }

    /**
     * Rumbo del GPS si es fiable a mas de 10 km/h; si no, rumbo geometrico
     * entre la posicion anterior y la actual. Al detenerse se conserva el
     * ultimo rumbo conocido para no perder la clasificacion.
     */
    private fun actualizarRumbo(
        latitud: Double,
        longitud: Double,
        bearingGrados: Float?,
        velocidadMps: Double
    ) {
        if (velocidadMps <= VELOCIDAD_MINIMA_RUMBO_MPS) return

        if (bearingGrados != null && bearingGrados.isFinite()) {
            rumboGrados = normalizarGrados(bearingGrados.toDouble())
            return
        }

        if (!hayReferencia) return
        val distancia = distanciaMetros(
            ultimaLatitud,
            ultimaLongitud,
            latitud,
            longitud
        )
        if (distancia < DISTANCIA_MINIMA_RUMBO_METROS) return

        rumboGrados = rumboGeometrico(
            ultimaLatitud,
            ultimaLongitud,
            latitud,
            longitud
        )
    }

    /**
     * Comprueba la ventana de sostenimiento: al menos un minuto observando y
     * un 80 por ciento de muestras en el regimen pedido.
     */
    private fun ventanaCumple(
        ahoraMs: Long,
        umbralMps: Double,
        porEncima: Boolean
    ): Boolean {
        if (muestras.size < MIN_MUESTRAS) return false
        if (ahoraMs - inicioObservacionMs < VENTANA_MS) return false

        var favorables = 0
        for (muestra in muestras) {
            val cumple = if (porEncima) {
                muestra.velocidadMps > umbralMps
            } else {
                muestra.velocidadMps < umbralMps
            }
            if (cumple) favorables++
        }

        val necesarias = ceil(muestras.size * FRACCION_MUESTRAS).toInt()
        return favorables >= necesarias
    }

    private fun rumboGeometrico(
        latitudOrigen: Double,
        longitudOrigen: Double,
        latitudDestino: Double,
        longitudDestino: Double
    ): Float {
        val phiOrigen = Math.toRadians(latitudOrigen)
        val phiDestino = Math.toRadians(latitudDestino)
        val deltaLongitud = Math.toRadians(longitudDestino - longitudOrigen)

        val y = sin(deltaLongitud) * cos(phiDestino)
        val x = cos(phiOrigen) * sin(phiDestino) -
            sin(phiOrigen) * cos(phiDestino) * cos(deltaLongitud)
        val grados = Math.toDegrees(atan2(y, x))
        return normalizarGrados(grados)
    }

    private fun distanciaMetros(
        latitudOrigen: Double,
        longitudOrigen: Double,
        latitudDestino: Double,
        longitudDestino: Double
    ): Double {
        val deltaLatitud = Math.toRadians(latitudDestino - latitudOrigen)
        val deltaLongitud = Math.toRadians(longitudDestino - longitudOrigen)
        val a = sin(deltaLatitud / 2).let { it * it } +
            cos(Math.toRadians(latitudOrigen)) *
            cos(Math.toRadians(latitudDestino)) *
            sin(deltaLongitud / 2).let { it * it }

        return 2.0 * RADIO_TIERRA_KM * asin(
            sqrt(a.coerceIn(0.0, 1.0))
        ) * 1000.0
    }

    companion object {

        /** 55 km/h: umbral para entrar en modo ruta. */
        const val VELOCIDAD_RUTA_MPS = 15.3

        /** 40 km/h: umbral para salir del modo ruta. */
        const val VELOCIDAD_CIUDAD_MPS = 11.1

        /** Diferencia angular maxima para considerar una estacion delante. */
        const val ANGULO_DELANTE_GRADOS = 105.0

        private const val VENTANA_MS = 60000L
        private const val FRACCION_MUESTRAS = 0.8
        private const val MIN_MUESTRAS = 4
        private const val VELOCIDAD_MINIMA_CALCULO_MPS = 0.5
        private const val VELOCIDAD_MINIMA_RUMBO_MPS = 2.78
        private const val DISTANCIA_MINIMA_RUMBO_METROS = 5.0
        private const val RADIO_TIERRA_KM = 6371.0088

        /**
         * Diferencia angular minima (0 a 180 grados) entre el rumbo de la
         * marcha y el azimut hacia la estacion.
         */
        fun desviacionGrados(
            rumboGrados: Float,
            latitudOrigen: Double,
            longitudOrigen: Double,
            latitudDestino: Double,
            longitudDestino: Double
        ): Double {
            val origen = Location(PROVEEDOR_INTERNO).apply {
                latitude = latitudOrigen
                longitude = longitudOrigen
            }
            val destino = Location(PROVEEDOR_INTERNO).apply {
                latitude = latitudDestino
                longitude = longitudDestino
            }
            val azimut = origen.bearingTo(destino).toDouble()
            return diferenciaAngular(rumboGrados.toDouble(), azimut)
        }

        /** Indica si la desviacion angular deja la estacion detras. */
        fun esRebasada(desviacion: Double): Boolean {
            return desviacion > ANGULO_DELANTE_GRADOS
        }

        private fun diferenciaAngular(rumbo: Double, azimut: Double): Double {
            val bruta = abs(azimut - rumbo) % 360.0
            return if (bruta > 180.0) 360.0 - bruta else bruta
        }

        private fun normalizarGrados(valor: Double): Float {
            val normalizado = valor % 360.0
            val positivo = if (normalizado < 0.0) normalizado + 360.0 else normalizado
            return positivo.toFloat()
        }

        private const val PROVEEDOR_INTERNO = "route-direction-helper"
    }
}
