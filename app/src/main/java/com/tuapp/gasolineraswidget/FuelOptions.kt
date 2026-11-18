package com.tuapp.gasolineraswidget

/**
 * Opciones de combustible principales de la API del Ministerio.
 * Las claves deben coincidir exactamente con las del JSON.
 */
object FuelOptions {

    const val GASOLINA_95 = "Precio Gasolina 95 E5"
    const val GASOLEO_A = "Precio Gasoleo A"
    const val GASOLINA_98 = "Precio Gasolina 98 E5"
    const val GASOLEO_PREMIUM = "Precio Gasoleo Premium"
    const val GLP = "Precio Gases licuados del petr\u00f3leo"

    data class Opcion(
        val claveJson: String,
        val etiquetaCorta: String,
        val codigo: String
    )

    val opciones = listOf(
        Opcion(GASOLINA_95, "G95", "G95"),
        Opcion(GASOLEO_A, "Diesel", "GOA"),
        Opcion(GASOLINA_98, "G98", "G98"),
        Opcion(GASOLEO_PREMIUM, "D+ Premium", "GOP"),
        Opcion(GLP, "GLP", "GLP")
    )

    val porDefecto: Opcion = opciones.first { it.claveJson == GASOLEO_A }

    fun porClave(clave: String): Opcion {
        return opciones.firstOrNull { it.claveJson == clave } ?: porDefecto
    }

    /**
     * Codigo corto mostrado junto al nombre de la estacion en la capsula.
     */
    fun codigoCorto(clave: String): String {
        return porClave(clave).codigo
    }
}
