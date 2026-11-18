package com.tuapp.gasolineraswidget

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pantalla principal de la app.
 *
 * Desde aqui se concede el permiso de superposicion y se arranca el modo
 * flotante coche. El widget de escritorio sigue funcionando igual y comparte
 * repositorio y escala de colores con la capsula flotante.
 */
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var floatingStatus: TextView
    private lateinit var stopButton: Button
    private lateinit var fuelGroup: RadioGroup
    private lateinit var litrosField: EditText
    private lateinit var configStatus: TextView
    private lateinit var navigationGroup: RadioGroup
    private lateinit var navigationStatus: TextView
    private lateinit var navigationUsageButton: Button

    private var esperandoOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        aplicarInsetsSistema()

        overlayStatus = findViewById(R.id.main_overlay_status)
        locationStatus = findViewById(R.id.main_location_status)
        floatingStatus = findViewById(R.id.main_floating_status)
        stopButton = findViewById(R.id.button_stop_floating)
        fuelGroup = findViewById(R.id.floating_fuel_group)
        litrosField = findViewById(R.id.edit_floating_litros)
        configStatus = findViewById(R.id.main_floating_config_status)
        navigationGroup = findViewById(R.id.main_nav_group)
        navigationStatus = findViewById(R.id.main_nav_status)
        navigationUsageButton = findViewById(R.id.main_nav_usage_button)

        seleccionarCarburante(FloatingWidgetPreferences.leerCarburante(this))
        mostrarLitros(FloatingWidgetPreferences.leerLitrosDeposito(this))
        actualizarEstadoConfiguracion()
        prepararSelectorNavegador()

        findViewById<Button>(R.id.button_floating).setOnClickListener {
            if (guardarConfiguracionFlotante(silencioso = true)) {
                activarModoFlotante()
            }
        }

        findViewById<Button>(R.id.button_save_floating_config).setOnClickListener {
            guardarConfiguracionFlotante(silencioso = false)
        }

        stopButton.setOnClickListener {
            FloatingWidgetService.detener(this)
            actualizarEstadoConRetraso()
        }

        solicitarPermisos()
    }

    override fun onResume() {
        super.onResume()
        if (esperandoOverlay && Settings.canDrawOverlays(this)) {
            esperandoOverlay = false
            iniciarServicio()
        }
        actualizarEstado()
        actualizarEstadoNavegacion()
        actualizarEstadoConRetraso()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OVERLAY) return

        esperandoOverlay = false
        if (Settings.canDrawOverlays(this)) {
            iniciarServicio()
        } else {
            Toast.makeText(
                this,
                R.string.floating_permission_still_missing,
                Toast.LENGTH_LONG
            ).show()
        }
        actualizarEstado()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        actualizarEstado()
    }

    @Suppress("DEPRECATION")
    private fun aplicarInsetsSistema() {
        val root = findViewById<View>(R.id.main_root)
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

    private fun solicitarPermisos() {
        val permisos = mutableListOf<String>()

        val fino = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val grueso = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fino != PackageManager.PERMISSION_GRANTED &&
            grueso != PackageManager.PERMISSION_GRANTED
        ) {
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permisos.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permisos.isNotEmpty()) {
            requestPermissions(permisos.toTypedArray(), REQUEST_PERMISOS)
        }
    }

    @Suppress("DEPRECATION")
    private fun activarModoFlotante() {
        if (!Settings.canDrawOverlays(this)) {
            esperandoOverlay = true
            Toast.makeText(
                this,
                R.string.floating_permission_message,
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )

            try {
                startActivityForResult(intent, REQUEST_OVERLAY)
            } catch (e: ActivityNotFoundException) {
                esperandoOverlay = false
                Toast.makeText(
                    this,
                    R.string.floating_permission_denied_message,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        iniciarServicio()
    }

    private fun iniciarServicio() {
        val repository = GasolineraRepository(applicationContext)
        repository.capturarUbicacionConocida()
        repository.solicitarRefrescoManual()

        try {
            FloatingWidgetService.iniciar(this)
            Toast.makeText(
                this,
                R.string.floating_activated_message,
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                R.string.floating_permission_denied_message,
                Toast.LENGTH_LONG
            ).show()
        }

        actualizarEstadoConRetraso()
    }

    private fun seleccionarCarburante(clave: String) {
        val id = when (clave) {
            FuelOptions.GASOLINA_98 -> R.id.floating_fuel_g98
            FuelOptions.GASOLEO_A -> R.id.floating_fuel_goa
            FuelOptions.GASOLEO_PREMIUM -> R.id.floating_fuel_gop
            FuelOptions.GLP -> R.id.floating_fuel_glp
            else -> R.id.floating_fuel_g95
        }
        fuelGroup.check(id)
    }

    private fun carburanteSeleccionado(): String {
        return when (fuelGroup.checkedRadioButtonId) {
            R.id.floating_fuel_g98 -> FuelOptions.GASOLINA_98
            R.id.floating_fuel_goa -> FuelOptions.GASOLEO_A
            R.id.floating_fuel_gop -> FuelOptions.GASOLEO_PREMIUM
            R.id.floating_fuel_glp -> FuelOptions.GLP
            else -> FuelOptions.GASOLINA_95
        }
    }

    private fun mostrarLitros(litros: Float) {
        val texto = formatearLitros(litros)
        litrosField.setText(texto)
        litrosField.setSelection(texto.length)
    }

    private fun formatearLitros(litros: Float): String {
        return if (litros == litros.toInt().toFloat()) {
            litros.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", litros)
        }
    }

    private fun litrosIntroducidos(): Float? {
        val texto = litrosField.text.toString().trim().replace(',', '.')
        val litros = texto.toFloatOrNull() ?: return null
        if (!litros.isFinite()) return null
        if (litros < FloatingWidgetPreferences.LITROS_MINIMOS ||
            litros > FloatingWidgetPreferences.LITROS_MAXIMOS
        ) {
            return null
        }
        return litros
    }

    private fun guardarConfiguracionFlotante(silencioso: Boolean): Boolean {
        val clave = carburanteSeleccionado()
        val litros = litrosIntroducidos()
        if (litros == null) {
            Toast.makeText(
                this,
                R.string.main_floating_config_error,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        FloatingWidgetPreferences.guardarCarburante(this, clave)
        FloatingWidgetPreferences.guardarLitrosDeposito(this, litros)
        actualizarEstadoConfiguracion()
        FloatingWidgetService.solicitarRecarga(this)

        if (!silencioso) {
            Toast.makeText(
                this,
                R.string.main_floating_config_saved,
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    private fun actualizarEstadoConfiguracion() {
        val clave = carburanteSeleccionado()
        val litros = litrosIntroducidos()
            ?: FloatingWidgetPreferences.leerLitrosDeposito(this)
        configStatus.text = getString(
            R.string.main_floating_config_status_format,
            FuelOptions.codigoCorto(clave),
            formatearLitros(litros)
        )
    }

    private fun actualizarEstadoConRetraso() {
        val root = findViewById<View>(R.id.main_root)
        root.postDelayed({ actualizarEstado() }, RETRASO_ESTADO_MS)
    }

    private fun actualizarEstado() {
        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.setText(
            if (overlay) R.string.main_overlay_granted else R.string.main_overlay_missing
        )
        overlayStatus.setTextColor(
            getColor(
                if (overlay) R.color.deposito_ahorro else R.color.deposito_sobrecoste
            )
        )

        val ubicacion = tienePermisoUbicacion()
        locationStatus.setText(
            if (ubicacion) R.string.main_location_granted else R.string.main_location_missing
        )
        locationStatus.setTextColor(
            getColor(
                if (ubicacion) R.color.deposito_ahorro else R.color.deposito_sobrecoste
            )
        )

        val activo = FloatingWidgetService.activo
        floatingStatus.setText(
            if (activo) R.string.main_floating_active else R.string.main_floating_inactive
        )
        floatingStatus.setTextColor(
            getColor(
                if (activo) R.color.deposito_ahorro else R.color.config_text_secondary
            )
        )

        stopButton.visibility = if (activo) View.VISIBLE else View.GONE
    }

    /**
     * Selector discreto del navegador GPS. El cambio se guarda al instante,
     * sin depender del boton de guardar del modo flotante.
     */
    private fun prepararSelectorNavegador() {
        navigationGroup.check(idNavegador(NavigationPreferences.leer(this)))
        navigationGroup.setOnCheckedChangeListener { _, id ->
            NavigationPreferences.guardar(this, navegadorDesde(id))
            actualizarEstadoNavegacion()
        }
        navigationUsageButton.setOnClickListener { abrirAjustesAccesoUso() }
        actualizarEstadoNavegacion()
    }

    private fun actualizarEstadoNavegacion() {
        val descripcion = NavigationHelper.descripcionNavegador(this)
        navigationStatus.text = if (descripcion == null) {
            getString(R.string.main_nav_status_preguntar)
        } else {
            getString(R.string.main_nav_status_se_abrira, descripcion)
        }
        navigationUsageButton.visibility =
            if (NavigationHelper.tienePermisoUso(this)) View.GONE else View.VISIBLE
    }

    /**
     * El permiso de acceso de uso es especial: solo se concede desde los
     * ajustes del sistema, por eso se ofrece el atajo cuando falta.
     */
    private fun abrirAjustesAccesoUso() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.main_nav_usage_missing,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun idNavegador(preferido: String): Int {
        return when (preferido) {
            NavigationPreferences.GOOGLE_MAPS -> R.id.main_nav_maps
            NavigationPreferences.WAZE -> R.id.main_nav_waze
            NavigationPreferences.SYGIC -> R.id.main_nav_sygic
            NavigationPreferences.PREGUNTAR -> R.id.main_nav_preguntar
            else -> R.id.main_nav_auto
        }
    }

    private fun navegadorDesde(id: Int): String {
        return when (id) {
            R.id.main_nav_maps -> NavigationPreferences.GOOGLE_MAPS
            R.id.main_nav_waze -> NavigationPreferences.WAZE
            R.id.main_nav_sygic -> NavigationPreferences.SYGIC
            R.id.main_nav_preguntar -> NavigationPreferences.PREGUNTAR
            else -> NavigationPreferences.AUTO
        }
    }

    private fun tienePermisoUbicacion(): Boolean {
        val fino = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val grueso = checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fino || grueso
    }

    private companion object {
        const val REQUEST_OVERLAY = 4001
        const val REQUEST_PERMISOS = 4002
        const val RETRASO_ESTADO_MS = 500L
    }
}
