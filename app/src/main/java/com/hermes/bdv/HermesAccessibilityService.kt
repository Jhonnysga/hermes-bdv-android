package com.hermes.bdv

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
import java.util.Calendar

/**
 * Hermes — automatización de compra de divisas del Banco de Venezuela.
 *
 * Port fiel del script Python `hermes_compra.py`: misma máquina de estados,
 * mismos selectores, mismas reglas de Jhon (reintentos, tiempos, recuperación).
 *
 * El flujo corre en un hilo de trabajo ([hiloFlujo]); los eventos de
 * accesibilidad solo despiertan al flujo vía [candado]. Ningún `sleep` corre
 * en el hilo principal del servicio.
 *
 * La CLAVE viaja únicamente en memoria (extra del Intent de arranque) y jamás
 * se escribe a disco. Al terminar (éxito o fallo) el flujo se detiene y se
 * avisa por broadcast; el servicio NO se auto-deshabilita para no romper las
 * ejecuciones programadas (Android exige que el usuario reactive manualmente
 * un servicio de accesibilidad deshabilitado).
 *
 * Notificaciones: broadcasts explícitos con acción [ACTION_EVENTO] y extras
 * [EXTRA_TIPO] ("pantalla" | "error" | "exito") y [EXTRA_MENSAJE]. La captura
 * del éxito la toma quien reciba el broadcast (takeScreenshot, API 30+).
 */
class HermesAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "Hermes"

        // Arranque del flujo
        const val EXTRA_MONTO = "monto"               // String, ej "500"
        const val EXTRA_CLAVE = "clave"               // String, solo memoria
        const val EXTRA_HORA_OBJETIVO = "hora_objetivo" // String "HH:MM:SS"

        // Broadcasts de eventos
        const val ACTION_EVENTO = "com.hermes.bdv.EVENTO"
        const val EXTRA_TIPO = "tipo"       // "pantalla" | "error" | "exito"
        const val EXTRA_MENSAJE = "mensaje"
        const val EXTRA_CAPTURA = "captura"           // ByteArray PNG (solo exito)
        const val EXTRA_CAPTURA_PATH = "captura_path" // ruta a PNG en caché (solo exito)
        const val TIPO_PANTALLA = "pantalla"
        const val TIPO_ERROR = "error"
        const val TIPO_EXITO = "exito"

        private const val PAQUETE_BDV = "com.bancodevenezuela.bdvdigital"
        private const val CTA_DEBITO = "7574"
        private const val CTA_DESTINO = "7470"

        // Reglas de Jhon
        // (límites de reintento eliminados por petición del usuario 29/09/2026:
        // Divisas y Compra reintentan sin límite hasta lograrlo;
        // patrón 29/09/2026 20:12: primera pulsación inmediata, luego
        // pulsar APENAS el botón se active, sin espera fija)
        private const val MS_ESPERA_FORM_TRAS_TAP = 3_000L
        private const val MARGEN_SYNC_LOGIN_SEG = 3.0
        private const val MAX_INTENTOS_COMPROBANTE = 20

        private const val CANAL_ID = "hermes_flujo"
        private const val NOTIF_ID = 1001

        /** Instancia viva del servicio (se fija en onServiceConnected). */
        @Volatile
        var instancia: HermesAccessibilityService? = null
            private set

        /**
         * Punto de entrada recomendado: MainActivity / AlarmReceiver llaman aquí.
         * Retorna false si no se pudo arrancar (p. ej. servicio no habilitado).
         */
        fun iniciar(contexto: Context, monto: String, clave: String, horaObjetivo: String): Boolean {
            return try {
                val intent = Intent(contexto, HermesAccessibilityService::class.java).apply {
                    putExtra(EXTRA_MONTO, monto)
                    putExtra(EXTRA_CLAVE, clave)
                    putExtra(EXTRA_HORA_OBJETIVO, horaObjetivo)
                }
                // startForegroundService cubre el arranque desde segundo plano
                // (AlarmReceiver); el servicio llama a startForeground de inmediato.
                androidx.core.content.ContextCompat.startForegroundService(contexto, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo arrancar el servicio", e)
                false
            }
        }

        /** True si el usuario habilitó Hermes en Ajustes → Accesibilidad. */
        fun estaHabilitado(contexto: Context): Boolean {
            val habilitados = Settings.Secure.getString(
                contexto.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return habilitados.contains(contexto.packageName, ignoreCase = true)
        }

        /** True si el servicio está realmente conectado (bound por el sistema). */
        fun estaConectado(): Boolean = instancia != null

        /**
         * Estado real del servicio:
         * - ACTIVO: habilitado en ajustes Y conectado al sistema.
         * - HABILITADO_SIN_CONEXION: habilitado en ajustes pero el sistema
         *   no lo ha conectado (requiere desactivar/reactivar).
         * - INACTIVO: no habilitado en ajustes.
         */
        fun estadoServicio(contexto: Context): EstadoServicio {
            val habilitado = estaHabilitado(contexto)
            val conectado = estaConectado()
            return when {
                habilitado && conectado -> EstadoServicio.ACTIVO
                habilitado -> EstadoServicio.HABILITADO_SIN_CONEXION
                else -> EstadoServicio.INACTIVO
            }
        }
    }

    enum class EstadoServicio { ACTIVO, HABILITADO_SIN_CONEXION, INACTIVO }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private val candado = Object()

    @Volatile private var hiloFlujo: Thread? = null
    @Volatile private var detenido = false

    // Datos de la ejecución actual (solo memoria, jamás a disco)
    @Volatile private var montoActual: String = ""
    @Volatile private var claveActual: String = ""
    @Volatile private var horaObjetivoActual: String = "08:00:00"

    // ------------------------------------------------------------------
    // Ciclo de vida del servicio
    // ------------------------------------------------------------------

    private val receptorDetener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "recibido DETENER")
            detenerFlujo()
            notificar(TIPO_PANTALLA, "⏹ Ejecución detenida por el usuario.")
        }
    }

    override fun onServiceConnected() {
        instancia = this
        Log.i(TAG, "servicio conectado")
        // Escuchar la orden de detener (la emite MainActivity)
        try {
            val filtro = IntentFilter("com.hermes.bdv.DETENER")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receptorDetener, filtro, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receptorDetener, filtro)
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo registrar receptor DETENER", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Los eventos solo despiertan al flujo; el trabajo pesado vive en hiloFlujo.
        synchronized(candado) { candado.notifyAll() }
    }

    override fun onInterrupt() {
        detenerFlujo()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detenerFlujo()
        if (instancia === this) instancia = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detenerFlujo()
        try { unregisterReceiver(receptorDetener) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monto = intent?.getStringExtra(EXTRA_MONTO)?.trim().orEmpty()
        // La clave se lee del almacén cifrado (preconfigurada por el usuario).
        // El intent puede traerla (compatibilidad), pero la fuente oficial es ClaveSegura.
        val clave = intent?.getStringExtra(EXTRA_CLAVE).orEmpty()
            .ifEmpty { ClaveSegura.leer(this) }
        val hora = intent?.getStringExtra(EXTRA_HORA_OBJETIVO)?.trim().orEmpty()

        if (monto.isEmpty() || clave.isEmpty()) {
            notificar(TIPO_ERROR, "⚠ Faltan datos para ejecutar (monto/clave). Configura la clave en la app.")
            return START_NOT_STICKY
        }
        if (!LogicaPura.validarMonto(monto)) {
            notificar(TIPO_ERROR, "⚠ Monto fuera de rango (1–500 USD): $monto")
            return START_NOT_STICKY
        }

        synchronized(this) {
            if (hiloFlujo?.isAlive == true) {
                Log.w(TAG, "ya hay un flujo en curso, se ignora el arranque")
                return START_NOT_STICKY
            }
            montoActual = monto
            claveActual = clave
            horaObjetivoActual = hora.ifEmpty { "08:00:00" }
            detenido = false
            pasarAPrimerPlano()
            hiloFlujo = Thread(::ejecutarFlujo, "hermes-flujo").also { it.start() }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // Foreground (mantiene vivo el proceso durante la ventana crítica y
    // cumple el requisito de startForegroundService)
    // ------------------------------------------------------------------

    private fun crearCanalNotificacion() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CANAL_ID, "Hermes", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo crear el canal de notificación", e)
        }
    }

    private fun pasarAPrimerPlano() {
        try {
            crearCanalNotificacion()
            val notif: Notification = NotificationCompat.Builder(this, CANAL_ID)
                .setContentTitle("Hermes")
                .setContentText("Ejecutando automatización de compra de divisas")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo pasar a primer plano", e)
        }
    }

    private fun salirDePrimerPlano() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun detenerFlujo() {
        detenido = true
        synchronized(candado) { candado.notifyAll() }
        hiloFlujo?.interrupt()
    }

    // ------------------------------------------------------------------
    // Notificaciones (broadcast explícito)
    // ------------------------------------------------------------------

    /**
     * Aviso de éxito con captura de pantalla (API 30+).
     * La captura se adjunta al broadcast para que el receptor la envíe por Telegram.
     */
    private fun notificarExitoConCaptura(mensaje: String) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            notificar(TIPO_EXITO, mensaje)
            return
        }
        var pngBytes: ByteArray? = null
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            if (bitmap != null) {
                                // Escalar a la mitad: el Binder no acepta >~1MB
                                val escalado = android.graphics.Bitmap.createScaledBitmap(
                                    bitmap, bitmap.width / 2, bitmap.height / 2, true
                                )
                                val stream = java.io.ByteArrayOutputStream()
                                escalado.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG, 75, stream
                                )
                                pngBytes = stream.toByteArray()
                                if (escalado != bitmap) escalado.recycle()
                                bitmap.recycle()
                            }
                            result.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "error procesando captura", e)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot falló: $errorCode")
                        latch.countDown()
                    }
                }
            )
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "captura de éxito falló", e)
        }
        try {
            sendBroadcast(Intent(ACTION_EVENTO).apply {
                setPackage(packageName)
                putExtra(EXTRA_TIPO, TIPO_EXITO)
                putExtra(EXTRA_MENSAJE, mensaje)
                val bytes = pngBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    if (bytes.size <= 800 * 1024) {
                        putExtra(EXTRA_CAPTURA, bytes)
                    } else {
                        val f = java.io.File(cacheDir, "hermes_exito.jpg")
                        f.writeBytes(bytes)
                        putExtra(EXTRA_CAPTURA_PATH, f.absolutePath)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo enviar broadcast de éxito", e)
        }
        Log.i(TAG, "[exito] $mensaje")
    }

    private fun notificar(tipo: String, mensaje: String) {
        Log.i(TAG, "[$tipo] $mensaje")
        try {
            sendBroadcast(Intent(ACTION_EVENTO).apply {
                setPackage(packageName)
                putExtra(EXTRA_TIPO, tipo)
                putExtra(EXTRA_MENSAJE, mensaje)
            })
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo enviar broadcast", e)
        }
    }

    // ------------------------------------------------------------------
    // Utilidades de espera (hilo de trabajo)
    // ------------------------------------------------------------------

    /** Espera interrumpible que se despierta ante eventos de accesibilidad. */
    private fun esperar(ms: Long) {
        if (ms <= 0) return
        val fin = SystemClock.uptimeMillis() + ms
        synchronized(candado) {
            while (!detenido) {
                val resto = fin - SystemClock.uptimeMillis()
                if (resto <= 0) break
                try {
                    candado.wait(minOf(resto, 250))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun esperarHasta(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val fin = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < fin && !detenido &&
            !Thread.currentThread().isInterrupted
        ) {
            if (cond()) return true
            esperar(250)
        }
        return !detenido && cond()
    }

    // ------------------------------------------------------------------
    // Búsqueda de nodos (siempre con raíz fresca y reciclado correcto)
    // ------------------------------------------------------------------

    private fun <T> conRaiz(bloque: (AccessibilityNodeInfo) -> T): T? {
        val raiz = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            bloque(raiz)
        } finally {
            try {
                raiz.recycle()
            } catch (e: Exception) {
                // ignorar
            }
        }
    }

    private fun reciclarSeguro(n: AccessibilityNodeInfo?) {
        try {
            n?.recycle()
        } catch (e: Exception) {
            // ignorar
        }
    }

    /**
     * Busca el primer nodo que cumpla [pred]. Recicla todo lo visitado salvo
     * el nodo devuelto, que el llamador debe reciclar tras usarlo.
     */
    private fun buscarNodo(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val raiz = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null
        val vistos = mutableListOf<AccessibilityNodeInfo>()
        var devuelto: AccessibilityNodeInfo? = null
        try {
            val pila = ArrayDeque<AccessibilityNodeInfo>()
            pila.add(raiz)
            vistos.add(raiz)
            while (pila.isNotEmpty()) {
                val n = pila.removeLast()
                if (pred(n)) {
                    devuelto = n
                    break
                }
                for (i in 0 until n.childCount) {
                    val h = try {
                        n.getChild(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (h != null) {
                        pila.add(h)
                        vistos.add(h)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "error buscando nodo", e)
        } finally {
            for (v in vistos) if (v !== devuelto) reciclarSeguro(v)
        }
        return devuelto
    }

    private fun descDe(n: AccessibilityNodeInfo): String = n.contentDescription?.toString().orEmpty()
    private fun textoDe(n: AccessibilityNodeInfo): String {
        // text puede ser null; algunos botones exponen el rótulo solo en content-desc
        return try {
            n.text?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun coincideDesc(n: AccessibilityNodeInfo, valor: String): Boolean = descDe(n) == valor
    private fun coincideDescContiene(n: AccessibilityNodeInfo, valor: String): Boolean =
        descDe(n).contains(valor)

    private fun coincideTexto(n: AccessibilityNodeInfo, valor: String): Boolean = textoDe(n) == valor
    private fun coincideTextoContiene(n: AccessibilityNodeInfo, valor: String): Boolean =
        textoDe(n).contains(valor)

    private fun esEditText(n: AccessibilityNodeInfo): Boolean =
        n.className?.toString() == "android.widget.EditText"

    /** Sube por padres hasta un ancestro clicable (máx 4 niveles).
     * Recicla los padres intermedios; el nodo devuelto lo recicla el llamador. */
    private fun ancestroClicable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val intermedios = mutableListOf<AccessibilityNodeInfo>()
        var actual: AccessibilityNodeInfo? = n
        var niveles = 0
        while (actual != null && niveles <= 4) {
            if (actual.isClickable) {
                for (m in intermedios) reciclarSeguro(m)
                return actual
            }
            val padre: AccessibilityNodeInfo? = try {
                actual.parent
            } catch (e: Exception) {
                null
            }
            if (padre == null || padre === actual) break
            if (actual !== n) intermedios.add(actual)
            actual = padre
            niveles++
        }
        for (m in intermedios) reciclarSeguro(m)
        return null
    }

    /**
     * Pulsa el primer nodo que coincida, con respaldo descripción → texto.
     * Retorna true si el tap se ejecutó.
     */
    private fun pulsar(
        desc: String? = null,
        descContiene: String? = null,
        texto: String? = null,
        textoContiene: String? = null,
        timeoutMs: Long = 4_000
    ): Boolean {        val estrategias = mutableListOf<(AccessibilityNodeInfo) -> Boolean>()
        desc?.let { v -> estrategias.add { coincideDesc(it, v) } }
        texto?.let { v -> estrategias.add { coincideTexto(it, v) } }
        descContiene?.let { v -> estrategias.add { coincideDescContiene(it, v) } }
        textoContiene?.let { v -> estrategias.add { coincideTextoContiene(it, v) } }
        // Respaldo cruzado descripción<->texto cuando solo se dio uno exacto
        if (estrategias.size == 1) {
            desc?.let { v -> estrategias.add { coincideTexto(it, v) } }
            texto?.let { v -> estrategias.add { coincideDesc(it, v) } }
            descContiene?.let { v -> estrategias.add { coincideTextoContiene(it, v) } }
            textoContiene?.let { v -> estrategias.add { coincideDescContiene(it, v) } }
        }
        for (pred in estrategias) {
            val fin = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < fin && !detenido) {
                val nodo = buscarNodo(pred)
                if (nodo == null) {
                    esperar(400)
                    continue
                }
                try {
                    val objetivo = ancestroClicable(nodo) ?: nodo
                    val ok = try {
                        objetivo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        false
                    }
                    if (objetivo !== nodo) reciclarSeguro(objetivo)
                    reciclarSeguro(nodo)
                    if (ok) {
                        Log.i(TAG, "pulsado desc=$desc texto=$texto")
                        return true
                    }
                } catch (e: Exception) {
                    reciclarSeguro(nodo)
                }
                esperar(500)
            }
        }
        Log.w(TAG, "NO APARECIO botón desc=$desc descContiene=$descContiene texto=$texto")
        return false
    }

    /**
     * Intento ÚNICO de pulsar, SIN esperas ni reintentos internos.
     * Para ciclos apretados donde el llamador controla el ritmo.
     * Retorna true si el tap se ejecutó.
     */
    private fun pulsarUnaVez(
        desc: String? = null,
        descContiene: String? = null,
        texto: String? = null,
        textoContiene: String? = null
    ): Boolean {
        val preds = mutableListOf<(AccessibilityNodeInfo) -> Boolean>()
        desc?.let { v -> preds.add { coincideDesc(it, v) } }
        texto?.let { v -> preds.add { coincideTexto(it, v) } }
        descContiene?.let { v -> preds.add { coincideDescContiene(it, v) } }
        textoContiene?.let { v -> preds.add { coincideTextoContiene(it, v) } }
        // Respaldo cruzado
        if (preds.size == 1) {
            desc?.let { v -> preds.add { coincideTexto(it, v) } }
            texto?.let { v -> preds.add { coincideDesc(it, v) } }
            descContiene?.let { v -> preds.add { coincideTextoContiene(it, v) } }
            textoContiene?.let { v -> preds.add { coincideDescContiene(it, v) } }
        }
        for (pred in preds) {
            if (detenido) return false
            val nodo = buscarNodo(pred) ?: continue
            try {
                val objetivo = ancestroClicable(nodo) ?: nodo
                val ok = try {
                    objetivo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) {
                    false
                }
                if (objetivo !== nodo) reciclarSeguro(objetivo)
                reciclarSeguro(nodo)
                if (ok) return true
            } catch (e: Exception) {
                reciclarSeguro(nodo)
            }
        }
        return false
    }

    /** Estado de un botón: true=habilitado, false=deshabilitado, null=no existe. */
    private fun estadoBoton(desc: String? = null, texto: String? = null): Boolean? {
        val pred: (AccessibilityNodeInfo) -> Boolean = {
            (desc != null && coincideDesc(it, desc)) ||
                (texto != null && coincideTexto(it, texto))
        }
        val nodo = buscarNodo(pred) ?: return null
        return try {
            nodo.isEnabled
        } finally {
            reciclarSeguro(nodo)
        }
    }

    /**
     * Pulsa un botón en cuanto esté visible Y habilitado (polling activo).
     * No espera un tiempo fijo: apenas se activa, lo pulsa de inmediato.
     * Retorna true si logró pulsarlo, false si se agotó el timeout o se detuvo.
     */
    private fun pulsarCuandoListo(
        desc: String? = null,
        descContiene: String? = null,
        texto: String? = null,
        textoContiene: String? = null,
        timeoutMs: Long = 30_000,
        intervaloMs: Long = 250
    ): Boolean {
        val fin = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < fin && !detenido) {
            val estado = estadoBotonGenerico(desc, descContiene, texto, textoContiene)
            if (estado == true) {
                // Activo: pulsar de inmediato
                if (pulsar(desc, descContiene, texto, textoContiene, timeoutMs = 1_000)) {
                    return true
                }
            }
            // No existe o deshabilitado: esperar un poco y reintentar
            esperar(intervaloMs)
        }
        return false
    }

    /** Estado genérico: true=visible y habilitado, false=visible pero deshabilitado, null=no visible. */
    private fun estadoBotonGenerico(
        desc: String?,
        descContiene: String?,
        texto: String?,
        textoContiene: String?
    ): Boolean? {
        val nodo = buscarNodo {
            (desc != null && coincideDesc(it, desc)) ||
                (descContiene != null && coincideDescContiene(it, descContiene)) ||
                (texto != null && coincideTexto(it, texto)) ||
                (textoContiene != null && coincideTextoContiene(it, textoContiene))
        } ?: return null
        return try {
            nodo.isEnabled
        } finally {
            reciclarSeguro(nodo)
        }
    }

    /** Escribe con ACTION_SET_TEXT (Bundle con la charsequence), previo tap de foco. */
    private fun escribirEnCampo(desc: String? = null, texto: String, timeoutMs: Long = 5_000): Boolean {
        val fin = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < fin && !detenido) {
            val nodo = if (desc != null) {
                buscarNodo { coincideDesc(it, desc) || coincideTexto(it, desc) }
            } else {
                buscarNodo { esEditText(it) }
            }
            if (nodo == null) {
                esperar(400)
                continue
            }
            try {
                try {
                    nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (e: Exception) {
                    // seguir: algunos campos aceptan SET_TEXT sin foco previo
                }
                esperar(300)
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        texto
                    )
                }
                val ok = try {
                    nodo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } catch (e: Exception) {
                    false
                }
                reciclarSeguro(nodo)
                if (ok) {
                    Log.i(TAG, "texto escrito en campo")
                    return true
                }
            } catch (e: Exception) {
                reciclarSeguro(nodo)
            }
            esperar(500)
        }
        return false
    }

    // ------------------------------------------------------------------
    // Huellas de pantalla (identificación multi-elemento, solo estáticos)
    // ------------------------------------------------------------------

    /** Cuenta hits; necesita 2 si hay 2+ candidatos (salida temprana). */
    private fun enPantalla(vararg candidatos: () -> Boolean): Boolean {
        var hits = 0
        val necesario = if (candidatos.size >= 2) 2 else 1
        for (c in candidatos) {
            try {
                if (c()) {
                    hits++
                    if (hits >= necesario) return true
                }
            } catch (e: Exception) {
                // ignorar y seguir
            }
            if (detenido) break
        }
        return false
    }

    private fun hayDesc(valor: String): Boolean =
        conRaiz { raiz ->
            buscarNodo { coincideDesc(it, valor) }?.also { reciclarSeguro(it) } != null
        } == true

    private fun hayTexto(valor: String): Boolean =
        conRaiz { raiz ->
            buscarNodo { coincideTexto(it, valor) }?.also { reciclarSeguro(it) } != null
        } == true

    private fun hayDescContiene(valor: String): Boolean =
        conRaiz { raiz ->
            buscarNodo { coincideDescContiene(it, valor) }?.also { reciclarSeguro(it) } != null
        } == true

    private fun hayTextoContiene(valor: String): Boolean =
        conRaiz { raiz ->
            buscarNodo { coincideTextoContiene(it, valor) }?.also { reciclarSeguro(it) } != null
        } == true

    private fun hayEditText(): Boolean =
        conRaiz { raiz ->
            buscarNodo { esEditText(it) }?.also { reciclarSeguro(it) } != null
        } == true

    private fun esSplash(): Boolean = enPantalla(
        { hayDesc("Iniciar sesión") },
        { hayTexto("Iniciar sesión") }
    )

    private fun esOpcionesLogin(): Boolean = enPantalla(
        { hayDescContiene("Ingresa con tu") },
        { hayTextoContiene("Ingresa con tu") }
    )

    private fun esCampoClave(): Boolean = enPantalla(
        { hayEditText() },
        { hayDesc("Aceptar") }
    )

    private fun esInicio(): Boolean = enPantalla(
        { hayDescContiene("Saldo Cuenta") },
        { hayDescContiene("0102") }
    )

    private fun esMenuDivisas(): Boolean = enPantalla(
        { hayDesc("Divisas") },
        { hayDesc("Scrim") }
    )

    private fun esFormCompra(): Boolean = enPantalla(
        { hayDesc("Compra de divisas") },
        { hayTexto("Compra de divisas") },
        { hayDescContiene("Cuenta a debitar") }
    )

    /** El MISMO formulario se expande tras el primer Continuar; el avance real
     * es la aparición de la sección "Destino de los fondos". */
    private fun esFormCompraSinExpandir(): Boolean {
        if (!enPantalla(
                { hayDesc("Compra de divisas") },
                { hayDescContiene("Cuenta a debitar") }
            )
        ) return false
        return !hayDescContiene("Destino de los fondos")
    }

    private fun esFormPaso2(): Boolean = enPantalla(
        { hayDescContiene("Destino de los fondos") },
        { hayTextoContiene("Destino de los fondos") }
    )

    private fun esConfirmar(): Boolean = enPantalla(
        { hayDesc("Confirmar Operación") },
        { hayTexto("Confirmar Operación") },
        { hayDescContiene("Confirmar Operaci") }
    )

    private fun esComprobante(): Boolean =
        hayDescContiene("Comprobante") || hayTextoContiene("Comprobante")

    private fun esNoDisponible(): Boolean =
        hayDescContiene("operaciones cambiarias") ||
            hayTextoContiene("disponibles m") ||
            hayTextoContiene("No se encuentra disponible el mercado")

    // ------------------------------------------------------------------
    // Acciones sobre la app BDV
    // ------------------------------------------------------------------

    private fun abrirApp(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(PAQUETE_BDV) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo abrir $PAQUETE_BDV", e)
            false
        }
    }

    /** Cierre sin root: BACK repetido + HOME; el ciclo re-abre y re-loguea. */
    private fun cerrarApp() {
        repeat(6) {
            if (detenido) return
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                // ignorar
            }
            esperar(400)
        }
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            // ignorar
        }
        esperar(500)
    }

    private fun atrasGlobal() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            // ignorar
        }
    }

    /** Cierra el diálogo "Ha ocurrido un error inesperado" con Aceptar. */
    private fun cerrarDialogoError(): Boolean {
        if (!hayTextoContiene("Ha ocurrido un error inesperado")) return false
        val ok = pulsar(desc = "Aceptar", texto = "Aceptar", timeoutMs = 2_000)
        if (ok) Log.i(TAG, "diálogo 'error inesperado' cerrado")
        return ok
    }

    /** Análisis de pantalla desconocida (para logcat / diagnóstico). */
    private fun analizarPantalla(contexto: String) {
        try {
            conRaiz { raiz ->
                val botones = mutableListOf<String>()
                val textos = mutableListOf<String>()
                val pila = ArrayDeque<AccessibilityNodeInfo>()
                pila.add(raiz)
                val vistos = mutableListOf<AccessibilityNodeInfo>()
                while (pila.isNotEmpty() && botones.size + textos.size < 40) {
                    val n = pila.removeLast()
                    vistos.add(n)
                    val d = descDe(n)
                    val t = textoDe(n)
                    val etiqueta = d.ifEmpty { t }
                    if (etiqueta.isNotEmpty() && etiqueta.length <= 80) {
                        if (n.isClickable) botones.add(etiqueta)
                        else if (t.isNotEmpty() && t.length < 50) textos.add(t)
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { pila.add(it); vistos.add(it) }
                    }
                }
                // Reciclar todo lo visitado salvo la raíz (la recicla conRaiz)
                for (v in vistos) if (v !== raiz) reciclarSeguro(v)
                Log.i(TAG, "pantalla desconocida ($contexto) botones=${botones.take(8)} textos=${textos.take(5)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "error analizando pantalla", e)
        }
    }

    /** Recuperación genérica: Continuar/Aceptar/Confirmar habilitados, sino BACK. */
    private fun intentarRecuperar(contexto: String): Boolean {
        analizarPantalla(contexto)
        for (boton in listOf("Continuar", "Aceptar", "Confirmar")) {
            val nodo = buscarNodo {
                val d = descDe(it)
                val t = textoDe(it)
                (d.contains(boton, ignoreCase = true) || t.contains(boton, ignoreCase = true)) &&
                    it.isClickable && it.isEnabled
            }
            if (nodo != null) {
                try {
                    val etiqueta = descDe(nodo).ifEmpty { textoDe(nodo) }
                    nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "recuperación: pulsando '$etiqueta'")
                    reciclarSeguro(nodo)
                    return true
                } catch (e: Exception) {
                    reciclarSeguro(nodo)
                }
            }
            if (detenido) return false
        }
        Log.i(TAG, "recuperación: sin botón de avance, BACK global")
        atrasGlobal()
        esperar(1_000)
        return true
    }

    /**
     * Pulsa un botón hasta avanzar de pantalla, SIN LÍMITE de intentos
     * (comportamiento BDV: si el tap no avanza, el botón se desactiva ~10s
     * y se reactiva → se reintenta al reactivarse).
     */
    private fun pulsarConReintento(
        nombre: String,
        verificarPantalla: (() -> Boolean)?,
        yaAvanzo: (() -> Boolean)? = null,
        desc: String? = null,
        texto: String? = null
    ): Boolean {
        var intento = 0
        while (!detenido && !Thread.currentThread().isInterrupted) {
            intento++
            // 1. Verificar pantalla correcta
            if (verificarPantalla != null && !verificarPantalla()) {
                if (yaAvanzo?.invoke() == true) {
                    Log.i(TAG, "'$nombre' ya había avanzado")
                    return true
                }
                Log.w(TAG, "no estamos en la pantalla de '$nombre', no se pulsa")
                return false
            }
            // 2. Esperar botón habilitado (15s cubre la ventana de ~10s desactivado)
            val btn = esperarBotonHabilitado(desc, texto, 15_000)
            if (btn == null) {
                Log.i(TAG, "$nombre ya no visible (avanzó) intento $intento")
                return true
            }
            // 3. Re-verificar justo antes de pulsar
            if (verificarPantalla != null && !verificarPantalla()) {
                reciclarSeguro(btn)
                if (yaAvanzo?.invoke() == true) {
                    Log.i(TAG, "'$nombre' ya había avanzado (antes de pulsar)")
                    return true
                }
                Log.w(TAG, "pantalla cambió antes de pulsar '$nombre'")
                return false
            }
            val tClick = SystemClock.uptimeMillis()
            try {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                Log.w(TAG, "clic falló en '$nombre', reintentando")
            }
            reciclarSeguro(btn)
            Log.i(TAG, "$nombre pulsado (intento $intento)")
            // 4. Post-tap: o avanza, o el botón se desactiva y reactiva → reintentar
            var avanzo = false
            val finPost = tClick + 16_000
            while (SystemClock.uptimeMillis() < finPost && !detenido) {
                if (cerrarDialogoError()) {
                    Log.i(TAG, "diálogo de error tras pulsar $nombre, reintentando")
                    break
                }
                when (estadoBoton(desc, texto)) {
                    null -> {
                        Log.i(TAG, "$nombre desapareció (avanzó)")
                        avanzo = true
                        break
                    }
                    false -> esperar(1_000) // desactivado: la app procesa
                    true -> {
                        if (SystemClock.uptimeMillis() - tClick > 2_000) {
                            if (verificarPantalla != null && !verificarPantalla()) {
                                Log.i(TAG, "pantalla cambió tras pulsar $nombre (avanzó)")
                                avanzo = true
                            } else {
                                Log.i(TAG, "$nombre reactivado sin avanzar, reintentando")
                            }
                            break
                        }
                        esperar(500)
                    }
                }
            }
            if (avanzo) return true
            // timeout o reactivación sin avance → el while externo reintenta
            if (detenido) return false
            Log.i(TAG, "reintentando '$nombre'...")
        }
        return false
    }

    /** Espera hasta [timeoutMs] un nodo habilitado que coincida; null si no aparece. */
    private fun esperarBotonHabilitado(
        desc: String?,
        texto: String?,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val fin = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < fin && !detenido) {
            cerrarDialogoError()
            val nodo = buscarNodo {
                ((desc != null && coincideDesc(it, desc)) ||
                    (texto != null && coincideTexto(it, texto)) ||
                    (desc != null && coincideTexto(it, desc)))
            }
            if (nodo != null) {
                val habilitado = try {
                    nodo.isEnabled
                } catch (e: Exception) {
                    false
                }
                if (habilitado) return nodo // el llamador recicla
                reciclarSeguro(nodo)
            }
            esperar(200) // polling apretado: apenas se active, se detecta
        }
        return null
    }

    // ------------------------------------------------------------------
    // Sincronización horaria del login
    // ------------------------------------------------------------------

    /** Espera hasta (horaObjetivo - margen) con SystemClock; si ya pasó, sigue. */
    private fun esperarHoraObjetivo(horaStr: String, margenSeg: Double = MARGEN_SYNC_LOGIN_SEG) {
        try {
            val partes = horaStr.split(":")
            if (partes.size < 2) return
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, partes[0].toInt())
            cal.set(Calendar.MINUTE, partes[1].toInt())
            cal.set(Calendar.SECOND, partes.getOrNull(2)?.toInt() ?: 0)
            cal.set(Calendar.MILLISECOND, 0)
            val momentoPulsar = cal.timeInMillis - (margenSeg * 1_000).toLong()
            var espera = momentoPulsar - System.currentTimeMillis()
            if (espera > 0) {
                Log.i(TAG, "esperando ${espera / 1000.0}s para sincronizar login a las $horaStr")
            }
            while (espera > 0 && !detenido) {
                esperar(minOf(espera, 200))
                espera = momentoPulsar - System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "hora objetivo inválida '$horaStr', continuando", e)
        }
    }

    // ------------------------------------------------------------------
    // Paso [1/6]: login
    // ------------------------------------------------------------------

    private fun login(): Boolean {
        if (!abrirApp()) {
            Log.w(TAG, "no se pudo abrir la app BDV")
            return false
        }
        esperar(500)

        // Splash: si no hay "Iniciar sesión", quizá la sesión ya es válida
        if (!esperarHasta(3_000) { hayDesc("Iniciar sesión") }) {
            if (esperarHasta(5_000) { hayDesc("Inicio") || hayTexto("Inicio") }) {
                Log.i(TAG, "sesión ya válida")
                return true
            }
            Log.w(TAG, "splash no reconocido")
            return false
        }
        if (!pulsar(desc = "Iniciar sesión", timeoutMs = 5_000)) {
            Log.i(TAG, "app ya estaba más adelante del splash")
        }

        // Esperar link "Ingresa con tu contraseña" (cancelar huella si aparece)
        var linkOk = false
        val finLink = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < finLink && !detenido) {
            if (hayDescContiene("Ingresa con tu") || hayTextoContiene("Ingresa con tu")) {
                linkOk = true
                break
            }
            val cancelar = buscarNodo { coincideDesc(it, "CANCELAR") }
            if (cancelar != null) {
                try {
                    cancelar.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "huella cancelada")
                } catch (e: Exception) {
                    // ignorar
                }
                reciclarSeguro(cancelar)
            }
            esperar(300)
        }
        if (!linkOk) {
            Log.w(TAG, "no apareció el link de contraseña")
            return false
        }
        pulsar(descContiene = "Ingresa con tu", textoContiene = "Ingresa con tu", timeoutMs = 5_000)
        Log.i(TAG, "link 'ingresa con tu contraseña'")

        if (!esperarHasta(4_000) { hayEditText() }) {
            Log.w(TAG, "campo clave no encontrado")
            return false
        }
        // Escribir la clave con ACTION_SET_TEXT (solo memoria, jamás a disco)
        if (!escribirEnCampo(texto = claveActual, timeoutMs = 5_000)) {
            Log.w(TAG, "no se pudo escribir la clave")
            return false
        }
        Log.i(TAG, "clave escrita")

        // Sincronización: el login debe completarse a la hora objetivo
        esperarHoraObjetivo(horaObjetivoActual, MARGEN_SYNC_LOGIN_SEG)

        val aceptar = buscarNodo { coincideDesc(it, "Aceptar") || coincideTexto(it, "Aceptar") }
        if (aceptar == null) {
            Log.w(TAG, "no se encontró Aceptar")
            return false
        }
        try {
            val objetivo = ancestroClicable(aceptar) ?: aceptar
            try {
                objetivo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                Log.w(TAG, "tap en Aceptar falló", e)
                if (objetivo !== aceptar) reciclarSeguro(objetivo)
                reciclarSeguro(aceptar)
                return false
            }
            if (objetivo !== aceptar) reciclarSeguro(objetivo)
        } catch (e: Exception) {
            Log.w(TAG, "tap en Aceptar falló", e)
            reciclarSeguro(aceptar)
            return false
        }
        reciclarSeguro(aceptar)
        Log.i(TAG, "Aceptar pulsado")
        esperar(300)

        // Verificar login: aparece Divisas o Inicio. Si no: clave rechazada → NO reintentar.
        val ok = esperarHasta(3_000) {
            hayDescContiene("Divisas") || hayDesc("Inicio") || hayTexto("Divisas")
        }
        if (ok) Log.i(TAG, "login verificado")
        else Log.w(TAG, "no se llegó al Inicio: clave incorrecta o error (NO se reintenta)")
        return ok
    }

    // ------------------------------------------------------------------
    // Paso [2/6]: Divisas (reintento sin límite)
    // ------------------------------------------------------------------

    private fun abrirDivisas(): Boolean {
        // Pulsación INMEDIATA tras el login, sin esperas.
        // Reintento sin límite hasta lograr el tap (intento único por vuelta).
        var i = 0
        while (!detenido) {
            i++
            if (pulsarUnaVez(descContiene = "Divisas", texto = "Divisas")) {
                Log.i(TAG, "Divisas pulsado (intento $i)")
                return true
            }
            // No visible aún: el ciclo apretado reintenta sin pausa
        }
        return false
    }

    // ------------------------------------------------------------------
    // Paso [3/6]: Compra (tap inmediato, reintento sin límite)
    // ------------------------------------------------------------------

    /** true=visible y habilitado, false=visible pero deshabilitado, null=no visible */
    private fun estadoCompra(): Boolean? {
        val nodo = buscarNodo { coincideDesc(it, "Compra") || coincideTexto(it, "Compra") }
            ?: return null
        return try {
            nodo.isEnabled
        } finally {
            reciclarSeguro(nodo)
        }
    }

    /**
     * Ciclo APRETADO Divisas→Compra (petición del usuario 29/09/2026 23:19):
     * SIN TIEMPOS MUERTOS. En cada vuelta:
     * - Si el formulario ya abrió: listo.
     * - Si Compra está ACTIVO: se pulsa DE INMEDIATO (intento único, sin espera).
     * - Si no: se pulsa Divisas DE INMEDIATO (intento único, sin espera).
     * Repetir hasta pasar a la siguiente etapa. Sin límite de intentos.
     */
    private fun cicloCompra(): Boolean {
        var intentosCompra = 0
        while (!detenido) {
            // ¿Ya abrió el formulario? (el tap anterior pudo funcionar)
            if (esFormCompra()) {
                Log.i(TAG, "formulario abierto")
                return true
            }
            when (estadoCompra()) {
                true -> {
                    // ACTIVO: pulsar DE INMEDIATO, sin espera previa ni posterior
                    intentosCompra++
                    pulsarUnaVez(desc = "Compra", texto = "Compra")
                    // El siguiente ciclo verifica si abrió; sin pausa aquí
                }
                else -> {
                    // No visible o deshabilitado: pulsar Divisas DE INMEDIATO
                    pulsarUnaVez(descContiene = "Divisas", texto = "Divisas")
                }
            }
            // Sin esperar(): ciclo apretado, el ritmo lo marca la UI
            if (esNoDisponible()) {
                Log.i(TAG, "mercado no disponible, insistiendo")
            }
        }
        Log.i(TAG, "cicloCompra detenido tras $intentosCompra taps a Compra")
        return false
    }

    // ------------------------------------------------------------------
    // Paso [4/6]: formulario — cuentas + monto + Continuar
    // ------------------------------------------------------------------

    private fun elegirCuenta(ultimos4: String): Boolean {
        val nodo = buscarNodo {
            coincideDescContiene(it, ultimos4) || coincideTextoContiene(it, ultimos4)
        } ?: run {
            Log.w(TAG, "cuenta **$ultimos4 no encontrada")
            return false
        }
        return try {
            val objetivo = ancestroClicable(nodo) ?: nodo
            val ok = objetivo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (objetivo !== nodo) reciclarSeguro(objetivo)
            Log.i(TAG, "cuenta **$ultimos4 elegida")
            esperar(300)
            ok
        } catch (e: Exception) {
            false
        } finally {
            reciclarSeguro(nodo)
        }
    }

    private fun formatoMonto(monto: String): String = LogicaPura.formatoMonto(monto)

    private fun formularioCuentasMonto(): Boolean {
        if (!esperarHasta(3_000) { esFormCompra() }) {
            Log.w(TAG, "no se detectó el formulario 'Compra de divisas'")
            return false
        }
        for ((selector, ult4) in listOf("Cuenta a debitar:" to CTA_DEBITO, "Cuenta destino:" to CTA_DESTINO)) {
            if (!pulsar(desc = selector, timeoutMs = 5_000)) {
                Log.w(TAG, "selector '$selector' no encontrado")
                return false
            }
            esperar(300)
            if (!elegirCuenta(ult4)) return false
        }
        if (!escribirEnCampo(texto = formatoMonto(montoActual), timeoutMs = 5_000)) {
            Log.w(TAG, "campo de monto no encontrado")
            return false
        }
        Log.i(TAG, "monto $montoActual escrito")
        atrasGlobal() // bajar el teclado
        // Continuar sin límite; el avance real es la expansión del formulario
        return pulsarConReintento(
            nombre = "Continuar pantalla 1",
            verificarPantalla = { esFormCompraSinExpandir() },
            yaAvanzo = { esFormPaso2() },
            desc = "Continuar",
            texto = "Continuar"
        )
    }

    // ------------------------------------------------------------------
    // Paso [5/6]: destino y actividad + Continuar
    // ------------------------------------------------------------------

    private fun pasoDestinoActividad(): Boolean {
        if (pulsar(descContiene = "Destino de los fondos", textoContiene = "Destino de los fondos", timeoutMs = 6_000)) {
            esperar(300)
            if (pulsar(desc = "Otros", texto = "Otros", timeoutMs = 5_000)) {
                Log.i(TAG, "Destino=Otros")
            }
        }
        if (pulsar(descContiene = "Actividad", timeoutMs = 6_000)) {
            esperar(300)
            if (pulsar(descContiene = "No aplica", textoContiene = "No aplica", timeoutMs = 5_000)) {
                Log.i(TAG, "Actividad=No aplica")
            }
        }
        return pulsarConReintento(
            nombre = "Continuar pantalla 2",
            verificarPantalla = { esFormPaso2() },
            desc = "Continuar",
            texto = "Continuar"
        )
    }

    // ------------------------------------------------------------------
    // Paso [6/6]: confirmar operación (automático) + comprobante
    // ------------------------------------------------------------------

    private fun confirmarYComprobante(): Boolean {
        if (!esperarHasta(10_000) { esConfirmar() }) {
            Log.w(TAG, "no se detectó 'Confirmar Operación'")
            return false
        }
        Log.i(TAG, "pantalla Confirmar Operación detectada")
        notificar(TIPO_PANTALLA, "Paso 6/6: Confirmar Operación.")

        // Pulsar Confirmar AUTOMÁTICAMENTE (Jhon: ningún paso es manual).
        // yaAvanzo=esComprobante: si al cerrar el diálogo la operación ya se
        // completó, NO se reintenta (evita duplicar la compra).
        val okConfirmar = pulsarConReintento(
            nombre = "Confirmar Operación",
            verificarPantalla = { esConfirmar() },
            yaAvanzo = { esComprobante() },
            desc = "Confirmar Operación",
            texto = "Confirmar Operación"
        )
        if (!okConfirmar) {
            Log.w(TAG, "no se pudo pulsar Confirmar")
            return false
        }
        repeat(MAX_INTENTOS_COMPROBANTE) {
            if (detenido) return false
            if (esComprobante() || hayTextoContiene("exitosa") || hayDescContiene("exitosa") ||
                hayTextoContiene("operación exitosa")
            ) {
                return true
            }
            esperar(300)
        }
        Log.w(TAG, "no se detectó comprobante")
        return false
    }

    // ------------------------------------------------------------------
    // Máquina de estados principal (hilo de trabajo)
    // ------------------------------------------------------------------

    private fun ejecutarFlujo() {
        var ciclo = 0
        try {
            // Aviso de inicio (petición del usuario 29/09/2026)
            notificar(TIPO_PANTALLA, "▶ Hermes iniciado ($montoActual USD). Paso 1/6: Login.")
            while (!detenido && !Thread.currentThread().isInterrupted) {
                ciclo++
                Log.i(TAG, "=== ciclo $ciclo ===")

                // [1/6] Login
                notificar(TIPO_PANTALLA, "📝 Iniciando sesión...")
                if (!login()) {
                    // Clave rechazada o fallo: NO reintentar (evitar bloqueo)
                    notificar(TIPO_ERROR, "⚠ FALLO en login (no se reintenta para evitar bloqueo).")
                    break
                }
                notificar(TIPO_PANTALLA, "✓ Sesión iniciada. Paso 2/6: Divisas.")

                // [2/6] Divisas (reintento sin límite hasta abrir)
                if (!abrirDivisas()) {
                    Log.i(TAG, "ciclo $ciclo: detenido por el usuario durante Divisas")
                    break
                }
                notificar(TIPO_PANTALLA, "Paso 3/6: Compra de divisas.")

                // [3/6] Compra (reintento sin límite hasta abrir)
                if (!cicloCompra()) {
                    Log.i(TAG, "ciclo $ciclo: detenido por el usuario durante Compra")
                    break
                }
                notificar(TIPO_PANTALLA, "Paso 4/6: Cuentas y monto.")

                // [4/6] Formulario
                if (!formularioCuentasMonto()) {
                    notificar(TIPO_ERROR, "⚠ FALLO: Continuar no avanzó en pantalla 1.")
                    break
                }

                // [5/6] Destino y actividad
                notificar(TIPO_PANTALLA, "Paso 5/6: Destino de los fondos.")
                if (!pasoDestinoActividad()) {
                    notificar(TIPO_ERROR, "⚠ FALLO: Continuar no avanzó en pantalla 2.")
                    break
                }

                // [6/6] Confirmar + comprobante
                if (confirmarYComprobante()) {
                    notificarExitoConCaptura("🎉 ¡Felicidades, lograste comprar divisas!")
                    break
                } else {
                    notificar(TIPO_ERROR, "⚠ No se detectó comprobante; revisa la app.")
                    break
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.i(TAG, "flujo interrumpido")
        } catch (e: Exception) {
            Log.e(TAG, "error inesperado en el flujo", e)
            notificar(TIPO_ERROR, "⚠ Error inesperado: ${e.message}")
        } finally {
            // Limpiar la clave de memoria al terminar
            claveActual = ""
            montoActual = ""
            salirDePrimerPlano()
            synchronized(this) {
                if (Thread.currentThread() === hiloFlujo) hiloFlujo = null
            }
            Log.i(TAG, "flujo terminado")
            // NOTA: no se llama a disableSelf(): Android exige reactivación
            // manual y rompería las ejecuciones programadas. El servicio queda
            // habilitado y listo para el próximo arranque.
        }
    }
}
