package com.example.sampleview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Places SDK
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, "AIzaSyBpdIbcsDSiVXRxGZx3mHr2DGSUyhNAv5E")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LocationBasedRecommendations(fusedLocationClient)
                }

        }
    }
}

@Composable
fun LocationBasedRecommendations(fusedLocationClient: FusedLocationProviderClient) {
    var recommendedPlaces by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var currentLocationName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val typeLabels = mapOf(
        "park" to "Parks 🏞️",
        "shopping_mall" to "Shopping Malls 🛍️",
        "tourist_attraction" to "Tourist Attractions 🗽"
    )

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            fetchLocationAndPlaces(context, fusedLocationClient) { locationName, places ->
                currentLocationName = locationName
                recommendedPlaces = places
                loading = false
            }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        when {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                fetchLocationAndPlaces(context, fusedLocationClient) { locationName, places ->
                    currentLocationName = locationName
                    recommendedPlaces = places
                    loading = false
                }
            }

            else -> {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Nearby Places", style = MaterialTheme.typography.headlineMedium)

        currentLocationName?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text("You're near: $it", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            recommendedPlaces.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No nearby places found", style = MaterialTheme.typography.bodyLarge)
                }
            }

            else -> {
                LazyColumn {
                    recommendedPlaces.forEach { (type, places) ->
                        val label = typeLabels[type] ?: type.replaceFirstChar { it.uppercase() }

                        item {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        items(places) { place ->
                            Text("• $place", modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                        }

                        item { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}



private val client = OkHttpClient()

fun getLocationName(context: Context, lat: Double, lon: Double): String? {
    return try {
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.featureName
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun fetchLocationAndPlaces(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient,
    onResult: (locationName: String?, places: Map<String, List<String>>) -> Unit
) {
    val cancellationTokenSource = CancellationTokenSource()

    try {
        fusedLocationClient.getCurrentLocation(
            LocationRequest.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            if (location != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val name = getLocationName(context, location.latitude, location.longitude)
                    val places = fetchNearbyCoolPlaces(location.latitude, location.longitude)
                    withContext(Dispatchers.Main) {
                        onResult(name, places)
                    }
                }
            } else {
                onResult(null, emptyMap())
            }
        }.addOnFailureListener {
            it.printStackTrace()
            onResult(null, emptyMap())
        }
    } catch (e: SecurityException) {
        e.printStackTrace()
        onResult(null, emptyMap())
    }
}

suspend fun fetchNearbyCoolPlaces(lat: Double, lon: Double): Map<String, List<String>> {
    val apiKey = "AIzaSyBpdIbcsDSiVXRxGZx3mHr2DGSUyhNAv5E"
    val radius = 2000
    val types = listOf("tourist_attraction", "shopping_mall", "park")

    val categorizedPlaces = mutableMapOf<String, MutableList<String>>()

    for (type in types) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("maps.googleapis.com")
            .addPathSegments("maps/api/place/nearbysearch/json")
            .addQueryParameter("location", "$lat,$lon")
            .addQueryParameter("radius", radius.toString())
            .addQueryParameter("type", type)
            .addQueryParameter("key", apiKey)
            .build()

        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) response.close()

                val json = response.body?.string()
                response.close()
                if (json == null) return emptyMap()

                val root = JSONObject(json)
                val results = root.getJSONArray("results")

                val list = categorizedPlaces.getOrPut(type) { mutableListOf() }

                for (i in 0 until results.length()) {
                    val place = results.getJSONObject(i)
                    val name = place.optString("name")
                    if (name.isNotEmpty()) {
                        list.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    return categorizedPlaces.mapValues { it.value.sorted() }
}



