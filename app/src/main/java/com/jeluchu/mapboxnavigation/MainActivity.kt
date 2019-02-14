package com.jeluchu.mapboxnavigation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.geocoding.v5.GeocodingCriteria
import com.mapbox.api.geocoding.v5.MapboxGeocoding
import com.mapbox.api.geocoding.v5.models.GeocodingResponse
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.offline.*
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerPlugin
import com.mapbox.mapboxsdk.plugins.places.autocomplete.PlaceAutocomplete
import com.mapbox.mapboxsdk.plugins.places.autocomplete.model.PlaceOptions
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.nio.charset.Charset
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(),  View.OnClickListener, PermissionsListener, OnMapReadyCallback {

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap
        enableLocationPlugin()
    }




    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private var transport = "driving-traffic"

    private lateinit var menuView: Menu

    private lateinit var permissionsManager: PermissionsManager
    private var originLocation: Location? = null


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

    private var destinationMarker: Marker? = null
    private var originCoord: LatLng? = null
    private var locationPlugin: LocationLayerPlugin? = null
    var locationEngine: LocationEngine? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OBTENCIÓN DEL TOKEN
        Mapbox.getInstance(this@MainActivity, getString(R.string.acces_token))
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        // MAP SETTINGS
        mapView.getMapAsync {

            mapboxMap = it

            offlineManager = OfflineManager.getInstance(this@MainActivity)

            mapboxMap.locationComponent.isLocationComponentEnabled = true
            mapboxMap.uiSettings.isAttributionEnabled = false
            mapboxMap.uiSettings.isLogoEnabled = false

            enableLocationComponent()

            mapboxMap.addOnMapClickListener {
                initMarker(it)
            }


            mapboxMap.setStyle(Style.TRAFFIC_DAY) {

                // Custom map style has been loaded and map is now ready

            }

        }

        btnFindPlace.setOnClickListener(this@MainActivity)
        btnDownload.setOnClickListener(this@MainActivity)
        btnOfflineList.setOnClickListener(this@MainActivity)

    }


    private fun enableLocationComponent() {

        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {


            // Get an instance of the component
            val locationComponent = mapboxMap.locationComponent

            // Enable to make component visible
            locationComponent.isLocationComponentEnabled = true


        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)

        }
    }


    @SuppressLint("MissingPermission")
    private fun enableLocationPlugin() {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {

            val options = LocationComponentOptions.builder(this)
                .trackingGesturesManagement(true)
                .accuracyColor(ContextCompat.getColor(this, R.color.primary_dark_material_dark))
                .build()

            // Get an instance of the component
            val locationComponent = mapboxMap.locationComponent

            mapboxMap.setStyle(Style.Builder().fromUrl(
                "mapbox://styles/mapbox/cjerxnqt3cgvp2rmyuxbeqme7")) { style ->

                // Activate the component
                locationComponent.activateLocationComponent(this, style)

                // Apply the options to the LocationComponent
                locationComponent.applyStyle(options)

                // Enable to make component visible
                locationComponent.isLocationComponentEnabled = true

                // Set the component's camera mode
                locationComponent.cameraMode = CameraMode.TRACKING
                locationComponent.renderMode = RenderMode.COMPASS

            }
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
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
                downloadRegionList()
            }
        }
    }

    /* -------------------------------- TOOLBAR MENU ---------------------------------------- */

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_transportation, menu!!)
        menuView = menu
        return true
    }


    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        menuView.findItem(R.id.traffic).setIcon(R.drawable.ic_traffic_black_24dp)
        menuView.findItem(R.id.car).setIcon(R.drawable.ic_directions_car_black_24dp)
        menuView.findItem(R.id.motorcycle).setIcon(R.drawable.ic_motorcycle_black_24dp)
        menuView.findItem(R.id.walking).setIcon(R.drawable.ic_directions_walk_black_24dp)

        when (item!!.itemId) {
            R.id.traffic -> {
                transport = "driving-traffic"
                item.setIcon(R.drawable.ic_traffic_white_24dp)
            }

            R.id.car -> {
                transport = "driving"
                item.setIcon(R.drawable.ic_directions_car_white_24dp)
            }

            R.id.motorcycle -> {
                transport = "cycling"
                item.setIcon(R.drawable.ic_motorcycle_white_24dp)
            }

            R.id.walking -> {
                transport = "walking"
                item.setIcon(R.drawable.ic_directions_walk_white_24dp)
            }
        }

        if (originPosition != null && destinationPosition != null)
            getRoute(originPosition!!, destinationPosition!!)

        return true
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
                Timber.e("onError reason: %s", error.reason)
                Timber.e("onError message: %s", error.message)
            }

            @SuppressLint("LogNotTimber")
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

    /* ------------------------------- NAVIGATION BUTTON ---------------------------------------- */
      /*private fun startNavigation(){
          if (originPosition != null && destinationPosition != null){
              val option = NavigationLauncherOptions.builder()
                  .initialMapCameraPosition(originCoord)
                  .origin(originPosition) // ERROR
                  .destination(destinationPosition)
                  .directionsProfile(transport)
                  .enableOffRouteDetection(true)
                  .build()

              NavigationLauncher.startNavigation(this@MainActivity, option)
          }
      } */


    /* ----------------------------- DOWNLOAD REGION BUTTON ---------------------------------------- */
    private fun downloadRegionList() {

        // RESET PLACE TO 0
        regionSelected = 0

        offlineManager?.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<out OfflineRegion>?) {

                if (offlineRegions == null || offlineRegions.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Aún no tienes lugares", Toast.LENGTH_SHORT).show()
                    return
                }

                // TODOS LOS NOMBRES DE LUGARES EN LA LISTA
                val offlineRegionsNames = ArrayList<String>()
                for (offlineRegion in offlineRegions) {
                    offlineRegionsNames.add(getRegionName(offlineRegion))
                }
                val items = offlineRegionsNames.toTypedArray<CharSequence>()

                // ALERT DIALOG
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Listado")
                    .setSingleChoiceItems(items, 0) { _, which ->
                        // VER CUÁL ES EL LUGAR SELECCIONADO
                        regionSelected = which
                    }
                    .setPositiveButton("Iniciar la navegación") { _, _ ->
                        Toast.makeText(this@MainActivity, items[regionSelected], Toast.LENGTH_LONG).show()

                        // RECOGER DATOS DEL LUGAR
                        val bounds = (offlineRegions[regionSelected].definition as OfflineTilePyramidRegionDefinition).bounds
                        val regionZoom = (offlineRegions[regionSelected].definition as OfflineTilePyramidRegionDefinition).minZoom

                        // CREAR UNA NUEVA POSICIÓN DE LA CÁMARA
                        val cameraPosition = CameraPosition.Builder()
                            .target(bounds.center)
                            .zoom(regionZoom)
                            .build()

                        // MOVER LA CÁMARA A DICHA POSICIÓN
                        mapboxMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                    }
                    .setNeutralButton("Eliminar") { _, _ ->
                        progress_bar.isIndeterminate = true
                        progress_bar.visibility = View.VISIBLE

                        // PROCESO DE ELIMINACIÓN
                        offlineRegions[regionSelected].delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {
                                progress_bar.visibility = View.INVISIBLE
                                progress_bar.isIndeterminate = false
                                Toast.makeText(this@MainActivity, "Lugar eliminado", Toast.LENGTH_LONG).show()
                            }

                            override fun onError(error: String) {
                                progress_bar.visibility = View.INVISIBLE
                                progress_bar.isIndeterminate = false
                                Timber.e("¡Ha ocurrido un error!")
                            }
                        })
                    }
                    .setNegativeButton("Cancelar") { _, _ ->
                        // NO SE HACE NADA, SE CIERRA AUTOMÁTICAMENTE
                    }.create()
                dialog.show()

            }

            @SuppressLint("LogNotTimber")
            override fun onError(error: String) {
                Log.e("ERROR", "Error: $error")
            }

        })
    }

    @SuppressLint("LogNotTimber")
    private fun getRegionName(offlineRegion: OfflineRegion): String {
        // OBTENCIÓN DEL NOMBRE MEDIANTE LOS METADATOS
        return try {
            val metadata = offlineRegion.metadata
            val json = metadata.toString(Charset.defaultCharset())
            val jsonObject = JSONObject(json)
            jsonObject.getString(JSON_FIELD_REGION_NAME)
        } catch (exception: Exception) {
            Timber.e("Failed to decode metadata: %s", exception.message)
            "Region " + offlineRegion.id
        }
    }

    private fun getRoute(origin: Point, destination: Point) {

        NavigationRoute.builder(applicationContext)
            .accessToken(getString(R.string.acces_token))
            .origin(origin)
            .destination(destination)
            .profile(transport)
            .build()
            .getRoute(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    if (response.body() == null) {
                        Timber.d("No routes found, make sure you set the right user and access token.")
                        return
                    } else if (response.body()!!.routes().isEmpty()) {
                        Timber.e("No routes found")
                        Toast.makeText(this@MainActivity, "No routes found", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val currentRoute = response.body()!!.routes()[0]

                    // PINTAR LA RUTA EN EL MAPA
                    if (navigationMapRoute != null) {
                        navigationMapRoute!!.removeRoute()
                    } else {
                        navigationMapRoute = NavigationMapRoute(null, mapView, mapboxMap, R.style.NavigationMapRoute)
                    }
                    navigationMapRoute!!.addRoute(currentRoute)
                }

                override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                    Timber.e("Error: %s", throwable.message)
                }
            })
    }

    private fun initMarker(latLng: LatLng): Boolean {

        val point: Point = Point.fromLngLat(latLng.longitude, latLng.latitude)
        MapboxGeocoding.builder()
            .accessToken(getString(R.string.acces_token))
            .mode("mapbox.places")
            .geocodingTypes(GeocodingCriteria.TYPE_POI)
            .query(point)
            .build()
            .enqueueCall(object : Callback<GeocodingResponse> {
                override fun onResponse(call: Call<GeocodingResponse>, response: Response<GeocodingResponse>) {

                    val features = response.body()?.features()!!
                    val selectedPlace = if (features.isNotEmpty()) features[0] else null

                    val markerOption = MarkerOptions()
                        .position(latLng)

                    if (selectedPlace != null) {
                        markerOption.title(selectedPlace.placeName())
                            .snippet(selectedPlace.address())
                    }

                    setMarkerAndRoute(markerOption)
                }

                override fun onFailure(call: Call<GeocodingResponse>, t: Throwable) {
                    Timber.e("Error: %s", t.message)
                }
            })
        return true
    }


    private fun setMarkerAndRoute(markerOptions: MarkerOptions) {
        if (destinationMarker != null)
            mapboxMap.removeMarker(destinationMarker!!)
        destinationMarker = mapboxMap.addMarker(markerOptions)

        destinationPosition = Point.fromLngLat(markerOptions.position.longitude, markerOptions.position.latitude)
        originPosition = Point.fromLngLat(originCoord!!.longitude, originCoord!!.latitude)
        getRoute(originPosition!!, destinationPosition!!)

    }

    /* -------------------------------- PERMISSIONS ---------------------------------------- */

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onPermissionResult(granted: Boolean) {

        if (granted) {
            enableLocationPlugin()
        } else {
            Toast.makeText(this, "Permisos no concedidos", Toast.LENGTH_LONG).show()
            finish()
        }

    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    /* ---------------------------- LIFECYCLE FOR MAP ACTIVITY ---------------------------------------- */

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

