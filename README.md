# TALLEDO FAMILY

Aplicación Android familiar construida con Kotlin y Jetpack Compose.

## Primera versión

- Acceso local mediante código familiar de 6 dígitos.
- Panel para Sayumi, Vania y David Fernando.
- Mapa familiar totalmente simulado.
- Mensajes locales de demostración.
- Toque familiar con confirmación dentro de la app.
- Botón **TE NECESITO** en modo demostración.
- GitHub Actions compila, prueba y publica el APK Debug como artefacto.

## Probar

El código de demostración es: **777777**.

Esta versión no solicita permisos, no lee contactos, no utiliza GPS, no se conecta a servidores y no envía alertas reales.

## Compilar localmente

Requisitos: JDK 17, Android SDK 35 y Gradle 8.10.2.

```bash
gradle testDebugUnitTest assembleDebug
```

APK local: `app/build/outputs/apk/debug/app-debug.apk`.

## Descargar desde GitHub Actions

Abre **Actions → Build Debug APK → ejecución más reciente → Artifacts** y descarga `TALLEDO-FAMILY-debug-apk`.
