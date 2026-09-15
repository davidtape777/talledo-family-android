package com.talledofamily.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@SuppressLint("MissingPermission")
@Composable
fun RealFamilyMap() {
    val context = LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val piura = remember { LatLng(-5.1945, -80.6328) }
    var current by remember { mutableStateOf<LatLng?>(null) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(piura, 12f) }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) fused.lastLocation.addOnSuccessListener { location ->
            location?.let {
                current = LatLng(it.latitude, it.longitude)
                camera.move(CameraUpdateFactory.newLatLngZoom(current!!, 15f))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = permissionGranted),
            uiSettings = MapUiSettings(myLocationButtonEnabled = permissionGranted, zoomControlsEnabled = false)
        ) {
            current?.let { Marker(state = rememberUpdatedMarkerState(it), title = "Mi ubicación", snippet = "Visible solo con tu permiso") }
        }
        Card(
            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f))
        ) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (permissionGranted) "Mapa real activado" else "La ubicación está desactivada", style = MaterialTheme.typography.titleMedium)
                Text("Tú decides cuándo compartirla", style = MaterialTheme.typography.bodySmall)
                if (!permissionGranted) {
                    TextButton(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                        Text("PERMITIR UBICACIÓN")
                    }
                }
            }
        }
    }
}
