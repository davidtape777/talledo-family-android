package com.talledofamily.app

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import com.google.android.gms.maps.model.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

fun locationAgeLabel(captured:String,now:java.time.Instant=java.time.Instant.now()):String = runCatching {
    val seconds=java.time.Duration.between(java.time.Instant.parse(captured),now).seconds.coerceAtLeast(0)
    if(seconds<120) "Reciente · hace ${seconds}s" else "Última conocida · hace ${seconds/60} min"
}.getOrDefault("Sin fecha válida")

@Composable
fun SharedFamilyMap(session:UserSession,me:FamilyMember,members:List<FamilyMember>,voice:FamilyVoice) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val api=remember{SupabaseService()}
    val scope=rememberCoroutineScope()
    val active by LocationSharing.running.collectAsState()
    val status by LocationSharing.status.collectAsState()
    var locations by remember{mutableStateOf(emptyList<SharedLocation>())}
    var places by remember{mutableStateOf(emptyList<FamilyPlace>())}
    var states by remember{mutableStateOf(emptyList<PlaceState>())}
    var newPlace by remember{mutableStateOf<LatLng?>(null)}
    var journeyPicker by remember{mutableStateOf(false)}
    var mapLoaded by remember{mutableStateOf(false)}
    val guardian=me.role in listOf("admin","adult") && me.relationship in listOf("padre","madre","tutor")
    var error by remember{mutableStateOf<String?>(null)}
    var consent by remember{mutableStateOf(false)}
    val camera=rememberCameraPositionState {position=CameraPosition.fromLatLngZoom(LatLng(-5.1945,-80.6328),12f)}
    var centered by remember{mutableStateOf(false)}
    fun startSharing() {
        if(!androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {error="Activa la ubicación del teléfono y vuelve a intentarlo";return}
        runCatching{LocationSharing.start(context)}.onFailure{error=it.message}
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted->
        if(granted) startSharing() else error="Permite las notificaciones para mostrar el aviso de ubicación compartida."
    }
    fun afterLocationPermission(){
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startSharing()
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result->
        if(result.values.any{it}) afterLocationPermission() else error="Sin permiso de ubicación no se puede compartir GPS."
    }
    LaunchedEffect(me.id) {
        while(true) {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runCatching{
                    if(LocationSharing.pendingPause && !LocationSharing.running.value) LocationSharing.pause(context)
                    api.locations(session,me.familyId)
                }.onSuccess{ rows->
                    locations=rows;error=null
                    val first=rows.firstOrNull{it.sharing && it.latitude.isFinite() && it.longitude.isFinite()}
                    if(!centered && first!=null){camera.position=CameraPosition.fromLatLngZoom(LatLng(first.latitude,first.longitude),15f);centered=true}
                }.onFailure{error="No se pudo sincronizar GPS: "+it.message}
                runCatching{places=api.places(session,me.familyId);states=api.placeStates(session,me.familyId)}.onFailure{error="Instala SQL 004 y comprueba Internet: "+it.message}
            }
            delay(10000)
        }
    }
    newPlace?.let{point->FamilyPlaceDialog(point.latitude,point.longitude,{newPlace=null}){name,kind,radius->
        scope.launch{runCatching{api.savePlace(session,me.familyId,name,kind,point.latitude,point.longitude,radius);api.places(session,me.familyId)}.onSuccess{places=it;newPlace=null}.onFailure{error=it.message}}
    }}
    if(journeyPicker) AlertDialog(onDismissRequest={journeyPicker=false},title={Text("Voy en camino a…")},text={Column{
        Text("Aviso declarado, no una ruta calculada. Necesitas GPS activo y reciente.",style=MaterialTheme.typography.bodySmall)
        if(places.isEmpty()) Text("Un padre/tutor debe agregar un lugar manteniendo pulsado el mapa.")
        places.forEach{place->TextButton(onClick={scope.launch{runCatching{api.journey(session,place.id)}.onSuccess{journeyPicker=false;voice.speak("Aviso de camino enviado a familiares autorizados.")}.onFailure{error=it.message}}}){Text(place.name)}}
    }},confirmButton={TextButton(onClick={journeyPicker=false}){Text("Cerrar")}})
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("Activar ubicación compartida")},text={Text("Este teléfono enviará GPS automáticamente mientras el servicio esté activo, incluso con la pantalla apagada. Los hijos comparten con padres/tutores aprobados. Los adultos solo con personas habilitadas en Privacidad. Habrá un aviso visible y podrás pausar. No es un servicio de emergencia.")},
        confirmButton={TextButton(onClick={
            consent=false
            val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(granted) afterLocationPermission() else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        }){Text("ACTIVAR")}},dismissButton={TextButton(onClick={consent=false}){Text("Cancelar")}})
    Column(Modifier.fillMaxSize()){
        Card(Modifier.fillMaxWidth().padding(10.dp)){
            Column(Modifier.padding(12.dp)){
                Text(if(active) "Ubicación automática activa" else "Este teléfono no está compartiendo",style=MaterialTheme.typography.titleMedium)
                Text(status,style=MaterialTheme.typography.bodySmall)
                Text("Mapa actualizado cada 10 s. GPS: aproximadamente 30 s.",style=MaterialTheme.typography.bodySmall)
                if(active) TextButton(onClick={scope.launch{runCatching{LocationSharing.pause(context)}.onFailure{error="Pausado localmente; pendiente de retirar la ubicación del servidor. Reintenta con Internet."}}}){Text("PAUSAR MI UBICACIÓN")}
                else TextButton(onClick={consent=true}){Text("ACTIVAR UBICACIÓN AUTOMÁTICA")}
            }
        }
        error?.let{Text(it,Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            TextButton(onClick={scope.launch{
                val visible=locations.filter{it.sharing && it.latitude.isFinite() && it.longitude.isFinite()}
                if(mapLoaded && visible.size==1) camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(visible[0].latitude,visible[0].longitude),15f))
                else if(mapLoaded && visible.size>1){val bounds=LatLngBounds.builder();visible.forEach{bounds.include(LatLng(it.latitude,it.longitude))};camera.animate(CameraUpdateFactory.newLatLngBounds(bounds.build(),90))}
            }}){Text("VER TODOS")}
            TextButton(onClick={journeyPicker=true}){Text("VOY EN CAMINO")}
        }
        if(guardian) Text("Mantén pulsado el mapa para agregar casa o colegio.",Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
        GoogleMap(Modifier.fillMaxWidth().weight(1f),cameraPositionState=camera,uiSettings=MapUiSettings(zoomControlsEnabled=false),onMapLoaded={mapLoaded=true},onMapLongClick={if(guardian) newPlace=it}){
            places.forEach{place->Circle(center=LatLng(place.latitude,place.longitude),radius=place.radius.toDouble(),strokeColor=Color(0xFF6B4EFF),fillColor=Color(0x226B4EFF),strokeWidth=3f)}
            locations.filter{it.sharing && it.latitude.isFinite() && it.longitude.isFinite()}.forEach{loc->
                key(loc.memberId){
                    val state=remember{MarkerState(position=LatLng(loc.latitude,loc.longitude))}
                    state.position=LatLng(loc.latitude,loc.longitude)
                    val name=members.find{it.id==loc.memberId}?.name?:"Perfil pendiente de sincronizar"
                    val icon=remember(loc.memberId,name){familyMarker(loc.memberId,name)}
                    Marker(state=state,title=name,icon=icon,snippet=locationAgeLabel(loc.capturedAt)+" · ±${loc.accuracy.toInt()} m")
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=190.dp),contentPadding=PaddingValues(12.dp)){
            if(locations.none{it.sharing}) item{Text("Sin ubicaciones compartidas autorizadas. Activa la ubicación en el otro teléfono.")}
            items(locations.filter{it.sharing},key={it.memberId}){loc->
                val name=members.find{it.id==loc.memberId}?.name?:"Perfil pendiente de sincronizar"
                val place=states.firstOrNull{it.memberId==loc.memberId && it.inside && recentFamilyFix(it.observedAt)}?.let{row->places.find{it.id==row.placeId}}
                Column(Modifier.fillMaxWidth().clickable{scope.launch{
                    voice.speak("Buscando la ubicación autorizada de $name.")
                    runCatching{api.locations(session,me.familyId)}.onSuccess{rows->locations=rows;val fix=rows.find{it.memberId==loc.memberId && it.sharing}
                        if(fix!=null && fix.latitude.isFinite() && fix.longitude.isFinite() && mapLoaded){camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude,fix.longitude),16f))}
                        voice.speak(if(fix!=null && recentFamilyFix(fix.capturedAt)) "Ya tenemos una ubicación reciente de $name." else "No hay una ubicación reciente de $name. Revisa la hora del último GPS.")
                    }.onFailure{error=it.message;voice.speak("No pudimos actualizar. Revisa Internet.")}
                }}.padding(vertical=6.dp)){
                    Text(name,style=MaterialTheme.typography.titleSmall)
                    Text(locationAgeLabel(loc.capturedAt)+" · ±${loc.accuracy.toInt()} m"+ (loc.battery?.let{" · $it %"+(if(loc.charging) " cargando" else "")}?:""),style=MaterialTheme.typography.bodySmall)
                    if(place!=null && recentFamilyFix(loc.capturedAt)) Text("En ${place.name} · zona confirmada por GPS",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun familyMarker(id:String,name:String):BitmapDescriptor {
    val bitmap=android.graphics.Bitmap.createBitmap(88,100,android.graphics.Bitmap.Config.ARGB_8888)
    val canvas=android.graphics.Canvas(bitmap)
    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color=android.graphics.Color.HSVToColor(floatArrayOf((id.hashCode().toLong().and(0x7fffffff)%360).toFloat(),.7f,.82f))
    canvas.drawCircle(44f,44f,40f,paint)
    val pointer=android.graphics.Path().apply{moveTo(31f,73f);lineTo(44f,99f);lineTo(57f,73f);close()};canvas.drawPath(pointer,paint)
    paint.color=android.graphics.Color.WHITE;paint.textAlign=android.graphics.Paint.Align.CENTER;paint.textSize=30f;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD
    val initials=name.trim().split(Regex("\\s+")).take(2).mapNotNull{it.firstOrNull()?.uppercaseChar()}.joinToString("")
    canvas.drawText(initials,44f,55f,paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@Composable
fun SyncedMessages(session:UserSession,me:FamilyMember,members:List<FamilyMember>) {
    val api=remember{SupabaseService()}
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var recipient by remember{mutableStateOf<String?>(null)}
    var rows by remember{mutableStateOf(emptyList<RemoteFamilyMessage>())}
    var draft by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var sending by remember{mutableStateOf(false)}
    var picker by remember{mutableStateOf(false)}
    LaunchedEffect(recipient) {
        rows=emptyList()
        while(true){
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching{api.messages(session,me.familyId,recipient,me.id)}
                .onSuccess{rows=it;error=null}.onFailure{error=it.message}
            delay(5000)
        }
    }
    if(picker) AlertDialog(onDismissRequest={picker=false},title={Text("Enviar mensaje a")},text={
        LazyColumn{item{TextButton(onClick={recipient=null;picker=false}){Text("Grupo familiar")}}
            items(members.filter{it.id!=me.id && it.authUserId!=null}){person->TextButton(onClick={recipient=person.id;picker=false}){Text(person.name)}}}
    },confirmButton={TextButton(onClick={picker=false}){Text("Cerrar")}})
    Column(Modifier.fillMaxSize().padding(14.dp)){
        TextButton(onClick={picker=true}){Text("Conversación: "+(members.find{it.id==recipient}?.name?:"Grupo familiar")+" ▾")}
        Text("Sincronización cada 5 segundos mientras esta pantalla está abierta.",style=MaterialTheme.typography.bodySmall)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp),reverseLayout=true,contentPadding=PaddingValues(vertical=12.dp)){
            items(rows.reversed(),key={it.id}){msg->
                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(msg.senderId==me.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)){
                    Column(Modifier.padding(12.dp)){
                        Text(members.find{it.id==msg.senderId}?.name?:"Integrante",style=MaterialTheme.typography.labelMedium)
                        Text(msg.body)
                        Text(runCatching{java.time.Instant.parse(msg.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace("T"," ")}.getOrDefault(msg.createdAt),style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        OutlinedTextField(draft,{draft=it.take(1000)},Modifier.fillMaxWidth(),label={Text("Escribe un mensaje")},maxLines=3)
        Button(enabled=!sending && draft.isNotBlank(),onClick={
            sending=true
            scope.launch{runCatching{api.sendMessage(session,me.familyId,me.id,recipient,draft)}
                .onSuccess{draft="";runCatching{api.messages(session,me.familyId,recipient,me.id)}.onSuccess{rows=it}}
                .onFailure{error=it.message};sending=false}
        },modifier=Modifier.fillMaxWidth()){Text(if(sending) "Enviando…" else "ENVIAR")}
    }
}
