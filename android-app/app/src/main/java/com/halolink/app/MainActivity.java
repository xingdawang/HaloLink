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

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {
    private static final String SERVICE_TYPE = "_halolink._tcp.";
    private static final String PREFS = "halolink";
    private static final int PORT_START = 8765;
    private static final int PORT_END = 8775;
    private static final long POLL_INTERVAL_MS = 1500L;

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
    private volatile int scanGeneration = 0;
    private String activeHost = "";
    private int activePort = 0;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (!activeHost.isEmpty() && activePort > 0) {
                pollCurrentState(activeHost, activePort);
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        haloView = new HaloView(this);
        setContentView(haloView);

        httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build();
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        acquireMulticastLock();

        haloView.setStatus("SEARCHING", "Searching...");
        trySavedHostThenDiscover();
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

    private void trySavedHostThenDiscover() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString("host", "");
        if (!host.isEmpty()) {
            scanForBridge(host, true);
        } else {
            startDiscovery();
        }
    }

    private synchronized void startDiscovery() {
        if (destroyed || discoveryRunning || nsdManager == null) return;
        runOnUiThread(() -> haloView.setStatus("SEARCHING", "Searching..."));
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
                    scanForBridge(host.getHostAddress(), false);
                }
            });
        } catch (RuntimeException error) {
            resolving.set(false);
        }
    }

    private void scanForBridge(String host, boolean fallBackToDiscovery) {
        int generation = ++scanGeneration;
        runOnUiThread(() -> haloView.setStatus("SEARCHING", "Finding Bridge..."));
        probePort(host, PORT_START, generation, fallBackToDiscovery);
    }

    private void probePort(String host, int port, int generation, boolean fallBackToDiscovery) {
        if (destroyed || generation != scanGeneration) return;
        if (port > PORT_END) {
            if (fallBackToDiscovery) runOnUiThread(this::startDiscovery);
            else scheduleDiscoveryRetry();
            return;
        }

        String url = "http://" + safeHost(host) + ":" + port + "/health";
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                probePort(host, port + 1, generation, fallBackToDiscovery);
            }

            @Override public void onResponse(Call call, Response response) {
                boolean verified = false;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        verified = json.optBoolean("ok") && "HaloLink".equals(json.optString("product"));
                    }
                } catch (Exception ignored) { }

                if (destroyed || generation != scanGeneration) return;
                if (verified) {
                    connectWebSocket(host, port);
                } else {
                    probePort(host, port + 1, generation, fallBackToDiscovery);
                }
            }
        });
    }

    private String safeHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private synchronized void connectWebSocket(String host, int port) {
        if (destroyed) return;
        closeSocket();
        activeHost = host;
        activePort = port;
        runOnUiThread(() -> haloView.setStatus("CONNECTING", "Connecting..."));
        startPolling();

        String url = "ws://" + safeHost(host) + ":" + port + "/ws/phone";
        Request request = new Request.Builder().url(url).build();
        WebSocket candidate = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                webSocket = socket;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("host", host).putInt("port", port).apply();
                runOnUiThread(() -> haloView.setStatus("READY", "Ready"));
                socket.send("{\"type\":\"hello\",\"role\":\"android\",\"version\":\"0.1.4\"}");
            }

            @Override public void onMessage(WebSocket socket, String text) {
                applyStatusPayload(text, false);
            }

            @Override public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (webSocket == socket) webSocket = null;
                scheduleReconnect(host);
            }

            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (webSocket == socket) webSocket = null;
                scheduleReconnect(host);
            }
        });
        webSocket = candidate;
    }

    private void applyStatusPayload(String text, boolean nestedState) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject status = nestedState ? json.optJSONObject("state") : json;
            if (status == null || !"status".equals(status.optString("type"))) return;
            String state = status.optString("state", "READY");
            String label = status.optString("label", state);
            runOnUiThread(() -> haloView.setStatus(state, label));
        } catch (Exception ignored) { }
    }

    private void startPolling() {
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private void pollCurrentState(String host, int port) {
        String url = "http://" + safeHost(host) + ":" + port + "/api/state";
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { }

            @Override public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    String text = response.body().string();
                    if (!host.equals(activeHost) || port != activePort) return;
                    applyStatusPayload(text, true);
                } catch (Exception ignored) { }
            }
        });
    }

    private void scheduleReconnect(String host) {
        if (destroyed) return;
        runOnUiThread(() -> haloView.setStatus("SEARCHING", "Reconnecting..."));
        handler.postDelayed(() -> scanForBridge(host, true), 1800);
    }

    private void scheduleDiscoveryRetry() {
        if (!destroyed) handler.postDelayed(this::startDiscovery, 2500);
    }

    private synchronized void safeStopDiscovery() {
        if (!discoveryRunning || discoveryListener == null || nsdManager == null) return;
        try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (RuntimeException ignored) { }
        discoveryRunning = false;
    }

    private synchronized void closeSocket() {
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
        ++scanGeneration;
        safeStopDiscovery();
        closeSocket();
        handler.removeCallbacksAndMessages(null);
        if (httpClient != null) httpClient.dispatcher().executorService().shutdown();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }
}
