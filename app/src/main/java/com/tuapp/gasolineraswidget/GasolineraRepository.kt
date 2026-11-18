package com.tuapp.gasolineraswidget

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordenadas y datos de movimiento de una ubicacion.
 */
data class Ubicacion(
    val latitud: Double,
    val longitud: Double,
    val velocidadMps: Float? = null,
    val timestampMillis: Long = 0L
)

/**
 * Modos de actualizacion del widget.
 */
enum class ModoActualizacion {
    REPOSO,
    CONDUCIENDO_BATERIA,
    CONDUCIENDO_CARGANDO
}

/**
 * Estado actual del dispositivo para decidir la frecuencia de actualizacion.
 */
data class EstadoDispositivo(
    val ubicacion: Ubicacion,
    val cargando: Boolean,
    val conduciendo: Boolean
) {
    val modo: ModoActualizacion
        get() = when {
            conduciendo && cargando -> ModoActualizacion.CONDUCIENDO_CARGANDO
            conduciendo -> ModoActualizacion.CONDUCIENDO_BATERIA
            else -> ModoActualizacion.REPOSO
        }
}

/**
 * Repositorio de datos del Ministerio con cache local persistente.
 *
 * La descarga del JSON y el calculo de distancias son independientes:
 * - La descarga se controla con TTL segun bateria y conduccion.
 * - Las distancias se recalculan desde el JSON cacheado segun movimiento.
 *
 * La ubicacion se obtiene de la ultima posicion conocida del dispositivo.
 * Si no hay permisos o no hay posicion disponible, se usa Donostia.
 */
class GasolineraRepository(context: Context) {

    private val appContext = context.applicationContext
    private val archivoCache = File(appContext.filesDir, CACHE_FILE_NAME)
    private val prefsCache = appContext.getSharedPreferences(
        CACHE_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var jsonEnMemoria: String? = null

    @Volatile
    private var timestampJsonEnMemoria: Long = 0L

    @Volatile
    private var listasEnMemoria: Map<ClaveLista, ListaCache> = emptyMap()

    @Volatile
    private var municipioCache: MunicipioCache? = null

    @Volatile
    private var ubicacionFresca: Ubicacion? = null

    /**
     * Captura la ubicacion desde la Activity en primer plano y la conserva
     * para que el servicio del widget pueda usarla despues.
     */
    fun capturarUbicacionConocida(): Ubicacion? {
        if (!tienePermisoUbicacion()) return null

        val actual = leerUltimaUbicacionDispositivo() ?: return null
        guardarUbicacionConocida(actual)
        return actual
    }

    /**
     * Marca la proxima actualizacion para intentar obtener una ubicacion
     * fresca antes de usar la ultima conocida.
     */
    fun solicitarUbicacionFresca() {
        prefsCache.edit().putBoolean(KEY_FORCE_FRESH_LOCATION, true).apply()
    }

    /**
     * Procesa una solicitud de ubicacion fresca. Debe llamarse desde un hilo
     * de fondo. Si la lectura actual falla o tarda, se usa la ultima conocida.
     */
    fun procesarSolicitudUbicacionFresca() {
        if (!prefsCache.getBoolean(KEY_FORCE_FRESH_LOCATION, false)) return

        prefsCache.edit().putBoolean(KEY_FORCE_FRESH_LOCATION, false).apply()
        val fresca = obtenerUbicacionFresca()
        if (fresca != null) {
            ubicacionFresca = fresca
            guardarUbicacionConocida(fresca)
        }
    }

    /**
     * Registra la ubicacion que entrega el servicio flotante durante la
     * ruta. Guarda el punto como ultima ubicacion conocida y fuerza el
     * recalculo de distancias en la proxima carga de la lista.
     */
    fun registrarUbicacionDeRuta(ubicacion: Ubicacion) {
        if (ubicacion.latitud !in -90.0..90.0) return
        if (ubicacion.longitud !in -180.0..180.0) return
        if (ubicacion.latitud == 0.0 && ubicacion.longitud == 0.0) return

        val normalizada = if (ubicacion.timestampMillis > 0L) {
            ubicacion
        } else {
            ubicacion.copy(timestampMillis = System.currentTimeMillis())
        }

        ubicacionFresca = normalizada
        guardarUbicacionConocida(normalizada)
        prefsCache.edit().putBoolean(KEY_FORCE_RECALC, true).apply()
    }

    /**
     * Devuelve el estado actual de bateria, ubicacion y conduccion.
     */
    fun obtenerEstadoDispositivo(): EstadoDispositivo {
        val ubicacion = obtenerUbicacionParaEstado()
        val conduciendo = detectarConduccion(ubicacion)
        return EstadoDispositivo(
            ubicacion = ubicacion,
            cargando = estaCargando(),
            conduciendo = conduciendo
        )
    }

    /**
     * Obtiene la localidad actual con Geocoder. Debe llamarse desde un hilo
     * de fondo. Si falla, no hay red o Geocoder no esta disponible, devuelve
     * una cadena vacia.
     */
    @Suppress("DEPRECATION")
    fun obtenerMunicipioActual(ubicacion: Ubicacion): String {
        val cache = municipioCache
        val ahora = System.currentTimeMillis()
        if (cache != null &&
            cache.municipio.isNotEmpty() &&
            ahora - cache.timestamp in 0..MUNICIPIO_CACHE_MS &&
            distanciaKm(
                cache.latitud,
                cache.longitud,
                ubicacion.latitud,
                ubicacion.longitud
            ) <= MUNICIPIO_CACHE_KM
        ) {
            return cache.municipio
        }

        if (!Geocoder.isPresent()) return ""

        return try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            val direcciones = geocoder.getFromLocation(
                ubicacion.latitud,
                ubicacion.longitud,
                1
            )
            val direccion = direcciones?.firstOrNull()
            val localidad = direccion?.locality?.trim().orEmpty()
            val municipio = if (localidad.isNotEmpty()) {
                localidad
            } else {
                direccion?.subAdminArea?.trim().orEmpty()
            }

            if (municipio.isNotEmpty()) {
                municipioCache = MunicipioCache(
                    latitud = ubicacion.latitud,
                    longitud = ubicacion.longitud,
                    timestamp = ahora,
                    municipio = municipio
                )
            }
            municipio
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo geocodificar la ubicacion actual", e)
            ""
        }
    }

    /**
     * Indica si la cache de red ha caducado para el modo actual.
     */
    fun debeDescargar(estado: EstadoDispositivo): Boolean {
        if (!archivoCache.exists()) return true

        val timestamp = leerTimestamp()
        if (timestamp <= 0L) return true

        val edad = System.currentTimeMillis() - timestamp
        if (edad < 0L) return true
        return edad >= ttlRedMs(estado.modo)
    }

    /**
     * Indica si toca recalcular distancias desde el JSON cacheado.
     */
    fun debeRecalcular(estado: EstadoDispositivo): Boolean {
        if (prefsCache.getBoolean(KEY_FORCE_RECALC, false)) return true

        val latitudAnterior = leerLatitudRecalculo() ?: return true
        val longitudAnterior = leerLongitudRecalculo() ?: return true
        val tiempoAnterior = prefsCache.getLong(KEY_RECALC_TIME, 0L)
        if (tiempoAnterior <= 0L) return true

        val distancia = distanciaKm(
            estado.ubicacion.latitud,
            estado.ubicacion.longitud,
            latitudAnterior,
            longitudAnterior
        )
        val ahora = System.currentTimeMillis()

        return when (estado.modo) {
            ModoActualizacion.REPOSO ->
                distancia > RECALC_REPOSO_KM

            ModoActualizacion.CONDUCIENDO_BATERIA ->
                distancia > RECALC_CONDUCCION_BATERIA_KM ||
                    ahora - tiempoAnterior >= RECALC_CONDUCCION_BATERIA_MS

            ModoActualizacion.CONDUCIENDO_CARGANDO ->
                distancia > RECALC_CONDUCCION_CARGA_KM ||
                    ahora - tiempoAnterior >= RECALC_CONDUCCION_CARGA_MS
        }
    }

    /**
     * Carga la lista que debe mostrarse. Si no toca recalcular, usa las
     * coordenadas del ultimo recalculo para no cambiar distancias ni orden.
     */
    fun cargarListaParaMostrar(
        claveCombustible: String,
        estado: EstadoDispositivo,
        recalcular: Boolean,
        municipioActual: String,
        filtroRed: String = WidgetNetworkPreferences.TODAS
    ): ResultadoLista {
        val ubicacionRecalculo = leerUbicacionRecalculo()
        val coordenadas = if (recalcular) {
            estado.ubicacion
        } else {
            ubicacionRecalculo ?: estado.ubicacion
        }

        val cache = cargarDesdeCache(
            claveCombustible,
            coordenadas,
            LIMITE_CANDIDATOS
        )
        val base = cache.gasolineras
        val hayCache = archivoCache.exists() && leerTimestamp() > 0L

        if (base.isEmpty()) {
            return ResultadoLista(emptyList(), hayCache)
        }

        if (recalcular || ubicacionRecalculo == null) {
            registrarRecalculo(coordenadas)
        }

        val filtradasRed = base.filter { pasaFiltroRed(it, filtroRed) }
        if (filtradasRed.isEmpty()) {
            return ResultadoLista(emptyList(), hayCache)
        }

        val momento = momentoActual()
        val abiertas = filtradasRed.filter { estaAbiertaAhora(it.horario, momento) }
        if (abiertas.isEmpty()) {
            return ResultadoLista(emptyList(), hayCache)
        }

        val velocidadKmh = velocidadParaRuta(estado.ubicacion)
        val conRuta = aplicarContextoRuta(abiertas, municipioActual, velocidadKmh)
        val conAcceso = conRuta.map { gasolinera ->
            gasolinera.copy(
                puntuacionAcceso = calcularPuntuacionAcceso(gasolinera)
            )
        }

        val precioMedio = abiertas.map { it.precio }.average()
        val provinciaReferencia = normalizarTexto(
            base.minByOrNull { it.distancia }?.provincia.orEmpty()
        )
        // Referencia unica para el ahorro y el color de la pastilla: media
        // provincial de todas las estaciones validas del carburante. No se
        // calcula sobre las filas mostradas; el fallback solo cubre el caso
        // de que la provincia no este disponible.
        val mediaProvincial = cache.mediasProvinciales[provinciaReferencia]
            ?: precioMedio

        val ordenadas = conAcceso.sortedWith(
            compareBy<Gasolinera> {
                it.distancia - ((precioMedio - it.precio) * FACTOR_AHORRO.toDouble())
            }
                .thenBy { it.distancia }
                .thenBy { it.nombre }
        )
        val seleccionadas = ordenadas.take(LIMITE_MOSTRAR)
        val idMasCercana = seleccionadas.minByOrNull { it.distancia }?.id.orEmpty()
        val idMasLejana = seleccionadas.maxByOrNull { it.distancia }?.id.orEmpty()

        return ResultadoLista(
            gasolineras = seleccionadas,
            hayCache = hayCache,
            precioMedio = precioMedio,
            mediaProvincial = mediaProvincial,
            idMasCercana = idMasCercana,
            idMasLejana = idMasLejana
        )
    }

    private fun velocidadParaRuta(ubicacion: Ubicacion): Double {
        if (ubicacion.timestampMillis <= 0L) return 0.0

        val edad = System.currentTimeMillis() - ubicacion.timestampMillis
        if (edad !in 0..MAX_EDAD_LECTURA_MS) return 0.0

        val velocidad = ubicacion.velocidadMps ?: return 0.0
        return velocidad * 3.6
    }

    private fun aplicarContextoRuta(
        gasolineras: List<Gasolinera>,
        municipioActual: String,
        velocidadKmh: Double
    ): List<Gasolinera> {
        val municipioNormalizado = normalizarTexto(municipioActual)

        return gasolineras.map { gasolinera ->
            val mismoMunicipio = municipiosCoinciden(
                municipioNormalizado,
                normalizarTexto(gasolinera.municipio)
            )
            val viaRapida = esViaRapida(gasolinera.direccion)

            val etiqueta: String
            val penalizacion: Double
            val aDesmano: Boolean

            when {
                mismoMunicipio -> {
                    etiqueta = "En zona"
                    penalizacion = 0.0
                    aDesmano = false
                }

                velocidadKmh >= 50.0 && viaRapida -> {
                    etiqueta = "En ruta"
                    penalizacion = 0.0
                    aDesmano = false
                }

                velocidadKmh >= 50.0 -> {
                    etiqueta = "Desvio"
                    penalizacion = PENALIZACION_DESVIO_KM
                    aDesmano = true
                }

                else -> {
                    etiqueta = "Cercana"
                    penalizacion = 0.0
                    aDesmano = false
                }
            }

            gasolinera.copy(
                etiquetaRuta = etiqueta,
                penalizacionKm = penalizacion,
                aDesmano = aDesmano
            )
        }
    }

    private fun municipiosCoinciden(actual: String, estacion: String): Boolean {
        if (actual.isEmpty() || estacion.isEmpty()) return false
        if (actual == estacion) return true

        if (actual.length >= 4 && estacion.length >= 4) {
            return actual.contains(estacion) || estacion.contains(actual)
        }
        return false
    }

    private fun esViaRapida(direccion: String): Boolean {
        val texto = normalizarTexto(direccion)
        if (texto.contains("AUTOVIA") || texto.contains("AUTOPISTA")) {
            return true
        }
        return REGEX_VIA_RAPIDA.containsMatchIn(texto)
    }

    fun esTradicional(rotulo: String): Boolean {
        val nombre = normalizarTexto(rotulo)
        if (nombre.isEmpty()) return false
        return MARCAS_TRADICIONALES.any { nombre.contains(it) }
    }

    private fun pasaFiltroRed(gasolinera: Gasolinera, filtroRed: String): Boolean {
        val tradicional = esTradicional(gasolinera.nombre)
        return when (filtroRed) {
            WidgetNetworkPreferences.TRADICIONAL -> tradicional
            WidgetNetworkPreferences.LOWCOST -> !tradicional
            else -> true
        }
    }

    private fun normalizarTexto(valor: String): String {
        if (valor.isEmpty()) return ""

        val sinAcentos = Normalizer.normalize(valor, Normalizer.Form.NFD)
            .replace(REGEX_DIACRITICOS, "")
        return sinAcentos.uppercase(Locale.ROOT).trim()
    }

    private fun calcularPuntuacionAcceso(gasolinera: Gasolinera): Int {
        if (gasolinera.etiquetaRuta == "En zona" ||
            gasolinera.etiquetaRuta == "En ruta" ||
            esViaRapida(gasolinera.direccion)
        ) {
            return 5
        }

        if (!gasolinera.aDesmano && gasolinera.distancia < 2.0) return 4
        if (!gasolinera.aDesmano) return 3
        if (gasolinera.distancia < 10.0) return 2
        return 1
    }

    private fun momentoActual(): MomentoActual {
        val calendario = Calendar.getInstance()
        val dia = when (calendario.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }
        val minutos = calendario.get(Calendar.HOUR_OF_DAY) * 60 +
            calendario.get(Calendar.MINUTE)
        return MomentoActual(dia, minutos)
    }

    private fun estaAbiertaAhora(horario: String, momento: MomentoActual): Boolean {
        if (horario.isBlank()) return true

        val normalizado = normalizarTexto(horario)
        if (normalizado.contains("24H")) return true

        val segmentos = normalizado.split(Regex("[;\\n]+"))
        var horarioReconocido = false

        for (segmento in segmentos) {
            val texto = segmento.trim()
            if (texto.isEmpty()) continue

            val partes = texto.split(":", limit = 2)
            val diasTexto = if (partes.size == 2) partes[0].trim() else ""
            val horasTexto = if (partes.size == 2) partes[1].trim() else texto
            val dias = diasAplicables(diasTexto)
            val aplicaHoy = dias == null || momento.dia in dias

            if (horasTexto.contains("CERRADO")) {
                if (aplicaHoy) horarioReconocido = true
                continue
            }

            val rangos = REGEX_HORARIO.findAll(horasTexto).toList()
            if (rangos.isNotEmpty()) horarioReconocido = true
            if (!aplicaHoy) continue

            for (rango in rangos) {
                val inicio = rango.groupValues[1].toInt() * 60 +
                    rango.groupValues[2].toInt()
                val fin = rango.groupValues[3].toInt() * 60 +
                    rango.groupValues[4].toInt()

                if (inicio !in 0..1439 || fin !in 0..1439) continue

                val dentro = when {
                    fin > inicio -> momento.minutos >= inicio &&
                        momento.minutos < fin

                    fin < inicio -> momento.minutos >= inicio ||
                        momento.minutos < fin

                    else -> false
                }
                if (dentro) return true
            }
        }

        if (!horarioReconocido) return true
        return false
    }

    private fun diasAplicables(diasTexto: String): Set<Int>? {
        val texto = diasTexto.trim()
        if (texto.isEmpty()) return null
        if (texto == "LABORABLES") return (0..4).toSet()
        if (texto == "FIN DE SEMANA") return setOf(5, 6)

        val preparado = texto.replace(" A ", "-")
        if (preparado.contains("-")) {
            val partes = preparado.split("-", limit = 2)
            val inicio = diaDesdeCodigo(partes[0].trim())
            val fin = diaDesdeCodigo(partes[1].trim())
            if (inicio != null && fin != null) {
                return if (inicio <= fin) {
                    (inicio..fin).toSet()
                } else {
                    (inicio..6).toSet() + (0..fin).toSet()
                }
            }
        }

        val dias = mutableSetOf<Int>()
        REGEX_DIAS.findAll(preparado).forEach { coincidencia ->
            diaDesdeCodigo(coincidencia.value)?.let { dias.add(it) }
        }
        return if (dias.isEmpty()) null else dias
    }

    private fun diaDesdeCodigo(codigo: String): Int? {
        return when (codigo) {
            "L", "LU", "LUN", "LUNES" -> 0
            "M", "MA", "MAR", "MARTES" -> 1
            "X", "MI", "MIE", "MIERCOLES" -> 2
            "J", "JU", "JUE", "JUEVES" -> 3
            "V", "VI", "VIE", "VIERNES" -> 4
            "S", "SA", "SAB", "SABADO" -> 5
            "D", "DO", "DOM", "DOMINGO" -> 6
            else -> null
        }
    }

    /**
     * Fuerza un recalculo de distancias en la proxima actualizacion.
     */
    fun solicitarRefrescoManual() {
        prefsCache.edit().putBoolean(KEY_FORCE_RECALC, true).apply()
    }

    /**
     * Lanza una descarga de precios en segundo plano.
     *
     * forceDownload = true ignora la expiracion de la cache y sobrescribe
     * siempre el archivo local con el JSON del Ministerio. El callback
     * recibe true solo cuando el JSON se ha descargado, validado y
     * guardado correctamente.
     */
    fun actualizarEnSegundoPlano(
        forceDownload: Boolean = false,
        onFinalizado: (Boolean) -> Unit
    ) {
        if (!descargaEnCurso.compareAndSet(false, true)) {
            Log.w(TAG, "Ya hay una descarga de precios en curso")
            onFinalizado(false)
            return
        }

        if (!forceDownload && !debeDescargar(obtenerEstadoDispositivo())) {
            descargaEnCurso.set(false)
            onFinalizado(false)
            return
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Accept", "application/json")
            .header("User-Agent", "GasolinerasWidget/1.0")
            .build()

        try {
            clienteHttp.newCall(request).enqueue(object : Callback {

                override fun onFailure(call: Call, e: IOException) {
                    descargaEnCurso.set(false)
                    Log.w(
                        TAG,
                        "Descarga fallida; se mantiene la cache anterior",
                        e
                    )
                    notificarResultado(onFinalizado, false)
                }

                override fun onResponse(call: Call, response: Response) {
                    val actualizado = try {
                        response.use { respuesta ->
                            if (!respuesta.isSuccessful) {
                                throw IOException("Respuesta HTTP ${respuesta.code}")
                            }
                            val json = respuesta.body?.string()
                                ?: throw IOException("Respuesta vacia")
                            validarJson(json)
                            guardarCache(json)
                        }
                        Log.i(TAG, "Cache de precios actualizada")
                        true
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "No se pudo guardar la cache; se mantiene la anterior",
                            e
                        )
                        false
                    } finally {
                        descargaEnCurso.set(false)
                    }

                    notificarResultado(onFinalizado, actualizado)
                }
            })
        } catch (e: Exception) {
            descargaEnCurso.set(false)
            Log.e(TAG, "No se pudo iniciar la descarga", e)
            notificarResultado(onFinalizado, false)
        }
    }

    /**
     * Devuelve el instante de la ultima descarga correcta, o 0 si aun no
     * existe una cache valida.
     */
    fun obtenerTimestampCache(): Long = leerTimestamp()

    private fun notificarResultado(
        onFinalizado: (Boolean) -> Unit,
        actualizado: Boolean
    ) {
        try {
            onFinalizado(actualizado)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo notificar el resultado de la descarga", e)
        }
    }

    /**
     * Carga de forma sincrona la lista desde la cache local. No hace red.
     */
    @Synchronized
    private fun cargarDesdeCache(
        claveCombustible: String,
        ubicacion: Ubicacion = UBICACION_DONOSTIA,
        limite: Int = 5
    ): ListaCache {
        if (claveCombustible.isBlank()) return ListaCache(emptyList(), emptyMap())

        val timestamp = leerTimestamp()
        if (timestamp <= 0L) return ListaCache(emptyList(), emptyMap())

        val json = leerJsonCache(timestamp) ?: return ListaCache(emptyList(), emptyMap())
        val claveLista = ClaveLista(
            claveCombustible = claveCombustible,
            latitud = ubicacion.latitud,
            longitud = ubicacion.longitud,
            limite = limite,
            timestamp = timestamp
        )

        listasEnMemoria[claveLista]?.let { return it }

        return try {
            val cache = calcularDesdeJson(
                json = json,
                claveCombustible = claveCombustible,
                latitudReferencia = ubicacion.latitud,
                longitudReferencia = ubicacion.longitud,
                limite = limite
            )
            listasEnMemoria = listasEnMemoria + (claveLista to cache)
            cache
        } catch (e: Exception) {
            Log.e(TAG, "La cache local no es valida; se eliminara", e)
            borrarCache()
            ListaCache(emptyList(), emptyMap())
        }
    }

    private fun obtenerUbicacionParaEstado(): Ubicacion {
        val fresca = ubicacionFresca
        if (fresca != null) {
            ubicacionFresca = null
            return fresca
        }

        if (!tienePermisoUbicacion()) {
            return UBICACION_DONOSTIA
        }

        val actual = leerUltimaUbicacionDispositivo()
        val conocida = leerUbicacionConocida()
        val elegida = when {
            actual == null -> conocida
            conocida == null -> actual
            conocida.timestampMillis > actual.timestampMillis -> conocida
            else -> actual
        }

        if (elegida != null) {
            guardarUbicacionConocida(elegida)
            return elegida
        }
        return UBICACION_DONOSTIA
    }

    private fun tienePermisoUbicacion(): Boolean {
        val permisoFino = appContext.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val permisoGrueso = appContext.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return permisoFino || permisoGrueso
    }

    @SuppressLint("MissingPermission")
    private fun leerUltimaUbicacionDispositivo(): Ubicacion? {
        val gestor = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val proveedores = try {
            gestor.getProviders(true)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para consultar proveedores de ubicacion", e)
            return null
        }

        var mejor: Location? = null

        for (proveedor in proveedores) {
            val ubicacion = try {
                gestor.getLastKnownLocation(proveedor)
            } catch (e: SecurityException) {
                Log.w(TAG, "Sin permiso para leer la ubicacion", e)
                null
            } catch (e: IllegalArgumentException) {
                null
            }

            if (ubicacion != null &&
                ubicacion.latitude in -90.0..90.0 &&
                ubicacion.longitude in -180.0..180.0
            ) {
                val anterior = mejor
                if (anterior == null || ubicacion.time > anterior.time) {
                    mejor = ubicacion
                }
            }
        }

        return mejor?.let {
            Ubicacion(
                latitud = it.latitude,
                longitud = it.longitude,
                velocidadMps = if (it.hasSpeed()) it.speed else null,
                timestampMillis = it.time
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun obtenerUbicacionFresca(): Ubicacion? {
        if (!tienePermisoUbicacion()) return null

        val gestor = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val proveedor = elegirProveedor(gestor)

        val ubicacion = if (proveedor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                obtenerUbicacionActual(gestor, proveedor)
            } else {
                obtenerUbicacionSingleUpdate(gestor, proveedor)
            }
        } else {
            null
        }

        return ubicacion?.let {
            Ubicacion(
                latitud = it.latitude,
                longitud = it.longitude,
                velocidadMps = if (it.hasSpeed()) it.speed else null,
                timestampMillis = it.time
            )
        } ?: leerUltimaUbicacionDispositivo() ?: leerUbicacionConocida()
    }

    @SuppressLint("MissingPermission")
    private fun elegirProveedor(gestor: LocationManager): String? {
        val permisoFino = appContext.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val gpsActivo = try {
            gestor.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        val redActiva = try {
            gestor.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }

        return when {
            permisoFino && gpsActivo -> LocationManager.GPS_PROVIDER
            redActiva -> LocationManager.NETWORK_PROVIDER
            else -> try {
                gestor.getProviders(true).firstOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }

    // Solo se invoca con API 30 o superior desde obtenerUbicacionFresca.
    @SuppressLint("MissingPermission", "NewApi")
    private fun obtenerUbicacionActual(
        gestor: LocationManager,
        proveedor: String
    ): Location? {
        val latido = CountDownLatch(1)
        val resultado = AtomicReference<Location?>(null)
        val cancelacion = CancellationSignal()

        return try {
            gestor.getCurrentLocation(
                proveedor,
                cancelacion,
                appContext.mainExecutor
            ) { location ->
                resultado.set(location)
                latido.countDown()
            }
            latido.await(FRESH_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            resultado.get()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener la ubicacion actual", e)
            null
        } finally {
            cancelacion.cancel()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun obtenerUbicacionSingleUpdate(
        gestor: LocationManager,
        proveedor: String
    ): Location? {
        val latido = CountDownLatch(1)
        val resultado = AtomicReference<Location?>(null)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                resultado.set(location)
                latido.countDown()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {
            }

            override fun onProviderDisabled(provider: String) {
                latido.countDown()
            }
        }

        return try {
            gestor.requestSingleUpdate(proveedor, listener, Looper.getMainLooper())
            latido.await(FRESH_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            resultado.get()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener la ubicacion con una sola lectura", e)
            null
        } finally {
            try {
                gestor.removeUpdates(listener)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron cancelar las actualizaciones", e)
            }
        }
    }

    private fun guardarUbicacionConocida(ubicacion: Ubicacion) {
        val timestamp = if (ubicacion.timestampMillis > 0L) {
            ubicacion.timestampMillis
        } else {
            System.currentTimeMillis()
        }

        prefsCache.edit()
            .putString(KEY_LAST_KNOWN_LAT, ubicacion.latitud.toString())
            .putString(KEY_LAST_KNOWN_LON, ubicacion.longitud.toString())
            .putLong(KEY_LAST_KNOWN_TIME, timestamp)
            .apply()
    }

    private fun leerUbicacionConocida(): Ubicacion? {
        val latitud = prefsCache.getString(KEY_LAST_KNOWN_LAT, null)
            ?.toDoubleOrNull()
            ?: return null
        val longitud = prefsCache.getString(KEY_LAST_KNOWN_LON, null)
            ?.toDoubleOrNull()
            ?: return null

        if (latitud !in -90.0..90.0 || longitud !in -180.0..180.0) {
            return null
        }
        return Ubicacion(
            latitud = latitud,
            longitud = longitud,
            timestampMillis = prefsCache.getLong(KEY_LAST_KNOWN_TIME, 0L)
        )
    }

    private fun detectarConduccion(ubicacion: Ubicacion): Boolean {
        val ahora = System.currentTimeMillis()
        val lecturaReciente = ubicacion.timestampMillis > 0L &&
            ahora - ubicacion.timestampMillis in 0..MAX_EDAD_LECTURA_MS

        var conduciendo = false

        if (lecturaReciente) {
            val velocidad = ubicacion.velocidadMps
            if (velocidad != null) {
                conduciendo = velocidad > VELOCIDAD_CONDUCCION_MPS
            } else {
                val latitudAnterior = prefsCache.getString(KEY_LAST_MOVE_LAT, null)
                    ?.toDoubleOrNull()
                val longitudAnterior = prefsCache.getString(KEY_LAST_MOVE_LON, null)
                    ?.toDoubleOrNull()
                val tiempoAnterior = prefsCache.getLong(KEY_LAST_MOVE_TIME, 0L)

                if (latitudAnterior != null &&
                    longitudAnterior != null &&
                    tiempoAnterior > 0L
                ) {
                    val segundos = (ubicacion.timestampMillis - tiempoAnterior) / 1000.0
                    if (segundos > 0.0) {
                        val distancia = distanciaKm(
                            latitudAnterior,
                            longitudAnterior,
                            ubicacion.latitud,
                            ubicacion.longitud
                        )
                        val velocidadKmh = distancia / (segundos / 3600.0)
                        conduciendo = velocidadKmh > VELOCIDAD_CONDUCCION_KMH
                    }
                }
            }
        }

        if (ubicacion.timestampMillis > 0L &&
            ubicacion.timestampMillis > prefsCache.getLong(KEY_LAST_MOVE_TIME, 0L)
        ) {
            prefsCache.edit()
                .putString(KEY_LAST_MOVE_LAT, ubicacion.latitud.toString())
                .putString(KEY_LAST_MOVE_LON, ubicacion.longitud.toString())
                .putLong(KEY_LAST_MOVE_TIME, ubicacion.timestampMillis)
                .apply()
        }

        if (conduciendo) {
            prefsCache.edit()
                .putLong(KEY_LAST_DRIVING_TIME, ahora)
                .apply()
            return true
        }

        val ultimaConduccion = prefsCache.getLong(KEY_LAST_DRIVING_TIME, 0L)
        return ahora - ultimaConduccion < CONDUCCION_HOLD_MS
    }

    @Suppress("DEPRECATION")
    private fun estaCargando(): Boolean {
        val intent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || plugged > 0
    }

    private fun ttlRedMs(modo: ModoActualizacion): Long {
        return when (modo) {
            ModoActualizacion.REPOSO -> TTL_REPOSO_MS
            ModoActualizacion.CONDUCIENDO_BATERIA -> TTL_CONDUCCION_BATERIA_MS
            ModoActualizacion.CONDUCIENDO_CARGANDO -> TTL_CONDUCCION_CARGA_MS
        }
    }

    private fun registrarRecalculo(ubicacion: Ubicacion) {
        prefsCache.edit()
            .putString(KEY_RECALC_LAT, ubicacion.latitud.toString())
            .putString(KEY_RECALC_LON, ubicacion.longitud.toString())
            .putLong(KEY_RECALC_TIME, System.currentTimeMillis())
            .putBoolean(KEY_FORCE_RECALC, false)
            .apply()
    }

    private fun leerUbicacionRecalculo(): Ubicacion? {
        val latitud = leerLatitudRecalculo() ?: return null
        val longitud = leerLongitudRecalculo() ?: return null
        return Ubicacion(latitud, longitud)
    }

    private fun leerLatitudRecalculo(): Double? {
        val latitud = prefsCache.getString(KEY_RECALC_LAT, null)
            ?.toDoubleOrNull()
            ?: return null
        if (latitud !in -90.0..90.0) return null
        return latitud
    }

    private fun leerLongitudRecalculo(): Double? {
        val longitud = prefsCache.getString(KEY_RECALC_LON, null)
            ?.toDoubleOrNull()
            ?: return null
        if (longitud !in -180.0..180.0) return null
        return longitud
    }

    private fun validarJson(json: String) {
        val estaciones = JSONObject(json).optJSONArray("ListaEESSPrecio")
        if (estaciones == null || estaciones.length() == 0) {
            throw IOException("JSON sin estaciones")
        }
    }

    private fun guardarCache(json: String) {
        val temporal = File(appContext.filesDir, CACHE_FILE_NAME + ".tmp")

        try {
            temporal.writeText(json)
            try {
                Files.move(
                    temporal.toPath(),
                    archivoCache.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: Exception) {
                Files.move(
                    temporal.toPath(),
                    archivoCache.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            val timestamp = System.currentTimeMillis()
            val guardado = prefsCache.edit()
                .putLong(KEY_LAST_SUCCESS, timestamp)
                .commit()
            if (!guardado) {
                throw IOException("No se pudo guardar el timestamp")
            }

            jsonEnMemoria = json
            timestampJsonEnMemoria = timestamp
            listasEnMemoria = emptyMap()
        } finally {
            if (temporal.exists()) {
                temporal.delete()
            }
        }
    }

    private fun leerJsonCache(timestamp: Long): String? {
        if (jsonEnMemoria != null && timestampJsonEnMemoria == timestamp) {
            return jsonEnMemoria
        }
        if (!archivoCache.exists()) return null

        return try {
            val json = archivoCache.readText()
            jsonEnMemoria = json
            timestampJsonEnMemoria = timestamp
            json
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo leer la cache local; se eliminara", e)
            borrarCache()
            null
        }
    }

    private fun leerTimestamp(): Long {
        return prefsCache.getLong(KEY_LAST_SUCCESS, 0L)
    }

    private fun borrarCache() {
        archivoCache.delete()
        prefsCache.edit()
            .remove(KEY_LAST_SUCCESS)
            .commit()
        jsonEnMemoria = null
        timestampJsonEnMemoria = 0L
        listasEnMemoria = emptyMap()
    }

    private fun calcularDesdeJson(
        json: String,
        claveCombustible: String,
        latitudReferencia: Double,
        longitudReferencia: Double,
        limite: Int
    ): ListaCache {
        val estaciones = JSONObject(json).optJSONArray("ListaEESSPrecio")
            ?: throw IOException("La respuesta no contiene ListaEESSPrecio")

        val sumasProvincia = mutableMapOf<String, Double>()
        val conteosProvincia = mutableMapOf<String, Int>()

        val candidatas = buildList {
            for (indice in 0 until estaciones.length()) {
                val estacion = estaciones.optJSONObject(indice) ?: continue
                val nombre = estacion.optString("R\u00f3tulo").trim()
                if (nombre.isEmpty()) continue

                val precio = leerPrecio(estacion, claveCombustible) ?: continue
                val provincia = estacion.optString("Provincia").trim()

                if (provincia.isNotEmpty()) {
                    val claveProvincia = normalizarTexto(provincia)
                    sumasProvincia[claveProvincia] =
                        (sumasProvincia[claveProvincia] ?: 0.0) + precio
                    conteosProvincia[claveProvincia] =
                        (conteosProvincia[claveProvincia] ?: 0) + 1
                }

                val latitud = leerDecimal(estacion.optString("Latitud")) ?: continue
                val longitud = leerDecimal(estacion.optString("Longitud (WGS84)")) ?: continue

                if (latitud !in -90.0..90.0 || longitud !in -180.0..180.0) continue
                if (latitud == 0.0 && longitud == 0.0) continue

                val direccion = estacion.optString("Direcci\u00f3n").trim()
                    .ifEmpty { estacion.optString("Direccion").trim() }
                val municipio = estacion.optString("Municipio").trim()
                val horario = estacion.optString("Horario").trim()
                val direccionFormateada = formatearDireccion(direccion, municipio)
                val id = estacion.optString("IDEESS").trim()
                    .ifEmpty { nombre + "_" + latitud + "_" + longitud }

                add(
                    Gasolinera(
                        id = id,
                        nombre = nombre,
                        precio = precio,
                        distancia = distanciaKm(
                            latitudReferencia,
                            longitudReferencia,
                            latitud,
                            longitud
                        ),
                        direccion = direccionFormateada,
                        municipio = municipio,
                        provincia = provincia,
                        horario = horario,
                        latitud = latitud,
                        longitud = longitud,
                        etiquetaRuta = "",
                        penalizacionKm = 0.0,
                        aDesmano = false,
                        puntuacionAcceso = 0
                    )
                )
            }
        }

        val mediasProvinciales = sumasProvincia.mapValues { (provincia, suma) ->
            suma / (conteosProvincia[provincia] ?: 1).toDouble()
        }

        if (candidatas.isEmpty()) {
            return ListaCache(emptyList(), mediasProvinciales)
        }

        val cercanas = candidatas
            .sortedBy { it.distancia }
            .take(limite.coerceAtLeast(1))

        return ListaCache(cercanas, mediasProvinciales)
    }

    private fun formatearDireccion(direccion: String, municipio: String): String {
        val partes = listOf(direccion, municipio).filter { it.isNotEmpty() }
        return if (partes.isEmpty()) {
            "Direccion no disponible"
        } else {
            partes.joinToString(", ")
        }
    }

    private fun leerPrecio(estacion: JSONObject, claveCombustible: String): Double? {
        var valor = estacion.optString(claveCombustible)
        if (valor.isBlank() && claveCombustible == FuelOptions.GLP) {
            // Algunas respuestas publican la clave sin tilde.
            valor = estacion.optString("Precio Gases licuados del petroleo")
        }
        val precio = leerDecimal(valor)
        return precio?.takeIf { it > 0.0 }
    }

    private fun leerDecimal(valor: String): Double? {
        val normalizado = valor.trim().replace(',', '.')
        if (normalizado.isEmpty()) return null
        return normalizado.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun distanciaKm(
        latitudOrigen: Double,
        longitudOrigen: Double,
        latitudDestino: Double,
        longitudDestino: Double
    ): Double {
        val radioTierraKm = 6371.0088
        val deltaLatitud = Math.toRadians(latitudDestino - latitudOrigen)
        val deltaLongitud = Math.toRadians(longitudDestino - longitudOrigen)

        val a = sin(deltaLatitud / 2).let { it * it } +
            cos(Math.toRadians(latitudOrigen)) *
            cos(Math.toRadians(latitudDestino)) *
            sin(deltaLongitud / 2).let { it * it }

        return 2 * radioTierraKm * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private data class MomentoActual(
        val dia: Int,
        val minutos: Int
    )

    private data class MunicipioCache(
        val latitud: Double,
        val longitud: Double,
        val timestamp: Long,
        val municipio: String
    )

    private data class ListaCache(
        val gasolineras: List<Gasolinera>,
        val mediasProvinciales: Map<String, Double>
    )

    private data class ClaveLista(
        val claveCombustible: String,
        val latitud: Double,
        val longitud: Double,
        val limite: Int,
        val timestamp: Long
    )

    companion object {
        val MARCAS_TRADICIONALES = setOf(
            "REPSOL",
            "CAMPSA",
            "PETRONOR",
            "CEPSA",
            "MOEVE",
            "BP",
            "SHELL",
            "GALP",
            "AVIA",
            "TOTAL"
        )

        private const val TAG = "GasolineraRepository"
        private const val ENDPOINT =
            "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/" +
                "PreciosCarburantes/EstacionesTerrestres/"
        private const val CACHE_FILE_NAME = "gasolineras_api_cache.json"
        private const val CACHE_PREFS_NAME = "gasolineras_cache_prefs"
        private const val KEY_LAST_SUCCESS = "last_success_millis"
        private const val KEY_LAST_KNOWN_LAT = "last_known_lat"
        private const val KEY_LAST_KNOWN_LON = "last_known_lon"
        private const val KEY_LAST_KNOWN_TIME = "last_known_time"
        private const val KEY_RECALC_LAT = "recalc_lat"
        private const val KEY_RECALC_LON = "recalc_lon"
        private const val KEY_RECALC_TIME = "recalc_time"
        private const val KEY_FORCE_RECALC = "force_recalc"
        private const val KEY_LAST_MOVE_LAT = "last_move_lat"
        private const val KEY_LAST_MOVE_LON = "last_move_lon"
        private const val KEY_LAST_MOVE_TIME = "last_move_time"
        private const val KEY_LAST_DRIVING_TIME = "last_driving_time"
        private const val KEY_FORCE_FRESH_LOCATION = "force_fresh_location"

        private const val TTL_REPOSO_MS = 60L * 60L * 1000L
        private const val TTL_CONDUCCION_BATERIA_MS = 45L * 60L * 1000L
        private const val TTL_CONDUCCION_CARGA_MS = 30L * 60L * 1000L

        private const val RECALC_REPOSO_KM = 5.0
        private const val RECALC_CONDUCCION_BATERIA_KM = 3.0
        private const val RECALC_CONDUCCION_CARGA_KM = 1.0
        private const val RECALC_CONDUCCION_BATERIA_MS = 4L * 60L * 1000L
        private const val RECALC_CONDUCCION_CARGA_MS = 60L * 1000L

        private const val VELOCIDAD_CONDUCCION_MPS = 5.5f
        private const val VELOCIDAD_CONDUCCION_KMH = 19.8
        private const val MAX_EDAD_LECTURA_MS = 10L * 60L * 1000L
        private const val CONDUCCION_HOLD_MS = 5L * 60L * 1000L
        private const val FRESH_LOCATION_TIMEOUT_SECONDS = 4L

        private const val PENALIZACION_DESVIO_KM = 15.0
        private const val FACTOR_AHORRO = 10.0f
        private const val MUNICIPIO_CACHE_MS = 15L * 60L * 1000L
        private const val MUNICIPIO_CACHE_KM = 2.0
        private const val LIMITE_CANDIDATOS = 100
        private const val LIMITE_MOSTRAR = 5
        private val REGEX_DIACRITICOS = Regex("\\p{M}+")
        private val REGEX_VIA_RAPIDA = Regex("(^|\\s)(A-|AP-|N-)[0-9]")
        private val REGEX_HORARIO =
            Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")
        private val REGEX_DIAS = Regex("[A-Z]+")

        private val UBICACION_DONOSTIA = Ubicacion(43.3183, -1.9812)

        private val descargaEnCurso = AtomicBoolean(false)

        private val clienteHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
