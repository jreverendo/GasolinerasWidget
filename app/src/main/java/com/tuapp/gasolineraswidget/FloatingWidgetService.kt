package com.tuapp.gasolineraswidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ventana flotante del modo coche.
 *
 * Vive en un servicio en primer plano con permiso SYSTEM_ALERT_WINDOW y se
 * dibuja sobre el navegador. Reutiliza GasolineraRepository para los datos,
 * la escala de 6 tramos de PrecioTramo y los drawables de precio existentes.
 *
 * El servicio no descarga ni calcula nada en el hilo principal: toda la
 * lectura de cache, ubicacion y red se hace en un ejecutor de un solo hilo.
 *
 * En ruta solicita actualizaciones de ubicacion cada 1500 metros y 30
 * segundos, y refresca la capsula sin intervencion del usuario. Un vigilante
 * de inactividad y los eventos de pantalla apagan el GPS cuando no hace
 * falta, de modo que el servicio nunca agota la bateria del coche.
 *
 * La capsula se adapta al tamano de la ventana: en un telefono en vertical
 * conserva su tamano compacto original, y en pantallas grandes o en
 * horizontal (radios de coche y tablets) ocupa casi la mitad del ancho y
 * escala textos, botones y espaciados para poder leerla y pulsarla a
 * distancia. El ancho y el factor de escala se recalculan al inflar la
 * vista y cada vez que cambia la configuracion de pantalla.
 *
 * En carretera el servicio detecta el modo ruta con la velocidad sostenida
 * y el rumbo de la marcha: las estaciones que quedan detras pasan al final
 * de la lista, sin desaparecer, y el boton de cierre cambia a tono rojo.
 * En ciudad o parado se mantiene el orden y el tono verde de siempre.
 */
class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var inflater: LayoutInflater
    private lateinit var repository: GasolineraRepository

    private val handler = Handler(Looper.getMainLooper())
    private val ejecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generacionCarga = AtomicInteger(0)

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var infoProtagonista: View
    private lateinit var nombre: TextView
    private lateinit var etiquetaCarburante: TextView
    private lateinit var distancia: TextView
    private lateinit var precio: TextView
    private lateinit var ahorro: TextView
    private lateinit var btnRed: TextView
    private lateinit var btnCriterio: TextView
    private lateinit var btnGps: ImageButton
    private lateinit var btnJson: ImageButton
    private lateinit var btnCerrar: ImageButton
    private lateinit var btnExpandir: TextView
    private lateinit var filasExtra: LinearLayout
    private lateinit var cabecera: View
    private lateinit var filaProtagonista: View

    private var redActual = WidgetNetworkPreferences.TODAS
    private var criterioActual = FloatingWidgetPreferences.CRITERIO_BARATA
    private var carburanteActual = FuelOptions.GASOLINA_95
    private var expandido = false
    private var ultimoResultado: ResultadoLista? = null
    private var datosVisibles = false

    private var receptorPantallaRegistrado = false
    private var actualizacionesRutaActivas = false
    private var proveedorRuta: String? = null
    private var ultimoMovimientoMs = 0L
    private var puntoMovimiento: Ubicacion? = null
    private var ultimaUbicacionRuta: Ubicacion? = null

    /** Detector de modo ruta con histeresis temporal y rumbo de la marcha. */
    private val detectorRuta = RouteDirectionHelper()
    private var modoRuta = false
    private var estacionesRebasadas: Set<String> = emptySet()
    private var ubicacionActual: Ubicacion? = null

    private var arrastreInicioX = 0
    private var arrastreInicioY = 0

    /** Factor de escala activo para textos, botones y espaciados. */
    private var escalaActual = 1f

    /**
     * Medidas originales de cada vista tal y como se inflan desde el XML.
     * Se capturan una sola vez para que reaplicar la escala sea idempotente
     * aunque la configuracion cambie varias veces.
     */
    private val medidasBase = WeakHashMap<View, MedidasBase>()

    /**
     * Apaga el GPS en cuanto la pantalla se apaga y lo reanuda al encenderse.
     * Se registra de forma dinamica para no dejar receptores persistentes.
     */
    private val receptorPantalla = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> manejarPantallaApagada()
                Intent.ACTION_SCREEN_ON -> manejarPantallaEncendida()
            }
        }
    }

    /**
     * Recibe las actualizaciones de ruta solicitadas al LocationManager.
     */
    private val escuchaRuta = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            procesarUbicacionRuta(location)
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
            if (!activo) return
            val actual = proveedorRuta
            if (actual != null && actual != provider) return
            detenerActualizacionesRuta()
            if (pantallaEncendida()) {
                handler.post { iniciarActualizacionesRuta() }
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    /**
     * Vigilante de inactividad: si pasan diez minutos sin un desplazamiento
     * real de mas de cien metros, se asume que el coche esta aparcado y se
     * cierra la capsula para dejar consumo cero.
     */
    private val tareaVigilancia = object : Runnable {
        override fun run() {
            if (!activo) return

            val ahora = System.currentTimeMillis()
            if (ultimoMovimientoMs > 0L &&
                ahora - ultimoMovimientoMs >= INACTIVIDAD_MAXIMA_MS
            ) {
                Log.i(TAG, "Sin movimiento real; se cierra la capsula para ahorrar bateria")
                detenerServicio()
                return
            }
            handler.postDelayed(this, INTERVALO_VIGILANCIA_MS)
        }
    }

    private val objetoArrastre = object : CapsuleDragLayout.OnDragListener {
        override fun onDragStart() {
            val p = params ?: return
            arrastreInicioX = p.x
            arrastreInicioY = p.y
        }

        override fun onDragMove(dx: Int, dy: Int) {
            moverVentana(arrastreInicioX + dx, arrastreInicioY + dy, false)
        }

        override fun onDragEnd() {
            val p = params ?: return
            moverVentana(p.x, p.y, true)
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        inflater = LayoutInflater.from(this)
        repository = GasolineraRepository(applicationContext)
        redActual = FloatingWidgetPreferences.leerRed(this)
        criterioActual = FloatingWidgetPreferences.leerCriterio(this)
        carburanteActual = FloatingWidgetPreferences.leerCarburante(this)

        crearCanalNotificacion()
        if (!iniciarPrimerPlano()) return

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Falta el permiso de superposicion")
            Toast.makeText(
                this,
                R.string.floating_permission_denied_message,
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        if (!mostrarVentana()) {
            stopSelf()
            return
        }

        activo = true
        registrarReceptorPantalla()
        reiniciarVigilancia()
        iniciarActualizacionesRuta()
        cargarDatos(mostrarCarga = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                detenerServicio()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                if (root != null) {
                    cargarDatos(mostrarCarga = false)
                }
            }
        }
        // El servicio solo vive mientras la capsula esta abierta: si el
        // sistema lo mata no debe reaparecer en segundo plano.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Se aplaza un ciclo para que WindowManager ya publique las medidas
        // de la nueva orientacion antes de recalcular ancho y escala.
        handler.post { reajustarPantalla() }
    }

    /**
     * Recalcula el tamano de la ventana y la escala de los elementos cuando
     * cambia la orientacion o la configuracion de pantalla. Mantiene la
     * capsula dentro de los bordes y reconstruye las filas del desplegable
     * para que quepan en la nueva altura disponible.
     */
    private fun reajustarPantalla() {
        val vista = root ?: return
        val p = params ?: return

        val pantalla = medirPantalla()
        val escalaAnterior = escalaActual
        val anchoAnterior = p.width
        escalaActual = calcularEscala(pantalla)
        val ancho = calcularAncho(pantalla)

        aplicarEscalaCapsula(escalaActual)

        p.width = ancho
        try {
            windowManager.updateViewLayout(vista, p)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo reajustar la ventana al cambiar la configuracion", e)
            return
        }

        if (datosVisibles && (escalaActual != escalaAnterior || ancho != anchoAnterior)) {
            // Se reconstruyen las filas para que el numero visible vuelva a
            // caber en la nueva altura y ancho escalados.
            ultimoResultado?.let { aplicarResultado(it) }
        }
        ajustarDentroDePantalla(true)
    }

    override fun onDestroy() {
        activo = false
        detenerVigilancia()
        detenerActualizacionesRuta()
        desregistrarReceptorPantalla()
        val vista = root
        root = null
        if (vista != null) {
            try {
                windowManager.removeView(vista)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo retirar la ventana flotante", e)
            }
        }
        handler.removeCallbacksAndMessages(null)
        ejecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun crearCanalNotificacion() {
        val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.floating_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        gestor.createNotificationChannel(canal)
    }

    private fun iniciarPrimerPlano(): Boolean {
        val notificacion = crearNotificacion()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val tipo = if (tienePermisoUbicacion()) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                try {
                    startForeground(NOTIFICATION_ID, notificacion, tipo)
                } catch (e: Exception) {
                    Log.w(TAG, "Tipo location no disponible; se usa specialUse", e)
                    startForeground(
                        NOTIFICATION_ID,
                        notificacion,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notificacion)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servicio en primer plano", e)
            stopSelf()
            false
        }
    }

    private fun crearNotificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this,
            REQUEST_ABRIR,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val detener = PendingIntent.getService(
            this,
            REQUEST_DETENER,
            Intent(this, FloatingWidgetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accion = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_floating),
            getString(R.string.floating_stop_action),
            detener
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_floating)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setContentIntent(abrir)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(accion)
            .build()
    }

    @SuppressLint("InflateParams")
    private fun mostrarVentana(): Boolean {
        val vista = inflater.inflate(R.layout.floating_widget, null)
        root = vista

        // setClipToOutline por codigo funciona desde API 21; el atributo XML
        // no se aplica en todas las versiones y dejaria las esquinas rectas.
        vista.findViewById<View>(R.id.floating_card).clipToOutline = true

        infoProtagonista = vista.findViewById(R.id.floating_protagonist_info)
        nombre = vista.findViewById(R.id.floating_nombre)
        etiquetaCarburante = vista.findViewById(R.id.floating_combustible)
        distancia = vista.findViewById(R.id.floating_distancia)
        precio = vista.findViewById(R.id.floating_precio)
        ahorro = vista.findViewById(R.id.floating_ahorro)
        btnRed = vista.findViewById(R.id.btn_network)
        btnCriterio = vista.findViewById(R.id.btn_criterio)
        btnGps = vista.findViewById(R.id.btn_gps)
        btnJson = vista.findViewById(R.id.btn_json)
        btnCerrar = vista.findViewById(R.id.btn_close)
        btnExpandir = vista.findViewById(R.id.btn_expand)
        filasExtra = vista.findViewById(R.id.floating_extra_rows)
        cabecera = vista.findViewById(R.id.floating_header)
        filaProtagonista = vista.findViewById(R.id.floating_protagonist_row)

        (vista as CapsuleDragLayout).onDragListener = objetoArrastre
        actualizarEtiquetaCarburante()

        val limites = limitesPantalla()
        val pantalla = medirPantalla(limites)
        escalaActual = calcularEscala(pantalla)
        val ancho = calcularAncho(pantalla)
        Log.d(
            TAG,
            "Pantalla " + pantalla.anchoPx + "x" + pantalla.altoPx + " px (" +
                pantalla.anchoDp.toInt() + "x" + pantalla.altoDp.toInt() +
                " dp); ancho=" + ancho + " px; escala=" + escalaActual
        )
        val p = WindowManager.LayoutParams(
            ancho,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = FloatingWidgetPreferences.leerX(
            this,
            ((limites.width() - ancho) / 2).coerceAtLeast(0)
        )
        p.y = FloatingWidgetPreferences.leerY(this, dp(96))
        params = p

        configurarControles(vista)
        // El cierre nace en tono de ciudad; el modo ruta lo pinta en rojo.
        actualizarColorCierre()
        // El XML es la referencia compacta: a partir de el se escala todo el
        // arbol para pantallas grandes sin tocar la logica de los controles.
        aplicarEscalaCapsula(escalaActual)

        return try {
            windowManager.addView(vista, p)
            vista.post { ajustarDentroDePantalla(false) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la ventana flotante", e)
            Toast.makeText(
                this,
                R.string.floating_permission_denied_message,
                Toast.LENGTH_LONG
            ).show()
            root = null
            false
        }
    }

    private fun configurarControles(vista: View) {
        actualizarTextosControles()

        btnRed.setOnClickListener { ciclarRed() }
        btnCriterio.setOnClickListener { ciclarCriterio() }
        btnGps.setOnClickListener { refrescarUbicacion() }
        btnJson.setOnClickListener { descargarPreciosForzado() }
        btnCerrar.setOnClickListener { detenerServicio() }
        btnExpandir.setOnClickListener { alternarExpansion() }
    }

    private fun moverVentana(x: Int, y: Int, guardar: Boolean) {
        val p = params ?: return
        val vista = root ?: return
        val limites = limitesPantalla()
        val ancho = anchoVentanaActual(vista)
        val alto = altoVentanaActual(vista)

        p.x = x.coerceIn(0, (limites.width() - ancho).coerceAtLeast(0))
        p.y = y.coerceIn(0, (limites.height() - alto).coerceAtLeast(0))

        try {
            windowManager.updateViewLayout(vista, p)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo mover la ventana flotante", e)
        }

        if (guardar) {
            FloatingWidgetPreferences.guardarPosicion(this, p.x, p.y)
        }
    }

    private fun ajustarDentroDePantalla(guardar: Boolean) {
        val vista = root ?: return
        vista.post {
            val p = params
            if (p != null && root != null) {
                moverVentana(p.x, p.y, guardar)
            }
        }
    }

    private fun limitesPantalla(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metricas = resources.displayMetrics
            Rect(0, 0, metricas.widthPixels, metricas.heightPixels)
        }
    }

    /**
     * Medidas reales de la ventana en pixeles y en dp. Se combinan
     * WindowManager y DisplayMetrics para que la deteccion funcione igual en
     * telefonos, tablets y radios de coche.
     */
    private data class Pantalla(
        val anchoPx: Int,
        val altoPx: Int,
        val anchoDp: Float,
        val altoDp: Float
    ) {
        val horizontal: Boolean get() = anchoPx > altoPx
    }

    /** Medidas originales de una vista, capturadas antes de escalarla. */
    private class MedidasBase(
        val tamanoTextoPx: Float,
        val relleno: IntArray,
        val anchoMinimo: Int,
        val altoMinimo: Int,
        val ancho: Int,
        val alto: Int,
        val margen: IntArray
    )

    private fun medirPantalla(limites: Rect = limitesPantalla()): Pantalla {
        val densidad = resources.displayMetrics.density.coerceAtLeast(0.1f)
        return Pantalla(
            anchoPx = limites.width(),
            altoPx = limites.height(),
            anchoDp = limites.width() / densidad,
            altoDp = limites.height() / densidad
        )
    }

    /**
     * Criterio de pantalla grande o panoramica: al menos 550 dp de ancho, o
     * al menos 600 dp con la ventana en horizontal. Es el rango habitual de
     * los radios Android de coche de 9 y 10 pulgadas y de las tablets.
     */
    private fun esPantallaGrande(pantalla: Pantalla): Boolean {
        return pantalla.anchoDp >= ANCHO_GRANDE_DP ||
            (pantalla.horizontal && pantalla.anchoDp >= ANCHO_GRANDE_HORIZONTAL_DP)
    }

    private fun calcularEscala(pantalla: Pantalla): Float {
        return if (esPantallaGrande(pantalla)) ESCALA_GRANDE else ESCALA_COMPACTA
    }

    /**
     * En horizontal y en pantallas grandes la capsula ocupa cerca de la mitad
     * del ancho, con un tope de 520 dp y sin bajar del ancho compacto actual
     * para que los textos escalados no se recorten. En un telefono en vertical
     * se mantiene exactamente el ancho de siempre.
     */
    private fun calcularAncho(pantalla: Pantalla): Int {
        val margen = dp(MARGEN_PANTALLA_DP)
        val disponible = (pantalla.anchoPx - margen)
            .coerceAtLeast(dp(ANCHO_COMPACTO_MINIMO_DP))

        if (!esPantallaGrande(pantalla)) {
            return disponible.coerceAtMost(dp(ANCHO_COMPACTO_MAXIMO_DP))
        }

        val fraccion = (pantalla.anchoPx * FRACCION_ANCHO_GRANDE).toInt()
        val minimo = dp(ANCHO_COMPACTO_MAXIMO_DP)
        val maximo = dp(ANCHO_GRANDE_MAXIMO_DP)
        val limite = disponible.coerceAtLeast(minimo)
        return fraccion.coerceIn(minimo, maximo).coerceAtMost(limite)
    }

    /** Ancho real de la ventana ya escalada, con el ancho calculado de reserva. */
    private fun anchoVentanaActual(vista: View?): Int {
        if (vista != null && vista.width > 0) return vista.width
        val p = params
        if (p != null && p.width > 0) return p.width
        return calcularAncho(medirPantalla())
    }

    /** Alto real de la ventana ya escalada, tambien antes del primer layout. */
    private fun altoVentanaActual(vista: View?): Int {
        if (vista == null) return 0
        if (vista.height > 0) return vista.height
        return vista.measuredHeight.coerceAtLeast(0)
    }

    /** Ancho util de la tarjeta, descontando el relleno de la raiz. */
    private fun anchoContenidoCapsula(): Int {
        val p = params
        val anchoVentana = if (p != null && p.width > 0) {
            p.width
        } else {
            anchoVentanaActual(root)
        }
        val relleno = (root?.paddingLeft ?: 0) + (root?.paddingRight ?: 0)
        return (anchoVentana - relleno).coerceAtLeast(0)
    }

    /**
     * Alto libre para las filas del desplegable "+": el alto de la pantalla
     * menos la cabecera, la fila protagonista, el relleno de la raiz y un
     * margen de seguridad. Asi la capsula expandida nunca se sale por abajo
     * en pantallas horizontales de coche.
     */
    private fun altoMaximoExtras(anchoContenido: Int): Int {
        var altoFijo = dp(2)
        if (this::cabecera.isInitialized) {
            altoFijo += medirAlto(cabecera, anchoContenido)
        }
        if (this::filaProtagonista.isInitialized) {
            altoFijo += medirAlto(filaProtagonista, anchoContenido)
        }
        val rellenoRaiz = (root?.paddingTop ?: 0) + (root?.paddingBottom ?: 0)
        return (medirPantalla().altoPx - altoFijo - rellenoRaiz - dp(MARGEN_SEGURIDAD_ALTO_DP))
            .coerceAtLeast(0)
    }

    private fun medirAlto(vista: View, ancho: Int): Int {
        if (ancho <= 0) return 0
        vista.measure(
            View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return vista.measuredHeight.coerceAtLeast(0)
    }

    /** Aplica la escala a la capsula completa y vuelve a medirla. */
    private fun aplicarEscalaCapsula(escala: Float) {
        val vista = root ?: return
        aplicarEscalaEnArbol(vista, escala)
        vista.requestLayout()
    }

    private fun aplicarEscalaEnArbol(vista: View, escala: Float) {
        aplicarEscala(vista, escala)
        if (vista is ViewGroup) {
            for (indice in 0 until vista.childCount) {
                aplicarEscalaEnArbol(vista.getChildAt(indice), escala)
            }
        }
    }

    /**
     * Escala textos, rellenos, minimos, margenes y tamanos fijos de una vista
     * partiendo siempre de su medida original, de modo que el resultado no
     * depende de cuantas veces se aplique ni de la orientacion anterior.
     */
    private fun aplicarEscala(vista: View, escala: Float) {
        val base = medidasBase[vista] ?: capturarMedidas(vista).also {
            medidasBase[vista] = it
        }

        (vista as? TextView)?.let { texto ->
            if (base.tamanoTextoPx > 0f) {
                texto.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    base.tamanoTextoPx * escala
                )
            }
        }

        vista.setPadding(
            escalar(base.relleno[0], escala),
            escalar(base.relleno[1], escala),
            escalar(base.relleno[2], escala),
            escalar(base.relleno[3], escala)
        )
        if (base.anchoMinimo > 0) {
            vista.minimumWidth = escalar(base.anchoMinimo, escala)
        }
        if (base.altoMinimo > 0) {
            vista.minimumHeight = escalar(base.altoMinimo, escala)
        }

        val lp = vista.layoutParams ?: return
        // La raiz usa los parametros de ventana que gobierna el servicio.
        if (lp is WindowManager.LayoutParams) return

        var modificado = false
        if (base.ancho >= 0) {
            val ancho = escalar(base.ancho, escala)
            if (lp.width != ancho) {
                lp.width = ancho
                modificado = true
            }
        }
        if (base.alto >= 0) {
            val alto = escalar(base.alto, escala)
            if (lp.height != alto) {
                lp.height = alto
                modificado = true
            }
        }
        if (lp is ViewGroup.MarginLayoutParams) {
            val margen = base.margen
            val inicio = escalar(margen[0], escala)
            val arriba = escalar(margen[1], escala)
            val fin = escalar(margen[2], escala)
            val abajo = escalar(margen[3], escala)
            if (lp.leftMargin != inicio || lp.topMargin != arriba ||
                lp.rightMargin != fin || lp.bottomMargin != abajo
            ) {
                lp.setMargins(inicio, arriba, fin, abajo)
                modificado = true
            }
        }
        if (modificado) {
            vista.layoutParams = lp
        }
    }

    private fun capturarMedidas(vista: View): MedidasBase {
        val lp = vista.layoutParams
        val margen = if (lp is ViewGroup.MarginLayoutParams) {
            intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        } else {
            intArrayOf(0, 0, 0, 0)
        }
        return MedidasBase(
            tamanoTextoPx = (vista as? TextView)?.textSize ?: -1f,
            relleno = intArrayOf(
                vista.paddingLeft,
                vista.paddingTop,
                vista.paddingRight,
                vista.paddingBottom
            ),
            anchoMinimo = vista.minimumWidth,
            altoMinimo = vista.minimumHeight,
            ancho = lp?.width ?: 0,
            alto = lp?.height ?: 0,
            margen = margen
        )
    }

    private fun escalar(valor: Int, escala: Float): Int {
        return (valor * escala).roundToInt()
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

    private fun actualizarTextosControles() {
        btnRed.text = when (redActual) {
            WidgetNetworkPreferences.TRADICIONAL ->
                getString(R.string.widget_toggle_red_tradicional)

            WidgetNetworkPreferences.LOWCOST ->
                getString(R.string.widget_toggle_red_lowcost)

            else ->
                getString(R.string.widget_toggle_red_todas)
        }

        btnCriterio.text = if (criterioActual == FloatingWidgetPreferences.CRITERIO_CERCANA) {
            getString(R.string.floating_criterio_cercana)
        } else {
            getString(R.string.floating_criterio_barata)
        }

        btnExpandir.text = if (expandido) {
            getString(R.string.floating_collapse_symbol)
        } else {
            getString(R.string.floating_expand_symbol)
        }
        btnExpandir.contentDescription = if (expandido) {
            getString(R.string.floating_collapse_description)
        } else {
            getString(R.string.floating_expand_description)
        }
    }

    private fun actualizarEtiquetaCarburante() {
        etiquetaCarburante.text = FuelOptions.codigoCorto(carburanteActual)
    }

    private fun ciclarRed() {
        redActual = when (redActual) {
            WidgetNetworkPreferences.TODAS -> WidgetNetworkPreferences.LOWCOST
            WidgetNetworkPreferences.LOWCOST -> WidgetNetworkPreferences.TRADICIONAL
            else -> WidgetNetworkPreferences.TODAS
        }
        FloatingWidgetPreferences.guardarRed(this, redActual)
        actualizarTextosControles()
        cargarDatos(mostrarCarga = false)
    }

    private fun ciclarCriterio() {
        criterioActual = if (criterioActual == FloatingWidgetPreferences.CRITERIO_CERCANA) {
            FloatingWidgetPreferences.CRITERIO_BARATA
        } else {
            FloatingWidgetPreferences.CRITERIO_CERCANA
        }
        FloatingWidgetPreferences.guardarCriterio(this, criterioActual)
        actualizarTextosControles()
        ultimoResultado?.let { aplicarResultado(it) }
    }

    private fun refrescarUbicacion() {
        repository.solicitarUbicacionFresca()
        repository.solicitarRefrescoManual()
        mostrarAviso(R.string.floating_updating)
        cargarDatos(mostrarCarga = false)
    }

    private fun descargarPreciosForzado() {
        mostrarAviso(R.string.floating_updating)
        repository.actualizarEnSegundoPlano(forceDownload = true) { actualizado ->
            handler.post {
                if (!activo) return@post
                mostrarAviso(
                    if (actualizado) {
                        R.string.floating_download_ok
                    } else {
                        R.string.floating_download_fail
                    }
                )
                if (actualizado) {
                    cargarDatos(mostrarCarga = false)
                }
            }
        }
    }

    private fun cargarDatos(mostrarCarga: Boolean) {
        carburanteActual = FloatingWidgetPreferences.leerCarburante(this)
        actualizarEtiquetaCarburante()
        if (mostrarCarga) mostrarCargando()

        val generacion = generacionCarga.incrementAndGet()
        val filtro = redActual
        val clave = carburanteActual

        try {
            ejecutor.execute {
                try {
                    repository.procesarSolicitudUbicacionFresca()
                    val estado = repository.obtenerEstadoDispositivo()
                    val recalcular = repository.debeRecalcular(estado)
                    val municipio = repository.obtenerMunicipioActual(estado.ubicacion)
                    val resultado = repository.cargarListaParaMostrar(
                        claveCombustible = clave,
                        estado = estado,
                        recalcular = recalcular,
                        municipioActual = municipio,
                        filtroRed = filtro
                    )
                    val descargar = repository.debeDescargar(estado)

                    handler.post {
                        if (!activo || generacionCarga.get() != generacion) return@post
                        aplicarResultado(resultado)
                    }

                    if (descargar) {
                        repository.actualizarEnSegundoPlano { actualizado ->
                            if (actualizado) {
                                handler.post {
                                    if (activo) cargarDatos(mostrarCarga = false)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudieron cargar las gasolineras", e)
                    handler.post {
                        if (activo && generacionCarga.get() == generacion) {
                            mostrarVacio()
                        }
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "El servicio ya esta detenido", e)
        }
    }

    private fun aplicarResultado(resultado: ResultadoLista) {
        ultimoResultado = resultado

        val ordenadas = ordenar(resultado.gasolineras)
        val protagonista = ordenadas.firstOrNull()
        if (protagonista == null) {
            mostrarVacio()
            return
        }

        val litros = FloatingWidgetPreferences.leerLitrosDeposito(this)
        pintarProtagonista(protagonista, resultado.mediaProvincial, litros)

        val extras = ordenadas.drop(1).take(MAX_FILAS_EXTRA)
        construirFilasExtra(extras, resultado.mediaProvincial, litros)

        // Solo hay desplegable si alguna fila ha cabido en la altura libre.
        val hayExtras = filasExtra.childCount > 0
        if (!hayExtras) expandido = false
        actualizarBotonExpansion(hayExtras)
        filasExtra.visibility = if (expandido) View.VISIBLE else View.GONE
        datosVisibles = true
        ajustarDentroDePantalla(true)
    }

    /**
     * Ordena por el criterio del usuario y, en modo ruta con rumbo fiable,
     * empuja al final las estaciones que quedan detras del vehiculo. No se
     * elimina ninguna: siguen en el desplegable por si conviene dar la
     * vuelta. Sin modo ruta el orden es exactamente el de siempre.
     */
    private fun ordenar(lista: List<Gasolinera>): List<Gasolinera> {
        val ordenadas = if (criterioActual == FloatingWidgetPreferences.CRITERIO_CERCANA) {
            lista.sortedWith(
                compareBy<Gasolinera> { it.distancia }
                    .thenBy { it.precio }
                    .thenBy { it.nombre }
            )
        } else {
            lista.sortedWith(
                compareBy<Gasolinera> { it.precio }
                    .thenBy { it.distancia }
                    .thenBy { it.nombre }
            )
        }

        estacionesRebasadas = emptySet()
        val rumbo = detectorRuta.rumbo
        val ubicacion = ubicacionActual
        if (!modoRuta || rumbo == null || ubicacion == null) return ordenadas

        val porDelante = ArrayList<Gasolinera>(ordenadas.size)
        val porDetras = ArrayList<Gasolinera>()
        val idsRebasados = HashSet<String>()

        for (gasolinera in ordenadas) {
            val desviacion = RouteDirectionHelper.desviacionGrados(
                rumbo,
                ubicacion.latitud,
                ubicacion.longitud,
                gasolinera.latitud,
                gasolinera.longitud
            )
            if (RouteDirectionHelper.esRebasada(desviacion)) {
                porDetras.add(gasolinera)
                idsRebasados.add(gasolinera.id)
            } else {
                porDelante.add(gasolinera)
            }
        }

        estacionesRebasadas = idsRebasados
        return porDelante + porDetras
    }

    private fun pintarProtagonista(
        gasolinera: Gasolinera,
        mediaProvincial: Double,
        litrosDeposito: Float
    ) {
        nombre.text = gasolinera.nombre
        distancia.text = textoDistancia(gasolinera)
        precio.visibility = View.VISIBLE
        precio.text = String.format(Locale.US, "%.3f", gasolinera.precio)

        val tramo = PrecioTramo.tramo(
            PrecioTramo.deltaPorLitro(gasolinera.precio, mediaProvincial)
        )
        precio.setBackgroundResource(tramo.fondo)
        precio.setTextColor(getColor(tramo.colorTexto))

        val diferencia = PrecioTramo.diferenciaCoste(
            gasolinera.precio,
            mediaProvincial,
            litrosDeposito
        )
        ahorro.text = PrecioTramo.textoDiferenciaCoste(this, diferencia)
        ahorro.setTextColor(PrecioTramo.colorDiferenciaCoste(this, diferencia))
        ahorro.visibility = View.VISIBLE

        infoProtagonista.isClickable = true
        infoProtagonista.setOnClickListener { abrirNavegacion(gasolinera) }
    }

    private fun construirFilasExtra(
        lista: List<Gasolinera>,
        mediaProvincial: Double,
        litrosDeposito: Float
    ) {
        filasExtra.removeAllViews()

        // Mismo criterio y misma tolerancia que el widget de escritorio:
        // la fila con el precio minimo visible usa su color de resaltado.
        val precioMinimoVisible = lista.minOfOrNull { it.precio } ?: Double.MAX_VALUE

        // El desplegable se limita a la altura libre de la pantalla con el
        // tamano ya escalado, de modo que nunca se sale por abajo.
        val anchoFila = anchoContenidoCapsula()
        val altoMaximo = altoMaximoExtras(anchoFila)
        var altoAcumulado = 0

        for (gasolinera in lista) {
            val fila = inflater.inflate(R.layout.item_floating_row, filasExtra, false)
            aplicarEscalaEnArbol(fila, escalaActual)

            val altoFila = medirAlto(fila, anchoFila)
            if (altoAcumulado + altoFila > altoMaximo) break
            altoAcumulado += altoFila

            val esMasBarata = abs(
                gasolinera.precio - precioMinimoVisible
            ) <= TOLERANCIA_PRECIO_MINIMO
            fila.setBackgroundResource(
                if (esMasBarata) {
                    R.drawable.bg_floating_row_cheapest
                } else {
                    R.drawable.bg_floating_row
                }
            )

            fila.findViewById<TextView>(R.id.row_nombre).text = gasolinera.nombre
            fila.findViewById<TextView>(R.id.row_distancia).text = textoDistancia(gasolinera)

            val vistaPrecio = fila.findViewById<TextView>(R.id.row_precio)
            vistaPrecio.text = String.format(Locale.US, "%.3f", gasolinera.precio)
            val tramo = PrecioTramo.tramo(
                PrecioTramo.deltaPorLitro(gasolinera.precio, mediaProvincial)
            )
            vistaPrecio.setBackgroundResource(tramo.fondo)
            vistaPrecio.setTextColor(getColor(tramo.colorTexto))

            val diferencia = PrecioTramo.diferenciaCoste(
                gasolinera.precio,
                mediaProvincial,
                litrosDeposito
            )
            val vistaAhorro = fila.findViewById<TextView>(R.id.row_ahorro)
            vistaAhorro.text = PrecioTramo.textoDiferenciaCoste(this, diferencia)
            vistaAhorro.setTextColor(
                PrecioTramo.colorDiferenciaCoste(this, diferencia)
            )

            fila.setOnClickListener { abrirNavegacion(gasolinera) }
            filasExtra.addView(fila)
        }
    }

    /** Distancia con el aviso discreto cuando la estacion queda detras. */
    private fun textoDistancia(gasolinera: Gasolinera): String {
        return if (esEstacionRebasada(gasolinera)) {
            getString(R.string.formato_distancia_atras, gasolinera.distancia)
        } else {
            getString(R.string.formato_distancia, gasolinera.distancia)
        }
    }

    private fun esEstacionRebasada(gasolinera: Gasolinera): Boolean {
        return modoRuta && estacionesRebasadas.contains(gasolinera.id)
    }

    /**
     * El cierre cambia de tono segun el modo, sin ocupar mas espacio: verde
     * suave en ciudad y rojo suave en ruta.
     */
    private fun actualizarColorCierre() {
        if (!this::btnCerrar.isInitialized) return
        btnCerrar.setBackgroundResource(
            if (modoRuta) {
                R.drawable.bg_icon_button_close
            } else {
                R.drawable.bg_icon_button_close_city
            }
        )
        btnCerrar.imageTintList = ColorStateList.valueOf(
            getColor(
                if (modoRuta) {
                    R.color.floating_close_icon
                } else {
                    R.color.floating_close_city_icon
                }
            )
        )
    }

    private fun actualizarBotonExpansion(habilitado: Boolean) {
        btnExpandir.isEnabled = habilitado
        btnExpandir.alpha = if (habilitado) 1f else 0.35f
        actualizarTextosControles()
    }

    private fun alternarExpansion() {
        if (!btnExpandir.isEnabled) {
            mostrarAviso(R.string.floating_no_more)
            return
        }
        expandido = !expandido
        filasExtra.visibility = if (expandido) View.VISIBLE else View.GONE
        actualizarTextosControles()
        ajustarDentroDePantalla(true)
    }

    private fun mostrarCargando() {
        nombre.text = getString(R.string.floating_loading)
        distancia.text = ""
        precio.visibility = View.GONE
        ahorro.visibility = View.GONE
        infoProtagonista.isClickable = false
        infoProtagonista.setOnClickListener(null)
        filasExtra.removeAllViews()
        filasExtra.visibility = View.GONE
        expandido = false
        datosVisibles = false
        btnExpandir.isEnabled = false
        btnExpandir.alpha = 0.35f
        actualizarTextosControles()
        ajustarDentroDePantalla(true)
    }

    private fun mostrarVacio() {
        nombre.text = getString(R.string.widget_empty)
        distancia.text = ""
        precio.visibility = View.GONE
        ahorro.visibility = View.GONE
        infoProtagonista.isClickable = false
        infoProtagonista.setOnClickListener(null)
        filasExtra.removeAllViews()
        filasExtra.visibility = View.GONE
        expandido = false
        datosVisibles = false
        btnExpandir.isEnabled = false
        btnExpandir.alpha = 0.35f
        actualizarTextosControles()
        ajustarDentroDePantalla(true)
    }

    private fun abrirNavegacion(gasolinera: Gasolinera) {
        // La deteccion del navegador y los formatos de URI viven en el helper
        // compartido con el widget de escritorio.
        val abierto = NavigationHelper.abrirNavegacionGasolinera(
            this,
            gasolinera.latitud,
            gasolinera.longitud,
            gasolinera.nombre
        )
        if (!abierto) {
            mostrarAviso(R.string.floating_navigation_missing)
        }
    }

    private fun detenerServicio() {
        handler.removeCallbacks(tareaVigilancia)
        detenerActualizacionesRuta()
        activo = false
        val vista = root
        root = null
        if (vista != null) {
            try {
                windowManager.removeView(vista)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo retirar la ventana flotante", e)
            }
        }
        stopSelf()
    }

    private fun manejarPantallaApagada() {
        Log.i(TAG, "Pantalla apagada; se detienen las actualizaciones de ruta")
        detenerActualizacionesRuta()
    }

    private fun manejarPantallaEncendida() {
        if (!activo) return
        Log.i(TAG, "Pantalla encendida; se reanudan las actualizaciones de ruta")
        reiniciarVigilancia()
        iniciarActualizacionesRuta()
    }

    private fun registrarReceptorPantalla() {
        if (receptorPantallaRegistrado) return

        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    receptorPantalla,
                    filtro,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(receptorPantalla, filtro)
            }
            receptorPantallaRegistrado = true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar el receptor de pantalla", e)
        }
    }

    private fun desregistrarReceptorPantalla() {
        if (!receptorPantallaRegistrado) return
        receptorPantallaRegistrado = false
        try {
            unregisterReceiver(receptorPantalla)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "El receptor de pantalla ya estaba desregistrado", e)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desregistrar el receptor de pantalla", e)
        }
    }

    private fun reiniciarVigilancia() {
        ultimoMovimientoMs = System.currentTimeMillis()
        handler.removeCallbacks(tareaVigilancia)
        handler.postDelayed(tareaVigilancia, INTERVALO_VIGILANCIA_MS)
    }

    private fun detenerVigilancia() {
        handler.removeCallbacks(tareaVigilancia)
    }

    /**
     * Activa las actualizaciones de ruta solo con la capsula abierta, la
     * pantalla encendida y permiso de ubicacion.
     *
     * La lista se sigue recalculando cada 1500 m, pero las lecturas llegan
     * cada 10 segundos para que el detector de modo ruta pueda evaluar su
     * ventana de un minuto. El GPS ya estaba activo con el refresco anterior;
     * solo cambia la frecuencia de las llamadas.
     */
    private fun iniciarActualizacionesRuta() {
        if (!activo || actualizacionesRutaActivas) return
        if (!pantallaEncendida()) return

        if (!tienePermisoUbicacion()) {
            Log.w(TAG, "Sin permiso de ubicacion; no se activan las actualizaciones de ruta")
            return
        }

        val gestor = gestorUbicacion()
        if (gestor == null) {
            Log.w(TAG, "El LocationManager no esta disponible")
            return
        }

        val proveedor = elegirProveedorRuta(gestor)
        if (proveedor == null) {
            Log.w(TAG, "No hay proveedor de ubicacion para la ruta")
            return
        }

        try {
            gestor.requestLocationUpdates(
                proveedor,
                INTERVALO_MUESTREO_MS,
                DISTANCIA_MUESTREO_METROS,
                escuchaRuta,
                Looper.getMainLooper()
            )
            actualizacionesRutaActivas = true
            proveedorRuta = proveedor
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para solicitar actualizaciones de ruta", e)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron activar las actualizaciones de ruta", e)
        }
    }

    private fun detenerActualizacionesRuta() {
        val proveedor = proveedorRuta
        proveedorRuta = null
        actualizacionesRutaActivas = false
        if (proveedor == null) return

        val gestor = gestorUbicacion() ?: return
        try {
            gestor.removeUpdates(escuchaRuta)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para detener las actualizaciones de ruta", e)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron detener las actualizaciones de ruta", e)
        }
    }

    private fun procesarUbicacionRuta(location: Location) {
        if (!activo) return
        if (location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0 ||
            (location.latitude == 0.0 && location.longitude == 0.0)
        ) {
            return
        }

        val ahora = System.currentTimeMillis()
        val tiempo = if (location.time > 0L) location.time else ahora
        val punto = Ubicacion(
            latitud = location.latitude,
            longitud = location.longitude,
            velocidadMps = if (location.hasSpeed()) location.speed else null,
            timestampMillis = tiempo
        )

        // El detector consume todas las lecturas, no solo las que superan
        // los 1500 m del refresco de la lista, para poder aplicar la
        // histeresis de un minuto sobre la velocidad.
        val rumboGps = if (location.hasBearing() && location.bearing.isFinite()) {
            location.bearing
        } else {
            null
        }
        val cambioModo = detectorRuta.registrar(
            latitud = punto.latitud,
            longitud = punto.longitud,
            velocidadMps = punto.velocidadMps,
            bearingGrados = rumboGps,
            tiempoMs = punto.timestampMillis
        )
        ubicacionActual = punto
        if (cambioModo) {
            modoRuta = detectorRuta.modoRuta
            Log.i(TAG, "Modo ruta " + (if (modoRuta) "activado" else "desactivado"))
            actualizarColorCierre()
            if (root != null && datosVisibles) {
                // Se reordena con el nuevo modo sin esperar al proximo
                // refresco de 1500 m.
                ultimoResultado?.let { aplicarResultado(it) }
            }
        }

        val referenciaMovimiento = puntoMovimiento
        if (referenciaMovimiento == null) {
            puntoMovimiento = punto
            ultimoMovimientoMs = ahora
        } else {
            val desplazamiento = distanciaMetros(
                referenciaMovimiento.latitud,
                referenciaMovimiento.longitud,
                punto.latitud,
                punto.longitud
            )
            if (desplazamiento > MOVIMIENTO_REAL_METROS) {
                puntoMovimiento = punto
                ultimoMovimientoMs = ahora
            }
        }

        val referenciaRuta = ultimaUbicacionRuta
        val desplazamientoRuta = if (referenciaRuta == null) {
            Double.MAX_VALUE
        } else {
            distanciaMetros(
                referenciaRuta.latitud,
                referenciaRuta.longitud,
                punto.latitud,
                punto.longitud
            )
        }

        if (desplazamientoRuta < DISTANCIA_RUTA_METROS) return

        ultimaUbicacionRuta = punto
        actualizarRuta(punto)
    }

    /**
     * Recalcula la lista con la nueva ubicacion y, si la cache del
     * Ministerio tiene mas de 45 minutos, la descarga en silencio.
     */
    private fun actualizarRuta(ubicacion: Ubicacion) {
        repository.registrarUbicacionDeRuta(ubicacion)
        cargarDatos(mostrarCarga = false)

        if (!debeDescargarJsonDeRuta()) return
        if (!hayConexion()) return

        repository.actualizarEnSegundoPlano(forceDownload = true) { actualizado ->
            if (!actualizado) return@actualizarEnSegundoPlano
            handler.post {
                if (activo) cargarDatos(mostrarCarga = false)
            }
        }
    }

    private fun debeDescargarJsonDeRuta(): Boolean {
        val timestamp = repository.obtenerTimestampCache()
        if (timestamp <= 0L) return true
        val edad = System.currentTimeMillis() - timestamp
        return edad >= CACHE_RUTA_MAX_EDAD_MS
    }

    private fun hayConexion(): Boolean {
        val gestor = getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val red = gestor.activeNetwork ?: return false
        val capacidades = gestor.getNetworkCapabilities(red) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun gestorUbicacion(): LocationManager? {
        return getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    private fun elegirProveedorRuta(gestor: LocationManager): String? {
        val permisoFino = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val gpsActivo = try {
            gestor.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        if (permisoFino && gpsActivo) return LocationManager.GPS_PROVIDER

        val redActiva = try {
            gestor.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
        if (redActiva) return LocationManager.NETWORK_PROVIDER

        return try {
            gestor.getProviders(true).firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun pantallaEncendida(): Boolean {
        val gestor = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return gestor.isInteractive
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

        return 2.0 * RADIO_TIERRA_KM * asin(sqrt(a.coerceIn(0.0, 1.0))) * 1000.0
    }

    private fun mostrarAviso(mensaje: Int) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
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

    companion object {
        private const val TAG = "FloatingWidgetService"
        private const val CHANNEL_ID = "floating_widget_channel"
        private const val NOTIFICATION_ID = 2101
        private const val REQUEST_ABRIR = 3101
        private const val REQUEST_DETENER = 3102
        private const val MAX_FILAS_EXTRA = 4
        private const val TOLERANCIA_PRECIO_MINIMO = 1e-9

        // Adaptacion de la capsula a pantallas grandes y al modo coche.
        // Ancho compacto: el de siempre en telefonos en vertical.
        private const val ANCHO_COMPACTO_MINIMO_DP = 200
        private const val ANCHO_COMPACTO_MAXIMO_DP = 420
        private const val MARGEN_PANTALLA_DP = 8

        // Pantalla grande o panoramica: radios de coche y tablets.
        private const val ANCHO_GRANDE_DP = 550f
        private const val ANCHO_GRANDE_HORIZONTAL_DP = 600f
        private const val FRACCION_ANCHO_GRANDE = 0.47f
        private const val ANCHO_GRANDE_MAXIMO_DP = 520
        private const val ESCALA_GRANDE = 1.25f
        private const val ESCALA_COMPACTA = 1f
        private const val MARGEN_SEGURIDAD_ALTO_DP = 16

        // Actualizacion automatica de ruta y proteccion de bateria.
        private const val DISTANCIA_RUTA_METROS = 1500f
        private const val INTERVALO_MUESTREO_MS = 10000L
        private const val DISTANCIA_MUESTREO_METROS = 0f
        private const val MOVIMIENTO_REAL_METROS = 100.0
        private const val INTERVALO_VIGILANCIA_MS = 2L * 60L * 1000L
        private const val INACTIVIDAD_MAXIMA_MS = 10L * 60L * 1000L
        private const val CACHE_RUTA_MAX_EDAD_MS = 45L * 60L * 1000L
        private const val RADIO_TIERRA_KM = 6371.0088

        const val ACTION_STOP = "com.tuapp.gasolineraswidget.ACTION_STOP_FLOATING"
        const val ACTION_RELOAD = "com.tuapp.gasolineraswidget.ACTION_RELOAD_FLOATING"

        /**
         * Indica si la ventana flotante esta dibujada en este proceso.
         * La MainActivity lo usa para mostrar el estado del servicio.
         */
        @Volatile
        var activo: Boolean = false

        fun iniciar(context: Context) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            context.startForegroundService(intent)
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, FloatingWidgetService::class.java))
        }

        /**
         * Recarga carburante y deposito en la capsula activa sin reiniciarla.
         */
        fun solicitarRecarga(context: Context) {
            if (!activo) return
            val intent = Intent(context, FloatingWidgetService::class.java)
                .setAction(ACTION_RELOAD)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo solicitar la recarga de la capsula", e)
            }
        }
    }
}
