# Mapa sin conexión opcional (v5)

Google Maps y la sincronización familiar siguen siendo los principales. El nuevo visor no utiliza Google Maps ni servidores de imágenes: lee una copia local de un archivo seleccionado por el usuario. No requiere suscripción nueva; obtener mapas de terceros puede tener costos o restricciones según su fuente.

## Uso

1. En el teléfono, abre **MAPA SIN CONEXIÓN · OPCIONAL** (también está disponible antes del inicio de sesión o si falla la conexión).
2. Pulsa **IMPORTAR .MBTILES** y selecciona un archivo confiable cuya licencia permita este uso.
3. El archivo debe ser MBTiles Web Mercator / TMS de imágenes **PNG, JPG o WebP**, hasta **500 MB**. Los MBTiles vectoriales PBF, archivos de Google Maps, OsmAnd u otras apps no son compatibles.
4. Arrastra el mapa, usa **+ / −**, o **ZONA** para volver al centro inicial. Los espacios grises indican imágenes no incluidas en el archivo o niveles sin cobertura.
5. Opcionalmente, pulsa **VER MI GPS LOCAL**, concede ubicación precisa y activa GPS. **MI GPS** centra tu posición. La primera señal puede tardar y mejora al aire libre; una tablet sin receptor GPS no podrá obtenerla sin Internet usando este modo.
6. **VOLVER** regresa a la app principal. **QUITAR** elimina solo la copia importada: no borra el original ni modifica Google Maps.

La ubicación GPS de este visor es únicamente del dispositivo. No se guarda en disco ni se envía desde este visor. Se detiene al salir de la pantalla o pasar la aplicación al fondo. La ubicación compartida previamente activada es un servicio independiente: continúa según su configuración y debe pausarse desde el mapa familiar.

Los mensajes y nuevas ubicaciones de familiares requieren conexión en ambos dispositivos. Esta entrega **no agrega caché de ubicaciones familiares, cola de mensajes, navegación/rutas ni descarga de regiones desde un proveedor**.

## Prueba en dos dispositivos

- Primero verifica que Google Maps y mensajes siguen funcionando como antes.
- Importa el mismo archivo compatible en cada dispositivo. No se copia automáticamente entre cuentas.
- Apaga Wi-Fi y datos móviles, pero deja GPS activo. Cierra y vuelve a abrir la app: el botón opcional debe seguir disponible aunque el acceso familiar no pueda conectar.
- Verifica desplazamiento, zoom, ZONA y GPS propio. No esperes posiciones nuevas del otro dispositivo ni mensajes sin Internet.
- Intenta importar un archivo inválido: debe aparecer un error y conservarse el mapa anterior.
- Quita la copia en uno de los dispositivos: el archivo original y la copia del otro deben permanecer.

## Obtención de archivos

No se incluye un mapa real ni se descargan imágenes de los servidores públicos de OpenStreetMap. Puedes exportar un MBTiles raster con QGIS a partir de datos locales autorizados o conseguir un archivo raster ya preparado de una fuente que permita uso offline. Verifica licencia y atribución; los servidores públicos OSM no permiten precargar regiones.

El ZIP del APK incluye **TALLEDO-DEMO-sin-calles.mbtiles**, una cuadrícula sintética original para probar importación, desplazamiento, zoom y GPS. **No representa calles, escuelas ni lugares reales**. Es solo una prueba técnica; para ver calles debes importar un mapa raster autorizado de tu zona.

- Especificación: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md
- Política OSM: https://operations.osmfoundation.org/policies/tiles/
- Selección de archivos Android: https://developer.android.com/training/data-storage/shared/documents-files

## Seguridad y pruebas

- Selector de documentos Android: sin permiso general sobre todos los archivos.
- Copia privada en `noBackupFilesDir`, excluida de copias de seguridad del sistema.
- Límite de archivo, validación SQLite/metadata/imagen de muestra, lectura solo local y reemplazo con AtomicFile. Un archivo inválido no sustituye el anterior.
- Imágenes limitadas a 2 MB / 1024×1024 por tile y caché de memoria acotada. Usa archivos confiables; validar una muestra no garantiza que todas las imágenes estén completas.
- Pruebas unitarias de proyección, orientación TMS, importación/reapertura, rechazo PBF/XYZ, preservación, recuperación, límites y eliminación de la copia. La prueba física sin red y del GPS está pendiente de realizar por el usuario.

Esta es una actualización Debug para pruebas, no una publicación Release firmada para Play Store.
