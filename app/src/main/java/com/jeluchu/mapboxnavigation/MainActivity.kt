package com.jeluchu.mapboxnavigation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.offline.*
import com.mapbox.mapboxsdk.plugins.places.autocomplete.PlaceAutocomplete
import com.mapbox.mapboxsdk.plugins.places.autocomplete.model.PlaceOptions
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.nio.charset.Charset


class MainActivity : AppCompatActivity(),  View.OnClickListener {

    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private var transport = "driving-traffic"

    private lateinit var menuView: Menu

    private val REQUEST_CODE_AUTOCOMPLETE = 1

    // OFFLINE MAP OPTIONS
    // JSON ENCODING/DECODING
    private val JSON_FIELD_REGION_NAME = "FIELD_REGION_NAME"
    private var isEndNotified: Boolean = false
    private var regionSelected: Int = 0
    private var offlineManager: OfflineManager? = null
    private var offlineRegionDownloaded : OfflineRegion? = null

    // CALCULATING AND DRAW ROUTE
    private var originPosition: Point? = null
    private var destinationPosition: Point? = null
    private var navigationMapRoute: NavigationMapRoute? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OBTENCIÓN DEL TOKEN
        Mapbox.getInstance(this@MainActivity, getString(R.string.acces_token))
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync {

            it.setStyle(Style.TRAFFIC_DAY)
            it.locationComponent.isLocationComponentEnabled = true
            it.uiSettings.isAttributionEnabled = false
            it.uiSettings.isLogoEnabled = false
        }

        btnFindPlace.setOnClickListener(this@MainActivity)
        btnDownload.setOnClickListener(this@MainActivity)

    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_transportation, menu!!)
        menuView = menu
        return true
    }


    override fun onClick(v: View?){
        when (v?.id) {
            R.id.btnFindPlace -> {
                findPlace()
            }
            R.id.btnStartNavigation -> {
                //startNavigation()
            }
            R.id.btnDownload -> {
                downloadRegionDialog()
            }
            R.id.btnOfflineList -> {
                //downloadRegionList()
            }
        }
    }

    /* ------------------------------- FIND PLACE BUTTON ---------------------------------------- */
    private fun findPlace(){
        val intent = PlaceAutocomplete.IntentBuilder()
            .accessToken(getString(R.string.acces_token))
            .placeOptions(PlaceOptions.builder()
                    .backgroundColor(Color.parseColor("#EEEEEE"))
                    .limit(10)
                    .hint("Busca un lugar...")
                    .build(PlaceOptions.MODE_FULLSCREEN))
            .build(this@MainActivity)
        startActivityForResult(intent, REQUEST_CODE_AUTOCOMPLETE)
    }


    /* -------------------------------- DOWNLOAD BUTTON ---------------------------------------- */
    private fun downloadRegionDialog() {

        val builder = AlertDialog.Builder(this@MainActivity)

        val regionNameEdit = EditText(this@MainActivity)
        regionNameEdit.hint = "Introduce un nombre"
        regionNameEdit.gravity = Gravity.CENTER_HORIZONTAL
        regionNameEdit.ellipsize

        // CREAR EL DIALOGO
        builder.setTitle("Registrar lugar")
            .setView(regionNameEdit)
            .setMessage("Descarga la zona del mapa que estás viendo actualmente.\n")
            .setPositiveButton("Descargar") { _, _ ->
                val regionName = regionNameEdit.text.toString()

                if (regionName.isEmpty()) {
                    Toast.makeText(this@MainActivity, "El campo no puede estar vacío", Toast.LENGTH_SHORT).show()
                } else {
                    // EMPEZAR DESCARGA
                    downloadRegion(regionName)
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ -> dialog.cancel() }

        // MOSTRAR
        builder.show()
    }

    @SuppressLint("LogNotTimber")
    private fun downloadRegion(regionName: String) {

        // INICIO PROGRESS BAR
        startProgress()

        val styleUrl = mapboxMap.style.toString()
        val bounds = mapboxMap.projection.visibleRegion.latLngBounds
        val minZoom = mapboxMap.cameraPosition.zoom
        val maxZoom = mapboxMap.maxZoomLevel
        val pixelRatio = this@MainActivity.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, minZoom, maxZoom, pixelRatio)

        val metadata: ByteArray? = try {
            val jsonObject = JSONObject()
            jsonObject.put(JSON_FIELD_REGION_NAME, regionName)
            val json = jsonObject.toString()
            json.toByteArray(Charset.defaultCharset())
        } catch (exception: Exception) {
            Log.d("ERROR", "Failed to encode metadata: " + exception.message)
            null
        }


        // CREAR LA ZONA OFFLINE Y LANZAR LA DESCARGA
        offlineManager?.createOfflineRegion(definition, metadata!!, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                Log.d("INFO", "Offline region created: $regionName")
                offlineRegionDownloaded = offlineRegion
                launchDownload()
            }

            override fun onError(error: String) {
                Log.e("ERROR", "Error: $error")
            }
        })
    }

    private fun startProgress() {

        // INICIAR PROGRESS BAR
        isEndNotified = false
        progress_bar.isIndeterminate = true
        progress_bar.visibility = View.VISIBLE
    }

    private fun launchDownload() {

        // NOTIFICAR AL USUARIO CUÁNDO SE COMPLETE LA DESCARGA
        offlineRegionDownloaded?.setObserver(object : OfflineRegion.OfflineRegionObserver {
            @SuppressLint("LogNotTimber")
            override fun onStatusChanged(status: OfflineRegionStatus) {
                // PORCENTAJE
                val percentage = if (status.requiredResourceCount >= 0)
                    100.0 * status.completedResourceCount / status.requiredResourceCount
                else
                    0.0

                if (status.isComplete) {
                    // DESCARGA COMPLETA
                    endProgress("Zona descargada correctamente")
                    return
                } else if (status.isRequiredResourceCountPrecise) {
                    setPercentage(Math.round(percentage).toInt())
                }

                Log.d("DOWNLOAD", String.format("%s/%s resources; %s bytes downloaded.",
                    status.completedResourceCount.toString(),
                    status.requiredResourceCount.toString(),
                    status.completedResourceSize.toString()))
            }

            override fun onError(error: OfflineRegionError) {
                Log.e("DOWNLOAD", "onError reason: " + error.reason)
                Log.e("DOWNLOAD", "onError message: " + error.message)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Log.e("DOWNLOAD", "Mapbox tile count limit exceeded: $limit")
            }
        })

        // CAMBIAR EL ESTADO DE LA REGIÓN
        offlineRegionDownloaded?.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun endProgress(message: String) {

        // NO NOTIFICAR MÁS DE UNA VEZ
        if (isEndNotified) {
            return
        }

        // PARAR Y OCULTAR PROGRESS BAR
        isEndNotified = true
        progress_bar.isIndeterminate = false
        progress_bar.visibility = View.GONE

        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
    }

    private fun setPercentage(percentage: Int) {
        progress_bar.isIndeterminate = false
        progress_bar.progress = percentage
    }



    /* LIFECYCLE FOR MAP ACTIVITY */

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)

        if(outState != null) {
            mapView.onSaveInstanceState(outState)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

}

