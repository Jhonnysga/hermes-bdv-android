# Hermes BDV — App Android nativa

Automatización de la compra de divisas en la app del Banco de Venezuela
(`com.bancodevenezuela.bdvdigital`), corriendo 100% en el teléfono:
**sin PC, sin ADB**.

## Cómo funciona

La app usa un `AccessibilityService` (`HermesAccessibilityService`) que observa
la pantalla del BDV y pulsa los botones directamente en memoria — sin red,
sin latencia ADB.

Flujo (7 pasos, todo automático):
1. **Login** — abre el BDV, espera el campo de clave. La clave la teclea el
   usuario al ejecutar (vive solo en memoria, nunca se guarda).
   Sincroniza el tap en "Aceptar" para completar el login a la hora objetivo.
2. **Divisas** — pulsa el tab Divisas (hasta 10 intentos).
3. **Compra** — pulsa "Compra" (máx 3 intentos, 10s exactos entre pulsaciones
   reales medidos con reloj monotónico). Si falla: cierra la app, re-login,
   reinicia el ciclo (ciclos sin límite).
4. **Formulario** — elige cuenta a debitar (**7574), cuenta destino (**7470),
   escribe el monto (formato "500,00"). Pulsa Continuar (reintento sin límite).
5. **Destino/Actividad** — Destino=Otros, Actividad=No aplica. Continuar.
6. **Confirmar** — pulsa "Confirmar Operación" automáticamente y verifica.
7. **Comprobante** — detecta "Comprobante" = éxito.

## Avisos por Telegram

- Cada pantalla superada: solo texto (📝 ...).
- Errores/alertas: solo texto (⚠ ...).
- Al lograr la compra: captura de pantalla + "🎉 ¡Felicidades, lograste comprar divisas!"

## Programación

Fecha/hora de inicio y de cierre; la app genera una ejecución por cada día
hábil (lun–vie) en el rango. Sobrevive reinicios del teléfono.

## Compilación

Cada push a `main` compila el APK debug en GitHub Actions
(`.github/workflows/build.yml`), que también corre los tests unitarios.
El APK queda en los artifacts del workflow.

## Permisos

- Accesibilidad (imprescindible: es el motor de automatización)
- Internet (avisos Telegram)
- Alarmas exactas + arranque completado (programación)
- Notificaciones

