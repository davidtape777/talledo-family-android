package com.talledofamily.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OfflineMapScreen(onClose: () -> Unit) {
    BackHandler(onBack=onClose)
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var info by remember { mutableStateOf<OfflineMapInfo?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var gps by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Location?>(null) }
    var age by remember { mutableLongStateOf(0) }
    var view by remember { mutableStateOf<OfflineMapView?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) {
            if (OfflineMapStore.file(context).exists() || java.io.File(OfflineMapStore.file(context).path+".bak").exists()) OfflineMapStore.load(context) else null
        } }.onSuccess { info=it }.onFailure { error="El mapa guardado no se pudo leer. Puedes importar otro." }
        busy=false
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            busy=true; view=null; error=null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { OfflineMapStore.import(context,uri) } }
                    .onSuccess { info=it; revision++ }
                    .onFailure { error=it.message ?: "Archivo no compatible. Se conserva el mapa anterior." }
                busy=false
            }
        }
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        gps=result[Manifest.permission.ACCESS_FINE_LOCATION]==true
        if(!gps) error="El GPS local necesita permiso de ubicación precisa. Puedes usar el mapa sin GPS."
    }
    DisposableEffect(gps,lifecycle) {
        val manager=context.getSystemService(LocationManager::class.java)
        var listening=false
        val listener=object:LocationListener {
            override fun onLocationChanged(fix:Location) {
                if(SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos < 120_000_000_000L) location=fix
            }
            override fun onProviderDisabled(provider:String) { location=null; error="Activa la ubicación GPS del dispositivo." }
            override fun onProviderEnabled(provider:String) { error=null }
            @Deprecated("Required by Android 8-10 LocationListener")
            override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?) {}
        }
        fun stop() { if(listening) manager.removeUpdates(listener); listening=false; location=null }
        fun start() {
            if(!gps || listening || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return
            runCatching {
                require(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Activa el GPS. La primera ubicación puede tardar; prueba cerca de una ventana o al aire libre." }
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,10_000L,0f,listener,android.os.Looper.getMainLooper())
                listening=true
            }.onFailure { error=it.message }
        }
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) start()
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer); start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(location) {
        while(location!=null) { age=((SystemClock.elapsedRealtimeNanos()-location!!.elapsedRealtimeNanos)/1_000_000_000).coerceAtLeast(0); delay(1000) }
    }
    if(showTerms) AlertDialog(onDismissRequest={showTerms=false},title={Text("Importar mapa local")},
        text={Text("Selecciona un archivo .mbtiles de imágenes PNG, JPG o WebP (máximo 500 MB). No admite PBF ni mapas descargados en otras aplicaciones. Usa solo archivos de una fuente confiable cuya licencia permita este uso. La app no descarga imágenes de servidores públicos. El archivo queda en este dispositivo y puedes quitarlo. Si hay un mapa anterior, se reemplazará solo después de validar el nuevo.")},
        confirmButton={TextButton(onClick={showTerms=false;picker.launch(arrayOf("*/*"))}){Text("SELECCIONAR ARCHIVO")}},
        dismissButton={TextButton(onClick={showTerms=false}){Text("Cancelar")}})
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Quitar mapa de este dispositivo")},
        text={Text("Se eliminará la copia importada. El archivo original y Google Maps no se modifican.")},
        confirmButton={TextButton(onClick={
            confirmDelete=false; view=null; busy=true
            scope.launch { runCatching { withContext(Dispatchers.IO){OfflineMapStore.delete(context)} }
                .onSuccess{info=null;revision++}.onFailure{error="No se pudo quitar el mapa."}; busy=false }
        }){Text("QUITAR COPIA")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancelar")}})
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick=onClose,enabled=!busy){Text("VOLVER · GOOGLE MAPS NO CAMBIA")}
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
            Text("Mapa sin conexión · opcional",style=MaterialTheme.typography.titleMedium)
            Text("GPS de este dispositivo únicamente. Ubicaciones familiares nuevas y mensajes requieren Internet.",style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick={showTerms=true},enabled=!busy){Text(if(info==null) "IMPORTAR .MBTILES" else "CAMBIAR ARCHIVO")}
                if(info!=null) TextButton(onClick={confirmDelete=true},enabled=!busy){Text("QUITAR")}
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        }
        if(busy) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator()}
        else if(info==null) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp),contentAlignment=androidx.compose.ui.Alignment.Center) {
            Text("Aún no hay un mapa importado. No necesitas crear otra cuenta ni cambiar tu configuración actual.")
        } else {
            val current=info!!
            Text(current.name+" · "+(current.bytes/1024/1024)+" MB",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelMedium)
            key(revision) {
                AndroidView(factory={OfflineMapView(it,current).also { created -> view=created; created.onReadError={message->error=message} }},
                    modifier=Modifier.fillMaxWidth().weight(1f),update={it.setLocalLocation(location)})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                TextButton(onClick={view?.zoomBy(-1)}){Text("−")}
                TextButton(onClick={view?.zoomBy(1)}){Text("+")}
                TextButton(onClick={view?.resetRegion()}){Text("ZONA")}
                TextButton(onClick={view?.centerLocal()},enabled=location!=null){Text("MI GPS")}
            }
            Text(current.attribution,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick={
                if(gps) {gps=false;location=null}
                else if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {gps=true;error=null}
                else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
            },enabled=!busy){Text(if(gps) "APAGAR GPS LOCAL" else "VER MI GPS LOCAL")}
            Text(if(!gps) "Solo en esta pantalla" else location?.let { "${if(age<120) "GPS" else "Último GPS"} · hace ${age}s · ±${it.accuracy.toInt()} m" } ?: "Esperando GPS…",
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
        }
        Text("Esta opción no activa ni pausa la ubicación compartida con tu familia.",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
    }
}
