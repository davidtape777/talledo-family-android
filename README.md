# TALLEDO FAMILY — v6, voz y alertas privadas

Android Kotlin/Compose, Supabase y Google Maps. APK Debug construido por GitHub Actions.

## Nuevo en v6

Asistente español de texto a voz local, apagado por defecto y solo con la app abierta/desbloqueada; lectura opcional de mensajes, volumen y silencio nocturno. Lugares familiares con confirmación GPS de llegada/salida, avisos de batería baja y **Voy en camino** declarado por la persona. Nombres actualizados, marcadores con iniciales/color, encuadre de integrantes, batería, hora y precisión. Alertas privadas por destinatario y outbox de servidor con permisos reevaluados antes de Firebase.

[Activar SQL 004, función Edge y webhook; probar en dos dispositivos](docs/ACTIVATE_ALERTS.md). El secreto Firebase privado se guarda únicamente en Supabase. CI no despliega el backend. No hay videollamadas ni monitor automático de desconexión.

## Mapa local opcional (se conserva v5)

**MAPA SIN CONEXIÓN · OPCIONAL** permite importar un MBTiles raster autorizado, mover/acercar el mapa y consultar el GPS propio sin red. Google Maps, familia, mensajes y privacidad no se reemplazan. No hay nueva migración SQL, cuenta ni servicio de pago. El ZIP incluye una cuadrícula sintética para probar, no un mapa real de calles. [Guía y límites](docs/OFFLINE_MAPS.md).

## Activación
1. Si ya ejecutaste 001, 002 y 003, ejecutar únicamente `supabase/migrations/004_places_voice_and_push.sql`. Para un proyecto nuevo, aplicar 001–004 en orden.
2. Activar función Edge y webhook siguiendo la guía enlazada; descargar el artefacto v6 desde Actions y extraer el APK.
3. Instalar en dos teléfonos sobre la versión anterior. Si hay conflicto de firma, comprobar procedencia de ambos APK y guardar datos locales necesarios antes de considerar una desinstalación.
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

TE NECESITO continúa DEMOSTRACIÓN, no despacha emergencias. Toque familiar se envía como mensaje. El servidor push está implementado pero requiere instalar SQL, función y webhook en tu Supabase, registrar dispositivos y probar entrega real. Push muestra aviso genérico, no GPS/nombres/contenido de mensajes. No hay videollamadas. Esta entrega es candidata Debug: NO es publicación Play Store ni APK Release firmado.

## CI y claves
Repository secrets: MAPS_API_KEY y GOOGLE_SERVICES_JSON. El JSON de configuración Android no se publica en git. FIREBASE_SERVICE_ACCOUNT_JSON solo en Supabase: nunca en GitHub o Android. Maps API key queda necesariamente dentro del APK; restringirla por paquete Android y certificado SHA-1 en Google Cloud. Cache de clave debug ayuda a conservar firma, pero puede expirar; para distribución definitiva hace falta keystore Release respaldado. CI verifica migraciones/RLS y outbox en PostgreSQL 16, función Edge con transporte simulado, pruebas unitarias y assembleDebug. No accede a datos reales de Supabase ni prueba entrega real FCM.
