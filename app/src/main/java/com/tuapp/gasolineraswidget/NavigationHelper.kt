package com.tuapp.gasolineraswidget

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.Locale

/**
 * Lanzamiento centralizado de la navegacion externa.
 *
 * La capsula flotante y el widget de escritorio llaman aqui para abrir una
 * gasolinera en el navegador GPS que el usuario esta usando, sin mostrar el
 * selector generico de Android siempre que sea posible.
 *
 * Orden de decision:
 * 1. Navegador forzado en los ajustes (Google Maps, Waze, Sygic o preguntar).
 * 2. En modo automatico, el navegador compatible que estuvo en primer plano
 *    en los ultimos minutos, si hay permiso de acceso de uso.
 * 3. Si no hay ninguno activo, el unico navegador instalado o Google Maps
 *    cuando hay varios; si no hay certeza, el Intent geo generico.
 *
 * Cada lanzamiento va protegido con try/catch y termina en el Intent geo
 * generico, de modo que nunca se propaga ActivityNotFoundException.
 */
object NavigationHelper {

    const val EXTRA_LATITUD = "navigation_latitud"
    const val EXTRA_LONGITUD = "navigation_longitud"
    const val EXTRA_NOMBRE = "navigation_nombre_estacion"

    const val PAQUETE_GOOGLE_MAPS = "com.google.android.apps.maps"
    const val PAQUETE_WAZE = "com.waze"
    const val PAQUETE_SYGIC_AURA = "com.sygic.aura"
    const val PAQUETE_SYGIC_TRUCK = "com.sygic.truck"
    const val PAQUETE_TOMTOM = "com.tomtom.gplay.navapp"
    const val PAQUETE_OSMAND = "net.osmand"
    const val PAQUETE_OSMAND_PLUS = "net.osmand.plus"

    private const val TAG = "NavigationHelper"

    // Ventana para considerar que un navegador estaba en uso al tocar.
    private const val VENTANA_NAVEGADOR_ACTIVO_MS = 3L * 60L * 1000L

    // Sygic publica el mismo esquema en sus dos paquetes.
    private const val ESQUEMA_SYGIC = "com.sygic.aura"

    private enum class TipoNavegador { MAPS, WAZE, SYGIC, GENERICO }

    private data class Navegador(val paquete: String, val tipo: TipoNavegador)

    // Orden de preferencia para decidir cuando hay varios instalados.
    private val paquetesCompatibles = listOf(
        PAQUETE_GOOGLE_MAPS,
        PAQUETE_WAZE,
        PAQUETE_SYGIC_AURA,
        PAQUETE_SYGIC_TRUCK,
        PAQUETE_TOMTOM,
        PAQUETE_OSMAND,
        PAQUETE_OSMAND_PLUS
    )

    /**
     * Abre la gasolinera en el navegador mas probable. Devuelve true si se
     * pudo lanzar alguna aplicacion o el selector del sistema.
     */
    fun abrirNavegacionGasolinera(
        context: Context,
        lat: Double,
        lon: Double,
        nombreEstacion: String
    ): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) {
            Log.w(TAG, "Coordenadas no validas para la navegacion")
            return false
        }

        val app = context.applicationContext
        val navegador = resolverNavegador(app)
        if (navegador != null) {
            val lanzado = when (navegador.tipo) {
                TipoNavegador.MAPS ->
                    lanzarMaps(app, lat, lon, nombreEstacion)

                TipoNavegador.WAZE ->
                    lanzarWaze(app, lat, lon)

                TipoNavegador.SYGIC ->
                    lanzarSygic(app, navegador.paquete, lat, lon, nombreEstacion)

                TipoNavegador.GENERICO ->
                    lanzarGeo(app, lat, lon, nombreEstacion, navegador.paquete)
            }
            if (lanzado) return true
            Log.w(TAG, "El navegador " + navegador.paquete + " no respondio; se usa el geo generico")
        }

        return lanzarConSelector(app, lat, lon, nombreEstacion)
    }

    /**
     * Indica si la app puede consultar el historial de uso; es el permiso
     * especial que concede el usuario en Ajustes.
     */
    @Suppress("DEPRECATION")
    fun tienePermisoUso(context: Context): Boolean {
        val gestor = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val modo = try {
            // Ambas consultas equivalen; unsafeCheckOpNoThrow evita el modo
            // noteOp y es la recomendada desde Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                gestor.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                gestor.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar el permiso de uso", e)
            return false
        }
        return modo == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Nombre visible del navegador que se abriria ahora mismo, o null si
     * habria que preguntar al usuario. La pantalla principal lo usa para
     * mostrar el resultado de la deteccion.
     */
    fun descripcionNavegador(context: Context): String? {
        val app = context.applicationContext
        val navegador = resolverNavegador(app) ?: return null
        return nombreVisible(app, navegador.paquete)
    }

    private fun resolverNavegador(context: Context): Navegador? {
        val preferido = NavigationPreferences.leer(context)

        // Un navegador forzado manda sobre cualquier deteccion.
        val forzado = navegadorPreferido(context, preferido)
        if (forzado != null) return forzado
        if (preferido == NavigationPreferences.PREGUNTAR) return null

        val activo = navegadorActivoReciente(context)
        if (activo != null) return activo

        return navegadorInstalado(context)
    }

    private fun navegadorPreferido(context: Context, preferido: String): Navegador? {
        return when (preferido) {
            NavigationPreferences.GOOGLE_MAPS ->
                crearNavegador(context, PAQUETE_GOOGLE_MAPS)

            NavigationPreferences.WAZE ->
                crearNavegador(context, PAQUETE_WAZE)

            NavigationPreferences.SYGIC ->
                crearNavegador(context, PAQUETE_SYGIC_AURA)
                    ?: crearNavegador(context, PAQUETE_SYGIC_TRUCK)

            else -> null
        }
    }

    private fun navegadorActivoReciente(context: Context): Navegador? {
        if (!tienePermisoUso(context)) return null

        val gestor = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null

        val ahora = System.currentTimeMillis()
        val evento = UsageEvents.Event()
        var paquete: String? = null
        var instante = 0L

        try {
            val eventos = gestor.queryEvents(ahora - VENTANA_NAVEGADOR_ACTIVO_MS, ahora)
            while (eventos.hasNextEvent()) {
                eventos.getNextEvent(evento)
                // ACTIVITY_RESUMED comparte valor con MOVE_TO_FOREGROUND en
                // todas las versiones, asi que cubre tambien API 26 a 28.
                if (evento.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue

                val candidato = evento.packageName ?: continue
                if (candidato !in paquetesCompatibles) continue

                if (evento.timeStamp >= instante) {
                    instante = evento.timeStamp
                    paquete = candidato
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los eventos de uso", e)
            return null
        }

        return paquete?.let { crearNavegador(context, it) }
    }

    /**
     * Sin permisos extra: si solo hay un navegador compatible instalado se
     * usa; si hay varios se prefiere Google Maps; si no hay certeza se deja
     * que decida el Intent geo generico.
     */
    private fun navegadorInstalado(context: Context): Navegador? {
        val instalados = paquetesCompatibles.filter { estaInstalado(context, it) }
        return when {
            instalados.isEmpty() -> null
            instalados.size == 1 -> crearNavegador(context, instalados.first())
            else -> crearNavegador(context, PAQUETE_GOOGLE_MAPS)
        }
    }

    private fun crearNavegador(context: Context, paquete: String): Navegador? {
        if (!estaInstalado(context, paquete)) return null
        return Navegador(paquete, tipoDe(paquete))
    }

    private fun tipoDe(paquete: String): TipoNavegador {
        return when (paquete) {
            PAQUETE_GOOGLE_MAPS -> TipoNavegador.MAPS
            PAQUETE_WAZE -> TipoNavegador.WAZE
            PAQUETE_SYGIC_AURA, PAQUETE_SYGIC_TRUCK -> TipoNavegador.SYGIC
            else -> TipoNavegador.GENERICO
        }
    }

    @Suppress("DEPRECATION")
    private fun estaInstalado(context: Context, paquete: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(paquete, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Google Maps: el marcador se pinta sobre la ruta activa y el usuario
     * puede anadirlo como parada sin perder el destino final.
     */
    private fun lanzarMaps(
        context: Context,
        lat: Double,
        lon: Double,
        nombre: String
    ): Boolean {
        val uri = Uri.parse(
            "geo:0,0?q=" + coordenada(lat) + "," + coordenada(lon) +
                "(" + Uri.encode(nombre) + ")"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(PAQUETE_GOOGLE_MAPS)
        return abrir(context, intent)
    }

    /**
     * Waze: con navigate=yes mantiene la ruta en curso y permite decidir si
     * la gasolinera se anade como parada intermedia.
     */
    private fun lanzarWaze(context: Context, lat: Double, lon: Double): Boolean {
        val uri = Uri.parse(
            "https://waze.com/ul?ll=" + coordenada(lat) + "," + coordenada(lon) +
                "&navigate=yes"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(PAQUETE_WAZE)
        return abrir(context, intent)
    }

    /**
     * Sygic espera LONGITUD|LATITUD, al reves que Maps y Waze. Se intenta el
     * modo drive, luego waypoint y por ultimo el esquema propio del paquete.
     */
    private fun lanzarSygic(
        context: Context,
        paquete: String,
        lat: Double,
        lon: Double,
        nombre: String
    ): Boolean {
        val coordenadaSygic = coordenada(lon) + "|" + coordenada(lat)
        val destinos = listOf("drive", "waypoint")

        for (destino in destinos) {
            val uri = Uri.parse(ESQUEMA_SYGIC + "://coordinate|" + coordenadaSygic + "|" + destino)
            if (abrir(context, Intent(Intent.ACTION_VIEW, uri).setPackage(paquete))) {
                return true
            }
        }

        val propio = Uri.parse(paquete + "://coordinate|" + coordenadaSygic + "|drive")
        if (abrir(context, Intent(Intent.ACTION_VIEW, propio).setPackage(paquete))) {
            return true
        }

        return lanzarGeo(context, lat, lon, nombre, null)
    }

    /**
     * Fallback universal: geo con el punto y el nombre, opcionalmente dirigido
     * a un paquete concreto (TomTom, OsmAnd u otros compatibles).
     */
    private fun lanzarGeo(
        context: Context,
        lat: Double,
        lon: Double,
        nombre: String,
        paquete: String?
    ): Boolean {
        val intent = intentGeo(lat, lon, nombre)
        if (paquete != null) intent.setPackage(paquete)
        return abrir(context, intent)
    }

    private fun lanzarConSelector(
        context: Context,
        lat: Double,
        lon: Double,
        nombre: String
    ): Boolean {
        val geo = intentGeo(lat, lon, nombre)
        val selector = Intent.createChooser(
            geo,
            context.getString(R.string.floating_nav_chooser)
        )
        if (abrir(context, selector)) return true
        if (abrir(context, geo)) return true
        return lanzarMapsWeb(context, lat, lon)
    }

    private fun intentGeo(lat: Double, lon: Double, nombre: String): Intent {
        val consulta = if (nombre.isBlank()) {
            coordenada(lat) + "," + coordenada(lon)
        } else {
            coordenada(lat) + "," + coordenada(lon) +
                "(" + Uri.encode(nombre) + ")"
        }
        val uri = Uri.parse(
            "geo:" + coordenada(lat) + "," + coordenada(lon) + "?q=" + consulta
        )
        return Intent(Intent.ACTION_VIEW, uri)
    }

    private fun lanzarMapsWeb(context: Context, lat: Double, lon: Double): Boolean {
        val url = "https://www.google.com/maps/search/?api=1&query=" +
            coordenada(lat) + "," + coordenada(lon)
        return abrir(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun abrir(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No hay aplicacion para " + intent)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para abrir " + intent, e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir " + intent, e)
            false
        }
    }

    private fun nombreVisible(context: Context, paquete: String): String {
        return when (paquete) {
            PAQUETE_GOOGLE_MAPS -> context.getString(R.string.nav_nombre_google_maps)
            PAQUETE_WAZE -> context.getString(R.string.nav_nombre_waze)
            PAQUETE_SYGIC_AURA, PAQUETE_SYGIC_TRUCK -> context.getString(R.string.nav_nombre_sygic)
            PAQUETE_TOMTOM -> context.getString(R.string.nav_nombre_tomtom)
            PAQUETE_OSMAND, PAQUETE_OSMAND_PLUS -> context.getString(R.string.nav_nombre_osmand)
            else -> context.getString(R.string.nav_nombre_generico)
        }
    }

    /**
     * Formato estable con punto decimal: el dispositivo puede estar en
     * espanol y un separador de coma romperia la URI.
     */
    private fun coordenada(valor: Double): String {
        return String.format(Locale.US, "%.6f", valor)
    }
}
