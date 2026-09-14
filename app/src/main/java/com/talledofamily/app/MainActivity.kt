package com.talledofamily.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DEMO_CODE = "777777"

fun isValidFamilyCode(code: String): Boolean =
    code.length == 6 && code.all(Char::isDigit)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TalledoFamilyTheme { TalledoFamilyApp() } }
    }
}

private val Purple = Color(0xFF6B4EFF)
private val Coral = Color(0xFFFF6B6B)
private val Cream = Color(0xFFFFF8F3)
private val Ink = Color(0xFF25233A)
private val Mint = Color(0xFF2DBE8C)

@Composable
private fun TalledoFamilyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Purple,
            secondary = Coral,
            tertiary = Mint,
            background = Cream,
            surface = Color.White,
            onPrimary = Color.White,
            onBackground = Ink,
            onSurface = Ink
        ),
        typography = Typography(),
        content = content
    )
}

@Composable
private fun TalledoFamilyApp() {
    var authenticated by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (authenticated) FamilyShell(onExit = { authenticated = false })
        else AccessScreen(onAccess = { authenticated = true })
    }
}

@Composable
private fun AccessScreen(onAccess: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(92.dp),
            shape = RoundedCornerShape(28.dp),
            color = Purple
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("TF", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("TALLEDO FAMILY", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Text(
            "Un espacio privado para mantenernos cerca",
            textAlign = TextAlign.Center,
            color = Ink.copy(alpha = .65f),
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
        )
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.filter(Char::isDigit).take(6)
                error = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Código familiar de 6 dígitos") },
            singleLine = true,
            isError = error,
            supportingText = {
                Text(if (error) "Código incorrecto" else "Modo demo · usa 777777")
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                error = code != DEMO_CODE
                if (!error) onAccess()
            },
            enabled = isValidFamilyCode(code),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("ENTRAR A MI FAMILIA", fontWeight = FontWeight.Bold) }
        Text(
            "Esta versión no usa ubicación, contactos ni servicios reales.",
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = Ink.copy(alpha = .55f),
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

private enum class FamilyTab(val label: String, val symbol: String) {
    HOME("Familia", "⌂"),
    MAP("Mapa", "◎"),
    MESSAGES("Mensajes", "✉"),
    HELP("Ayuda", "!")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyShell(onExit: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(FamilyTab.HOME) }
    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TALLEDO FAMILY", fontWeight = FontWeight.ExtraBold)
                        Text("Demostración segura", fontSize = 11.sp, color = Mint)
                    }
                },
                actions = { TextButton(onClick = onExit) { Text("Salir") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                FamilyTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.symbol, fontSize = 21.sp, fontWeight = FontWeight.Bold) },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                FamilyTab.HOME -> FamilyHome(
                    openMap = { tab = FamilyTab.MAP },
                    openMessages = { tab = FamilyTab.MESSAGES },
                    openHelp = { tab = FamilyTab.HELP }
                )
                FamilyTab.MAP -> FamilyMap()
                FamilyTab.MESSAGES -> MessagesScreen()
                FamilyTab.HELP -> NeedMeScreen()
            }
        }
    }
}

private data class ChildCardData(
    val name: String,
    val detail: String,
    val status: String,
    val initials: String,
    val color: Color
)

private val children = listOf(
    ChildCardData("Sayumi", "Hija mayor", "En casa · simulado", "S", Color(0xFFE76F9B)),
    ChildCardData("Vania", "Hija", "En clases · simulado", "V", Color(0xFF3A86D1)),
    ChildCardData("David Fernando", "Hijo", "Con familia · simulado", "D", Color(0xFF24A477))
)

@Composable
private fun FamilyHome(
    openMap: () -> Unit,
    openMessages: () -> Unit,
    openHelp: () -> Unit
) {
    var showTouch by remember { mutableStateOf(false) }
    if (showTouch) {
        AlertDialog(
            onDismissRequest = { showTouch = false },
            title = { Text("Toque familiar enviado 💜") },
            text = { Text("En esta demostración nadie recibe una notificación real.") },
            confirmButton = { TextButton(onClick = { showTouch = false }) { Text("Entendido") } }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Hola, familia 👋", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Aquí siempre podemos sentirnos cerca.", color = Ink.copy(alpha = .65f))
        }
        items(children) { child -> ChildCard(child) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard("Mapa familiar", "Ver demo", "◎", openMap, Modifier.weight(1f))
                ActionCard("Mensajes", "Conversar", "✉", openMessages, Modifier.weight(1f))
            }
        }
        item {
            Button(
                onClick = { showTouch = true },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) { Text("💜  ENVIAR TOQUE FAMILIAR", fontWeight = FontWeight.Bold) }
        }
        item {
            OutlinedButton(
                onClick = openHelp,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Coral)
            ) { Text("TE NECESITO", fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun ChildCard(child: ChildCardData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = child.color, modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(child.initials, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(child.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(child.detail, fontSize = 13.sp, color = Ink.copy(alpha = .55f))
            }
            AssistChip(
                onClick = {},
                label = { Text(child.status, fontSize = 11.sp) }
            )
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(116.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(symbol, fontSize = 25.sp, color = Purple)
            Spacer(Modifier.weight(1f))
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = Ink.copy(alpha = .55f))
        }
    }
}

@Composable
private fun FamilyMap() {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Mapa familiar", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Escenario ilustrativo, sin GPS ni ubicaciones reales.", color = Ink.copy(alpha = .6f))
        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3EE))
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val road = Color.White
                    drawLine(road, Offset(0f, size.height * .25f), Offset(size.width, size.height * .62f), 28f)
                    drawLine(road, Offset(size.width * .30f, 0f), Offset(size.width * .55f, size.height), 22f)
                    drawLine(Color(0xFFB8DCC8), Offset(0f, size.height * .75f), Offset(size.width, size.height * .38f), 9f)
                    listOf(
                        Triple(.22f, .28f, Color(0xFFE76F9B)),
                        Triple(.70f, .39f, Color(0xFF3A86D1)),
                        Triple(.48f, .72f, Color(0xFF24A477))
                    ).forEach { (x, y, color) ->
                        drawCircle(color.copy(alpha = .18f), 44f, Offset(size.width * x, size.height * y))
                        drawCircle(color, 23f, Offset(size.width * x, size.height * y))
                        drawCircle(Color.White, 23f, Offset(size.width * x, size.height * y), style = Stroke(5f))
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = .94f)
                ) {
                    Text(
                        "DEMO · posiciones inventadas",
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontWeight = FontWeight.Bold,
                        color = Purple
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        children.forEach { child ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(12.dp).background(child.color, CircleShape))
                Text(child.name, Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                Text(child.status, fontSize = 12.sp, color = Ink.copy(alpha = .55f))
            }
        }
    }
}

private data class FamilyMessage(val sender: String, val text: String, val mine: Boolean)

@Composable
private fun MessagesScreen() {
    val messages = remember {
        mutableStateListOf(
            FamilyMessage("Sayumi", "¡Hola familia! Este es un mensaje de ejemplo.", false),
            FamilyMessage("Papá", "Siempre estoy aquí para ustedes 💜", true),
            FamilyMessage("Vania", "Todo está bien. Mensaje simulado.", false)
        )
    }
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("Mensajes", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Conversación local de demostración", color = Ink.copy(alpha = .6f))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (message.mine) Purple else Color.White,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.widthIn(max = 290.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                message.sender,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (message.mine) Color.White.copy(alpha = .75f) else Purple
                            )
                            Text(message.text, color = if (message.mine) Color.White else Ink)
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(160) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje de prueba") },
                shape = RoundedCornerShape(18.dp),
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        messages += FamilyMessage("Papá", draft.trim(), true)
                        draft = ""
                    }
                },
                modifier = Modifier.size(52.dp),
                contentPadding = PaddingValues(0.dp),
                shape = CircleShape
            ) { Text("➤") }
        }
    }
}

@Composable
private fun NeedMeScreen() {
    var confirm by remember { mutableStateOf(false) }
    var activated by remember { mutableStateOf(false) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Text("🫶", fontSize = 34.sp) },
            title = { Text("Activar TE NECESITO") },
            text = {
                Text("Esto solo mostrará una confirmación dentro de la app. No llamará, enviará mensajes ni compartirá ubicación.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        activated = true
                        confirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral)
                ) { Text("ACTIVAR DEMO") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Coral.copy(alpha = .13f),
            modifier = Modifier.size(132.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Text("🫶", fontSize = 58.sp) }
        }
        Spacer(Modifier.height(24.dp))
        Text("TE NECESITO", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Coral)
        Text(
            "Un botón para expresar que necesitas acompañamiento familiar.",
            textAlign = TextAlign.Center,
            color = Ink.copy(alpha = .65f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
        if (activated) {
            Surface(
                color = Mint.copy(alpha = .13f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            ) {
                Text(
                    "✓ Demostración activada. No se envió ninguna alerta real.",
                    Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = Color(0xFF147456),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Button(
            onClick = { confirm = true },
            modifier = Modifier.fillMaxWidth().height(62.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral)
        ) {
            Text(if (activated) "PROBAR NUEVAMENTE" else "PRESIONAR EN MODO DEMO", fontWeight = FontWeight.ExtraBold)
        }
        Text(
            "En una fase futura podrá configurarse contacto, privacidad y protocolo de ayuda.",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = Ink.copy(alpha = .5f),
            modifier = Modifier.padding(top = 18.dp)
        )
    }
}
