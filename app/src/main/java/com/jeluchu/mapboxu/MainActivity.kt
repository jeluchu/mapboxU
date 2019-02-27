package com.jeluchu.mapboxu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.hlab.fabrevealmenu.enums.Direction
import com.hlab.fabrevealmenu.listeners.OnFABMenuSelectedListener
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineListener
import com.mapbox.android.core.location.LocationEnginePriority
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.DirectionsCriteria.*
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.offline.*
import com.mapbox.mapboxsdk.plugins.places.autocomplete.PlaceAutocomplete
import com.mapbox.mapboxsdk.plugins.places.autocomplete.model.PlaceOptions
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillOpacity
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.toolbar.*
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.Charset
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(),
    PermissionsListener, LocationEngineListener, OnMapReadyCallback, MapboxMap.OnMapClickListener,
    OnFABMenuSelectedListener {

    private val REQUEST_CHECK_SETTINGS = 1
    private val REQUEST_CODE_AUTOCOMPLETE = 1
    private var settingsClient: SettingsClient? = null

    lateinit var map: MapboxMap
    private lateinit var permissionManager: PermissionsManager
    private var originLocation: Location? = null

    private var locationEngine: LocationEngine? = null
    private var locationComponent: LocationComponent? = null

    var navigationMapRoute: NavigationMapRoute? = null
    var currentRoute: DirectionsRoute? = null

    private var transport: String = PROFILE_DRIVING_TRAFFIC
    private lateinit var menuView: Menu

    // Offline Map
    // JSON encoding/decoding
    private val JSON_FIELD_REGION_NAME = "FIELD_REGION_NAME"
    private var isEndNotified: Boolean = false
    private var regionSelected: Int = 0
    private var offlineManager: OfflineManager? = null
    private var offlineRegionDownloaded : OfflineRegion? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.acces_token))
        setContentView(R.layout.activity_main)

        // TOOLBAR SUPPORT
        setSupportActionBar(toolbar)
        supportActionBar!!.title = "MapboxU"

        mapbox.onCreate(savedInstanceState)
        mapbox.getMapAsync(this)
        settingsClient = LocationServices.getSettingsClient(this)

        // MAPBOX SETTINGS
        mapbox.getMapAsync {
            it.locationComponent.isLocationComponentEnabled = true
            it.uiSettings.isAttributionEnabled = false
            it .uiSettings.isLogoEnabled = false

            // TRY TO LOAD LAYER FOR LIMITS
            try{
                val geoJsonUrl = URL(getString(R.string.geosonLimit))
                val urbanAreasSource = GeoJsonSource("urban-areas", geoJsonUrl)
                map.addSource(urbanAreasSource)

                val urbanArea = FillLayer("urban-areas-fill", "urban-areas")

                urbanArea.setProperties(
                    fillColor(Color.parseColor("#8ee614")),
                    fillOpacity(0.36f)
                )

                map.addLayer(urbanArea)
            } catch (malformedUrlException: MalformedURLException) {
                malformedUrlException.printStackTrace()
            }
        }

        // FAB MENU OPTIONS
        btnExtend!!.bindAnchorView(btnNavigate!!)
        btnExtend!!.setOnFABMenuSelectedListener(this)

        // FABBUTTON OPENING
        btnNavigate.setOnClickListener {
            btnExtend!!.menuDirection = Direction.LEFT
        }

    }

    /* ------------------------------- FABBUTTON OPTIONS -------------------------------- */
    override fun onMenuItemSelected(view: View, id: Int) {
        when (id) {
            R.id.startNavigation -> startNavigation()
            R.id.searchPlace -> findPlace()
            R.id.downloadMap -> downloadRegionDialog()
            R.id.listPlace -> downloadRegionList()
        }
    }

    /* -------------------------------- START NAVIGATION -------------------------------- */
    private fun startNavigation() {

        if (currentRoute != null && transport == PROFILE_DRIVING_TRAFFIC) {
            val navigationLauncherOptions = NavigationLauncherOptions.builder()
                .directionsRoute(currentRoute)
                .directionsProfile(PROFILE_DRIVING_TRAFFIC)
                .shouldSimulateRoute(true) //3
                .build()

            NavigationLauncher.startNavigation(this, navigationLauncherOptions)
        } else if (currentRoute != null && transport == PROFILE_DRIVING) {
            val navigationLauncherOptions = NavigationLauncherOptions.builder()
                .directionsRoute(currentRoute)
                .directionsProfile(PROFILE_DRIVING)
                .shouldSimulateRoute(true) //3
                .build()

            NavigationLauncher.startNavigation(this, navigationLauncherOptions)
        } else if (currentRoute != null && transport == PROFILE_CYCLING) {
            val navigationLauncherOptions = NavigationLauncherOptions.builder()
                .directionsRoute(currentRoute)
                .directionsProfile(PROFILE_CYCLING)
                .shouldSimulateRoute(true) //3
                .build()

            NavigationLauncher.startNavigation(this, navigationLauncherOptions)
        } else if (currentRoute != null && transport == PROFILE_WALKING) {
            val navigationLauncherOptions = NavigationLauncherOptions.builder()
                .directionsRoute(currentRoute)
                .directionsProfile(PROFILE_WALKING)
                .shouldSimulateRoute(true) //3
                .build()

            NavigationLauncher.startNavigation(this, navigationLauncherOptions)
        } else {

            // ALERT DIALOG FOR LOOK MISTAKES
            val builder = AlertDialog.Builder(this@MainActivity)

            builder.setTitle("¡Aviso!")
            builder.setMessage("Necesitas marcar el punto de destino para iniciar la navegación")

            builder.setNegativeButton("Cerrar") { dialog, _
                -> dialog.dismiss()
            }

            val dialog: AlertDialog = builder.create()
            dialog.show()

        }
    }

    /* ------------------------------- SEARCH NAVIGATION -------------------------------- */
    private fun findPlace(){

        val intent = PlaceAutocomplete.IntentBuilder()
              .accessToken(getString(R.string.acces_token))
              .placeOptions(PlaceOptions.builder()
                  .backgroundColor(Color.parseColor("#EEEEEE"))
                  .limit(10)
                  .country("es")
                  .hint("Busca un lugar...")
                  .toolbarColor(Color.parseColor("#14C889"))
                  .build(PlaceOptions.MODE_FULLSCREEN))
              .build(this@MainActivity)
          startActivityForResult(intent, REQUEST_CODE_AUTOCOMPLETE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_AUTOCOMPLETE) {

            enableLocation()

            val feature = PlaceAutocomplete.getPlace(data)
            Toast.makeText(this, feature.text(), Toast.LENGTH_LONG).show()

        } else if (resultCode == Activity.RESULT_CANCELED) {
            finish()
        }
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

        val styleUrl = map.styleUrl.toString()
        val bounds = map.projection.visibleRegion.latLngBounds
        val minZoom = map.cameraPosition.zoom
        val maxZoom = map.maxZoomLevel
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
        progress_bar.visibility = View.INVISIBLE
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
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
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


    /* -------------------------------- TRANSPORT OPTION ------------------------------------------- */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_transportation, menu!!)
        menuView = menu
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        menuView.findItem(R.id.defaultNav).setIcon(R.drawable.ic_trafdef_active)
        menuView.findItem(R.id.car).setIcon(R.drawable.ic_car_active)
        menuView.findItem(R.id.motorcycle).setIcon(R.drawable.ic_motorcycle_active)
        menuView.findItem(R.id.walking).setIcon(R.drawable.ic_walk_active)

        when (item!!.itemId) {
            R.id.defaultNav -> {
                transport = PROFILE_DRIVING_TRAFFIC
                item.setIcon(R.drawable.ic_trafdef)
            }

            R.id.car -> {
                transport = PROFILE_DRIVING
                item.setIcon(R.drawable.ic_car)
            }

            R.id.motorcycle -> {
                transport = PROFILE_CYCLING
                item.setIcon(R.drawable.ic_motorcycle)
            }

            R.id.walking -> {
                transport = PROFILE_WALKING
                item.setIcon(R.drawable.ic_walk)
            }

            R.id.info -> {
                val info = Intent(this@MainActivity, InfoActivity::class.java)
                startActivity(info)

            }
        }
        return true
    }


    /* -------------------------------- LIFECYCLE  -----------------------------------------------*/
    @SuppressWarnings("MissingPermission")
    override fun onStart() {
        super.onStart()
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            locationEngine?.requestLocationUpdates()
            locationComponent?.onStart()
        }

        mapbox.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapbox.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapbox.onPause()
    }

    override fun onStop() {
        super.onStop()
        locationEngine?.removeLocationUpdates()
        locationComponent?.onStop()
        mapbox.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationEngine?.deactivate()
        mapbox.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapbox.onLowMemory()
    }


    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        Toast.makeText(this, "This app needs location permission to be able to show your location on the map", Toast.LENGTH_LONG).show()
    }

    /* -------------------------------- PERMISSIONS APP ----------------------------------------- */

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocation()
        } else {
            Toast.makeText(this, "User location was not granted", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onLocationChanged(location: Location?) {
        location?.run {
            originLocation = this
            setCameraPosition(this)
        }
    }

    override fun onConnected() {
    }

    override fun onMapReady(mapboxMap: MapboxMap?) {
        //1
        map = mapboxMap ?: return

        //2
        val locationRequestBuilder = LocationSettingsRequest.Builder().addLocationRequest(LocationRequest()
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        )

        //3
        val locationRequest = locationRequestBuilder?.build()

        settingsClient?.checkLocationSettings(locationRequest)?.run {
            addOnSuccessListener {
                enableLocation()
            }

            addOnFailureListener {
                val statusCode = (it as ApiException).statusCode

                if (statusCode == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                    val resolvableException = it as? ResolvableApiException
                    resolvableException?.startResolutionForResult(this@MainActivity, REQUEST_CHECK_SETTINGS)
                }
            }
        }
    }

    //1
    private fun enableLocation() {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            initializeLocationComponent()
            initializeLocationEngine()
            map.addOnMapClickListener(this)
        } else {
            permissionManager = PermissionsManager(this)
            permissionManager.requestLocationPermissions(this)
        }
    }

    //2
    @SuppressWarnings("MissingPermission")
    fun initializeLocationEngine() {
        locationEngine = LocationEngineProvider(this).obtainBestLocationEngineAvailable()
        locationEngine?.priority = LocationEnginePriority.HIGH_ACCURACY
        locationEngine?.activate()
        locationEngine?.addLocationEngineListener(this)

        val lastLocation = locationEngine?.lastLocation
        if (lastLocation != null) {
            originLocation = lastLocation
            setCameraPosition(lastLocation)
        } else {
            locationEngine?.addLocationEngineListener(this)
        }
    }

    @SuppressWarnings("MissingPermission")
    fun initializeLocationComponent() {
        locationComponent = map.locationComponent
        locationComponent?.activateLocationComponent(this)
        locationComponent?.isLocationComponentEnabled = true
        locationComponent?.cameraMode = CameraMode.TRACKING
    }

    //3
    private fun setCameraPosition(location: Location) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude,
            location.longitude), 30.0))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onMapClick(point: LatLng) {
        if (!map.markers.isEmpty()) {
            map.clear()
        }

        map.addMarker(MarkerOptions().setTitle("¡Soy Marki! ʕ⊙ᴥ⊙ʔ").setSnippet("Un marcador y te llevaré hasta tu destino").position(point))

        checkLocation()
        originLocation?.run {
            val startPoint = Point.fromLngLat(longitude, latitude)
            val endPoint = Point.fromLngLat(point.longitude, point.latitude)

            getRoute(startPoint, endPoint)
        }
    }


    @SuppressLint("MissingPermission")
    private fun checkLocation() {
        if (originLocation == null) {
            map.locationComponent.lastKnownLocation?.run {
                originLocation = this
            }
        }
    }

    private fun getRoute(originPoint: Point, endPoint: Point) {
        NavigationRoute.builder(this) //1
            .accessToken(Mapbox.getAccessToken()!!) //2
            .origin(originPoint) //3
            .profile(transport)
            .voiceUnits(METRIC)
            .destination(endPoint) //4
            .build() //5
            .getRoute(object : Callback<DirectionsResponse> { //6
                override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                    Timber.d(t.localizedMessage)
                }


                override fun onResponse(call: Call<DirectionsResponse>,
                                        response: Response<DirectionsResponse>) {
                    if (navigationMapRoute != null) {
                        navigationMapRoute?.updateRouteVisibilityTo(false)
                    } else {
                        navigationMapRoute = NavigationMapRoute(null, mapbox, map)
                    }

                    currentRoute = response.body()?.routes()?.first()
                    if (currentRoute != null) {
                        navigationMapRoute?.addRoute(currentRoute)
                    }

                    btnNavigate.isEnabled = true
                }
            })
    }
}
