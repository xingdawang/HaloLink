package com.halolink.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {
    private static final String SERVICE_TYPE = "_halolink._tcp.";
    private static final String PREFS = "halolink";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private HaloView haloView;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private boolean discoveryRunning = false;
    private boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        haloView = new HaloView(this);
        setContentView(haloView);

        httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(4, TimeUnit.SECONDS)
                .build();
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        acquireMulticastLock();

        haloView.setStatus("SEARCHING", "Searching...");
        trySavedEndpointThenDiscover();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("HaloLink-mdns");
            multicastLock.setReferenceCounted(false);
            try { multicastLock.acquire(); } catch (RuntimeException ignored) { }
        }
    }

    private void trySavedEndpointThenDiscover() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 8765);
        if (!host.isEmpty()) {
            connectWebSocket(host, port, true);
            handler.postDelayed(() -> {
                if (webSocket == null && !destroyed) startDiscovery();
            }, 1800);
        } else {
            startDiscovery();
        }
    }

    private synchronized void startDiscovery() {
        if (destroyed || discoveryRunning || nsdManager == null) return;
        haloView.setStatus("SEARCHING", "Searching...");
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String regType) { discoveryRunning = true; }
            @Override public void onServiceFound(NsdServiceInfo service) {
                String name = service.getServiceName() == null ? "" : service.getServiceName();
                if (name.startsWith("HaloLink") && resolving.compareAndSet(false, true)) {
                    resolveService(service);
                }
            }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onDiscoveryStopped(String serviceType) { discoveryRunning = false; }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryRunning = false;
                safeStopDiscovery();
                scheduleDiscoveryRetry();
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                discoveryRunning = false;
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException error) {
            discoveryRunning = false;
            scheduleDiscoveryRetry();
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveService(NsdServiceInfo service) {
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    resolving.set(false);
                }
                @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    resolving.set(false);
                    InetAddress host = serviceInfo.getHost();
                    if (host == null) return;
                    safeStopDiscovery();
                    connectWebSocket(host.getHostAddress(), serviceInfo.getPort(), false);
                }
            });
        } catch (RuntimeException error) {
            resolving.set(false);
        }
    }

    private void connectWebSocket(String host, int port, boolean savedEndpoint) {
        if (destroyed) return;
        closeSocket();
        haloView.setStatus("CONNECTING", "Connecting...");
        String safeHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String url = "ws://" + safeHost + ":" + port + "/ws/phone";
        Request request = new Request.Builder().url(url).build();
        WebSocket candidate = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                webSocket = socket;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("host", host).putInt("port", port).apply();
                runOnUiThread(() -> haloView.setStatus("READY", "Ready"));
                socket.send("{\"type\":\"hello\",\"role\":\"android\",\"version\":\"0.1.0\"}");
            }

            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    if (!"status".equals(json.optString("type"))) return;
                    String state = json.optString("state", "READY");
                    String label = json.optString("label", state);
                    runOnUiThread(() -> haloView.setStatus(state, label));
                } catch (Exception ignored) { }
            }

            @Override public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (webSocket == socket) webSocket = null;
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (webSocket == socket) webSocket = null;
                if (savedEndpoint) {
                    runOnUiThread(() -> startDiscovery());
                } else {
                    scheduleReconnect();
                }
            }
        });
        webSocket = candidate;
    }

    private void scheduleReconnect() {
        if (destroyed) return;
        runOnUiThread(() -> haloView.setStatus("SEARCHING", "Reconnecting..."));
        handler.postDelayed(this::startDiscovery, 1500);
    }

    private void scheduleDiscoveryRetry() {
        if (!destroyed) handler.postDelayed(this::startDiscovery, 2500);
    }

    private synchronized void safeStopDiscovery() {
        if (!discoveryRunning || discoveryListener == null || nsdManager == null) return;
        try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (RuntimeException ignored) { }
        discoveryRunning = false;
    }

    private void closeSocket() {
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) socket.close(1000, "Reconnect");
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        safeStopDiscovery();
        closeSocket();
        if (httpClient != null) httpClient.dispatcher().executorService().shutdown();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
