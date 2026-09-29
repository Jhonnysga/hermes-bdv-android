package com.hermes.bdv

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Pantalla principal de Hermes BDV (UI 100% en español, construida por código).
 *
 * CONTRATO CON HermesAccessibilityService (lo implementa el servicio):
 * - Para iniciar una ejecución, esta Activity envía un startService() con un
 *   Intent explícito (ComponentName "com.hermes.bdv/.HermesAccessibilityService"),
 *   acción [ACCION_EJECUTAR] y extras [EXTRA_MONTO] (String) y
 *   [EXTRA_HORA_OBJETIVO] (String "HH:mm:ss" o "ahora").
 *   El servicio debe atenderlo en onStartCommand() y leer la clave desde
 *   [claveEnMemoria] (SOLO memoria, nunca disco).
 * - Para detener, se emite el broadcast [ACCION_DETENER]; el servicio debe
 *   tener un receptor dinámico registrado para esa acción y abortar el ciclo.
 * - El servicio emite broadcasts [HermesEventReceiver.ACCION_EVENTO] con los
 *   extras "tipo"/"mensaje"/"captura"/"captura_path" según progresa.
 *
 * SEGURIDAD: la clave bancaria jamás se persiste. Vive únicamente en
 * [claveEnMemoria] durante la ejecución y se limpia al terminar.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Clave bancaria SOLO en memoria. Se limpia al terminar cada ejecución. */
        var claveEnMemoria: String? = null

        const val EXTRA_EJECUTAR_PROGRAMADO = "ejecutar_programado"

        const val ACCION_EJECUTAR = "com.hermes.bdv.EJECUTAR"
        const val ACCION_DETENER = "com.hermes.bdv.DETENER"
        const val EXTRA_MONTO = "monto"
        const val EXTRA_HORA_OBJETIVO = "hora_objetivo"

        /** Paquetes candidatos de la app del Banco de Venezuela. */
        val PAQUETES_BDV = listOf(
            "com.bancodevenezuela.bdvdigital",
            "com.bdvapp",
            "com.bdv.personas"
        )

        private const val SERVICIO_ACCESIBILIDAD =
            "com.hermes.bdv/.HermesAccessibilityService"

        private const val FORMATO_FECHA = "EEEE d/MM HH:mm"
    }

    private lateinit var txtEstadoAccesibilidad: TextView
    private lateinit var txtListaProgramaciones: LinearLayout
    private lateinit var txtRegistro: TextView
    private lateinit var scrollRegistro: ScrollView
    private lateinit var switchAvisos: Switch

    private var iniCal: Calendar? = null
    private var finCal: Calendar? = null
    private lateinit var btnIniFecha: Button
    private lateinit var btnFinFecha: Button

    private var receiver: HermesEventReceiver? = null
    private var ultimaProgramacionProcesada: Long = 0L

    private val formatoFecha = SimpleDateFormat(FORMATO_FECHA, Locale("es", "VE"))
    private val formatoCorto = SimpleDateFormat("d/MM HH:mm", Locale("es", "VE"))

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(32))
                buildUI(this)
            })
        }
        setContentView(raiz)

        manejarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        receiver = HermesEventReceiver { linea -> agregarRegistro(linea) }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(HermesEventReceiver.ACCION_EVENTO),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        receiver?.let { unregisterReceiver(it) }
        receiver = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadoAccesibilidad()
        refrescarProgramaciones()
        switchAvisos.isChecked = Config.isAvisosActivos(this)
    }

    private fun buildUI(cont: LinearLayout) {
        cont.addView(titulo("Hermes BDV", 24f))

        // --- Estado del servicio de accesibilidad ---
        cont.addView(seccion("Estado"))
        txtEstadoAccesibilidad = texto("")
        cont.addView(txtEstadoAccesibilidad)
        cont.addView(boton("Abrir ajustes de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // --- Monto ---
        cont.addView(seccion("Monto (USD por operación)"))
        val etMonto = campo(Config.getMonto(this), InputType.TYPE_CLASS_NUMBER)
        cont.addView(etMonto)
        cont.addView(boton("Guardar monto") {
            val v = etMonto.text.toString().trim()
            if (v.isEmpty()) {
                toast("Escribe un monto válido")
            } else {
                Config.setMonto(this, v)
                toast("Monto guardado: $v USD")
                agregarRegistro("Monto configurado: $v USD")
            }
        })

        // --- Telegram ---
        cont.addView(seccion("Telegram"))
        val etToken = campo(Config.getTokenTelegram(this)).apply {
            hint = "Token del bot"
        }
        cont.addView(etToken)
        val chatGuardado = Config.getChatTelegram(this)
        val etChat = campo(if (chatGuardado == 0L) "" else chatGuardado.toString()).apply {
            hint = "Chat ID"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        cont.addView(etChat)
        cont.addView(boton("Guardar Telegram") {
            Config.setTokenTelegram(this, etToken.text.toString())
            val chat = etChat.text.toString().trim().toLongOrNull() ?: 0L
            Config.setChatTelegram(this, chat)
            toast("Telegram guardado")
            agregarRegistro("Telegram configurado (chat $chat)")
        })

        // --- Programar rango ---
        cont.addView(seccion("Programar (rango de días hábiles)"))
        btnIniFecha = boton("Inicio: sin elegir") { elegirFechaHora(true) }
        cont.addView(btnIniFecha)
        btnFinFecha = boton("Cierre: sin elegir") { elegirFechaHora(false) }
        cont.addView(btnFinFecha)
        cont.addView(boton("Programar rango") { programarRango() })

        // --- Lista de programaciones ---
        cont.addView(seccion("Programadas"))
        txtListaProgramaciones = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        cont.addView(txtListaProgramaciones)
        cont.addView(boton("Borrar todas las programaciones") {
            confirmar("¿Borrar todas las programaciones?") {
                Scheduler.cancelarTodos(this)
                refrescarProgramaciones()
                agregarRegistro("Programaciones borradas.")
            }
        })

        // --- Ejecución ---
        cont.addView(seccion("Ejecución"))
        cont.addView(boton("▶ Ejecutar ahora") { flujoEjecutarAhora() })
        cont.addView(boton("⏹ Detener") { flujoDetener() })

        // --- Avisos ---
        cont.addView(seccion("Avisos"))
        switchAvisos = Switch(this).apply {
            text = "Avisos por Telegram"
            isChecked = Config.isAvisosActivos(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Config.setAvisosActivos(this@MainActivity, checked)
                agregarRegistro("Avisos ${if (checked) "activados" else "desactivados"}")
            }
        }
        cont.addView(switchAvisos)
        cont.addView(texto(
            "Los avisos de pantalla y errores llegan solo como texto; " +
                "la captura solo se envía al lograr la compra."
        ))

        // --- Registro ---
        cont.addView(seccion("Registro de eventos"))
        scrollRegistro = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            )
        }
        txtRegistro = TextView(this).apply {
            text = "—\n"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollRegistro.addView(txtRegistro)
        cont.addView(scrollRegistro)
    }

    // ------------------------------------------------------- Programación

    private fun elegirFechaHora(esInicio: Boolean) {
        val base = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                TimePickerDialog(
                    this,
                    { _, h, min ->
                        val cal = Calendar.getInstance().apply {
                            set(y, m, d, h, min, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        if (esInicio) {
                            iniCal = cal
                            btnIniFecha.text = "Inicio: ${formatoCorto.format(cal.time)}"
                        } else {
                            finCal = cal
                            btnFinFecha.text = "Cierre: ${formatoCorto.format(cal.time)}"
                        }
                    },
                    base.get(Calendar.HOUR_OF_DAY),
                    base.get(Calendar.MINUTE),
                    true
                ).show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * Genera una programación por cada día hábil (lunes a viernes) dentro del
     * rango, a la hora elegida como inicio, con el monto configurado.
     */
    private fun programarRango() {
        val ini = iniCal
        val fin = finCal
        if (ini == null || fin == null) {
            toast("Elige fecha y hora de inicio y de cierre")
            return
        }
        if (!fin.after(ini)) {
            toast("El cierre debe ser posterior al inicio")
            return
        }
        val monto = Config.getMonto(this)
        val ahora = System.currentTimeMillis()
        var creadas = 0

        val dia = (ini.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val ultimoDia = (fin.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (!dia.after(ultimoDia)) {
            val dow = dia.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                val cita = (dia.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, ini.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, ini.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                if (cita.timeInMillis > ahora + 60_000) {
                    Scheduler.programar(this, cita.timeInMillis, monto)
                    creadas++
                }
            }
            dia.add(Calendar.DAY_OF_MONTH, 1)
        }

        if (creadas == 0) {
            toast("No hay días hábiles futuros en ese rango")
        } else {
            toast("Programadas $creadas ejecuciones")
            agregarRegistro("Rango programado: $creadas ejecuciones ($monto USD c/u)")
        }
        refrescarProgramaciones()
    }

    private fun refrescarProgramaciones() {
        if (!::txtListaProgramaciones.isInitialized) return
        txtListaProgramaciones.removeAllViews()
        val lista = Config.obtenerProgramaciones(this)
        if (lista.isEmpty()) {
            txtListaProgramaciones.addView(texto("Sin programaciones."))
            return
        }
        lista.forEachIndexed { index, p ->
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val lbl = TextView(this).apply {
                text = "${formatoFecha.format(p.fechaHora)} — ${p.monto} USD"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this).apply {
                text = "Borrar"
                setOnClickListener {
                    Scheduler.cancelarUna(this@MainActivity, p.fechaHora)
                    refrescarProgramaciones()
                    agregarRegistro("Programación borrada: ${formatoCorto.format(p.fechaHora)}")
                }
            }
            fila.addView(lbl)
            fila.addView(btn)
            txtListaProgramaciones.addView(fila)
            if (index < lista.size - 1) {
                txtListaProgramaciones.addView(espaciador())
            }
        }
    }

    // -------------------------------------------------------- Ejecución

    private fun manejarIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_EJECUTAR_PROGRAMADO, false)) return
        val fh = intent.getLongExtra(AlarmReceiver.EXTRA_FECHA_HORA, 0L)
        val monto = intent.getStringExtra(AlarmReceiver.EXTRA_MONTO)
            ?: Config.getMonto(this)
        if (fh == 0L || fh == ultimaProgramacionProcesada) return
        ultimaProgramacionProcesada = fh

        agregarRegistro("Alarma: ejecución programada de $monto USD")
        if (claveEnMemoria.isNullOrEmpty()) {
            pedirClave(monto, esProgramada = true) { clave ->
                iniciarEjecucion(monto, clave, horaObjetivo = "ahora")
            }
        } else {
            // Caso de pruebas: ya hay clave en memoria
            iniciarEjecucion(monto, claveEnMemoria!!, horaObjetivo = "ahora")
        }
    }

    private fun flujoEjecutarAhora() {
        if (!servicioAccesibilidadActivo()) {
            AlertDialog.Builder(this)
                .setTitle("Servicio inactivo")
                .setMessage(
                    "Activa el servicio de accesibilidad \"Hermes BDV\" en " +
                        "Ajustes → Accesibilidad antes de ejecutar."
                )
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if (!bdvInstalado()) {
            AlertDialog.Builder(this)
                .setTitle("App del BDV no encontrada")
                .setMessage(
                    "No se encontró instalada la app del Banco de Venezuela. " +
                        "Instálala antes de ejecutar."
                )
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        val monto = Config.getMonto(this)
        pedirClave(monto, esProgramada = false) { clave ->
            iniciarEjecucion(monto, clave, horaObjetivo = "ahora")
        }
    }

    /**
     * Pide la clave con campo de contraseña. No se guarda en ningún lado:
     * al confirmar solo queda en [claveEnMemoria].
     */
    private fun pedirClave(monto: String, esProgramada: Boolean, alConfirmar: (String) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        layout.addView(TextView(this).apply {
            text = if (esProgramada) {
                "Ejecución programada ($monto USD).\nEscribe tu clave para iniciar:"
            } else {
                "Monto: $monto USD\nEscribe tu clave (no se guarda):"
            }
        })
        val etClave = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Clave"
        }
        layout.addView(etClave)

        AlertDialog.Builder(this)
            .setTitle("Clave del BDV")
            .setView(layout)
            .setPositiveButton("Iniciar") { _, _ ->
                val clave = etClave.text.toString()
                if (clave.isEmpty()) {
                    toast("La clave no puede estar vacía")
                } else {
                    alConfirmar(clave)
                }
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(true)
            .show()
    }

    private fun iniciarEjecucion(monto: String, clave: String, horaObjetivo: String) {
        claveEnMemoria = clave
        val ok = HermesAccessibilityService.iniciar(this, monto, clave, horaObjetivo)
        if (ok) {
            agregarRegistro("▶ Ejecución iniciada ($monto USD)")
            toast("Hermes en ejecución")
        } else {
            claveEnMemoria = null
            agregarRegistro("No se pudo iniciar el servicio (¿habilitado en Accesibilidad?)")
            toast("No se pudo iniciar el servicio")
        }
    }

    private fun flujoDetener() {
        confirmar("¿Detener la ejecución en curso?") {
            sendBroadcast(Intent(ACCION_DETENER))
            claveEnMemoria = null
            agregarRegistro("⏹ Detener enviado.")
            confirmar("¿Borrar también las programaciones pendientes?") {
                Scheduler.cancelarTodos(this)
                refrescarProgramaciones()
                agregarRegistro("Programaciones borradas.")
            }
        }
    }

    // ---------------------------------------------------------- Estado

    private fun actualizarEstadoAccesibilidad() {
        if (!::txtEstadoAccesibilidad.isInitialized) return
        txtEstadoAccesibilidad.text = if (servicioAccesibilidadActivo()) {
            "✓ Servicio de accesibilidad ACTIVO"
        } else {
            "✗ Servicio de accesibilidad INACTIVO — ábrelo en ajustes"
        }
    }

    private fun servicioAccesibilidadActivo(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(SERVICIO_ACCESIBILIDAD, ignoreCase = true) }
    }

    private fun bdvInstalado(): Boolean {
        return PAQUETES_BDV.any { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(pkg, 0)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ---------------------------------------------------------- Util UI

    private fun agregarRegistro(linea: String) {
        if (!::txtRegistro.isInitialized) return
        runOnUiThread {
            val hora = SimpleDateFormat("HH:mm:ss", Locale("es", "VE"))
                .format(Calendar.getInstance().time)
            val actual = txtRegistro.text.toString()
            val lineas = actual.lines().toMutableList()
            lineas.add("[$hora] $linea")
            while (lineas.size > 200) lineas.removeAt(0)
            txtRegistro.text = lineas.joinToString("\n")
            scrollRegistro.post { scrollRegistro.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun confirmar(mensaje: String, alSi: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(mensaje)
            .setPositiveButton("Sí") { _, _ -> alSi() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun titulo(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun seccion(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun texto(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun campo(valor: String, inputType: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply {
            setText(valor)
            this.inputType = inputType
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

    private fun boton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun espaciador() = LinearLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
        )
    }
}
