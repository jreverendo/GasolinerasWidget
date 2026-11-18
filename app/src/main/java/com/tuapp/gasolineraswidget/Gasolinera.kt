package com.tuapp.gasolineraswidget

/**
 * Datos de una gasolinera mostrada en el widget.
 */
data class Gasolinera(
    val id: String,
    val nombre: String,
    val precio: Double,
    val distancia: Double,
    val direccion: String,
    val municipio: String,
    val provincia: String,
    val horario: String,
    val latitud: Double,
    val longitud: Double,
    val etiquetaRuta: String,
    val penalizacionKm: Double,
    val aDesmano: Boolean,
    val puntuacionAcceso: Int
)

/**
 * Resultado de cargar la lista visible.
 *
 * [precioMedio] es la media de la seleccion filtrada usada para el score.
 * [mediaProvincial] es la referencia de precio de la provincia actual.
 */
data class ResultadoLista(
    val gasolineras: List<Gasolinera>,
    val hayCache: Boolean,
    val precioMedio: Double = 0.0,
    val mediaProvincial: Double = 0.0,
    val idMasCercana: String = "",
    val idMasLejana: String = ""
)
