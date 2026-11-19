package com.tuapp.gasolineraswidget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Actividad invisible que usa el widget de escritorio para abrir la
 * navegacion.
 *
 * RemoteViews solo puede lanzar intents, no ejecutar codigo, y la deteccion
 * del navegador activo tiene que ocurrir en el momento del toque. Esta
 * actividad recibe las coordenadas de la fila pulsada, delega en
 * NavigationHelper y se cierra antes de mostrarse.
 */
class NavigationDispatchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val latitud = intent.getDoubleExtra(
                NavigationHelper.EXTRA_LATITUD,
                Double.NaN
            )
            val longitud = intent.getDoubleExtra(
                NavigationHelper.EXTRA_LONGITUD,
                Double.NaN
            )
            val nombre = intent.getStringExtra(NavigationHelper.EXTRA_NOMBRE).orEmpty()

            val abierto = NavigationHelper.abrirNavegacionGasolinera(
                this,
                latitud,
                longitud,
                nombre
            )
            if (!abierto) {
                Toast.makeText(
                    this,
                    R.string.floating_navigation_missing,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Theme.NoDisplay exige cerrar antes de que la actividad se muestre.
        finish()
    }
}
