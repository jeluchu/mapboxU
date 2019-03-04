package com.jeluchu.mapboxu

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import com.hlab.fabrevealmenu.enums.Direction
import com.hlab.fabrevealmenu.listeners.OnFABMenuSelectedListener
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.DirectionsCriteria.*
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.geocoding.v5.models.CarmenFeature
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.offline.*
import com.mapbox.mapboxsdk.plugins.building.BuildingPlugin
import com.mapbox.mapboxsdk.plugins.offline.OfflineRegionSelector
import com.mapbox.mapboxsdk.plugins.offline.model.NotificationOptions
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.model.RegionSelectionOptions
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflinePlugin
import com.mapbox.mapboxsdk.plugins.offline.utils.OfflineUtils
import com.mapbox.mapboxsdk.plugins.places.autocomplete.PlaceAutocomplete
import com.mapbox.mapboxsdk.plugins.places.autocomplete.model.PlaceOptions
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
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

@Suppress("DEPRECATION", "PrivatePropertyName")
class MainActivity : AppCompatActivity(), OnMapReadyCallback, MapboxMap.OnMapClickListener, PermissionsListener,
    OnFABMenuSelectedListener {

    // MAPBOX
    private var mapboxMap: MapboxMap? = null

    // PERMISSIONS MANAGER REQUEST
    private var permissionsManager: PermissionsManager? = null
    private var locationComponent: LocationComponent? = null

    // BUILDING PLUGIN MAPBOX
    private var buildingPlugin: BuildingPlugin? = null

    // CALCULATE ROUTE
    private var currentRoute: DirectionsRoute? = null
    var navigationMapRoute: NavigationMapRoute? = null

    private var transport: String = PROFILE_DRIVING_TRAFFIC
    private lateinit var menuView: Menu

    private val REQUEST_CODE_AUTOCOMPLETE = 1

    val JSON_CHARSET = "UTF-8"
    val JSON_FIELD_REGION_NAME = "FIELD_REGION_NAME"

    private val TAG = "MapActivity"

    lateinit var metadata: ByteArray


    private val PLACES_PLUGIN_SEARCH_RESULT_SOURCE_ID = "PLACES_PLUGIN_SEARCH_RESULT_SOURCE_ID"


    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.acces_token))
        setContentView(R.layout.activity_main)

        // TOOLBAR SUPPORT
        setSupportActionBar(toolbar)
        supportActionBar!!.title = "MapboxU"

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // FAB MENU OPTIONS
        btnExtend.bindAnchorView(btnNavigate)
        btnExtend.setOnFABMenuSelectedListener(this)

        // FABBUTTON OPENING
        btnNavigate.setOnClickListener {
            btnExtend.menuDirection = Direction.LEFT
        }

        initFloatButtonOption()

    }

    /* ------------------------------- FABBUTTON OPTIONS -------------------------------- */
    override fun onMenuItemSelected(view: View, id: Int) {
        when (id) {
            R.id.searchPlace -> findPlace()
            R.id.downloadMap -> onDownlaodMapClicked()
            R.id.listPlace -> {}
        }
    }

    private fun initFloatButtonOption() {
        val fab: View = findViewById(R.id.btnNavigation)
        fab.setOnClickListener {
            startNavigation()
        }
    }

    /* --------------------------------- ON MAP READY ----------------------------------- */
    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap
        mapboxMap.setStyle(Style.TRAFFIC_DAY) { style ->

            enableLocationComponent(style)

            initBuildingPlugin(style)

            addLimitCentral(style)

            mapboxMap.uiSettings.isAttributionEnabled = false
            mapboxMap.uiSettings.isLogoEnabled = false

            addDestinationIconSymbolLayer(style)

            mapboxMap.addOnMapClickListener(this@MainActivity)
            // Add the marker image to map
            style.addImage(
                "marker-icon-id",
                BitmapFactory.decodeResource(
                    this@MainActivity.resources, R.drawable.mapbox_marker_icon_default
                )
            )

            val geoJsonSource = GeoJsonSource(
                "source-id", Feature.fromGeometry(
                    Point.fromLngLat(-87.679, 41.885)
                )
            )
            style.addSource(geoJsonSource)

            val symbolLayer = SymbolLayer("layer-id", "source-id")
            symbolLayer.withProperties(
                PropertyFactory.iconImage("marker-icon-id")
            )
            style.addLayer(symbolLayer)
        }
    }

    /* ----------------------------------- PLUGINS -------------------------------------- */
    private fun initBuildingPlugin(@NonNull loadedMapStyle: Style) {
        buildingPlugin = mapboxMap?.let { BuildingPlugin(mapView, it, loadedMapStyle) }
        buildingPlugin!!.setVisibility(true)
    }

    /* ----------------------------------- GEOJSON -------------------------------------- */
    private fun addLimitCentral(loadedMapStyle: Style) {

        try{
            val geoJsonUrl = URL(getString(R.string.geojsonLimit))
            val urbanAreasSource = GeoJsonSource("urban-areas", geoJsonUrl)
            loadedMapStyle.addSource(urbanAreasSource)

            val urbanArea = FillLayer("urban-areas-fill", "urban-areas")

            urbanArea.setProperties(
                PropertyFactory.fillColor(Color.parseColor("#8ee614")),
                PropertyFactory.fillOpacity(0.36f)
            )

            loadedMapStyle.addLayerBelow(urbanArea, "cameras")
        } catch (malformedUrlException: MalformedURLException) {
            malformedUrlException.printStackTrace()
        }

    }
    private fun addDestinationIconSymbolLayer(loadedMapStyle: Style) {
        loadedMapStyle.addImage(
            "destination-icon-id",
            BitmapFactory.decodeResource(this.resources, R.drawable.mapbox_marker_icon_default)
        )
        val geoJsonSource = GeoJsonSource("destination-source-id")
        loadedMapStyle.addSource(geoJsonSource)
        val destinationSymbolLayer = SymbolLayer("destination-symbol-layer-id", "destination-source-id")
        destinationSymbolLayer.withProperties(
            PropertyFactory.iconImage("destination-icon-id"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
        loadedMapStyle.addLayer(destinationSymbolLayer)
    }

    /* --------------------------------- PERMISSIONS ------------------------------------ */
    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(loadedMapStyle: Style) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            // Activate the MapboxMap LocationComponent to show user location
            // Adding in LocationComponentOptions is also an optional parameter
            locationComponent = mapboxMap!!.locationComponent
            locationComponent!!.activateLocationComponent(this, loadedMapStyle)
            locationComponent!!.isLocationComponentEnabled = true
            // Set the component's camera mode
            locationComponent!!.cameraMode = CameraMode.TRACKING
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager!!.requestLocationPermissions(this)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        permissionsManager!!.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onExplanationNeeded(permissionsToExplain: List<String>) {
        Toast.makeText(this, R.string.user_location_permission_explanation, Toast.LENGTH_LONG).show()
    }
    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationComponent(mapboxMap!!.style!!)
        } else {
            Toast.makeText(this, R.string.user_location_permission_not_granted, Toast.LENGTH_LONG).show()
            finish()
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

    /* -------------------------------- SEARCH NAVIGATION -------------------------------- */
    private fun findPlace() {

        val intent = PlaceAutocomplete.IntentBuilder()
            .accessToken(getString(R.string.acces_token))
            .placeOptions(PlaceOptions
                .builder()
                .backgroundColor(Color.parseColor("#EEEEEE"))
                .limit(10)
                .country("es")
                .hint("Busca un lugar...")
                .toolbarColor(Color.parseColor("#FFFFFF"))
                .build(PlaceOptions.MODE_FULLSCREEN))
            .build(this)
        startActivityForResult(intent, REQUEST_CODE_AUTOCOMPLETE)

    }
    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_AUTOCOMPLETE) {

            val feature: CarmenFeature = PlaceAutocomplete.getPlace(data)

            if (mapboxMap!!.style != null) {
                val source = mapboxMap!!.style!!.getSourceAs<GeoJsonSource>(PLACES_PLUGIN_SEARCH_RESULT_SOURCE_ID)
                source?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        arrayOf(Feature.fromJson(feature.toJson()))
                    )
                )
            }

            val newCameraPosition = CameraPosition.Builder()
                .target(
                    LatLng(
                        feature.center()!!.coordinates()[1],
                        feature.center()!!.coordinates()[0]
                    )
                )
                .zoom(16.0)
                .build()

            mapboxMap!!.animateCamera(CameraUpdateFactory.newCameraPosition(newCameraPosition), 3000)
            Toast.makeText(applicationContext, "Pulsa para generar la ruta", Toast.LENGTH_SHORT).show()

        }
    }

    /* ---------------------------------- DOWNLOAD MAP ----------------------------------- */

    fun onDownlaodMapClicked() {


        val offlineManager = OfflineManager.getInstance(this@MainActivity)

        val latLngBounds = LatLngBounds.Builder()
            .include(LatLng(37.7897, -119.5073)) // Northeast
            .include(LatLng(37.6744, -119.6815)) // Southwest
            .build()

        val definition = OfflineTilePyramidRegionDefinition(
            mapboxMap!!.style.toString(),
            latLngBounds,
            10.0,
            20.0,this@MainActivity.resources.displayMetrics.density)

        try {
            val jsonObject = JSONObject()
            jsonObject.put(JSON_FIELD_REGION_NAME, "Yosemite National Park")
            val json = jsonObject.toString()
            metadata = json.toByteArray(charset(JSON_CHARSET))
        } catch (exception: Exception) {
            Log.e("Error", "Failed to encode metadata: " + exception.message)
        }


        offlineManager.createOfflineRegion(definition, metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)

                    // Monitor the download progress using setObserver
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {

                            // Calculate the download percentage
                            val percentage = if (status.requiredResourceCount >= 0)
                                100.0 * status.completedResourceCount /status.requiredResourceCount else 0.0

                            if (status.isComplete) {
                                // Download complete
                                Log.d(TAG, "Region downloaded successfully.")
                            } else if (status.isRequiredResourceCountPrecise) {
                                Log.d(TAG, percentage.toString())
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            // If an error occurs, print to logcat
                            Log.e(TAG, "onError reason: " + error.reason)
                            Log.e(TAG, "onError message: " + error.message)
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            // Notify if offline region exceeds maximum tile count
                            Log.e(TAG, "Mapbox tile count limit exceeded: $limit")
                        }
                    })
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Error: $error")
                }
            })




    }

    /* -------------------------------- TRANSPORT OPTION -------------------------------- */
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

    /* -------------------------------- ON MAP CLICK ------------------------------------ */
    @SuppressLint("MissingPermission")
    override fun onMapClick(point: LatLng): Boolean {

        val destinationPoint = Point.fromLngLat(point.longitude, point.latitude)
        val originPoint = Point.fromLngLat(
            locationComponent!!.lastKnownLocation!!.longitude,
            locationComponent!!.lastKnownLocation!!.latitude
        )

        val source = mapboxMap!!.style!!.getSourceAs<GeoJsonSource>("destination-source-id")
        source?.setGeoJson(Feature.fromGeometry(destinationPoint))
        getRoute(originPoint, destinationPoint)


        return true
    }

    /* -------------------------------- LIFECYCLE  -----------------------------------------------*/
    public override fun onStart() {
        super.onStart()
        mapView.onStart()
    }
    public override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    public override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    public override fun onStop() {
        super.onStop()
        mapView.onStop()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private fun getRoute(origin: Point, destination: Point) {
        NavigationRoute.builder(this)
            .accessToken(Mapbox.getAccessToken()!!)
            .origin(origin)
            .profile(transport)
            .destination(destination)
            .build()
            .getRoute(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    // You can get the generic HTTP info about the response
                    Timber.d("Response code: %s", response.code())
                    if (response.body() == null) {
                        Timber.e("No routes found, make sure you set the right user and access token.")
                        return
                    } else if (response.body()!!.routes().size < 1) {
                        Timber.e("No routes found")
                        return
                    }

                    currentRoute = response.body()!!.routes()[0]

                    // Draw the route on the map
                    if (navigationMapRoute != null) {
                        navigationMapRoute!!.removeRoute()
                    } else {
                        navigationMapRoute = mapboxMap?.let {
                            NavigationMapRoute(null, mapView,
                                it, R.style.NavigationMapRoute)
                        }
                    }
                    navigationMapRoute!!.addRoute(currentRoute)
                }

                override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                    Timber.e("Error: %s", throwable.message)
                }
            })
    }
}
