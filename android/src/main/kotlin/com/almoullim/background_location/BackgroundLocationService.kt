package com.almoullim.background_location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class BackgroundLocationService : MethodChannel.MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener {
    companion object {
        val METHOD_CHANNEL_NAME = "${BackgroundLocationPlugin.PLUGIN_ID}/methods"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 34
        private val TAG = BackgroundLocationService::class.java.simpleName

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BackgroundLocationService? = null

        /**
         * Requests the singleton instance of [BackgroundLocationService] or creates it,
         * if it does not yet exist.
         */
        fun getInstance() =
            instance ?: synchronized(this) { // synchronized to avoid concurrency problem
                instance ?: BackgroundLocationService().also { instance = it }
            }
    }


    /**
     * Context that is set once attached to a FlutterEngine.
     * Context should no longer be referenced when detached.
     */
    private var context: Context? = null
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var isAttached = false
    private var receiver: MyReceiver? = null
    private var service: LocationUpdatesService? = null

    /**
     * Signals whether the LocationUpdatesService is bound
     */
    private var bound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bound = true
            val binder = service as LocationUpdatesService.LocalBinder
            this@BackgroundLocationService.service = binder.service
            requestLocation()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun onAttachedToEngine(context: Context, messenger: BinaryMessenger) {
        this.context = context
        isAttached = true
        channel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        receiver = MyReceiver().also {
            LocalBroadcastManager.getInstance(context).registerReceiver(
                it, IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
            )
        }
    }

    fun onDetachedFromEngine() {
        receiver?.let {
            LocalBroadcastManager.getInstance(context!!).unregisterReceiver(it)
        }
        receiver = null
        channel.setMethodCallHandler(null)
        context = null
        isAttached = false
    }

    fun setActivity(binding: ActivityPluginBinding?) {
        this.activity = binding?.activity

        activity?.let {
            if (Utils.requestingLocationUpdates(context ?: return)) {
                if (!checkPermissions()) {
                    requestPermissions()
                } else {
                    service?.requestLocationUpdates()
                }
            }
        }
    }

    private fun startLocationService(
        startOnBoot: Boolean?,
        interval: Int?,
        fastestInterval: Int?,
        priority: Int?,
        distanceFilter: Double?,
        forceLocationManager: Boolean?,
        callbackHandle: Long?,
        locationCallback: Long?,
    ): Int {
        context?.let { ctx ->
            receiver?.let { receiver ->
                LocalBroadcastManager.getInstance(ctx).registerReceiver(
                    receiver, IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
                )
            }

            val intent = Intent(ctx, LocationUpdatesService::class.java).apply {
                action = LocationUpdatesService.ACTION_START_FOREGROUND_SERVICE
                putExtra("startOnBoot", startOnBoot)
                putExtra("interval", interval?.toLong())
                putExtra("fastest_interval", fastestInterval?.toLong())
                putExtra("priority", priority)
                putExtra("distance_filter", distanceFilter)
                putExtra("force_location_manager", forceLocationManager)
                putExtra("callbackHandle", callbackHandle)
                putExtra("locationCallback", locationCallback)
            }

            ContextCompat.startForegroundService(ctx, intent)
            ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } ?: return -1

        return 0
    }


    private fun isLocationServiceRunning(): Boolean {
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.let { manager ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android O and above, you can't rely on getRunningServices
                // Check if the service is bound or running through a different mechanism like JobScheduler
                return service != null || bound
            } else {
                // Use getRunningServices for devices below Android O
                @Suppress("Deprecated")
                val services = manager.getRunningServices(Integer.MAX_VALUE)
                return services.firstOrNull { it.service.className == LocationUpdatesService::class.java.name && it.foreground } != null
            }
        }
        return false
    }

    private fun stopLocationService(): Int {
        context?.let { ctx ->
            receiver?.let {
                LocalBroadcastManager.getInstance(ctx).unregisterReceiver(it)
                receiver = null
            }

            val intent = Intent(ctx, LocationUpdatesService::class.java).apply {
                action = "${ctx.packageName}.service_requests"
                putExtra(LocationUpdatesService.ACTION_SERVICE_REQUEST, LocationUpdatesService.ACTION_STOP_FOREGROUND_SERVICE)
            }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)

            if (bound) {
                ctx.unbindService(serviceConnection)
                bound = false
            }
        } ?: return -1

        return 0
    }

    private fun setAndroidNotification(
        channelID: String?,
        title: String?,
        message: String?,
        icon: String?,
        color: Int?,
    ): Int {
        if (channelID != null) LocationUpdatesService.NOTIFICATION_CHANNEL_ID = channelID
        if (title != null) LocationUpdatesService.NOTIFICATION_TITLE = title
        if (message != null) LocationUpdatesService.NOTIFICATION_MESSAGE = message
        if (icon != null) LocationUpdatesService.NOTIFICATION_ICON = icon
        if (color != null) {
            LocationUpdatesService.NOTIFICATION_COLOR = color
        }

        if (service != null) {
            service?.updateNotification()
        }

        return 0
    }

    private fun setConfiguration(timeInterval: Long?): Int {
        if (timeInterval != null) {
            LocationUpdatesService.UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval
            LocationUpdatesService.FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = timeInterval / 2
        }

        return 0
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "stop_location_service" -> result.success(stopLocationService())
            "start_location_service" -> {
                if (!checkPermissions()) {
                    requestPermissions()
                    return
                }

                var locationCallback: Long? = 0L
                try {
                    locationCallback = call.argument("locationCallback")
                } catch (ex: Throwable) {
                    Log.w(TAG, ex.toString())
                }

                var callbackHandle: Long? = 0L
                try {
                    callbackHandle = call.argument("callbackHandle")
                } catch (ex: Throwable) {
                    Log.w(TAG, ex.toString())
                }

                val startOnBoot: Boolean = call.argument("startOnBoot") ?: false
                val interval: Int? = call.argument("interval")
                val fastestInterval: Int? = call.argument("fastest_interval")
                val priority: Int? = call.argument("priority")
                val distanceFilter: Double? = call.argument("distance_filter")
                val forceLocationManager: Boolean? = call.argument("force_location_manager")

                result.success(
                    startLocationService(
                        startOnBoot,
                        interval,
                        fastestInterval,
                        priority,
                        distanceFilter,
                        forceLocationManager,
                        callbackHandle,
                        locationCallback,
                    )
                )
            }

            "is_service_running" -> result.success(isLocationServiceRunning())
            "set_android_notification" -> {
                val channelID: String? = call.argument("channelID")
                val notificationTitle: String? = call.argument("title")
                val notificationMessage: String? = call.argument("message")
                val notificationIcon: String? = call.argument("icon")
                val colorObject: Any? = call.argument("color")

                result.success(
                    setAndroidNotification(
                        channelID,
                        notificationTitle,
                        notificationMessage,
                        notificationIcon,
                        (colorObject as Number).toInt()
                    )
                )
            }

            "set_configuration" -> result.success(
                setConfiguration(
                    call.argument<String>("interval")?.toLongOrNull()
                )
            )

            else -> result.notImplemented()
        }
    }

    /**
     * Requests a location updated.
     * If permission is denied, it requests the needed permission
     */
    private fun requestLocation() {
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            service?.requestLocationUpdates()
        }
    }

    /**
     * Checks the current permission for `ACCESS_FINE_LOCATION`
     */
    private fun checkPermissions(): Boolean {
        context?.let { ctx ->
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                    ctx, Manifest.permission.ACCESS_FINE_LOCATION
                ) && PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                    ctx, Manifest.permission.FOREGROUND_SERVICE_LOCATION
                )
            } else {
                PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                    ctx, Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }
        return false
    }


    /**
     * Requests permission for location.
     * Depending on the current activity, displays a rationale for the request.
     */
    private fun requestPermissions() {
        activity?.let { act ->
            val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                act, Manifest.permission.ACCESS_FINE_LOCATION
            )

            if (shouldProvideRationale) {
                Toast.makeText(context, R.string.permission_rationale, Toast.LENGTH_LONG).show()
            } else {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                        ActivityCompat.requestPermissions(
                            act, arrayOf(
                                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS
                            ), REQUEST_PERMISSIONS_REQUEST_CODE
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                        ActivityCompat.requestPermissions(
                            act, arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS
                            ), REQUEST_PERMISSIONS_REQUEST_CODE
                        )
                    }
                    else -> {
                        ActivityCompat.requestPermissions(
                            act, arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ), REQUEST_PERMISSIONS_REQUEST_CODE
                        )
                    }
                }
            }
        }
    }


    private inner class MyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val actionType = intent.getStringExtra(LocationUpdatesService.ACTION_BROADCAST_TYPE)

            val location =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(LocationUpdatesService.EXTRA_LOCATION, Location::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(LocationUpdatesService.EXTRA_LOCATION)
                }
            val locationMap = HashMap<String, Any>()
            if (location != null) {
                locationMap["latitude"] = location.latitude
                locationMap["longitude"] = location.longitude
                locationMap["altitude"] = location.altitude
                locationMap["accuracy"] = location.accuracy.toDouble()
                locationMap["bearing"] = location.bearing.toDouble()
                locationMap["speed"] = location.speed.toDouble()
                locationMap["time"] = location.time.toDouble()
                locationMap["is_mock"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
            }

            when (actionType) {
                LocationUpdatesService.ACTION_BROADCAST_LOCATION -> channel.invokeMethod(
                    "location",
                    locationMap,
                    null
                )

                LocationUpdatesService.ACTION_NOTIFICATION_ACTIONED -> {
                    val result = HashMap<String, Any>()
                    result["ARG_LOCATION"] = locationMap
                    result["ARG_CALLBACK"] =
                        intent.getLongExtra(LocationUpdatesService.EXTRA_ACTION_CALLBACK, 0L)
                    channel.invokeMethod("notificationAction", result, null)
                }
            }
        }
    }

    /**
     * Handle the response from a permission request
     * @return true if the result has been handled.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        Log.i(BackgroundLocationPlugin.TAG, "onRequestPermissionResult")
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            when {
                grantResults.isEmpty() -> Log.i(
                    BackgroundLocationPlugin.TAG,
                    "User interaction was cancelled."
                )

                grantResults[0] == PackageManager.PERMISSION_GRANTED -> service?.requestLocationUpdates()
                else -> Toast.makeText(
                    context,
                    R.string.permission_denied_explanation,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }
}
