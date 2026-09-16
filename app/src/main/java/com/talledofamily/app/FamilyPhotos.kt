package com.talledofamily.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun PrivateFamilyPhoto(session:UserSession,path:String?,modifier:Modifier=Modifier){
    var bitmap by remember(path){mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(path){
        if(!path.isNullOrBlank() && !path.startsWith("http")) runCatching {
            withContext(Dispatchers.IO){
                val url=SupabaseService().photoUrl(session,path)
                val c=URL(url).openConnection() as HttpURLConnection
                c.connectTimeout=15000;c.readTimeout=20000
                try { c.inputStream.use{BitmapFactory.decodeStream(it)} } finally{c.disconnect()}
            }
        }.onSuccess{bitmap=it}
    }
    bitmap?.let{Image(it.asImageBitmap(),contentDescription="Foto familiar",modifier=modifier,contentScale=ContentScale.Crop)}
}

@Composable
fun PickFamilyPhoto(session:UserSession,familyId:String,path:String?,onPhoto:(String)->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
        if(uri!=null){
            loading=true
            scope.launch {
                runCatching{
                    val bytes=withContext(Dispatchers.IO){
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                        require(bounds.outWidth>0 && bounds.outHeight>0){"No se pudo leer la imagen"}
                        var sample=1
                        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024) sample*=2
                        val options=BitmapFactory.Options().apply{inSampleSize=sample}
                        val bmp=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)} ?: throw IllegalArgumentException("Imagen inválida")
                        val out=ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG,85,out);bmp.recycle();out.toByteArray()
                    }
                    SupabaseService().uploadPhoto(session,familyId,bytes)
                }.onSuccess{onPhoto(it);error=null}.onFailure{error=it.message}
                loading=false
            }
        }
    }
    Column {
        PrivateFamilyPhoto(session,path,Modifier.fillMaxWidth().height(110.dp))
        TextButton(enabled=!loading,onClick={launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}){Text(if(loading) "Subiendo imagen…" else "ELEGIR FOTO DEL TELÉFONO")}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}
