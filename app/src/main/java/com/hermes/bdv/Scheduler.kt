package com.hermes.bdv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Programa ejecuciones exactas con AlarmManager. Cada programación persiste en
 * [Config] y tiene su propio PendingIntent hacia [AlarmReceiver].
 */
object Scheduler {

    /**
     * Guarda la programación y agenda la alarma exacta.
     * En Android 12+, si el permiso SCHEDULE_EXACT_ALARM no está concedido,
     * se usa una alarma inexacta como respaldo en lugar de fallar.
     */
    fun programar(context: Context, fechaHoraMillis: Long, monto: String) {
        Config.agregarProgramacion(context, fechaHoraMillis, monto)
        agendar(context, fechaHoraMillis)
    }

    /** Cancela todas las alarmas y borra todas las programaciones. */
    fun cancelarTodos(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (p in Config.obtenerProgramaciones(context)) {
            am.cancel(pendingPara(context, p.fechaHora))
        }
        Config.limpiarProgramaciones(context)
    }

    /** Cancela una programación puntual. */
    fun cancelarUna(context: Context, fechaHora: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingPara(context, fechaHora))
        Config.eliminarPorFechaHora(context, fechaHora)
    }

    /**
     * Tras un reinicio del teléfono: reagenda las programaciones futuras y
     * descarta las que ya pasaron.
     */
    fun reprogramarTrasReinicio(context: Context) {
        val ahora = System.currentTimeMillis()
        val futuras = Config.obtenerProgramaciones(context)
            .filter { it.fechaHora > ahora }
        for (p in futuras) {
            agendar(context, p.fechaHora)
        }
        // Descarta las vencidas para no acumular basura
        Config.reemplazarProgramaciones(context, futuras)
    }

    private fun agendar(context: Context, fechaHoraMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingPara(context, fechaHoraMillis)
        val puedeExacta =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (puedeExacta) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fechaHoraMillis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fechaHoraMillis, pi)
        }
    }

    private fun pendingPara(context: Context, fechaHoraMillis: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_FECHA_HORA, fechaHoraMillis)
        }
        val requestCode = (fechaHoraMillis / 1000).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
