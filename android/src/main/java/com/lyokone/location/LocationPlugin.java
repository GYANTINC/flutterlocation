package com.lyokone.location;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.common.api.Result;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;

import java.util.HashMap;
import java.util.logging.StreamHandler;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * LocationPlugin
 */
public class LocationPlugin implements MethodChannel.MethodCallHandler, EventChannel.StreamHandler, FlutterPlugin, ActivityAware {

    interface PermissionsRegistry {
        void addListener(PluginRegistry.RequestPermissionsResultListener handler);
    }


    private static final String STREAM_CHANNEL_NAME = "lyokone/locationstream";
    private static final String METHOD_CHANNEL_NAME = "lyokone/location";

    private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;
    private static final int REQUEST_CHECK_SETTINGS = 0x1;

    private static final int GPS_ENABLE_REQUEST = 0x1001;

    private FusedLocationProviderClient mFusedLocationClient;
    private SettingsClient mSettingsClient;
    private static LocationRequest mLocationRequest;
    private LocationSettingsRequest mLocationSettingsRequest;
    private LocationCallback mLocationCallback;
    private PluginRegistry.RequestPermissionsResultListener mPermissionsResultListener;

    @TargetApi(Build.VERSION_CODES.N)
    private OnNmeaMessageListener mMessageListener;

    private Double mLastMslAltitude;

    // Parameters of the request
    private static long update_interval_in_milliseconds = 5000;
    private static long fastest_update_interval_in_milliseconds = update_interval_in_milliseconds / 2;
    private static Integer location_accuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
    private static float distanceFilter = 0f;

    private EventChannel.EventSink events;
    private MethodChannel.Result result;

    private int locationPermissionState;

    private Activity activity;
    private MethodChannel methodChannel;
    private EventChannel eventChannel;
    private FlutterPluginBinding pluginBinding;

    private boolean waitingForPermission = false;
    private LocationManager locationManager;

    private SparseIntArray mapFlutterAccuracy = new SparseIntArray();

    public LocationPlugin() {

        this.mapFlutterAccuracy.put(0, LocationRequest.PRIORITY_NO_POWER);
        this.mapFlutterAccuracy.put(1, LocationRequest.PRIORITY_LOW_POWER);
        this.mapFlutterAccuracy.put(2, LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
        this.mapFlutterAccuracy.put(3, LocationRequest.PRIORITY_HIGH_ACCURACY);
        this.mapFlutterAccuracy.put(4, LocationRequest.PRIORITY_HIGH_ACCURACY);

        setupLocationHandlers();
    }

    private void tearUp(BinaryMessenger binaryMessenger, PermissionsRegistry permissionRegistry, Activity activity) {

        this.activity = activity;
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(activity);
        mSettingsClient = LocationServices.getSettingsClient(activity);
        locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);

        methodChannel = new MethodChannel(binaryMessenger, METHOD_CHANNEL_NAME);
        methodChannel.setMethodCallHandler(this);

        permissionRegistry.addListener(getPermissionsResultListener());

        eventChannel = new EventChannel(binaryMessenger, STREAM_CHANNEL_NAME);
        eventChannel.setStreamHandler(this);

    }

    private void tearDown() {
        methodChannel = null;
        eventChannel = null;
        mFusedLocationClient = null;
        mSettingsClient = null;
        locationManager = null;
        activity = null;
        pluginBinding = null;
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {

        switch (call.method) {

            case "changeSettings":
                try {
                    location_accuracy = mapFlutterAccuracy.get(call.<Integer>argument("accuracy"));
                    update_interval_in_milliseconds = call.<Integer>argument("interval").longValue();
                    fastest_update_interval_in_milliseconds = update_interval_in_milliseconds / 2;

                    distanceFilter = call.<Double>argument("distanceFilter").floatValue();

                    setupLocationHandlers();

                    result.success(1);
                } catch(Exception e) {
                    result.error("CHANGE_SETTINGS_ERROR", "An unexpected error happened during location settings change:" + e.getMessage(), null);
                }
                break;

            case "getLocation":
                this.result = result;
                if (!checkPermissions()) {
                    requestPermissions();
                } else {
                    startRequestingLocation();
                }
                break;

            case "hasPermission":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    result.success(1);
                    return;
                }

                if (checkPermissions()) {
                    result.success(1);
                } else {
                    result.success(0);
                }
                break;

            case "requestPermission":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    result.success(1);
                    return;
                }

                this.waitingForPermission = true;
                this.result = result;
                requestPermissions();
                break;

            case "serviceEnabled":
                checkServiceEnabled(result);
                break;

            case "requestService":
                requestService(result);
                break;

            default:
                result.notImplemented();
        }
    }

    private void setupLocationHandlers() {
        createLocationCallback();
        createLocationRequest();
        createPermissionsResultListener();
        buildLocationSettingsRequest();
    }

    private PluginRegistry.RequestPermissionsResultListener getPermissionsResultListener() {
        return mPermissionsResultListener;
    }

    private void createPermissionsResultListener() {
        mPermissionsResultListener = (requestCode, permissions, grantResults) -> {
            if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE && permissions.length == 1 && permissions[0].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                if (waitingForPermission) {
                    waitingForPermission = false;
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        result.success(1);
                    } else {
                        result.success(0);
                    }
                    result = null;
                }
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (result != null) {
                        startRequestingLocation();
                    } else if (events != null) {
                        startRequestingLocation();
                    }
                } else {
                    if (!shouldShowRequestPermissionRationale()) {
                        if (result != null) {
                            result.error("PERMISSION_DENIED_NEVER_ASK", "Location permission denied forever- please open app settings", null);
                        } else if (events != null) {
                            events.error("PERMISSION_DENIED_NEVER_ASK", "Location permission denied forever - please open app settings", null);
                            events = null;
                        }
                    } else {
                        if (result != null) {
                            result.error("PERMISSION_DENIED", "Location permission denied", null);
                        } else if (events != null) {
                            events.error("PERMISSION_DENIED", "Location permission denied", null);
                            events = null;
                        }
                    }
                }
                return true;
            }
            return false;
        };
    }


    /**
     * Creates a callback for receiving location events.
     */
    private void createLocationCallback() {
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                Location location = locationResult.getLastLocation();
                HashMap<String, Double> loc = new HashMap<>();
                loc.put("latitude", location.getLatitude());
                loc.put("longitude", location.getLongitude());
                loc.put("accuracy", (double) location.getAccuracy());
                
                // Using NMEA Data to get MSL level altitude
                if (mLastMslAltitude == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    loc.put("altitude", location.getAltitude());
                } else {
                    loc.put("altitude", mLastMslAltitude);
                }

                loc.put("speed", (double) location.getSpeed());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.put("speed_accuracy", (double) location.getSpeedAccuracyMetersPerSecond());
                }
                loc.put("heading", (double) location.getBearing());
                loc.put("time", (double) location.getTime());

                if (result != null) {
                    result.success(loc);
                    result = null;
                }
                if (events != null) {
                    events.success(loc);
                } else {
                    mFusedLocationClient.removeLocationUpdates(mLocationCallback);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { 
            mMessageListener = (message, timestamp) -> {
                if (message.startsWith("$")) {
                    String[] tokens = message.split(",");
                    String type = tokens[0];

                    // Parse altitude above sea level, Detailed description of NMEA string here
                    // http://aprs.gids.nl/nmea/#gga
                    if (type.startsWith("$GPGGA")) {
                        if (!tokens[9].isEmpty()) {
                            mLastMslAltitude = Double.parseDouble(tokens[9]);
                        }
                    }
                }
            };
        }
    }

    /**
     * Sets up the location request. Android has two location request settings:
     * {@code ACCESS_COARSE_LOCATION} and {@code ACCESS_FINE_LOCATION}. These settings control
     * the accuracy of the current location. This sample uses ACCESS_FINE_LOCATION, as defined in
     * the AndroidManifest.xml.
     * <p/>
     * When the ACCESS_FINE_LOCATION setting is specified, combined with a fast update
     * interval (5 seconds), the Fused Location Provider API returns location updates that are
     * accurate to within a few feet.
     * <p/>
     * These settings are appropriate for mapping applications that show real-time location
     * updates.
     */
    private void createLocationRequest() {
        mLocationRequest = LocationRequest.create();

        // Sets the desired interval for active location updates. This interval is
        // inexact. You may not receive updates at all if no location sources are available, or
        // you may receive them slower than requested. You may also receive updates faster than
        // requested if other applications are requesting location at a faster interval.
        mLocationRequest.setInterval(update_interval_in_milliseconds);

        // Sets the fastest rate for active location updates. This interval is exact, and your
        // application will never receive updates faster than this value.
        mLocationRequest.setFastestInterval(fastest_update_interval_in_milliseconds);

        mLocationRequest.setPriority(location_accuracy);
        mLocationRequest.setSmallestDisplacement(distanceFilter);
    }

    /**
     * Uses a {@link com.google.android.gms.location.LocationSettingsRequest.Builder} to build
     * a {@link com.google.android.gms.location.LocationSettingsRequest} that is used for checking
     * if a device has the needed location settings.
     */
    private void buildLocationSettingsRequest() {
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
        builder.addLocationRequest(mLocationRequest);
        mLocationSettingsRequest = builder.build();
    }

    /**
     * Return the current state of the permissions needed.
     */
    private boolean checkPermissions() {
        this.locationPermissionState = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION);
        return this.locationPermissionState == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_PERMISSIONS_REQUEST_CODE);
    }

    private boolean shouldShowRequestPermissionRationale() {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean checkServiceEnabled(final MethodChannel.Result result) {
        boolean gps_enabled = false;
        boolean network_enabled = false;

        try {
            gps_enabled = this.locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            network_enabled = this.locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            result.error("SERVICE_STATUS_ERROR", "Location service status couldn't be determined", null);
            return false;
        }
        if (gps_enabled || network_enabled) {
            if (result != null) {
                result.success(1);
            } 
            return true;
            
        } else {
            if (result != null) {
                result.success(0);
            } 
            return false;
        }
    }

    private void requestService(final MethodChannel.Result result) {
        if (this.checkServiceEnabled(null)) {
            result.success(1);
            return;
        }
        this.result = result;
        mSettingsClient.checkLocationSettings(mLocationSettingsRequest)
            .addOnFailureListener(activity, e -> {
                int statusCode = ((ApiException) e).getStatusCode();
                switch (statusCode) {
                case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                    try {
                        // Show the dialog by calling startResolutionForResult(), and check the
                        // result in onActivityResult().
                        ResolvableApiException rae = (ResolvableApiException) e;
                        rae.startResolutionForResult(activity, GPS_ENABLE_REQUEST);
                    } catch (IntentSender.SendIntentException sie) {
                        Log.i(METHOD_CHANNEL_NAME, "PendingIntent unable to execute request.");
                    }
                    break;
                case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                    result.error("SERVICE_STATUS_DISABLED",
                            "Failed to get location. Location services disabled", null);
                }
            });
    }

    private void startRequestingLocation() {
        mSettingsClient.checkLocationSettings(mLocationSettingsRequest)
                .addOnSuccessListener(activity, locationSettingsResponse -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        locationManager.addNmeaListener(mMessageListener);
                    }
                    mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
                }).addOnFailureListener(activity, e -> {
                    int statusCode = ((ApiException) e).getStatusCode();
                    switch (statusCode) {
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        try {
                            // Show the dialog by calling startResolutionForResult(), and check the
                            // result in onActivityResult().
                            ResolvableApiException rae = (ResolvableApiException) e;
                            rae.startResolutionForResult(activity, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException sie) {
                            Log.i(METHOD_CHANNEL_NAME, "PendingIntent unable to execute request.");
                        }
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        String errorMessage = "Location settings are inadequate, and cannot be "
                                + "fixed here. Fix in Settings.";
                        Log.e(METHOD_CHANNEL_NAME, errorMessage);
                    }
                });
    }

    @Override
    public void onListen(Object arguments, final EventChannel.EventSink eventsSink) {
        events = eventsSink;
        if (!checkPermissions()) {
            requestPermissions();
            if (this.locationPermissionState == PackageManager.PERMISSION_DENIED) {
                result.error("PERMISSION_DENIED",
                        "The user explicitly denied the use of location services for this app or location services are currently disabled in Settings.",
                        null);
            }
        }     
        startRequestingLocation();   
    }

    @Override
    public void onCancel(Object arguments) {
        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        events = null;
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        pluginBinding = binding;
    }

    @Override
    public void onDetachedFromEngine(@NonNull  FlutterPluginBinding binding) {
        pluginBinding = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        tearUp(pluginBinding.getBinaryMessenger(),
                binding::addRequestPermissionsResultListener,
                binding.getActivity());
    }

    @Override
    public void onDetachedFromActivity() {
        tearDown();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull  ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }


}
