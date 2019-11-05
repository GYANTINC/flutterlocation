package com.lyokone.locationexample;

import com.lyokone.location.LocationPlugin;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.plugins.shim.ShimPluginRegistry;
import io.flutter.plugins.googlemaps.GoogleMapsPlugin;

public class MainActivity extends FlutterActivity {
    // TODO(vitor-gyant): Remove this once v2 of GeneratedPluginRegistrant rolls to stable. https://github.com/flutter/flutter/issues/42694
    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {

        flutterEngine.getPlugins().add(new LocationPlugin());
        
        ShimPluginRegistry shimPluginRegistry = new ShimPluginRegistry(flutterEngine);
        GoogleMapsPlugin.registerWith(shimPluginRegistry.registrarFor("io.flutter.plugins.googlemaps.GoogleMapsPlugin"));

    }
}