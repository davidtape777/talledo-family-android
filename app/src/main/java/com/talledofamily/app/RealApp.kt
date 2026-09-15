package com.talledofamily.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TfPurple = Color(0xFF6B4EFF)
private val TfCoral = Color(0xFFFF5F63)
private val TfCream = Color(0xFFFFF8F3)
private val TfInk = Color(0xFF25233A)
private val TfMint = Color(0xFF2DBE8C)
private val relationships = listOf("padre", "madre", "hijo", "hija", "tutor", "otro")

@Composable
fun RealTalledoFamilyApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = TfPurple, secondary = TfCoral, tertiary = TfMint, background = TfCream)) {
        var session by remember { mutableStateOf<UserSession?>(null) }
        Surface(Modifier.fillMaxSize(), color = TfCream) {
            if (session == null) AuthScreen { session = it }
            else ConnectedApp(session!!, onExit = { session = null })
        }
    }
}

@Composable
private fun AuthScreen(onSession: (UserSession) -> Unit) {
    val api = remember { SupabaseService() }
    val scope = rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(Modifier.size(86.dp), shape = RoundedCornerShape(26.dp), color = TfPurple) {
            Box(contentAlignment = Alignment.Center) { Text("TF", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black) }
        }
        Spacer(Modifier.height(20.dp))
        Text("TALLEDO FAMILY", fontSize = 27.sp, fontWeight = FontWeight.Black, color = TfInk)
        Text(if (register) "Crear una cuenta real" else "Ingresa a tu espacio familiar", color = TfInk.copy(alpha=.62f))
        Spacer(Modifier.height(26.dp))
        OutlinedTextField(email, { email = it.trim(); message = null }, Modifier.fillMaxWidth(), label = { Text("Correo electrónico") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it; message = null }, Modifier.fillMaxWidth(), label = { Text("Contraseña (mínimo 6 caracteres)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(16.dp))
        message?.let { Text(it, color = if (it.startsWith("Revisa")) TfMint else TfCoral, modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center) }
        Button(
            enabled = !loading && email.contains("@") && password.length >= 6,
            onClick = {
                loading = true
                scope.launch {
                    runCatching {
                        if (register) api.signUp(email, password) else api.signIn(email, password)
                    }.onSuccess {
                        loading = false
                        if (it == null) message = "Revisa tu correo y confirma la cuenta; luego inicia sesión."
                        else onSession(it)
                    }.onFailure { loading = false; message = it.message ?: "No se pudo conectar" }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (register) "CREAR CUENTA" else "INICIAR SESIÓN", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { register = !register; message = null }) {
            Text(if (register) "Ya tengo una cuenta" else "Crear una cuenta nueva")
        }
        Text("Conexión protegida con Supabase · pruebas reales", fontSize = 11.sp, color = TfInk.copy(alpha=.48f))
    }
}

@Composable
private fun ConnectedApp(session: UserSession, onExit: () -> Unit) {
    val api = remember { SupabaseService() }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<FamilyMember?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(session, reload) {
        loading = true
        runCatching { api.myMember(session) }
            .onSuccess { current = it }
            .onFailure { error = it.message }
        loading = false
    }
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> ErrorScreen(error!!) { error = null; reload++ }
        current == null -> FamilyOnboarding(session) { reload++ }
        else -> FamilyDashboard(session, current!!, onExit) { reload++ }
    }
}

@Composable
private fun FamilyOnboarding(session: UserSession, onReady: () -> Unit) {
    val api = remember { SupabaseService() }
    val scope = rememberCoroutineScope()
    var createMode by rememberSaveable { mutableStateOf(true) }
    var personName by rememberSaveable { mutableStateOf("") }
    var familyName by rememberSaveable { mutableStateOf("Familia Talledo") }
    var code by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("padre") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Configura tu familia", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Puedes crear un grupo o entrar con un código de 6 dígitos.", color = TfInk.copy(alpha=.6f))
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(createMode, { createMode = true }, SegmentedButtonDefaults.itemShape(0,2)) { Text("Crear") }
                SegmentedButton(!createMode, { createMode = false }, SegmentedButtonDefaults.itemShape(1,2)) { Text("Unirme") }
            }
        }
        item { OutlinedTextField(personName, { personName=it }, Modifier.fillMaxWidth(), label={Text("Tu nombre")}, shape=RoundedCornerShape(16.dp)) }
        if (createMode) item { OutlinedTextField(familyName, { familyName=it }, Modifier.fillMaxWidth(), label={Text("Nombre del grupo familiar")}, shape=RoundedCornerShape(16.dp)) }
        else item { OutlinedTextField(code, { code=it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label={Text("Código familiar")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), shape=RoundedCornerShape(16.dp)) }
        item {
            Text("Tu relación", fontWeight=FontWeight.Bold)
            RelationshipPicker(relationship) { relationship=it }
        }
        error?.let { item { Text(it, color=TfCoral) } }
        item {
            Button(
                enabled=!loading && personName.isNotBlank() && (createMode || code.length==6),
                onClick={
                    loading=true
                    scope.launch {
                        runCatching {
                            if(createMode) {
                                api.createFamily(session, familyName, personName)
                                val me=api.myMember(session)!!
                                api.updateMember(session, me.id, personName, relationship)
                            } else api.joinFamily(session, code, personName, relationship)
                        }.onSuccess { loading=false; onReady() }
                         .onFailure { loading=false; error=it.message }
                    }
                },
                modifier=Modifier.fillMaxWidth().height(54.dp),
                shape=RoundedCornerShape(16.dp)
            ){ Text(if(createMode) "CREAR MI FAMILIA" else "UNIRME A LA FAMILIA", fontWeight=FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyDashboard(session: UserSession, me: FamilyMember, onExit: () -> Unit, onReload: () -> Unit) {
    val api=remember { SupabaseService() }
    val scope=rememberCoroutineScope()
    var family by remember { mutableStateOf<FamilyInfo?>(null) }
    var members by remember { mutableStateOf(emptyList<FamilyMember>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editMember by remember { mutableStateOf<FamilyMember?>(null) }
    var addMember by remember { mutableStateOf(false) }
    var editFamily by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    fun reload() { scope.launch {
        runCatching {
            family=api.family(session,me.familyId)
            members=api.members(session,me.familyId)
        }.onFailure { error=it.message }
    }}
    LaunchedEffect(me) { reload() }

    editMember?.let { target ->
        MemberDialog(target.name,target.relationship,"Editar integrante",{editMember=null}) { name,relation ->
            scope.launch { runCatching { api.updateMember(session,target.id,name,relation) }
                .onSuccess { editMember=null; reload() }.onFailure { error=it.message } }
        }
    }
    if(addMember) MemberDialog("","hijo","Agregar integrante",{addMember=false}) { name,relation ->
        scope.launch { runCatching { api.addProfile(session,me.familyId,name,relation) }
            .onSuccess { addMember=false; reload() }.onFailure { error=it.message } }
    }
    if(editFamily && family!=null) FamilyDialog(family!!,{editFamily=false}) { name,photo ->
        scope.launch { runCatching { api.updateFamily(session,me.familyId,name,photo) }
            .onSuccess { editFamily=false; reload() }.onFailure { error=it.message } }
    }

    Scaffold(
        containerColor=TfCream,
        topBar={ TopAppBar(title={Column{Text(family?.name ?: "TALLEDO FAMILY",fontWeight=FontWeight.Black); Text("Cuenta real · mapa protegido",fontSize=11.sp,color=TfMint)}},actions={TextButton(onClick=onExit){Text("Salir")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=TfCream)) },
        bottomBar={ NavigationBar { listOf("Familia","Mapa","Privacidad").forEachIndexed { i,label -> NavigationBarItem(selected = selectedTab == i, onClick = { selectedTab = i }, icon = { Text(listOf("⌂","◎","◉")[i], fontSize = 20.sp) }, label = { Text(label) }) } } }
    ){ padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when(selectedTab) {
                0 -> LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors=CardDefaults.cardColors(containerColor=TfPurple),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text(family?.name ?: "Tu familia",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Black)
                                Text("Código para invitar: ${family?.joinCode ?: "------"}",color=Color.White.copy(alpha=.85f))
                                if(me.role=="admin") TextButton(onClick={editFamily=true},colors=ButtonDefaults.textButtonColors(contentColor=Color.White)){Text("Cambiar nombre o imagen")}
                            }
                        }
                    }
                    items(members){member ->
                        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){
                            Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){
                                Surface(Modifier.size(48.dp),CircleShape,color=TfPurple.copy(alpha=.13f)){Box(contentAlignment=Alignment.Center){Text(member.name.take(1).uppercase(),fontWeight=FontWeight.Black,color=TfPurple)}}
                                Column(Modifier.padding(start=12.dp).weight(1f)){Text(member.name,fontWeight=FontWeight.Bold);Text(member.relationship.replaceFirstChar{it.uppercase()},fontSize=12.sp,color=TfInk.copy(alpha=.55f))}
                                if(member.authUserId==session.userId || me.role=="admin") TextButton(onClick={editMember=member}){Text("Editar")}
                            }
                        }
                    }
                    if(me.role=="admin") item { OutlinedButton(onClick={addMember=true},Modifier.fillMaxWidth()){Text("+ Agregar hijo o integrante")} }
                    item {
                        Button(onClick={scope.launch{runCatching{api.sendEvent(session,me.familyId,me.id,"family_touch")}.onSuccess{notice="Toque familiar registrado en Supabase"}.onFailure{error=it.message}}},Modifier.fillMaxWidth().height(54.dp)){Text("💜 ENVIAR TOQUE FAMILIAR")}
                    }
                    item {
                        Button(onClick={scope.launch{runCatching{api.sendEvent(session,me.familyId,me.id,"need_me")}.onSuccess{notice="Prueba TE NECESITO registrada; no se enviaron alertas externas"}.onFailure{error=it.message}}},Modifier.fillMaxWidth().height(58.dp),colors=ButtonDefaults.buttonColors(containerColor=TfCoral)){Text("TE NECESITO · PRUEBA",fontWeight=FontWeight.Black)}
                    }
                }
                1 -> RealFamilyMap()
                else -> PrivacyScreen(session,api,me,members) { error=it }
            }
            error?.let { AlertDialog(onDismissRequest={error=null},title={Text("No se pudo completar")},text={Text(it)},confirmButton={TextButton(onClick={error=null}){Text("Entendido")}}) }
            notice?.let { AlertDialog(onDismissRequest={notice=null},title={Text("Listo")},text={Text(it)},confirmButton={TextButton(onClick={notice=null}){Text("Cerrar")}}) }
        }
    }
}

@Composable
private fun PrivacyScreen(session:UserSession,api:SupabaseService,me:FamilyMember,members:List<FamilyMember>,onError:(String)->Unit){
    val scope=rememberCoroutineScope()
    var modes by remember { mutableStateOf(mapOf<String,String>()) }
    val adults=members.filter{it.id!=me.id && it.relationship in listOf("padre","madre","tutor")}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item { Text("Privacidad entre adultos",fontSize=26.sp,fontWeight=FontWeight.Black);Text("Tu configuración no elimina al otro progenitor ni afecta su vínculo con los hijos.",color=TfInk.copy(alpha=.6f)) }
        items(adults){target ->
            val mode=modes[target.id] ?: "visible"
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)){
                Column(Modifier.padding(16.dp)){
                    Text(target.name,fontWeight=FontWeight.Bold)
                    Text("Visibilidad actual: ${mode.replace('_',' ')}",fontSize=13.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                        listOf("visible","children_only","hidden","blocked").forEach{choice ->
                            FilterChip(mode==choice,{
                                modes=modes+(target.id to choice)
                                scope.launch{runCatching{api.setVisibility(session,me.familyId,me.id,target.id,choice)}.onFailure{onError(it.message?:"Error")}}
                            },{Text(when(choice){"children_only"->"Solo hijos";"hidden"->"Oculto";"blocked"->"Bloqueado";else->"Visible"},fontSize=10.sp)})
                        }
                    }
                }
            }
        }
        if(adults.isEmpty()) item { Text("Cuando otro padre, madre o tutor se una, podrás configurar aquí su visibilidad.") }
    }
}

@Composable
private fun RelationshipPicker(selected:String,onSelect:(String)->Unit){
    Column { relationships.chunked(3).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{r->FilterChip(selected==r,{onSelect(r)},{Text(r.replaceFirstChar{it.uppercase()})})}} } }
}

@Composable
private fun MemberDialog(initialName:String,initialRelationship:String,title:String,onDismiss:()->Unit,onSave:(String,String)->Unit){
    var name by remember { mutableStateOf(initialName) }; var relation by remember { mutableStateOf(initialRelationship) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column{OutlinedTextField(name,{name=it},label={Text("Nombre")});Spacer(Modifier.height(8.dp));RelationshipPicker(relation){relation=it}}},confirmButton={Button(enabled=name.isNotBlank(),onClick={onSave(name.trim(),relation)}){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}

@Composable
private fun FamilyDialog(family:FamilyInfo,onDismiss:()->Unit,onSave:(String,String?)->Unit){
    var name by remember { mutableStateOf(family.name) }; var photo by remember { mutableStateOf(family.photoUrl.orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Editar grupo familiar")},text={Column{OutlinedTextField(name,{name=it},label={Text("Nombre de la familia")});Spacer(Modifier.height(8.dp));OutlinedTextField(photo,{photo=it},label={Text("URL de imagen (opcional)")},supportingText={Text("La carga directa de fotos se habilitará después de estas pruebas.")})}},confirmButton={Button(enabled=name.isNotBlank(),onClick={onSave(name.trim(),photo.trim().ifBlank{null})}){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}

@Composable
private fun ErrorScreen(message:String,onRetry:()->Unit){Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text("No pudimos cargar la familia",fontWeight=FontWeight.Bold,fontSize=22.sp);Text(message,textAlign=TextAlign.Center,modifier=Modifier.padding(12.dp));Button(onClick=onRetry){Text("Reintentar")}}}}
