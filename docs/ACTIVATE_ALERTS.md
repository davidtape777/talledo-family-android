# TALLEDO FAMILY v6 — activar voz y avisos reales

Esta es una candidata Debug para probar en dos dispositivos, no una publicación de producción ni un servicio de emergencia. No contiene videollamadas. El mapa sin conexión sigue siendo una opción separada, sin sustituir Google Maps ni sincronizar GPS sin Internet.

## 1. Base de datos (una sola vez)

Abre https://supabase.com/dashboard/project/xxlakmkmlhfhscvdpeyu/sql/new

Copia TODO `supabase/migrations/004_places_voice_and_push.sql` del repositorio y pulsa **Run**. Requiere haber ejecutado 001, 002 y 003. No vuelvas a ejecutar las migraciones anteriores ni 004 si ya terminó correctamente. La migración es transaccional: un fallo cancela sus cambios.

## 2. Función de servidor

Abre https://supabase.com/dashboard/project/xxlakmkmlhfhscvdpeyu/functions

Usa **Deploy a new function / Via Editor**, nombre **family-push**. Reemplaza TODO el código por `supabase/functions/family-push/index.ts` del repositorio y despliega.

En los ajustes de esa función desactiva **Verify JWT / Enforce JWT verification**: la función verifica por sí misma el encabezado secreto del webhook; rechaza claves públicas y sesiones normales. Si usas CLI, `supabase functions deploy family-push --no-verify-jwt` hace lo equivalente.

El secreto `FIREBASE_SERVICE_ACCOUNT_JSON` debe contener el JSON privado completo de la cuenta de servicio del proyecto Firebase `talledo-family`. Ya lo guardaste: no lo subas a GitHub ni al chat. No debe confundirse con `google-services.json`. La función usa las variables de servidor preconfiguradas `SUPABASE_URL` y `SUPABASE_SERVICE_ROLE_KEY`. Si esta última no existe en tu proyecto, detente: no reemplaces el secreto por una clave pública.

## 3. Webhook de base de datos

En **Edge Functions → Secrets**, crea `FAMILY_WEBHOOK_SECRET` con un valor aleatorio de al menos 32 caracteres generado por un gestor de contraseñas, sin espacios iniciales/finales. Guarda una copia privada para el encabezado del webhook. No es tu contraseña ni una clave API; no lo envíes al chat ni lo guardes en Android/GitHub. Este secreto autentica el webhook; la clave de servicio preconfigurada se usa únicamente para las consultas administrativas del servidor.

Si ya tienes la función anterior, reemplaza su código completo por la versión actual y pulsa **Deploy updates**, dejando Verify JWT desactivado. Esta corrección de servidor no requiere reinstalar Android ni volver a ejecutar SQL.

Abre https://supabase.com/dashboard/project/xxlakmkmlhfhscvdpeyu/integrations/webhooks/webhooks, instala la integración si hace falta y crea uno (o edita el existente, sin duplicarlo):

| Campo | Valor |
|---|---|
| Nombre | `family-notification-push` |
| Esquema / tabla | `public.family_notifications` |
| Evento | Solo `INSERT` |
| Tipo | Supabase Edge Functions (o HTTP POST) |
| Función / URL | `family-push` / `https://xxlakmkmlhfhscvdpeyu.supabase.co/functions/v1/family-push` |
| Encabezado personalizado | Nombre `x-family-webhook-secret`; valor idéntico a `FAMILY_WEBHOOK_SECRET` |
| Content-Type | `application/json` |
| Timeout HTTP, si aparece | `10000` ms |

El valor del encabezado es SOLO el secreto personalizado, sin `Bearer`, comillas ni dos puntos. Añádelo con **Add header**, no con **Add secret key**. Después de desplegar el código actualizado, elimina del webhook la fila `Authorization` anterior, conserva `Content-type: application/json`, añade `x-family-webhook-secret` y guarda. No elimines los secretos predeterminados de Supabase ni el secreto Firebase. La función rechaza claves públicas y la antigua autorización de servicio como sustitutos de este encabezado. No uses este secreto para webhooks hacia otros dominios.

El cuerpo estándar del webhook incluye `type`, `schema`, `table` y `record.id`. La función ignora el texto y el destinatario recibidos, consulta la base y vuelve a comprobar permisos. El envío es genérico, sin coordenadas, nombres ni contenido del mensaje en Firebase o pantalla bloqueada.

## 4. Probar Android en dos dispositivos

Instala el APK v6 sobre el anterior (misma firma Debug de CI) en ambos dispositivos. Si Android detecta conflicto de paquete, no desinstales antes de guardar lo necesario: comprueba que ambos APK proceden de este mismo repositorio/flujo.

1. Padre: tu cuenta aprobada. Tablet: cuenta independiente de Sayumi, unida con código como hija. Verifica el nombre editable en Familia; se refresca aproximadamente cada 10 s.
2. En **Avisos**, habilita notificaciones en ambos dispositivos. Activa **Voz española**; habilita **Leer también mensajes** si lo deseas. La voz viene apagada por privacidad. Ajusta volumen y silencio nocturno. **PROBAR VOZ** solo habla con pantalla desbloqueada, app abierta, sonido normal, sin No molestar y fuera de silencio nocturno.
3. Si falta voz, instala datos españoles sin conexión en los ajustes de texto a voz de Android. No se paga un proveedor de voz ni se pide micrófono.
4. Envía un mensaje desde la tablet al padre. Con la app del padre abierta debe aparecer en Mensajes/Avisos; con la app en segundo plano, debe llegar un aviso genérico al Android. Ábrelo y consulta el detalle. Si tienes voz activada y el mensaje llega mientras la app está abierta, se lee según tus ajustes.
5. Activa compartir GPS expresamente en la tablet. En el mapa del padre comprueba nombre/iniciales, ubicación, precisión, hora y batería. Pulsa el integrante para actualizar y centrar; **VER TODOS** encuadra ubicaciones autorizadas. Una ubicación antigua se llama última conocida, no actual.
6. Como padre, mantén pulsado el mapa en una ubicación de prueba para agregar Casa/Colegio (radio 150 m inicialmente). El primer GPS establece la referencia y NO anuncia una llegada ficticia. Prueba salir más allá del radio + 50 m y volver a entrar: hacen falta dos lecturas precisas, separadas al menos 25 s. Con poca precisión no se anuncia. No uses un traslado arriesgado solo para probar la app.
7. **VOY EN CAMINO** elige un lugar y avisa a familiares con permiso. Es una declaración del usuario, no una predicción ni navegación automática.
8. Pausa GPS: desaparecen coordenadas y estados de lugares; se cancela el acceso a detalles de avisos de ubicación. Bloquea visibilidad entre adultos y comprueba que no se filtran avisos/fotos/GPS. Los mensajes directos solo se ven por emisor y destinatario.
9. El mapa sin conexión sigue funcionando con el archivo local; no muestra ubicaciones familiares nuevas sin Internet. **TE NECESITO** sigue en modo prueba, no llama a emergencias.

## Diagnóstico y límites

- **Avisos → Push pendiente**: webhook no instalado o fallo en la llamada. Revisa invocaciones/logs de `family-push` y del webhook en tu panel; no compartas claves.
- **Sin dispositivos / failed**: abre Avisos y habilita notificaciones de nuevo en el destinatario, con Internet. Revisa nombre del secreto y que su cuenta de servicio pertenece a Firebase `talledo-family`, y que Cloud Messaging API está habilitada en ese proyecto.
- **sent** significa aceptado por Firebase, NO recibido o leído. Leer una notificación se marca por el usuario dentro de la app; no es confirmación de lectura de un mensaje.
- No hay entrega garantizada ni reintento programado/monitor de dispositivos desconectados. El outbox admite hasta tres intentos de servidor; para reintentar manualmente un fallo, desde el panel vuelve a invocar el webhook autorizado con su `record.id`. No llames esta función desde Android. Los avisos de ubicación pierden autorización si el GPS queda antiguo o pausado.
- No se generan alertas fiables de desconexión cuando el móvil deja de reportar: requerirían un monitor de servidor adicional. No hay historia de rutas, geocodificación, rutas/ETA ni llamadas de vídeo.
- FCM/voz local no requieren contratar un proveedor de voz o video. Supabase y Maps mantienen sus propias cuotas y facturación: esto no garantiza coste total cero.
- Reinicio, cierre forzado, ahorro de batería, falta de permiso o de Internet pueden detener ubicación y notificaciones. Se reactivan voluntariamente dentro de la app, nunca de forma oculta.
- Antes de una distribución definitiva: pruebas reales en dos dispositivos, revisión de privacidad/retención, firma Release permanente y respaldo seguro de esa firma. El APK actual es Debug para validar.

## Pruebas automáticas

GitHub Actions verifica migraciones/RLS (incluido bloqueo/pausa de alertas, mensajes directos y tokens privados), función Edge con transporte simulado, políticas de voz/frescura, pruebas anteriores de mapas offline y compilación Android. No despliega tu backend ni utiliza claves privadas Firebase. Las pruebas de función no prueban entrega real FCM: esa se confirma con el paso 4.
