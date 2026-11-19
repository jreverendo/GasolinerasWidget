# ⛽ GasolinerasWidget

Widget de escritorio y ventana flotante para Android diseñada específicamente para **pantallas multimedia de coche (Android Head Units)** como Junsun, Teyes, Joying, etc. Permite localizar en tiempo real las gasolineras más baratas y cercanas en España y enviar el destino directamente a tu navegador GPS con un solo toque.

---

## ✨ Características principales

* **Precios oficiales en tiempo real:** Consulta directamente los datos abiertos del Ministerio para la Transición Ecológica (España) sin necesidad de claves de API ni registros.
* **Diseñada para conducción:** Interfaz con tipografía grande, alto contraste y botones amplios pensados para pantallas panorámicas de coche.
* **Modo Widget y Ventana Flotante:** Funciona tanto fijada en el launcher del coche como en una ventana flotante superpuesta sobre tu navegador GPS en marcha.
* **Integración directa con navegadores:** Al seleccionar una estación de servicio, lanza automáticamente las coordenadas y la ruta hacia **Sygic**, **Google Maps** o **Waze**.
* **Sin anuncios ni rastreo:** Aplicación ligera, gratuita y de código abierto.

---

## 📸 Capturas de pantalla

*(Añade aquí tus fotos o capturas cuando la tengas funcionando en la pantalla del coche)*

---

## 📥 Instalación en la pantalla del coche

1. Descarga el último archivo `.apk` desde la sección [**Releases**](https://github.com/jreverendo/GasolinerasWidget/releases) de este repositorio.
2. Cópialo a un pendrive USB (o descárgalo directamente desde el navegador de la radio).
3. Abre el **Explorador de archivos** de la pantalla del coche, pulsa sobre el `.apk` e instálalo (activa el permiso de *Orígenes desconocidos* si el sistema te lo solicita).

---

## ⚙️ Permisos necesarios

Para funcionar correctamente en segundo plano mientras conduces, la aplicación requiere:

* **Ubicación (GPS):** Necesario para calcular la distancia real a las estaciones de servicio cercanas y ordenar los resultados.
* **Mostrar sobre otras aplicaciones:** Necesario para poder desplegar el botón o la ventana flotante encima del navegador GPS.
* **Sin restricciones de batería (Recomendado):** En radios Android de coche conviene desactivar la optimización de batería para esta app en *Ajustes > Aplicaciones* para evitar que el sistema cierre el widget al suspender la radio.

---

## 🛠️ Stack técnico

* **Lenguaje:** Kotlin
* **Entorno:** Android Studio
* **Fuente de datos:** REST API pública de carburantes terrestres (Ministerio de Industria / MITECO)

---

## 📄 Licencia

Proyecto distribuido bajo licencia MIT. Puedes usar, modificar y compartir el código libremente.
