# GasolinerasWidget — Contexto Técnico y Funcional

> **Propósito de este documento:** Servir como memoria técnica de referencia en el directorio raíz del proyecto para retomar el desarrollo, depurar comportamientos o preparar la publicación en Google Play sin perder el contexto de las decisiones de diseño ya tomadas.

---

## 0. REGLA DE DESARROLLO OBLIGATORIA (ASCII ESTRICTO)

> **Obligatorio en todo el codigo fuente del proyecto.**
>
> En comentarios, nombres de variables, nombres de funciones, mensajes de log
> (`Log.d/w/e/i`), claves de SharedPreferences, acciones de Intent, canales de
> notificacion y cualquier literal interno del codigo fuente **esta prohibido
> usar acentos, la letra enie con virgulilla, signos de apertura de
> interrogacion o exclamacion, y cualquier caracter no ASCII**.
>
> Motivo: las radios Android de coche y varios toolchains de compilacion usan
> code pages y fuentes que no representan todos los caracteres Unicode, y un
> comentario o un literal con acentos puede romper la compilacion, corromper el
> APK o mostrar simbolos raros en la pantalla del coche.
>
> Como cumplir la regla:
> - Texto plano sin diacriticos: `informacion`, `atras`, `desvio`, `anio`.
> - Palabras que normalmente llevan enie se escriben sin virgulilla:
>   `Espana`, `senal`, `anadir`, `diseno`.
> - En los textos visibles de `res/values/strings.xml` se permiten acentos solo
>   si el recurso se revisa manualmente; el codigo Kotlin/Java nunca los usa.
> - Los caracteres Unicode que se muestran en pantalla se escriben con escape
>   ASCII, por ejemplo `"\u00f3"` en `FuelOptions.GLP` o
>   `"\u2582\u2583\u2585\u2586"` en `IndicadorAcceso.texto()`.
> - Los mensajes de error de descarga se muestran sin acentos a proposito para
>   que se lean igual en cualquier radio (`download_error_*` en `strings.xml`).
>
> Esta regla afecta tambien a este documento: todo el contenido tecnico nuevo
> (secciones 6 a 11) esta escrito sin acentos ni caracteres especiales para que
> pueda copiarse a scripts, configuraciones o mensajes de commit.

---

## 1. Resumen y Propósito de la Aplicación
**GasolinerasWidget** es una aplicación Android nativa, gratuita, sin anuncios y de código abierto (`github.com/jreverendo/GasolinerasWidget`), diseñada específicamente para **pantallas multimedia horizontales de coche** (como radios Android / Junsun) y usable también en teléfonos móviles en soporte.

Su objetivo es resolver la incomodidad de las apps comerciales de gasolineras (pensadas para uso en vertical, llenas de publicidad y que obligan a abandonar el navegador), permitiendo consultar precios oficiales en tiempo real mediante un **widget de escritorio** y una **cápsula flotante** superpuesta sobre aplicaciones de navegación (Google Maps, Waze, Sygic, etc.).

---

## 2. Características Funcionales Principales

1. **Interfaz Dual (Widget + Cápsula Flotante / Burbuja):**
   - **Widget de escritorio:** Muestra información rápida en el launcher de la radio.
   - **Cápsula flotante (`SYSTEM_ALERT_WINDOW`):** Ventana superpuesta con tipografía de alto contraste y botones grandes para minimizar distracciones al volante.
   - **Modos de vista:** Permite alternar entre modo compacto (1 estación destacada) y modo desplegado (listado de las 5 estaciones relevantes), cerrarse de un toque o refrescarse manualmente.
   - **Filtros de ordenación:** Pestañas para alternar entre **Cercana** (proximidad) y **Todas / Barata** (mejor precio).
2. **Fuente de Datos Oficial y Cálculo de Ahorro:**
   - Consulta directa a la API/datos oficiales del **Ministerio para la Transición Ecológica y el Reto Demográfico** (precios de carburantes en España).
   - **Cálculo de ahorro real en euros:** Calcula automáticamente cuánto dinero se ahorra (en verde, ej. `-10.91 eur`) o se pierde (en rojo, ej. `+5.09 eur`) al llenar un depósito completo respecto al precio medio provincial, evitando cálculos mentales conduciendo.
   - Indicador visual de cercanía y distancia en kilómetros.
3. **Modo Ruta y Gestión Inteligente de GPS/Energía:**
   - Detección de sentido de la marcha en ruta para priorizar estaciones favorables sin obligar a realizar cambios de sentido innecesarios.
   - Refresco automático por proximidad (ej. cada `250 m`) cuando el dispositivo detecta desplazamiento o alimentación de corriente conectada (encendido del vehículo), optimizando el consumo de datos y batería.
4. **Integración con Navegadores Externos:**
   - Al pulsar sobre una estación de servicio, se lanzan las coordenadas directamente hacia el navegador configurado (Google Maps, Waze, Sygic).
5. **Monetización Opcional / Soporte:**
   - App 100 % libre y sin limitaciones. Incluye acceso discreto con código QR hacia **Ko-fi** (`ko-fi.com/jrvjrv`) para donaciones voluntarias.

---

## 3. Arquitectura y Decisiones Técnicas Clave

### 3.1. Permisos Críticos de Android
- `INTERNET` / `ACCESS_NETWORK_STATE`: Descarga de precios oficiales del Ministerio.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`: Posicionamiento GPS en tiempo real para calcular distancias y sentido de la marcha.
- `SYSTEM_ALERT_WINDOW` (*Mostrar sobre otras aplicaciones*): Indispensable para dibujar la cápsula flotante encima de Waze, Google Maps o Sygic. En radios Android (Junsun/head units) el usuario debe concederlo manualmente la primera vez en los ajustes del sistema.
- `FOREGROUND_SERVICE` (si aplica al servicio de burbuja/GPS en ruta): Mantiene vivo el servicio flotante mientras el navegador está en primer plano.

### 3.2. Limitaciones Conocidas de Intents en Navegadores (No son bugs)
Al tocar una gasolinera para iniciar guiado:
- **Intents estándar (`geo:` / `google.navigation:`):** Android delega en la app predeterminada del sistema.
- **Waze:** La API pública de intents/URLs de Waze **no soporta inyectar paradas intermedias** sobre una ruta ya activa; siempre sustituye el destino actual o delega en el selector del sistema si se usa `geo:`.
- **Google Maps:** Cuando ya existe una navegación en curso, Maps solicita confirmación explícita al usuario (*"¿Salir de la navegación?"*) antes de iniciar guiado hacia la gasolinera seleccionada, ya que las apps externas no conocen el destino final previo del usuario para construir una URL `waypoints`.

### 3.3. Consideraciones de UI/UX para Radios Android
- Las pantallas de coche suelen tener resoluciones apaisadas con densidades de píxeles (`dpi`) personalizadas por el fabricante.
- La cápsula flotante está posicionada y dimensionada para encajar en el lateral derecho sin solapar las cajas de maniobras superiores (izquierda) ni las barras inferiores de tiempo estimado de llegada de Waze/Maps.

---

## 4. Distribución y Hoja de Ruta (Google Play)

### Estado Actual
- Repositorio público: `https://github.com/jreverendo/GasolinerasWidget`
- Releases estables compilados en `.apk` firmado (`Gasolineras.apk`). *(Nota: mantener coherencia entre el nombre del archivo adjunto en el release y las notas de versión).*

### Requisitos Pendientes si se Sube a Google Play Store
1. **Empaquetado:** Compilar como **Android App Bundle (`.aab`)** firmado con la misma keystore de producción.
2. **Privacidad (`PRIVACY.md`):** Publicar una política de privacidad accesible por URL (puede alojarse en el propio repositorio de GitHub) declarando que:
   - La ubicación GPS se procesa exclusivamente en local/tiempo real para consultar distancias a estaciones de servicio.
   - No se almacenan historiales de ubicación ni se envían datos personales a servidores propios.
3. **Formulario de Seguridad de Datos y Permisos en Play Console:**
   - Justificar el uso de ubicación y superposición en pantalla como funcionalidad principal de asistencia a la conducción.
4. **Test Cerrado Obligatorio (Cuentas Personales Nuevas):**
   - Google exige un mínimo de **12 testers activos durante 14 días consecutivos** en pista de pruebas cerrada antes de habilitar el paso a Producción.

---

## 5. Checklist Rápido para Futuras Modificaciones
- [ ] Al tocar código de UI de la burbuja, verificar en formato horizontal (`landscape`) que no invade el área de instrucciones de giro de Waze/Maps.
- [ ] Si se modifica la lógica de refresco GPS, comprobar tanto el comportamiento con cargador/corriente conectada (modo coche) como en batería (modo móvil).
- [ ] Antes de generar una nueva release en GitHub, actualizar `versionCode` y `versionName` en `build.gradle` y verificar que el nombre del artefacto `.apk` coincide con la descripción del release.

---

## 6. ARBOL DE DIRECTORIOS Y DESCRIPCION DE CADA CLASE

### 6.1. Arbol real del proyecto (sin `build/`, `.gradle/`, `.idea/`, `.kotlin/`)

```
GasolinerasWidget/
|-- build.gradle.kts                 Plugin AGP a nivel raiz
|-- settings.gradle.kts              Repositorios y modulo :app
|-- gradle.properties                Flags de compilacion y encoding
|-- gradlew / gradlew.bat            Wrapper de Gradle
|-- gradle/wrapper/gradle-wrapper.properties   Gradle 9.6.0
|-- gradle/gradle-daemon-jvm.properties
|-- CONTEXTO_PROYECTO.md             Este documento
|-- README.md
|-- LICENSE
|-- .gitignore
|-- .github/FUNDING.yml
|-- app/
|   |-- build.gradle.kts             Configuracion del modulo de aplicacion
|   |-- proguard-rules.pro           Reglas R8 (sin reglas propias)
|   |-- release/                     APK firmado y output-metadata.json
|   `-- src/main/
|       |-- AndroidManifest.xml
|       |-- java/com/tuapp/gasolineraswidget/
|       |   |-- MainActivity.kt
|       |   |-- WidgetConfigActivity.kt
|       |   |-- NavigationDispatchActivity.kt
|       |   |-- GasolinerasWidgetProvider.kt
|       |   |-- GasolineraWidgetService.kt
|       |   |-- GasolineraRemoteViewsFactory.kt
|       |   |-- FloatingWidgetService.kt
|       |   |-- CapsuleDragLayout.kt
|       |   |-- GasolineraRepository.kt
|       |   |-- Gasolinera.kt
|       |   |-- PrecioTramo.kt
|       |   |-- RouteDirectionHelper.kt
|       |   |-- NavigationHelper.kt
|       |   |-- IndicadorAcceso.kt
|       |   |-- FuelOptions.kt
|       |   |-- KoFiSupport.kt
|       |   |-- WidgetFuelPreferences.kt
|       |   |-- WidgetNetworkPreferences.kt
|       |   |-- WidgetDepositPreferences.kt
|       |   |-- FloatingWidgetPreferences.kt
|       |   |-- NavigationPreferences.kt
|       |   |-- WidgetUpdateScheduler.kt
|       |   |-- WidgetRefreshReceiver.kt
|       |   |-- PowerStateReceiver.kt
|       |   `-- BootReceiver.kt
|       `-- res/
|           |-- drawable/            25 drawables XML (fondos, iconos, barras)
|           |-- drawable-nodpi/      qr_kofi.png
|           |-- layout/              8 layouts XML
|           |-- mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/  ic_launcher
|           |-- values/              colors.xml, strings.xml, styles.xml, themes.xml
|           `-- xml/                 gasolineras_widget_info.xml
```

### 6.2. Actividades

| Archivo | Tipo | Responsabilidad real |
| --- | --- | --- |
| `MainActivity.kt` | Activity (LAUNCHER) | Pantalla unica en negro. Muestra estado de superposicion, ubicacion y modo flotante; permite descargar precios a mano, elegir carburante y litros del deposito, elegir navegador GPS y activar o detener la capsula. Pide en runtime ubicacion y notificaciones. |
| `WidgetConfigActivity.kt` | Activity (`APPWIDGET_CONFIGURE`) | Configuracion al colocar el widget: combustible por instancia, tipo de red por instancia y litros del deposito globales. Guarda, refresca ubicacion y actualiza todos los widgets. |
| `NavigationDispatchActivity.kt` | Activity invisible (`Theme.NoDisplay`, `noHistory`, `excludeFromRecents`) | Puente que necesita el widget: `RemoteViews` solo lanza intents, asi que esta actividad recibe latitud, longitud y nombre por extras, llama a `NavigationHelper` y se cierra sin mostrarse. |

### 6.3. Servicio flotante y vistas

| Archivo | Tipo | Responsabilidad real |
| --- | --- | --- |
| `FloatingWidgetService.kt` | Service en primer plano (`location\|specialUse`) | Dibuja la capsula con `WindowManager` (`TYPE_APPLICATION_OVERLAY`), gestiona arrastre, escala, filtros, QR de Ko-fi, modo ruta, vigilancia de inactividad y actualizaciones GPS. No descarga ni calcula en el hilo principal: usa un ejecutor de un solo hilo. |
| `CapsuleDragLayout.kt` | FrameLayout propio | Raiz de `floating_widget.xml`. Intercepta el arrastre en toda la capsula con `touchSlop`; un toque limpio sigue llegando al hijo para que `onClick` funcione. |
| `GasolinerasWidgetProvider.kt` | AppWidgetProvider | Construye las `RemoteViews`, la plantilla `PendingIntent` de navegacion, los broadcasts de refresco, cambio de red y descarga forzada, y programa el `AlarmManager`. |
| `GasolineraWidgetService.kt` | RemoteViewsService | Devuelve la fabrica de filas leyendo el `appWidgetId` del extra, del `remoteAdapterAppWidgetId` o del ultimo segmento de la URI `gasolineraswidget://widget/<id>`. |
| `GasolineraRemoteViewsFactory.kt` | RemoteViewsFactory | Carga la lista desde cache en `onDataSetChanged()` (hilo binder), pinta cada fila, resalta la mas barata y adjunta el fill-in intent con las coordenadas. |

### 6.4. Modelos de datos y logica de dominio

| Archivo | Contenido |
| --- | --- |
| `Gasolinera.kt` | `data class Gasolinera` (id, nombre, precio, distancia, direccion, municipio, provincia, horario, latitud, longitud, etiquetaRuta, penalizacionKm, aDesmano, puntuacionAcceso) y `data class ResultadoLista` (gasolineras, hayCache, precioMedio, mediaProvincial, idMasCercana, idMasLejana). |
| `PrecioTramo.kt` | `object` con la escala unica de 6 tramos (`Tramo` enum: VERDE_BRILLANTE, VERDE, NEGRO, AMARILLO, NARANJA, ROJO), `deltaPorLitro`, `diferenciaCoste`, `tramo`, `textoDiferenciaCoste`, `colorDiferenciaCoste`. |
| `RouteDirectionHelper.kt` | Detector de modo ruta con ventana de 60 s e histeresis (entra a 55 km/h, sale a 40 km/h), calculo de rumbo por GPS o geometrico y `desviacionGrados` / `esRebasada`. |
| `IndicadorAcceso.kt` | Traduce `puntuacionAcceso` (1 a 5) a drawable de barras, glifo de texto ASCII escapado y color de cercania. |
| `FuelOptions.kt` | Claves exactas del JSON del Ministerio para los 5 carburantes, etiquetas cortas y codigos de capsula (G95, GOA, G98, GOP, GLP). |
| `NavigationHelper.kt` | Resolucion del navegador y lanzamiento de intents (`geo:`, Google Maps, Waze, Sygic, TomTom, OsmAnd, web de Maps). |
| `KoFiSupport.kt` | Enlace a `https://ko-fi.com/jrvjrv`, tarjeta de soporte y dialogo con el QR local. |

### 6.5. Cliente de red y repositorio

| Archivo | Contenido |
| --- | --- |
| `GasolineraRepository.kt` | Unico cliente de red y cache. Clases internas: `Ubicacion`, `EstadoDispositivo`, `ModoActualizacion` (REPOSO, CONDUCIENDO_BATERIA, CONDUCIENDO_CARGANDO), `ErrorDescarga`, `ResultadoDescarga`, `ListaCache`, `ClaveLista`, `MunicipioCache`, `MomentoActual`, `SesionDescarga`. Descarga con OkHttp, valida, guarda en `filesDir`, calcula distancias Haversine, filtra por red, por horario de apertura, aplica contexto de ruta y devuelve las 5 estaciones ordenadas. |

### 6.6. Preferencias y planificacion

| Archivo | SharedPreferences |
| --- | --- |
| `WidgetFuelPreferences.kt` | `gasolineras_widget_config`, clave `fuel_pref_<appWidgetId>` |
| `WidgetNetworkPreferences.kt` | `gasolineras_widget_config`, clave `network_pref_<appWidgetId>` |
| `WidgetDepositPreferences.kt` | `gasolineras_widget_config`, clave `pref_litros_deposito` |
| `FloatingWidgetPreferences.kt` | `gasolineras_floating_prefs` |
| `NavigationPreferences.kt` | `gasolineras_navigation_prefs` |
| `WidgetUpdateScheduler.kt` | Sin prefs: programa `AlarmManager` (`ELAPSED_REALTIME_WAKEUP`) |
| `WidgetRefreshReceiver.kt` | Broadcast del chequeo periodico |
| `PowerStateReceiver.kt` | Reprograma al conectar o desconectar corriente |
| `BootReceiver.kt` | Reprograma tras `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED` |

### 6.7. Recursos XML

| Archivo | Uso |
| --- | --- |
| `layout/floating_widget.xml` | Raiz `CapsuleDragLayout` con tarjeta, cabecera de botones, panel QR, fila protagonista y contenedor de filas extra. |
| `layout/item_floating_row.xml` | Fila del desplegable de la capsula (nombre, distancia, barras, precio, ahorro). |
| `layout/gasolinera_widget.xml` | Layout del widget: titulo, chip de red, refresco, descarga forzada, hora, `ListView` y vista vacia. |
| `layout/item_gasolinera.xml` | Fila del `ListView` del widget. |
| `layout/activity_main.xml` | Pantalla principal con `ScrollView`, estados, descarga, carburante, litros, navegador y botones flotantes. |
| `layout/activity_widget_config.xml` | Formulario de configuracion del widget. |
| `layout/include_kofi_support.xml` | Tarjeta de Ko-fi reutilizable. |
| `layout/dialog_qr_kofi.xml` | Dialogo del QR. |
| `xml/gasolineras_widget_info.xml` | Metadatos del widget: `minWidth` 250dp, `minHeight` 110dp, `targetCellWidth` 4, `targetCellHeight` 2, `updatePeriodMillis` 0, `resizeMode` horizontal\|vertical. |
| `drawable/bg_precio_*.xml` | 6 fondos de pastilla de precio (verde brillante, verde, negro, amarillo, naranja, rojo). |
| `drawable/ic_acceso_barras_1..5.xml` | Iconos de nivel de acceso. |
| `drawable/bg_floating_*.xml`, `bg_chip_pill.xml`, `bg_icon_button*.xml`, `bg_config_card.xml`, `bg_toggle_red.xml` | Fondos de la capsula y de las pantallas. |
| `values/colors.xml` | Paleta de widget, precios, deposito, configuracion y capsula. |
| `values/styles.xml` | `FloatingChipButton`, `FloatingIconButton`, `FloatingIconButton.Close`. |
| `values/themes.xml` | `Theme.GasolinerasWidget.Main` (Material NoActionBar oscuro) y `Theme.GasolinerasWidget.Config`. |

---

## 7. CONFIGURACION DE GRADLE Y EMPAQUETADO

### 7.1. Versiones

| Elemento | Valor real |
| --- | --- |
| Plugin Android (AGP) | `com.android.application` version `9.4.1` (raiz, `apply false`) |
| Gradle | `9.6.0` (`gradle/wrapper/gradle-wrapper.properties`) |
| `compileSdk` | `36` |
| `minSdk` | `26` (Android 8.0) |
| `targetSdk` | `36` |
| `versionCode` | `2` |
| `versionName` | `1.1` |
| `namespace` / `applicationId` | `com.tuapp.gasolineraswidget` |
| Java | `sourceCompatibility` y `targetCompatibility` = `VERSION_17` |
| Dependencia unica | `com.squareup.okhttp3:okhttp:4.12.0` |

No se usan AndroidX, Jetpack Compose, Material Components, Play Services,
Gson ni ninguna libreria de QR: el proyecto se mantiene minimo a proposito.

### 7.2. `settings.gradle.kts`

- `pluginManagement.repositories`: `google()`, `mavenCentral()`, `gradlePluginPortal()`.
- `dependencyResolutionManagement`: `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, repos `google()` y `mavenCentral()`.
- `rootProject.name = "GasolinerasWidget"`, `include(":app")`.

### 7.3. `gradle.properties`

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
org.gradle.tooling.parallel=true
```

### 7.4. Reglas de ProGuard / R8

- `buildTypes.release { isMinifyEnabled = false }`.
- Se declaran `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`, pero `app/proguard-rules.pro` solo contiene comentarios: **no hay reglas propias**.
- Consecuencia practica: el release no ofusca ni encoge codigo, asi que los nombres de clase del manifest y los `RemoteViews` no requieren reglas `keep`. Si se activa `isMinifyEnabled`, habra que anadir reglas para `AppWidgetProvider`, `RemoteViewsService` y `OkHttp`.

### 7.5. Empaquetado y artefactos

- Debug: `app/build/outputs/apk/debug/app-debug.apk`.
- Release: `app/build/outputs/apk/release/Gasolineras.apk` y copia en `app/release/app-release.apk`.
- `.gitignore` excluye `*.apk` y `*.aab`, `build/`, `.idea/`, `.kotlin/`, `local.properties`.
- Sin `.aab` en el repositorio: es el pendiente de la seccion 4 para Google Play.

---

## 8. PERMISOS DECLARADOS Y GESTION EN TIEMPO DE EJECUCION

### 8.1. Permisos en `AndroidManifest.xml` (exactos)

| Permiso | Por que esta |
| --- | --- |
| `android.permission.INTERNET` | Descarga del JSON del Ministerio. |
| `android.permission.ACCESS_NETWORK_STATE` | Comprobar `NET_CAPABILITY_INTERNET` antes de pedir y clasificar el fallo. |
| `android.permission.ACCESS_COARSE_LOCATION` | Ubicacion aproximada para distancias. |
| `android.permission.ACCESS_FINE_LOCATION` | GPS de ruta y proveedor fino. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Reprogramar alarmas tras reinicio. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Dibujar la capsula sobre el navegador. |
| `android.permission.FOREGROUND_SERVICE` | Servicio flotante en primer plano. |
| `android.permission.FOREGROUND_SERVICE_LOCATION` | Tipo `location` en Android 14+. |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Alternativa cuando no hay permiso de ubicacion. |
| `android.permission.POST_NOTIFICATIONS` | Notificacion obligatoria del primer plano en Android 13+. |
| `android.permission.PACKAGE_USAGE_STATS` | Detectar el navegador activo (permiso especial, `tools:ignore="ProtectedPermissions"`). |

### 8.2. Consultas de visibilidad (`<queries>`)

Paquetes declarados explicitamente para Android 11+:
`com.google.android.apps.maps`, `com.waze`, `com.sygic.aura`,
`com.sygic.truck`, `com.tomtom.gplay.navapp`, `net.osmand`, `net.osmand.plus`.

Intents declarados: `VIEW` con scheme `geo`, `VIEW` con scheme `https`,
`VIEW` con scheme `com.sygic.aura`.

### 8.3. Gestion en runtime

1. **Ubicacion** (`MainActivity.solicitarPermisos`, `WidgetConfigActivity.solicitarPermisoUbicacion`):
   se piden `ACCESS_FINE_LOCATION` y `ACCESS_COARSE_LOCATION` juntas si
   ninguna esta concedida (`REQUEST_PERMISOS = 4002`,
   `REQUEST_LOCATION = 1001`). El resultado solo refresca la interfaz.
2. **Notificaciones**: `POST_NOTIFICATIONS` se pide solo en API 33+
   (`TIRAMISU`) y solo si no esta concedido.
3. **Superposicion**: no es un permiso de runtime clasico.
   `Settings.canDrawOverlays()` decide; si falta, se abre
   `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` con
   `Uri.parse("package:" + packageName)` y `startActivityForResult`
   (`REQUEST_OVERLAY = 4001`). `onResume` y `onActivityResult` comprueban de
   nuevo y arrancan el servicio. `FloatingWidgetService.onCreate` vuelve a
   comprobarlo y hace `stopSelf()` si falta.
4. **Acceso de uso** (`PACKAGE_USAGE_STATS`): se comprueba con
   `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)` en API 29+
   y `checkOpNoThrow` antes. Si falta, `MainActivity` muestra el boton
   `main_nav_usage_button`, que abre `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
   Sin ese permiso, `NavigationHelper` ignora el historial de uso y usa el
   navegador instalado o el `geo:` generico.
5. **Primer plano**: `startForeground` con
   `FOREGROUND_SERVICE_TYPE_LOCATION` si hay permiso de ubicacion y, si falla,
   con `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (subtipo
   `floating_overlay_widget`). Canal `floating_widget_channel`,
   `IMPORTANCE_LOW`, sin vibracion ni sonido.
6. **Permisos que no se piden en runtime**: `INTERNET`,
   `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE*`.
7. **Seguridad del receptor de pantalla**: en API 33+ se registra con
   `Context.RECEIVER_NOT_EXPORTED`.

---

## 9. ALMACENAMIENTO LOCAL, CLAVES Y ESTRUCTURA DEL JSON

### 9.1. Ficheros y preferencias

| Nombre | Tipo | Contenido |
| --- | --- | --- |
| `gasolineras_api_cache.json` | Fichero en `filesDir` | Ultimo JSON valido del Ministerio (unos 12 MB). |
| `gasolineras_api_cache.json.tmp` | Fichero temporal | Se escribe y se mueve con `ATOMIC_MOVE` (con `REPLACE_EXISTING` de respaldo). |
| `gasolineras_cache_prefs` | SharedPreferences | Estado de cache, ubicacion y conduccion. |
| `gasolineras_floating_prefs` | SharedPreferences | Estado de la capsula. |
| `gasolineras_widget_config` | SharedPreferences | Configuracion por instancia de widget y deposito global. |
| `gasolineras_navigation_prefs` | SharedPreferences | Navegador preferido. |

### 9.2. Claves exactas de `gasolineras_cache_prefs`

| Clave | Tipo | Uso |
| --- | --- | --- |
| `last_success_millis` | Long | Instante de la ultima descarga correcta; 0 = sin cache. |
| `last_known_lat` | String (Double) | Latitud de la ultima ubicacion conocida. |
| `last_known_lon` | String (Double) | Longitud de la ultima ubicacion conocida. |
| `last_known_time` | Long | Timestamp de esa ubicacion. |
| `recalc_lat` | String (Double) | Latitud del ultimo recalculo de distancias. |
| `recalc_lon` | String (Double) | Longitud del ultimo recalculo. |
| `recalc_time` | Long | Instante del ultimo recalculo. |
| `force_recalc` | Boolean | Fuerza recalculo en la siguiente carga. |
| `last_move_lat` / `last_move_lon` / `last_move_time` | String / String / Long | Ultimo movimiento usado para estimar velocidad sin GPS. |
| `last_driving_time` | Long | Histeresis de conduccion (`CONDUCCION_HOLD_MS`). |
| `force_fresh_location` | Boolean | Pide una lectura GPS fresca antes de usar la ultima conocida. |

### 9.3. Claves exactas de las demas preferencias

- `gasolineras_floating_prefs`: `floating_x`, `floating_y`, `floating_red`,
  `floating_criterio` (`BARATA` / `CERCANA`), `floating_carburante`,
  `floating_litros_deposito` (1 a 200, por defecto 50).
- `gasolineras_widget_config`: `fuel_pref_<appWidgetId>`,
  `network_pref_<appWidgetId>` (`TODAS` / `TRADICIONAL` / `LOWCOST`),
  `pref_litros_deposito`.
- `gasolineras_navigation_prefs`: `nav_navegador`
  (`AUTO` / `PREGUNTAR` / `GOOGLE_MAPS` / `WAZE` / `SYGIC`).

### 9.4. Endpoint consumido

```
GET https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/
Accept: application/json
User-Agent: Mozilla/5.0 (Android; GasolinerasWidget)
```

- No se fija `Accept-Encoding`: OkHttp anade gzip y descomprime solo.
- Tiempos: connect 30 s, read 60 s, write 30 s, call 180 s.
- Reintento: 2000 ms, un reintento para timeouts, red y sin conexion;
  reintento inmediato con cliente TLS compatible si el fallo es SSL.
- Cliente de reserva `clienteHttpTlsCompat`: `COMPATIBLE_TLS`, TrustManager
  que acepta la cadena si el certificado sigue vigente, e interceptor que
  rechaza cualquier host distinto de `sedeaplicaciones.minetur.gob.es`.

### 9.5. Estructura del JSON que se explota

```
{
  "ListaEESSPrecio": [
    {
      "IDEESS": "1234",
      "R\u00f3tulo": "REPSOL",
      "Provincia": "Gipuzkoa",
      "Municipio": "Donostia",
      "Direcci\u00f3n": "CALLE ...",
      "Horario": "L-D: 06:00-22:00",
      "Latitud": "43.32...",
      "Longitud (WGS84)": "-1.98...",
      "Precio Gasolina 95 E5": "1.599",
      "Precio Gasoleo A": "1.499",
      "Precio Gasolina 98 E5": "1.799",
      "Precio Gasoleo Premium": "1.699",
      "Precio Gases licuados del petr\u00f3leo": "0.999"
    }
  ]
}
```

Notas de lectura reales del codigo:

- La raiz valida es `ListaEESSPrecio`; si falta o esta vacia se descarta.
- Campos accedidos con `optString`: `R\u00f3tulo`, `IDEESS`, `Provincia`,
  `Municipio`, `Direcci\u00f3n` (con `Direccion` de respaldo), `Horario`,
  `Latitud`, `Longitud (WGS84)` y la clave del carburante.
- `FuelOptions` define las claves exactas:
  `Precio Gasolina 95 E5`, `Precio Gasoleo A`, `Precio Gasolina 98 E5`,
  `Precio Gasoleo Premium`, `Precio Gases licuados del petr\u00f3leo`.
- Para GLP se prueba tambien la clave sin tilde
  `Precio Gases licuados del petroleo`.
- Los decimales se leen reemplazando coma por punto; se descartan precios
  `<= 0`, latitud fuera de `-90..90`, longitud fuera de `-180..180` y el par
  `0,0`.
- El identificador de fila es `IDEESS`; si viene vacio se usa
  `nombre_latitud_longitud`.

---

## 10. FLUJO TECNICO PASO A PASO

### 10.1. Calculo del ahorro en euros

1. `GasolineraRepository.calcularDesdeJson` recorre todas las estaciones con
   precio valido del carburante y acumula `sumasProvincia` y
   `conteosProvincia` por provincia normalizada (sin acentos, en mayusculas).
2. Al terminar calcula `mediasProvinciales[provincia] = suma / conteo`.
   Esa media es la referencia unica del ahorro y del color de la pastilla.
3. `cargarListaParaMostrar` filtra por red, descarta estaciones cerradas y
   selecciona `mediaProvincial` de la provincia de la estacion mas cercana;
   si no hay provincia usa `precioMedio` de las abiertas como respaldo.
4. `PrecioTramo.deltaPorLitro(precio, mediaProvincial) = precio - media`.
   Negativo = mas barata que la media.
5. `PrecioTramo.diferenciaCoste = deltaPorLitro * litrosDeposito`.
   Negativo = ahorro, positivo = sobrecoste.
6. Texto: `String.format(Locale.US, "%+.2f eur", diferencia)`; por debajo de
   `0.005` se muestra `deposito_neutro` (`0.00 eur`).
7. Color: verde `deposito_ahorro` si `< -0.005`, rojo
   `deposito_sobrecoste` si `> 0.005`, gris `deposito_neutro` en medio.
8. La pastilla usa `PrecioTramo.tramo(delta)` con umbrales `0.03`, `0.06`,
   `0.10` y `EPSILON_DELTA = 1e-9` para absorber coma flotante.
9. El orden de la lista no es por precio puro:
   `distancia - (precioMedio - precio) * FACTOR_AHORRO` con
   `FACTOR_AHORRO = 10.0f`, luego distancia y luego nombre. Se muestran 5.
10. La misma funcion la usan `GasolineraRemoteViewsFactory` (widget) y
    `FloatingWidgetService` (capsula), por eso el numero coincide en ambos.

### 10.2. Refresco del GPS en ruta

1. `FloatingWidgetService.onCreate` pide actualizaciones con
   `requestLocationUpdates(proveedor, 10000L, 0f, escuchaRuta, mainLooper)`.
   El proveedor se elige: GPS si hay permiso fino y GPS activo; si no,
   `NETWORK_PROVIDER`; si no, el primer proveedor habilitado.
2. Cada lectura pasa por `RouteDirectionHelper.registrar`, que:
   - estima velocidad del GPS o entre dos lecturas consecutivas,
   - actualiza rumbo con `bearing` o con rumbo geometrico,
   - mantiene una ventana de 60 s y cambia `modoRuta` con histeresis
     (entra por encima de 15.3 m/s = 55 km/h, sale por debajo de
     11.1 m/s = 40 km/h, tolerando un 20 % de muestras discordantes).
3. Si cambia el modo, se repinta el color del cierre y se reordena la lista
   sin esperar al siguiente refresco.
4. El umbral de recalculo local depende de la alimentacion:
   `250 m` con corriente externa (AC, USB o inalambrica) y `1500 m` con
   bateria. Al superarlo se llama a `actualizarRuta`.
5. `actualizarRuta` registra la ubicacion en el repositorio
   (`registrarUbicacionDeRuta`, que valida rangos y fuerza `force_recalc`),
   recalcula distancias y filtros en el ejecutor, y solo descarga el JSON si
   la cache supera `CACHE_RUTA_MAX_EDAD_MS` (45 min) y hay red.
6. Ahorro de bateria:
   - `ACTION_SCREEN_OFF` detiene las actualizaciones; `ACTION_SCREEN_ON` las
     reanuda.
   - Vigilancia cada 2 min: si pasan 10 min sin un desplazamiento real de mas
     de 100 m, se cierra la capsula (`INACTIVIDAD_MAXIMA_MS`).
   - `onDestroy` hace `removeUpdates`, `removeCallbacksAndMessages`,
     `shutdownNow` y `stopForeground(STOP_FOREGROUND_REMOVE)`.
7. En el widget, el mismo estado se usa en `WidgetUpdateScheduler`:
   reposo 5 min, conduccion con bateria 4 min, conduccion cargando 1 min.
8. Fuera de ruta, `GasolineraRepository` usa TTL de red de 60 min en reposo,
   45 min conduciendo con bateria y 30 min conduciendo cargando, y recalculo
   por distancia de 5 km / 3 km / 1 km respectivamente.
9. Si no hay permiso o no hay posicion, se usa `UBICACION_DONOSTIA`
   (`43.3183, -1.9812`) para no dejar la lista vacia.

### 10.3. Lanzamiento del Intent al navegador

1. El widget no puede ejecutar codigo: la plantilla `PendingIntent` apunta a
   `NavigationDispatchActivity` (`FLAG_MUTABLE` en API 31+) y cada fila aporta
   un fill-in intent con `navigation_latitud`, `navigation_longitud` y
   `navigation_nombre_estacion`.
2. La capsula llama directamente a
   `NavigationHelper.abrirNavegacionGasolinera(context, lat, lon, nombre)`.
3. `NavigationHelper` valida coordenadas finitas y resuelve el navegador:
   1. preferencia forzada en `NavigationPreferences` (Maps, Waze, Sygic);
   2. `PREGUNTAR` devuelve null y se usa el selector;
   3. navegador compatible con evento `ACTIVITY_RESUMED` en los ultimos
      3 min, si hay permiso de acceso de uso;
   4. unico navegador compatible instalado, o Google Maps si hay varios;
   5. si no hay certeza, `geo:` generico.
4. Construccion de la URI segun el tipo:
   - **Maps**: `geo:0,0?q=<lat>,<lon>(<nombre URL-encoded>)` con
     `setPackage("com.google.android.apps.maps")`.
   - **Waze**: `https://waze.com/ul?ll=<lat>,<lon>&navigate=yes` con
     `setPackage("com.waze")`.
   - **Sygic**: `com.sygic.aura://coordinate|<lon>|<lat>|drive`, luego
     `...|waypoint`, luego `<paquete>://coordinate|...|drive`.
   - **Generico**: `geo:<lat>,<lon>?q=<lat>,<lon>(<nombre>)`, opcionalmente
     dirigido al paquete (TomTom, OsmAnd).
5. Las coordenadas se formatean con `String.format(Locale.US, "%.6f", valor)`
   para que el separador decimal sea siempre punto.
6. Cada lanzamiento va con `FLAG_ACTIVITY_NEW_TASK` y `try/catch` de
   `ActivityNotFoundException`, `SecurityException` y `Exception`; si falla,
   se cae al selector `Intent.createChooser` y, en ultimo termino, a
   `https://www.google.com/maps/search/?api=1&query=<lat>,<lon>`.
7. Si nada funciona, se muestra `floating_navigation_missing` y la app no se
   cierra.

### 10.4. Descarga y cache del JSON (resumen del flujo real)

1. `actualizarEnSegundoPlanoDetallado` usa un `AtomicBoolean` global para
   admitir una sola descarga por proceso.
2. Si no es forzada y `debeDescargar` es false, termina con
   `ErrorDescarga.NINGUNO`.
3. `OkHttpClient` normal; ante fallo SSL se reintenta con el cliente
   compatible; ante timeout/red/sin conexion se reintenta una vez a los
   2000 ms; los 5xx tambien reintentan.
4. Respuesta: se comprueba `isSuccessful`, cuerpo no vacio, `validarJson`
   (`ListaEESSPrecio` no vacio) y `guardarCache` (fichero temporal +
   `ATOMIC_MOVE` + `commit()` del timestamp).
5. `ErrorDescarga` clasifica el fallo en NINGUNO, SIN_CONEXION, TIMEOUT, SSL,
   HTTP (con codigo), RESPUESTA_VACIA, JSON_NO_VALIDO, GUARDADO, RED, EN_CURSO
   o DESCONOCIDO, y `mensajeDescarga` lo convierte en un texto corto sin
   acentos de `strings.xml`.
6. Al terminar con exito se refresca la capsula
   (`FloatingWidgetService.solicitarRecarga`) y se invalidan los adaptadores
   del widget (`GasolinerasWidgetProvider.notificarDatosActualizados`).

---

## 11. TABLA DE CONSTANTES Y UMBRALES DEL CODIGO

| Constante | Valor | Archivo |
| --- | --- | --- |
| `TTL_REPOSO_MS` | 60 min | `GasolineraRepository` |
| `TTL_CONDUCCION_BATERIA_MS` | 45 min | `GasolineraRepository` |
| `TTL_CONDUCCION_CARGA_MS` | 30 min | `GasolineraRepository` |
| `RECALC_REPOSO_KM` | 5.0 | `GasolineraRepository` |
| `RECALC_CONDUCCION_BATERIA_KM` | 3.0 | `GasolineraRepository` |
| `RECALC_CONDUCCION_CARGA_KM` | 1.0 | `GasolineraRepository` |
| `RECALC_CONDUCCION_BATERIA_MS` | 4 min | `GasolineraRepository` |
| `RECALC_CONDUCCION_CARGA_MS` | 1 min | `GasolineraRepository` |
| `VELOCIDAD_CONDUCCION_MPS` / `_KMH` | 5.5 / 19.8 | `GasolineraRepository` |
| `MAX_EDAD_LECTURA_MS` | 10 min | `GasolineraRepository` |
| `CONDUCCION_HOLD_MS` | 5 min | `GasolineraRepository` |
| `FRESH_LOCATION_TIMEOUT_SECONDS` | 4 s | `GasolineraRepository` |
| `PENALIZACION_DESVIO_KM` | 15.0 | `GasolineraRepository` |
| `FACTOR_AHORRO` | 10.0 | `GasolineraRepository` |
| `MUNICIPIO_CACHE_MS` / `_KM` | 15 min / 2.0 | `GasolineraRepository` |
| `LIMITE_CANDIDATOS` / `LIMITE_MOSTRAR` | 100 / 5 | `GasolineraRepository` |
| `CALL_TIMEOUT_SECONDS` | 180 s | `GasolineraRepository` |
| `RETRY_DELAY_MS` | 2000 ms | `GasolineraRepository` |
| `UMBRAL_RECALCULO_CORRIENTE_METROS` | 250.0 | `FloatingWidgetService` |
| `UMBRAL_RECALCULO_BATERIA_METROS` | 1500.0 | `FloatingWidgetService` |
| `INTERVALO_MUESTREO_MS` | 10000 ms | `FloatingWidgetService` |
| `MOVIMIENTO_REAL_METROS` | 100.0 | `FloatingWidgetService` |
| `INTERVALO_VIGILANCIA_MS` | 2 min | `FloatingWidgetService` |
| `INACTIVIDAD_MAXIMA_MS` | 10 min | `FloatingWidgetService` |
| `CACHE_RUTA_MAX_EDAD_MS` | 45 min | `FloatingWidgetService` |
| `MAX_FILAS_EXTRA` | 4 | `FloatingWidgetService` |
| `ESCALA_GRANDE` / `ESCALA_COMPACTA` | 1.25 / 1.0 | `FloatingWidgetService` |
| `ANCHO_GRANDE_DP` / `_HORIZONTAL_DP` | 550 / 600 | `FloatingWidgetService` |
| `FRACCION_ANCHO_GRANDE` | 0.47 | `FloatingWidgetService` |
| `QR_MINIMO_DP` / `QR_MAXIMO_DP` / `QR_MAXIMO_PX` | 96 / 300 / 768 | `FloatingWidgetService` |
| `INTERVALO_REPOSO_MS` | 5 min | `WidgetUpdateScheduler` |
| `INTERVALO_CONDUCCION_BATERIA_MS` | 4 min | `WidgetUpdateScheduler` |
| `INTERVALO_CONDUCCION_CARGA_MS` | 1 min | `WidgetUpdateScheduler` |
| `VELOCIDAD_RUTA_MPS` | 15.3 (55 km/h) | `RouteDirectionHelper` |
| `VELOCIDAD_CIUDAD_MPS` | 11.1 (40 km/h) | `RouteDirectionHelper` |
| `ANGULO_DELANTE_GRADOS` | 105.0 | `RouteDirectionHelper` |
| `VENTANA_MS` / `FRACCION_MUESTRAS` | 60000 / 0.8 | `RouteDirectionHelper` |
| `VENTANA_NAVEGADOR_ACTIVO_MS` | 3 min | `NavigationHelper` |
| `UMBRAL_MUY_BARATA` / `UMBRAL_BARATA` | 0.10 / 0.03 | `PrecioTramo` |
| `UMBRAL_AMARILLO` / `UMBRAL_NARANJA` | 0.06 / 0.10 | `PrecioTramo` |
| `UMBRAL_IMPACTO` | 0.005 | `PrecioTramo` |
| `LITROS_POR_DEFECTO` / `MINIMOS` / `MAXIMOS` | 50 / 1 / 200 | `FloatingWidgetPreferences`, `WidgetDepositPreferences` |

---

## 12. Recordatorio final de la regla ASCII

Antes de cualquier commit, revision de codigo o cambio de `strings.xml`:

- 0 acentos, 0 virgulillas, 0 signos de apertura, 0 caracteres no ASCII en
  Kotlin/Java, en claves de preferencias, en acciones de Intent y en logs.
- Los unicos caracteres no ASCII permitidos son escapes `\uXXXX` en literales
  que se pintan en pantalla.
- Todo texto nuevo de este documento se mantiene en ASCII para poder
  reutilizarlo en scripts, mensajes de commit y configuraciones.