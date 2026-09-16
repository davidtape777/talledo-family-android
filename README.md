# TALLEDO FAMILY — v4, candidata para pruebas familiares

Android Kotlin/Compose, Supabase y Google Maps. APK Debug construido por GitHub Actions.

## Activación
1. En el proyecto Supabase ya configurado, ejecutar únicamente `supabase/migrations/003_shared_location_and_security.sql` después de 001 y 002.
2. Descargar el artefacto v4 desde Actions y extraer el APK.
3. Instalar en dos teléfonos; si hay conflicto de firma, desinstalar la versión previa (se conserva la familia en Supabase; se borra la sesión local).
4. Usar DOS cuentas distintas; la primera crea la familia, la segunda elige Unirme y usa el código familiar.
5. Si la segunda cuenta elige padre/madre/tutor, el administrador pulsa Autorizar acceso parental en Familia.
6. En el teléfono que comparte, Mapa → Activar ubicación automática → confirmar permisos y notificaciones.
7. Para probar dos adultos: el que comparte habilita al otro en Privacidad. Los adultos no comparten por defecto.
8. Consultar Mapa en el otro teléfono. Verificar hora/precisión, pantalla apagada, pausa y bloqueo. Mensajes → elegir grupo o conversación directa.

No es necesario enviar alertas para actualizar GPS. Actualizaciones aproximadamente cada 30 s; mapa consulta cada 10 s; mensajes cada 5 s mientras la pantalla está abierta. Sin conexiones WebSocket: sincronización por consulta periódica.

## Privacidad
Solo padres/tutores aprobados ven GPS de hijos. Cada adulto controla su propia ubicación; bloqueo bilateral impide GPS/mensajes entre esos adultos sin retirar acceso a hijos. Nombres y roles mínimos permanecen en el listado para restaurar permisos; teléfono/nacimiento/fotos privados respetan autorización. Imágenes privadas de Supabase; tokens de sesión cifrados con Android Keystore; contraseñas no guardadas. Pausa retira coordenadas cuando hay Internet; si no hay conexión queda pendiente. Ubicaciones de más de 24 h dejan de ser visibles, pero permanecen en base hasta pausa/siguiente envío; no se guarda un historial por este cliente.

## Límites que deben probarse
Servicio foreground visible, iniciado por la persona desde la app. No hay rastreo oculto ni arranque después de reiniciar/forzar cierre: abrir y activar nuevamente. El sistema/fabricante puede detener el servicio; el mapa muestra última posición conocida, no garantiza GPS continuo. Una cuenta por teléfono/persona; usar la misma cuenta en dos dispositivos hace que compartan identidad y ubicación.

TE NECESITO continúa DEMOSTRACIÓN, no despacha emergencias. Toque familiar se envía como mensaje. Firebase integra recepción, pero NO se implementó todavía el servidor de notificaciones push ni videollamadas. Esta entrega NO es publicación Play Store ni APK Release firmado.

## CI y claves
Repository secrets: MAPS_API_KEY y GOOGLE_SERVICES_JSON. Firebase JSON no se publica en git. Maps API key queda necesariamente dentro del APK; restringirla por paquete Android y certificado SHA-1 en Google Cloud. Cache de clave debug ayuda a conservar firma, pero puede expirar; para distribución definitiva hace falta keystore Release respaldado. CI verifica migraciones/RLS en PostgreSQL 16 con datos ficticios, pruebas unitarias y assembleDebug. No accede a datos reales de Supabase.
