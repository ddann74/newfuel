package com.newfuel.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/**
 * The whole app is this one Activity: a WebView loading the exact same
 * static/index.html + style.css + app.js the Streamlit (app.py) path
 * serves, bundled directly into the APK under assets/ (kept in sync by the
 * :app module's syncWebAssets Gradle task - see app/build.gradle) rather
 * than fetched from anywhere at runtime.
 *
 * TWO things needed real, deliberate handling beyond a default WebView:
 *
 * 1. ORIGIN: loading raw file:///android_asset/index.html sends
 *    Origin: null on every fetch() the page makes (NSW FuelCheck auth,
 *    TomTom routing, Nominatim geocoding) - and app.js's own existing NSW-
 *    auth error message ("CORS restriction — run via local server or
 *    Streamlit proxy") already shows this exact class of failure was hit
 *    before and is a real, not hypothetical, risk. WebViewAssetLoader
 *    (Google's own documented fix for this) serves the same bundled files
 *    over a real https://appassets.androidplatform.net origin instead,
 *    which most permissive (Access-Control-Allow-Origin: *) CORS setups
 *    handle correctly where a null origin often doesn't. NOT verified
 *    against the live NSW FuelCheck API from the environment this was
 *    written in (no device/network access to that specific host there) -
 *    if fetches still fail with a CORS error in real use, the underlying
 *    API would need a real server-side proxy in front of it; no client-
 *    side trick can fix a server that strictly allowlists specific origins.
 *
 * 2. GEOLOCATION + TARGET=_BLANK LINKS: the web app calls
 *    navigator.geolocation.getCurrentPosition (detectLocation() in app.js)
 *    and opens Waze via a real <a target="_blank"> click (both the manual
 *    "Navigate via Waze" link and the automatic autoNavigateToBest()).
 *    Neither works out of the box in a bare WebView - see
 *    onGeolocationPermissionsShowPrompt and onCreateWindow below.
 */
public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 100;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Requested proactively at startup rather than waiting for the web
        // page's own first geolocation call - by the time the driver taps
        // "Find Best Deal" (which auto-calls detectLocation() if no fix is
        // held yet), the OS permission dialog has very likely already been
        // resolved one way or the other, so onGeolocationPermissionsShowPrompt
        // below can answer immediately instead of the WebView appearing to
        // silently do nothing while a native permission dialog is still
        // pending underneath it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        }

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebView webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // localStorage (saveSettings/loadSettings in app.js) needs this -
        // without it, "remembered" Advanced Settings would silently fail to
        // persist at all, every single search resetting to hardcoded
        // defaults exactly like before that feature was added.
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        // Needed for the target="_blank" Waze links (see onCreateWindow) -
        // without this, WebView drops window.open()/target=_blank clicks
        // with no callback and no visible error at all.
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                // Anything that isn't our own bundled-assets origin (the
                // app has no other reason to navigate the main WebView
                // itself anywhere else - the Waze links all go through
                // onCreateWindow below, being target="_blank") is treated
                // as an external link and handed to the OS instead of
                // letting the single-page app's own WebView navigate away
                // from itself.
                if ("appassets.androidplatform.net".equals(url.getHost())) {
                    return false;
                }
                launchExternally(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                // false for "don't remember this choice" (retain), not
                // "denied" - retaining would skip re-checking the real OS
                // permission state (e.g. after the driver later grants it
                // from system Settings) on a future call.
                callback.invoke(origin, granted, false);
            }

            // Standard (if awkward) idiom for handling target="_blank"/
            // window.open() in a WebView: a temporary, never-displayed
            // WebView is handed the navigation so its own WebViewClient can
            // read the intended URL, which is then launched as a real
            // external Intent instead - both the manual "Navigate via Waze"
            // link and autoNavigateToBest()'s synthetic .click() go through
            // this same path, since both use target="_blank".
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView transportWebView = new WebView(MainActivity.this);
                transportWebView.setWebViewClient(new WebViewClientCompat() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        launchExternally(request.getUrl());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(transportWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void launchExternally(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException e) {
            // Real if unlikely edge case (no Waze app AND no browser
            // capable of handling the link) - a Toast rather than a crash,
            // same "never let a secondary action block the main flow"
            // principle the web app's own showErr/showWarn already follow.
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // No action needed here beyond the default - onGeolocationPermissionsShowPrompt
        // re-checks the real permission state on every call rather than
        // caching the result of this callback, so whatever the driver
        // chose is picked up correctly the next time the web page asks.
    }
}
