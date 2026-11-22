package com.tuapp.gasolineraswidget

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Donaciones voluntarias a traves de Ko-fi.
 *
 * El soporte es discreto: una tarjeta al final de las pantallas de
 * configuracion, un boton con icono de taza en la capsula flotante y el
 * mismo QR en ambos sitios. No anade ninguna libreria al APK: el QR es un
 * PNG de recursos y la donacion se abre siempre en el navegador externo.
 */
object KoFiSupport {

    /** Pagina de donaciones. */
    const val URL = "https://ko-fi.com/jrvjrv"

    /** Version corta y legible de la misma direccion. */
    const val URL_VISIBLE = "ko-fi.com/jrvjrv"

    /**
     * Abre la pagina de Ko-fi en el navegador externo. Devuelve false si el
     * dispositivo no tiene ninguna aplicacion capaz de abrir el enlace, de
     * modo que la pantalla pueda avisar sin cerrarse.
     */
    fun abrirPagina(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL))
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Conecta la tarjeta "Apoya el proyecto" que incluyen las pantallas de
     * configuracion. Si la pantalla no tiene la tarjeta, no hace nada.
     */
    fun conectarTarjeta(actividad: Activity) {
        actividad.findViewById<Button>(R.id.button_kofi_open)?.setOnClickListener {
            if (!abrirPagina(actividad)) {
                Toast.makeText(
                    actividad,
                    R.string.kofi_open_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        actividad.findViewById<Button>(R.id.button_kofi_qr)?.setOnClickListener {
            mostrarDialogoQr(actividad)
        }
    }

    /**
     * Dialogo con el QR, el texto breve y la direccion legible. El QR se
     * muestra tal cual desde los recursos, sin pedir permiso ni conexion.
     */
    fun mostrarDialogoQr(actividad: Activity) {
        if (actividad.isFinishing || actividad.isDestroyed) return

        val vista = actividad.layoutInflater.inflate(R.layout.dialog_qr_kofi, null)
        vista.findViewById<TextView>(R.id.dialog_qr_url)?.text = URL_VISIBLE

        val dialogo = AlertDialog.Builder(actividad)
            .setView(vista)
            .setCancelable(true)
            .create()

        vista.findViewById<Button>(R.id.dialog_qr_close)?.setOnClickListener {
            dialogo.dismiss()
        }
        dialogo.show()
    }
}
