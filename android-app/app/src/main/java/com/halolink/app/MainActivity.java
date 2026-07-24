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
import android.provider.Settings;
import android.util.Log;
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
    private static final String TAG = "HaloLink";
    private static final String SERVICE_TYPE = "_halolink._tcp.";
    private static final String PREFS = "halolink";
    private static final int PORT_START = 8766;
    private static final int PORT_END = 8775;
    private static final long POLL_INTERVAL_MS = 1500L;
    private static final long IDLE_DIM_DELAY_MS = 30_000L;
    private static final float MAX_IDLE_BRIGHTNESS = 0.12f;
    private static final String APP_VERSION = "0.1.6";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private HaloView haloView;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private Runnable reconnectRunnable;
    private boolean discoveryRunning = false;
    private boolean destroyed = false;
    private volatile boolean foreground = false;
    private volatile boolean webSocketConnected = false;
    private volatile int scanGeneration = 0;
    private volatile String activeHost = "";
    private volatile int activePort = 0;

    private final Runnable idleDimRunnable = () -> {
        if (foreground && haloView != null && EnergyPolicy.isIdleState(haloView.getState())) {
            setWindowBrightness(idleBrightness());
            Log.i(TAG, "idle display dimmed to reduce OLED power");
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            boolean bridgeSelected = !activeHost.isEmpty() && activePort > 0;
            if (!EnergyPolicy.shouldPoll(foreground, webSocketConnected, bridgeSelected)) {
                return;
            }
            if (pollInFlight.compareAndSet(false, true)) {
                pollCurrentState(activeHost, activePort);
            }
            if (EnergyPolicy.shouldPoll(foreground, webSocketConnected, bridgeSelected)) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
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

        updateDisplayStatus("SEARCHING", "Searching...");
        Log.i(TAG, "app created");
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
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("HaloLink-mdns");
                multicastLock.setReferenceCounted(false);
            }
            try {
                multicastLock.acquire();
                Log.i(TAG, "multicast lock acquired");
            } catch (RuntimeException error) {
                Log.e(TAG, "multicast lock failed", error);
            }
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
                Log.i(TAG, "multicast lock released");
            } catch (RuntimeException error) {
                Log.w(TAG, "multicast lock release failed", error);
            }
        }
    }

    private void trySavedHostThenDiscover() {
        if (!isActive()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", PORT_START);
        if (!host.isEmpty()) {
            Log.i(TAG, "probing saved Bridge host=" + host + " preferredPort=" + port);
            scanForBridge(host, port, true);
        } else {
            Log.i(TAG, "no saved Bridge; starting mDNS discovery");
            startDiscovery();
        }
    }

    private synchronized void startDiscovery() {
        if (!isActive() || discoveryRunning || nsdManager == null) return;
        acquireMulticastLock();
        updateDisplayStatus("SEARCHING", "Searching...");
        int generation = scanGeneration;
        discoveryListener = new NsdManager.DiscoveryListener() {
            private boolean isCurrent() {
                return discoveryListener == this && generation == scanGeneration;
            }

            @Override public void onDiscoveryStarted(String regType) {
                if (!isCurrent()) return;
                discoveryRunning = true;
                Log.i(TAG, "mDNS discovery started type=" + regType);
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                if (!isCurrent()) return;
                String name = service.getServiceName() == null ? "" : service.getServiceName();
                Log.i(TAG, "mDNS service found name=" + name);
                if (name.startsWith("HaloLink") && resolving.compareAndSet(false, true)) {
                    resolveService(service, generation);
                }
            }
            @Override public void onServiceLost(NsdServiceInfo service) {
                if (!isCurrent()) return;
                Log.w(TAG, "mDNS service lost name=" + service.getServiceName());
            }
            @Override public void onDiscoveryStopped(String serviceType) {
                if (!isCurrent()) return;
                discoveryRunning = false;
                discoveryListener = null;
                releaseMulticastLock();
                Log.i(TAG, "mDNS discovery stopped type=" + serviceType);
            }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (!isCurrent()) return;
                discoveryRunning = false;
                Log.e(TAG, "mDNS discovery start failed code=" + errorCode);
                safeStopDiscovery();
                scheduleDiscoveryRetry();
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                if (!isCurrent()) return;
                discoveryRunning = false;
                discoveryListener = null;
                releaseMulticastLock();
                Log.e(TAG, "mDNS discovery stop failed code=" + errorCode);
            }
        };
        try {
            discoveryRunning = true;
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException error) {
            discoveryRunning = false;
            discoveryListener = null;
            releaseMulticastLock();
            Log.e(TAG, "mDNS discovery threw exception", error);
            scheduleDiscoveryRetry();
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveService(NsdServiceInfo service, int generation) {
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    resolving.set(false);
                    if (generation != scanGeneration) return;
                    Log.e(TAG, "mDNS resolve failed code=" + errorCode);
                    if (isActive()) scheduleDiscoveryRetry();
                }
                @Override public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    resolving.set(false);
                    if (!isActive() || generation != scanGeneration) return;
                    InetAddress host = serviceInfo.getHost();
                    if (host == null) {
                        Log.e(TAG, "mDNS resolved without host");
                        return;
                    }
                    Log.i(
                            TAG,
                            "mDNS resolved host=" + host.getHostAddress()
                                    + " advertisedPort=" + serviceInfo.getPort()
                    );
                    safeStopDiscovery();
                    scanForBridge(host.getHostAddress(), serviceInfo.getPort(), false);
                }
            });
        } catch (RuntimeException error) {
            resolving.set(false);
            Log.e(TAG, "mDNS resolve threw exception", error);
        }
    }

    private void scanForBridge(String host, int preferredPort, boolean fallBackToDiscovery) {
        if (!isActive()) return;
        int generation = ++scanGeneration;
        Log.i(
                TAG,
                "scanning host=" + host + " preferredPort=" + preferredPort
                        + " generation=" + generation
        );
        updateDisplayStatus("SEARCHING", "Finding Bridge...");
        int firstPort = preferredPort >= PORT_START && preferredPort <= PORT_END
                ? preferredPort : PORT_START;
        probePort(host, firstPort, generation, fallBackToDiscovery, true);
    }

    private void probePort(
            String host,
            int port,
            int generation,
            boolean fallBackToDiscovery,
            boolean preferredAttempt
    ) {
        if (!isActive() || generation != scanGeneration) return;
        if (port > PORT_END) {
            Log.w(TAG, "no verified Bridge on host=" + host);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("host").remove("port").apply();
            activeHost = "";
            activePort = 0;
            if (fallBackToDiscovery) runOnUiThread(this::startDiscovery);
            else scheduleDiscoveryRetry();
            return;
        }

        String url = "http://" + safeHost(host) + ":" + port + "/health";
        Log.i(TAG, "probing host:port=" + host + ":" + port);
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                Log.w(TAG, "health probe failed " + host + ":" + port + " error=" + error);
                probePort(
                        host,
                        nextProbePort(port, preferredAttempt),
                        generation,
                        fallBackToDiscovery,
                        false
                );
            }

            @Override public void onResponse(Call call, Response response) {
                boolean verified = false;
                String version = "";
                int pid = 0;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        verified = json.optBoolean("ok")
                                && "HaloLink".equals(json.optString("product"))
                                && json.optInt("port", port) == port;
                        version = json.optString("version");
                        pid = json.optInt("pid");
                    }
                } catch (Exception error) {
                    Log.w(TAG, "health response parse failed " + host + ":" + port, error);
                }

                if (!isActive() || generation != scanGeneration) return;
                if (verified) {
                    Log.i(
                            TAG,
                            "health verified host=" + host + " port=" + port
                                    + " version=" + version + " pid=" + pid
                    );
                    Log.i(TAG, "selected Bridge host=" + host + " port=" + port);
                    connectWebSocket(host, port);
                } else {
                    Log.w(TAG, "health rejected host=" + host + " port=" + port);
                    probePort(
                            host,
                            nextProbePort(port, preferredAttempt),
                            generation,
                            fallBackToDiscovery,
                            false
                    );
                }
            }
        });
    }

    private int nextProbePort(int port, boolean preferredAttempt) {
        if (preferredAttempt && port != PORT_START) return PORT_START;
        return port + 1;
    }

    private String safeHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private synchronized void connectWebSocket(String host, int port) {
        if (!isActive()) return;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        closeSocket();
        activeHost = host;
        activePort = port;
        updateDisplayStatus("CONNECTING", "Connecting...");
        startFallbackPolling();

        String url = "ws://" + safeHost(host) + ":" + port + "/ws/phone";
        Log.i(TAG, "WebSocket connecting url=" + url);
        Request request = new Request.Builder().url(url).build();
        WebSocket candidate = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                synchronized (MainActivity.this) {
                    if (!isActive() || webSocket != socket) {
                        socket.close(1000, "Superseded");
                        Log.i(TAG, "ignoring superseded WebSocket open");
                        return;
                    }
                    webSocketConnected = true;
                }
                stopFallbackPolling();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("host", host).putInt("port", port).apply();
                updateDisplayStatus("READY", "Ready");
                Log.i(TAG, "WebSocket opened host=" + host + " port=" + port);
                Log.i(TAG, "HTTP fallback polling stopped; WebSocket is healthy");
                socket.send(
                        "{\"type\":\"hello\",\"role\":\"android\",\"version\":\""
                                + APP_VERSION + "\"}"
                );
            }

            @Override public void onMessage(WebSocket socket, String text) {
                synchronized (MainActivity.this) {
                    if (!isActive() || webSocket != socket || !webSocketConnected) return;
                }
                Log.i(TAG, "WebSocket message received");
                applyStatusPayload(text, false);
            }

            @Override public void onClosing(WebSocket socket, int code, String reason) {
                Log.w(TAG, "WebSocket closing code=" + code + " reason=" + reason);
                socket.close(code, reason);
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                boolean current;
                synchronized (MainActivity.this) {
                    current = webSocket == socket;
                    if (current) {
                        webSocket = null;
                        webSocketConnected = false;
                    }
                }
                Log.w(TAG, "WebSocket closed code=" + code + " reason=" + reason);
                if (current && isActive()) {
                    startFallbackPolling();
                    scheduleReconnect(host);
                }
            }

            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                boolean current;
                synchronized (MainActivity.this) {
                    current = webSocket == socket;
                    if (current) {
                        webSocket = null;
                        webSocketConnected = false;
                    }
                }
                int status = response == null ? 0 : response.code();
                Log.e(
                        TAG,
                        "WebSocket failed host=" + host + " port=" + port
                                + " httpStatus=" + status,
                        error
                );
                if (current && isActive()) {
                    startFallbackPolling();
                    scheduleReconnect(host);
                }
            }
        });
        webSocket = candidate;
    }

    private void applyStatusPayload(String text, boolean nestedState) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject status = nestedState ? json.optJSONObject("state") : json;
            if (status == null || !"status".equals(status.optString("type"))) {
                Log.i(TAG, "non-status Bridge message type=" + json.optString("type"));
                return;
            }
            String state = status.optString("state", "READY");
            String label = status.optString("label", state);
            Log.i(TAG, "state received and applied state=" + state + " source="
                    + (nestedState ? "http-poll" : "websocket"));
            if (isActive()) updateDisplayStatus(state, label);
        } catch (Exception error) {
            Log.w(TAG, "status payload parse failed", error);
        }
    }

    private void startFallbackPolling() {
        boolean bridgeSelected = !activeHost.isEmpty() && activePort > 0;
        if (!EnergyPolicy.shouldPoll(foreground, webSocketConnected, bridgeSelected)) return;
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        Log.i(TAG, "HTTP fallback polling started; WebSocket is unavailable");
    }

    private void stopFallbackPolling() {
        handler.removeCallbacks(pollRunnable);
    }

    private void pollCurrentState(String host, int port) {
        String url = "http://" + safeHost(host) + ":" + port + "/api/state";
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                pollInFlight.set(false);
                Log.w(TAG, "HTTP polling failed host=" + host + " port=" + port
                        + " error=" + error);
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w(TAG, "HTTP polling rejected status=" + response.code());
                        return;
                    }
                    String text = response.body().string();
                    if (!isActive() || webSocketConnected
                            || !host.equals(activeHost) || port != activePort) return;
                    Log.i(TAG, "HTTP polling success host=" + host + " port=" + port);
                    applyStatusPayload(text, true);
                } catch (Exception error) {
                    Log.w(TAG, "HTTP polling response failed", error);
                } finally {
                    pollInFlight.set(false);
                }
            }
        });
    }

    private synchronized void scheduleReconnect(String host) {
        if (!isActive()) return;
        if (reconnectRunnable != null) handler.removeCallbacks(reconnectRunnable);
        Log.i(TAG, "reconnect scheduled host=" + host + " delayMs=1800");
        updateDisplayStatus("SEARCHING", "Reconnecting...");
        int port = activePort;
        reconnectRunnable = () -> {
            synchronized (MainActivity.this) {
                reconnectRunnable = null;
            }
            scanForBridge(host, port, true);
        };
        handler.postDelayed(reconnectRunnable, 1800);
    }

    private void scheduleDiscoveryRetry() {
        if (isActive()) {
            Log.i(TAG, "mDNS discovery retry scheduled delayMs=2500");
            handler.postDelayed(this::startDiscovery, 2500);
        }
    }

    private synchronized void safeStopDiscovery() {
        if (discoveryRunning && discoveryListener != null && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) { }
        }
        discoveryRunning = false;
        discoveryListener = null;
        resolving.set(false);
        releaseMulticastLock();
    }

    private synchronized void closeSocket() {
        WebSocket socket = webSocket;
        webSocket = null;
        webSocketConnected = false;
        if (socket != null) socket.close(1000, "Reconnect");
    }

    private boolean isActive() {
        return foreground && !destroyed;
    }

    private void updateDisplayStatus(String state, String label) {
        runOnUiThread(() -> {
            if (destroyed || haloView == null) return;
            if (!haloView.setStatus(state, label)) return;
            handler.removeCallbacks(idleDimRunnable);
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
            if (foreground && EnergyPolicy.isIdleState(state)) {
                handler.postDelayed(idleDimRunnable, IDLE_DIM_DELAY_MS);
            }
        });
    }

    private float idleBrightness() {
        try {
            int systemBrightness = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS
            );
            float normalized = Math.max(0f, Math.min(1f, systemBrightness / 255f));
            return Math.min(MAX_IDLE_BRIGHTNESS, normalized);
        } catch (Settings.SettingNotFoundException ignored) {
            return MAX_IDLE_BRIGHTNESS;
        }
    }

    private void setWindowBrightness(float brightness) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (Math.abs(attributes.screenBrightness - brightness) < 0.001f) return;
        attributes.screenBrightness = brightness;
        getWindow().setAttributes(attributes);
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        haloView.setAnimationsEnabled(true);
        updateDisplayStatus("SEARCHING", "Searching...");
        Log.i(TAG, "app entered foreground; Bridge connection enabled");
        trySavedHostThenDiscover();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onStop() {
        foreground = false;
        ++scanGeneration;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
        handler.removeCallbacks(idleDimRunnable);
        stopFallbackPolling();
        safeStopDiscovery();
        closeSocket();
        haloView.setAnimationsEnabled(false);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        Log.i(TAG, "app left foreground; network, discovery, polling, and animation paused");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        foreground = false;
        ++scanGeneration;
        reconnectRunnable = null;
        stopFallbackPolling();
        safeStopDiscovery();
        closeSocket();
        handler.removeCallbacksAndMessages(null);
        if (httpClient != null) httpClient.dispatcher().executorService().shutdown();
        releaseMulticastLock();
        if (haloView != null) haloView.setAnimationsEnabled(false);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        super.onDestroy();
    }
}
