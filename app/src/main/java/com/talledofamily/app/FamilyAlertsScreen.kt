package com.talledofamily.app

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun FamilyAlertsScreen(session:UserSession,me:FamilyMember,notices:List<FamilyNotice>,voice:FamilyVoice) {
    val context=LocalContext.current
    val api=remember{SupabaseService()}
    val scope=rememberCoroutineScope()
    val registrationStatus by FamilyPush.registrationStatus.collectAsState()
    var places by remember{mutableStateOf(emptyList<FamilyPlace>())}
    var error by remember{mutableStateOf<String?>(null)}
    var enabled by remember{mutableStateOf(voice.enabled)}
    var messages by remember{mutableStateOf(voice.readMessages)}
    var background by remember{mutableStateOf(voice.readInBackground)}
    var quiet by remember{mutableStateOf(voice.quiet)}
    var volume by remember{mutableFloatStateOf(voice.volume)}
    var voiceStatus by remember{mutableStateOf(voice.status)}
    var deletePlace by remember{mutableStateOf<FamilyPlace?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(!granted) error="Activa Notificaciones en Ajustes de Android para recibir avisos con la app cerrada."
        else FamilyPush.register(context,session)
    }
    LaunchedEffect(me.id){
        runCatching{api.places(session,me.familyId)}.onSuccess{places=it}.onFailure{error="Falta instalar SQL 004 o revisar la conexión: "+it.message}
        while(true){voiceStatus=voice.status;kotlinx.coroutines.delay(1000)}
    }
    deletePlace?.let{place->AlertDialog(onDismissRequest={deletePlace=null},title={Text("Eliminar ${place.name}?")},text={Text("Se detendrán sus alertas automáticas. Puedes agregar un lugar corregido desde el mapa.")},
        confirmButton={TextButton(onClick={scope.launch{runCatching{api.deletePlace(session,place.id);api.places(session,me.familyId)}.onSuccess{places=it;deletePlace=null}.onFailure{error=it.message}}}){Text("Eliminar")}},dismissButton={TextButton(onClick={deletePlace=null}){Text("Cancelar")}})}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Asistente familiar",style=MaterialTheme.typography.headlineSmall)}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
            Row{Text("Voz española",Modifier.weight(1f));Switch(enabled,{enabled=it;voice.enabled=it})}
            Text(voiceStatus,style=MaterialTheme.typography.bodySmall)
            Row{Text("Leer también mensajes",Modifier.weight(1f));Switch(messages,{messages=it;voice.readMessages=it})}
            Row{Text("Leer mensajes en segundo plano",Modifier.weight(1f));Switch(background,{background=it;voice.readInBackground=it;if(!it) context.stopService(Intent(context,FamilyVoiceService::class.java))})}
            Row{Text("Silencio de 22:00 a 07:00",Modifier.weight(1f));Switch(quiet,{quiet=it;voice.quiet=it})}
            Text("Volumen de voz: ${(volume*100).toInt()} %")
            Slider(volume,{volume=it;voice.volume=it},valueRange=.1f..1f)
            Text("Con la opción de segundo plano puede leer mensajes nuevos mientras usas otra app. Activa también Voz española y Leer también mensajes. Solo con pantalla desbloqueada; respeta silencio y No molestar. Durante la lectura muestra un aviso para detenerla. No necesita micrófono.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={voice.speak("Hola, soy el asistente de Talledo Family. Tu familia está conectada.")}){Text("PROBAR VOZ")}
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("AJUSTES DE VOZ DE ANDROID")}
        }}}
        item{OutlinedButton(onClick={
            if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else FamilyPush.register(context,session)
        },modifier=Modifier.fillMaxWidth()){Text("HABILITAR AVISOS EN ESTE DISPOSITIVO")}}
        item{Text(registrationStatus,style=MaterialTheme.typography.bodySmall)}
        item{Text("Lugares y alertas",style=MaterialTheme.typography.titleLarge);Text("Padres/tutores: mantén pulsado el mapa para agregar casa, colegio u otro lugar. Dos lecturas GPS precisas confirman entradas y salidas. No se avisa al activar GPS por primera vez.",style=MaterialTheme.typography.bodySmall)}
        items(places,key={it.id}){place->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text("${place.name} · ${place.kind} · radio ${place.radius} m")
            if(me.role in listOf("admin","adult") && me.relationship in listOf("padre","madre","tutor")) TextButton(onClick={deletePlace=place}){Text("Eliminar / corregir")}
        }}}
        item{Text("Mis avisos autorizados",style=MaterialTheme.typography.titleLarge);Text("Las notificaciones externas muestran solo un aviso genérico por privacidad. La voz y el detalle se consultan dentro de la app.",style=MaterialTheme.typography.bodySmall)}
        if(notices.isEmpty()) item{Text("Aún no hay avisos. Prueba un mensaje desde el otro dispositivo.")}
        items(notices,key={it.id}){notice->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(notice.text)
            Text(locationAgeLabel(notice.createdAt),style=MaterialTheme.typography.bodySmall)
            Text("Push: "+when(notice.pushState){"sent"->"aceptado por Firebase (no confirma lectura)";"failed"->"no enviado / sin dispositivo registrado";"processing"->"enviando";"cancelled"->"retirado";else->"pendiente: revisa instalación del servidor"},style=MaterialTheme.typography.bodySmall)
            Row{TextButton(onClick={voice.speak(notice.text,notice.kind=="message")}){Text("ESCUCHAR")}
                if(!notice.read) TextButton(onClick={scope.launch{runCatching{api.readNotice(session,notice.id)}.onFailure{error=it.message}}}){Text("MARCAR LEÍDO")}}
        }}}
        error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        item{Text("No es un sistema de emergencia. Sin Internet no hay ubicaciones remotas ni avisos nuevos. Android puede detener GPS por batería, reinicio o cierre forzado.",style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
fun FamilyPlaceDialog(lat:Double,lng:Double,onDismiss:()->Unit,onSave:(String,String,Int)->Unit) {
    var name by remember{mutableStateOf("")}
    var kind by remember{mutableStateOf("casa")}
    var radius by remember{mutableStateOf("150")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Agregar lugar familiar")},text={Column{
        Text("Punto elegido: %.5f, %.5f".format(java.util.Locale.US,lat,lng),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it.take(60)},label={Text("Nombre: Casa / Colegio")})
        Row{listOf("casa","colegio","otro").forEach{value->TextButton(onClick={kind=value}){Text(if(kind==value) "✓ $value" else value)}}}
        OutlinedTextField(radius,{radius=it.filter(Char::isDigit).take(4)},label={Text("Radio de 100 a 2000 metros")})
        Text("El punto elegido debe corresponder al lugar, no a un integrante.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(enabled=name.isNotBlank() && radius.toIntOrNull()?.let{it in 100..2000}==true,onClick={onSave(name.trim(),kind,radius.toInt())}){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}
