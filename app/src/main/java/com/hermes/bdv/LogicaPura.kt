package com.hermes.bdv

import java.util.Calendar

/**
 * Lógica pura de Hermes (sin dependencias de Android): testeable con JUnit.
 *
 * Reúne las reglas de negocio que no tocan la UI:
 * - Formato y validación del monto.
 * - Días hábiles para la programación.
 * - Cálculo de espera entre pulsaciones de Compra (10s exactos, reloj monotónico).
 */
object LogicaPura {

    /** Monto mínimo y máximo por operación (USD), según la app BDV. */
    const val MONTO_MIN = 1.0
    const val MONTO_MAX = 500.0

    /** Milisegundos exactos entre pulsaciones reales del botón Compra. */
    const val ESPERA_COMPRA_MS = 10_000L

    /** Máximo de pulsaciones de Compra por ciclo antes de re-login. */
    const val MAX_TAPS_COMPRA = 3

    /**
     * Formato BDV para el monto: "500" -> "500,00"; "250.5" -> "250,5".
     * (La app lee "100" como 1.00, por eso siempre van decimales con coma.)
     */
    fun formatoMonto(monto: String): String {
        var mf = monto.trim().replace(".", ",")
        if (!mf.contains(",")) mf += ",00"
        return mf
    }

    /** True si el monto es un número dentro del rango permitido. */
    fun validarMonto(monto: String): Boolean {
        val f = monto.trim().replace(",", ".").toDoubleOrNull() ?: return false
        return f in MONTO_MIN..MONTO_MAX
    }

    /**
     * True si es día hábil (lunes–viernes).
     * @param dayOfWeek constante de [Calendar] (SUNDAY=1 ... SATURDAY=7).
     */
    fun esDiaHabil(dayOfWeek: Int): Boolean =
        dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY

    /**
     * Próximo día hábil (incluye hoy si es hábil) a la hora indicada.
     * @return epoch millis del próximo slot.
     */
    fun proximoDiaHabil(ahoraMillis: Long, hora: Int, minuto: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = ahoraMillis
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Si la hora de hoy ya pasó, empezar mañana
        if (cal.timeInMillis <= ahoraMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        while (!esDiaHabil(cal.get(Calendar.DAY_OF_WEEK))) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Milisegundos que faltan para respetar los 10s exactos desde la última
     * pulsación real. 0 si ya pasó el intervalo o si no hubo pulsación previa.
     * @param ultimoTapUptimeMillis valor de SystemClock.uptimeMillis() del último tap (0 = ninguno).
     * @param ahoraUptimeMillis valor actual de SystemClock.uptimeMillis().
     */
    fun esperaRestanteCompra(ultimoTapUptimeMillis: Long, ahoraUptimeMillis: Long): Long {
        if (ultimoTapUptimeMillis <= 0) return 0
        val transcurrido = ahoraUptimeMillis - ultimoTapUptimeMillis
        val restante = ESPERA_COMPRA_MS - transcurrido
        return if (restante > 0) restante else 0
    }

    /**
     * Genera los slots de ejecución para un rango: un slot por día hábil
     * entre [inicioMillis] y [finMillis] (ambos inclusive), a la hora indicada.
     */
    fun slotsEnRango(
        inicioMillis: Long,
        finMillis: Long,
        hora: Int,
        minuto: Int
    ): List<Long> {
        require(finMillis >= inicioMillis) { "fin anterior a inicio" }
        val slots = mutableListOf<Long>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = inicioMillis
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Normalizar el inicio al comienzo de su día para iterar día por día
        val finCal = Calendar.getInstance().apply { timeInMillis = finMillis }
        while (!cal.after(finCal)) {
            if (esDiaHabil(cal.get(Calendar.DAY_OF_WEEK))) {
                val slot = cal.timeInMillis
                if (slot in inicioMillis..finMillis) slots.add(slot)
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return slots
    }
}
