# Multicuenta para Android

App Android (Kotlin) con WhatsApp Web y Telegram Web reales, una cuenta por icono en la barra vertical izquierda, como la versión de escritorio.

- **Aislamiento:** cada cuenta usa su propio perfil de WebView (cookies, almacenamiento y service workers separados). Para eso hace falta que Android System WebView admita perfiles (`MULTI_PROFILE`). Si no los admite, solo se abre la primera cuenta de cada servicio y las demás piden actualizar WebView.
- **Emojis:** usa la fuente del sistema, es decir, Noto Color Emoji de Android.
- **WhatsApp Web** se abre con un user agent de escritorio, porque en móvil WhatsApp redirige a la tienda.
- **Añadir cuenta:** botón **+**. Mantén pulsado un icono para recargar o eliminar la cuenta.

## Obtener el APK

GitHub Actions lo compila al hacer push a la rama `android` (workflow `.github/workflows/android.yml`). El APK de depuración aparece como artefacto `multicuenta-debug-apk` en la ejecución del workflow.

En local, con JDK 17, Android SDK y Gradle 8.10:

```bash
gradle -p android assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/`.
