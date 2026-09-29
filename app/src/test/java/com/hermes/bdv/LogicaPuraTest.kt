package com.hermes.bdv

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Tests de la lógica pura de Hermes (sin Android).
 * Se ejecutan en GitHub Actions en cada push.
 */
class LogicaPuraTest {

    // ---- formatoMonto ----

    @Test
    fun formatoMonto_entero_agregaComaCeroCero() {
        assertEquals("500,00", LogicaPura.formatoMonto("500"))
    }

    @Test
    fun formatoMonto_puntoSeVuelveComa() {
        assertEquals("250,5", LogicaPura.formatoMonto("250.5"))
    }

    @Test
    fun formatoMonto_yaConComa_noCambia() {
        assertEquals("100,00", LogicaPura.formatoMonto("100,00"))
    }

    @Test
    fun formatoMonto_ignoraEspacios() {
        assertEquals("300,00", LogicaPura.formatoMonto(" 300 "))
    }

    // ---- validarMonto ----

    @Test
    fun validarMonto_rangoValido() {
        assertTrue(LogicaPura.validarMonto("500"))
        assertTrue(LogicaPura.validarMonto("1"))
        assertTrue(LogicaPura.validarMonto("250,50"))
        assertTrue(LogicaPura.validarMonto("1.0"))
    }

    @Test
    fun validarMonto_fueraDeRango() {
        assertFalse(LogicaPura.validarMonto("0.5"))
        assertFalse(LogicaPura.validarMonto("500.01"))
        assertFalse(LogicaPura.validarMonto("1000"))
    }

    @Test
    fun validarMonto_noNumerico() {
        assertFalse(LogicaPura.validarMonto(""))
        assertFalse(LogicaPura.validarMonto("abc"))
        assertFalse(LogicaPura.validarMonto("12x"))
    }

    // ---- esDiaHabil ----

    @Test
    fun esDiaHabil_lunAVie_true() {
        assertTrue(LogicaPura.esDiaHabil(Calendar.MONDAY))
        assertTrue(LogicaPura.esDiaHabil(Calendar.TUESDAY))
        assertTrue(LogicaPura.esDiaHabil(Calendar.WEDNESDAY))
        assertTrue(LogicaPura.esDiaHabil(Calendar.THURSDAY))
        assertTrue(LogicaPura.esDiaHabil(Calendar.FRIDAY))
    }

    @Test
    fun esDiaHabil_finDeSemana_false() {
        assertFalse(LogicaPura.esDiaHabil(Calendar.SATURDAY))
        assertFalse(LogicaPura.esDiaHabil(Calendar.SUNDAY))
    }

    // ---- esperaRestanteCompra (10s exactos, reloj monotónico) ----

    @Test
    fun esperaRestanteCompra_sinTapPrevio_cero() {
        assertEquals(0L, LogicaPura.esperaRestanteCompra(0L, 100_000L))
    }

    @Test
    fun esperaRestanteCompra_intervaloCompleto() {
        // Último tap hace 3s -> faltan 7s
        assertEquals(7_000L, LogicaPura.esperaRestanteCompra(100_000L, 103_000L))
    }

    @Test
    fun esperaRestanteCompra_intervaloCumplido_cero() {
        // Último tap hace 10s exactos -> no espera
        assertEquals(0L, LogicaPura.esperaRestanteCompra(100_000L, 110_000L))
    }

    @Test
    fun esperaRestanteCompra_intervaloExcedido_cero() {
        // Último tap hace 15s -> no espera (nunca negativo)
        assertEquals(0L, LogicaPura.esperaRestanteCompra(100_000L, 115_000L))
    }

    @Test
    fun esperaRestanteCompra_bordeUnMilisegundo() {
        // Falta 1ms para los 10s
        assertEquals(1L, LogicaPura.esperaRestanteCompra(100_000L, 109_999L))
    }

    // ---- proximoDiaHabil ----

    @Test
    fun proximoDiaHabil_hoyHabil_horaFutura_hoy() {
        // Lunes 2026-09-28 07:00, hora objetivo 08:00 -> hoy mismo 08:00
        val lunes7am = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 28, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = LogicaPura.proximoDiaHabil(lunes7am, 8, 0)
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun proximoDiaHabil_hoyHabil_horaPasada_manana() {
        // Lunes 2026-09-28 09:00, hora objetivo 08:00 -> martes 08:00
        val lunes9am = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 28, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = LogicaPura.proximoDiaHabil(lunes9am, 8, 0)
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun proximoDiaHabil_viernesTarde_lunes() {
        // Viernes 2026-10-02 18:00, hora objetivo 08:00 -> lunes 05/10 08:00
        val viernes6pm = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 2, 18, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = LogicaPura.proximoDiaHabil(viernes6pm, 8, 0)
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(5, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun proximoDiaHabil_sabado_lunes() {
        // Sábado 2026-10-03 10:00 -> lunes 05/10 08:00
        val sabado = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 3, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val slot = LogicaPura.proximoDiaHabil(sabado, 8, 0)
        val cal = Calendar.getInstance().apply { timeInMillis = slot }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    // ---- slotsEnRango ----

    @Test
    fun slotsEnRango_semanaCompleta_cincoSlots() {
        // Lun 28/09/2026 -> Vie 02/10/2026, 08:00 => 5 slots
        val ini = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 28, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fin = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 2, 23, 59, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val slots = LogicaPura.slotsEnRango(ini, fin, 8, 0)
        assertEquals(5, slots.size)
        // Todos a las 08:00 y en días hábiles
        for (s in slots) {
            val c = Calendar.getInstance().apply { timeInMillis = s }
            assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
            assertTrue(LogicaPura.esDiaHabil(c.get(Calendar.DAY_OF_WEEK)))
        }
        // Ordenados
        assertEquals(slots.sorted(), slots)
    }

    @Test
    fun slotsEnRango_incluyeFinDeSemana_losSalta() {
        // Vie 02/10 -> Lun 05/10 => solo vie y lun (2 slots)
        val ini = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 2, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fin = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 23, 59, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val slots = LogicaPura.slotsEnRango(ini, fin, 8, 0)
        assertEquals(2, slots.size)
    }

    @Test
    fun slotsEnRango_unSoloDia_unSlot() {
        val ini = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fin = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 29, 23, 59, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val slots = LogicaPura.slotsEnRango(ini, fin, 8, 0)
        assertEquals(1, slots.size)
    }

    // ---- constantes críticas ----

    @Test
    fun constantes_respetanReglasDeJhon() {
        assertEquals(10_000L, LogicaPura.ESPERA_COMPRA_MS) // 10s exactos entre taps
        assertEquals(3, LogicaPura.MAX_TAPS_COMPRA)        // máx 3 intentos
        assertEquals(1.0, LogicaPura.MONTO_MIN, 0.0)
        assertEquals(500.0, LogicaPura.MONTO_MAX, 0.0)
    }
}
