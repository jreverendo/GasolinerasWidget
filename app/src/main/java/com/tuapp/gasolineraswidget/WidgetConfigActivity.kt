package com.tuapp.gasolineraswidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import java.util.Locale

/**
 * Pantalla de configuracion que se abre al colocar el widget.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)
        aplicarInsetsSistema()
        solicitarPermisoUbicacion()

        val grupo = findViewById<RadioGroup>(R.id.fuel_group)
        seleccionarOpcionGuardada(grupo)
        val grupoRed = findViewById<RadioGroup>(R.id.network_group)
        seleccionarRedGuardada(grupoRed)
        val campoLitros = findViewById<EditText>(R.id.edit_litros_deposito)
        mostrarLitros(campoLitros, WidgetDepositPreferences.leer(this))

        // Tarjeta discreta de donaciones voluntarias al final de la pantalla.
        KoFiSupport.conectarTarjeta(this)

        findViewById<Button>(R.id.button_save).setOnClickListener {
            val clave = claveSeleccionada(grupo)
            if (clave == null) {
                Toast.makeText(
                    this,
                    R.string.config_select_message,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val litros = litrosIntroducidos(campoLitros)
            if (litros == null) {
                Toast.makeText(
                    this,
                    R.string.config_deposit_error,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            guardarYActualizar(clave, redSeleccionada(grupoRed), litros)
        }
    }

    @Suppress("DEPRECATION")
    private fun aplicarInsetsSistema() {
        val root = findViewById<View>(R.id.config_root)
        val base = (24 * resources.displayMetrics.density).toInt()

        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val barras = insets.getInsets(WindowInsets.Type.systemBars())
                top = barras.top
                bottom = barras.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }

            view.setPadding(base, base + top, base, base + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun solicitarPermisoUbicacion() {
        val fino = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val grueso = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fino != PackageManager.PERMISSION_GRANTED &&
            grueso != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
        }
    }

    private fun seleccionarOpcionGuardada(grupo: RadioGroup) {
        val guardada = WidgetFuelPreferences.leer(this, appWidgetId)
        val radioId = when (guardada) {
            FuelOptions.GASOLINA_95 -> R.id.radio_gasolina_95
            FuelOptions.GASOLINA_98 -> R.id.radio_gasolina_98
            FuelOptions.GASOLEO_PREMIUM -> R.id.radio_gasoleo_premium
            else -> R.id.radio_gasoleo_a
        }
        grupo.check(radioId)
    }

    private fun claveSeleccionada(grupo: RadioGroup): String? {
        return when (grupo.checkedRadioButtonId) {
            R.id.radio_gasolina_95 -> FuelOptions.GASOLINA_95
            R.id.radio_gasoleo_a -> FuelOptions.GASOLEO_A
            R.id.radio_gasolina_98 -> FuelOptions.GASOLINA_98
            R.id.radio_gasoleo_premium -> FuelOptions.GASOLEO_PREMIUM
            else -> null
        }
    }

    private fun seleccionarRedGuardada(grupo: RadioGroup) {
        val guardada = WidgetNetworkPreferences.leer(this, appWidgetId)
        val radioId = when (guardada) {
            WidgetNetworkPreferences.TRADICIONAL -> R.id.radio_network_traditional
            WidgetNetworkPreferences.LOWCOST -> R.id.radio_network_lowcost
            else -> R.id.radio_network_all
        }
        grupo.check(radioId)
    }

    private fun redSeleccionada(grupo: RadioGroup): String {
        return when (grupo.checkedRadioButtonId) {
            R.id.radio_network_traditional -> WidgetNetworkPreferences.TRADICIONAL
            R.id.radio_network_lowcost -> WidgetNetworkPreferences.LOWCOST
            else -> WidgetNetworkPreferences.TODAS
        }
    }

    private fun mostrarLitros(campo: EditText, litros: Float) {
        val texto = if (litros == litros.toInt().toFloat()) {
            litros.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", litros)
        }
        campo.setText(texto)
        campo.setSelection(texto.length)
    }

    private fun litrosIntroducidos(campo: EditText): Float? {
        val texto = campo.text.toString().trim().replace(',', '.')
        val litros = texto.toFloatOrNull() ?: return null
        if (!litros.isFinite()) return null
        if (litros < WidgetDepositPreferences.LITROS_MINIMOS ||
            litros > WidgetDepositPreferences.LITROS_MAXIMOS
        ) {
            return null
        }
        return litros
    }

    private fun guardarYActualizar(
        claveCombustible: String,
        tipoRed: String,
        litrosDeposito: Float
    ) {
        WidgetFuelPreferences.guardar(this, appWidgetId, claveCombustible)
        WidgetNetworkPreferences.guardar(this, appWidgetId, tipoRed)
        WidgetDepositPreferences.guardar(this, litrosDeposito)

        val repository = GasolineraRepository(this)
        repository.capturarUbicacionConocida()
        repository.solicitarRefrescoManual()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(this, GasolinerasWidgetProvider::class.java)
        )
        GasolinerasWidgetProvider.actualizarWidget(
            this,
            appWidgetManager,
            if (ids.isEmpty()) intArrayOf(appWidgetId) else ids
        )

        val result = Intent().putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId
        )
        setResult(RESULT_OK, result)
        finish()
    }

    private companion object {
        const val REQUEST_LOCATION = 1001
    }
}
