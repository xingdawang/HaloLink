package com.halolink.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
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
    private static final int RECONNECTS_BEFORE_HEALTH_CHECK = 5;
    private static final long DISCOVERY_WINDOW_MS = 8_000L;
    private static final long LOW_POWER_DELAY_MS = 30_000L;
    private static final String APP_VERSION = "0.1.7";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean resolving = new AtomicBoolean(false);
    private final Random reconnectJitter = new Random();
    private final Set<Network> lanNetworks = new HashSet<>();

    private HaloView haloView;
    private NsdManager nsdManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private Call healthProbeCall;
    private Call activeVerificationCall;
    private Call pollCall;
    private Runnable reconnectRunnable;
    private Runnable discoveryRetryRunnable;
    private Runnable discoveryTimeoutRunnable;
    private volatile boolean discoveryRunning = false;
    private boolean networkCallbackRegistered = false;
    private volatile boolean lanNetworkAvailable = false;
    private volatile boolean bridgeUnavailableLowPower = false;
    private boolean bridgeUnavailableWindowScheduled = false;
    private volatile boolean destroyed = false;
    private volatile boolean foreground = false;
    private volatile boolean webSocketConnected = false;
    private volatile boolean pollInFlight = false;
    private volatile int scanGeneration = 0;
    private volatile int connectionGeneration = 0;
    private volatile int discoverySession = 0;
    private volatile int discoveryRetryAttempt = 0;
    private int reconnectAttempt = 0;
    private volatile long webSocketDisconnectedAtMs = 0L;
    private volatile String activeHost = "";
    private volatile int activePort = 0;

    private final Runnable idleLowPowerRunnable = () -> {
        if (isActive() && haloView != null
                && EnergyPolicy.isIdleState(haloView.getState())) {
            enterMinimalLowPowerMode("idle");
        }
    };

    private final Runnable offlineLowPowerRunnable = () -> {
        if (isActive() && haloView != null
                && "DISCONNECTED".equalsIgnoreCase(haloView.getState())) {
            enterMinimalLowPowerMode("offline");
        }
    };

    private final Runnable bridgeUnavailableLowPowerRunnable = () -> {
        bridgeUnavailableWindowScheduled = false;
        if (!isConnectionActive() || webSocketConnected) return;
        bridgeUnavailableLowPower = true;
        updateDisplayStatus("DISCONNECTED", "Bridge unavailable");
        enterMinimalLowPowerMode("Bridge unavailable");
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            boolean bridgeSelected = !activeHost.isEmpty() && activePort > 0;
            if (!EnergyPolicy.shouldPoll(
                    isConnectionActive(), webSocketConnected, bridgeSelected)) {
                return;
            }
            String host = activeHost;
            int port = activePort;
            int generation = connectionGeneration;
            if (!pollInFlight) pollCurrentState(host, port, generation);

            long disconnectedDurationMs = Math.max(
                    0L, SystemClock.elapsedRealtime() - webSocketDisconnectedAtMs);
            long delayMs = EnergyPolicy.pollingIntervalMs(disconnectedDurationMs);
            handler.postDelayed(this, delayMs);
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
                // The phone owns liveness detection for /ws/phone. The Bridge only
                // auto-replies to this ping, so exactly one side initiates heartbeats.
                .pingInterval(45, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build();
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        updateDisplayStatus("SEARCHING", "Checking local network...");
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

    private void registerLanNetworkCallback() {
        if (networkCallbackRegistered || connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(
                    Network network, NetworkCapabilities capabilities) {
                boolean isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (isLan) {
                    boolean becameAvailable = lanNetworks.add(network)
                            && !lanNetworkAvailable;
                    if (becameAvailable) {
                        lanNetworkAvailable = true;
                        onLanNetworkAvailable();
                    }
                }
            }

            @Override
            public void onLost(Network network) {
                lanNetworks.remove(network);
                if (lanNetworkAvailable && lanNetworks.isEmpty()) {
                    lanNetworkAvailable = false;
                    onLanNetworkLost();
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback, handler);
            networkCallbackRegistered = true;
            Log.i(TAG, "LAN network callback registered");
        } catch (RuntimeException error) {
            networkCallback = null;
            lanNetworkAvailable = true;
            Log.e(TAG, "LAN callback registration failed; using connection fallback", error);
            trySavedHostThenDiscover();
        }
    }

    private void unregisterLanNetworkCallback() {
        if (!networkCallbackRegistered || connectivityManager == null
                || networkCallback == null) {
            lanNetworks.clear();
            lanNetworkAvailable = false;
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException error) {
            Log.w(TAG, "LAN callback unregister failed", error);
        }
        networkCallbackRegistered = false;
        networkCallback = null;
        lanNetworks.clear();
        lanNetworkAvailable = false;
        Log.i(TAG, "LAN network callback unregistered");
    }

    private void onLanNetworkAvailable() {
        if (!isActive()) return;
        Log.i(TAG, "local network available; connection recovery started");
        cancelOfflineLowPower();
        cancelBridgeUnavailableLowPower();
        discoveryRetryAttempt = 0;
        reconnectAttempt = 0;
        updateDisplayStatus("SEARCHING", "Searching...");
        beginBridgeUnavailableWindow();
        trySavedHostThenDiscover();
    }

    private void onLanNetworkLost() {
        if (!isActive()) return;
        Log.w(TAG, "local network unavailable; connection work paused");
        stopConnectionWork();
        updateDisplayStatus("DISCONNECTED", "No local network");
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifi =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return;
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

    private void releaseMulticastLock() {
        if (multicastLock == null || !multicastLock.isHeld()) return;
        try {
            multicastLock.release();
            Log.i(TAG, "multicast lock released");
        } catch (RuntimeException error) {
            Log.w(TAG, "multicast lock release failed", error);
        }
    }

    private void trySavedHostThenDiscover() {
        if (!isConnectionActive()) return;
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
        if (!isConnectionActive() || discoveryRunning || nsdManager == null) return;
        cancelDiscoveryRetry();
        int session = ++discoverySession;
        resolving.set(false);
        acquireMulticastLock();
        if (!bridgeUnavailableLowPower) {
            updateDisplayStatus("SEARCHING", "Searching...");
        }

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            private boolean isCurrent() {
                return discoveryListener == this
                        && session == discoverySession
                        && isConnectionActive();
            }

            @Override
            public void onDiscoveryStarted(String regType) {
                if (!isCurrent()) return;
                discoveryRunning = true;
                Log.i(TAG, "mDNS discovery started type=" + regType
                        + " session=" + session);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (!isCurrent()) return;
                String name = service.getServiceName() == null
                        ? "" : service.getServiceName();
                Log.i(TAG, "mDNS service found name=" + name);
                if (name.startsWith("HaloLink")
                        && resolving.compareAndSet(false, true)) {
                    resolveService(service, session);
                    stopDiscoveryForResolution(session);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                if (isCurrent()) {
                    Log.w(TAG, "mDNS service lost name=" + service.getServiceName());
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                if (!isCurrent()) return;
                discoveryRunning = false;
                discoveryListener = null;
                cancelDiscoveryTimeout();
                releaseMulticastLock();
                Log.i(TAG, "mDNS discovery stopped type=" + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (!isCurrent()) return;
                // Discovery never became active, so stopServiceDiscovery must not
                // be called for this listener.
                discoveryRunning = false;
                discoveryListener = null;
                resolving.set(false);
                cancelDiscoveryTimeout();
                releaseMulticastLock();
                Log.e(TAG, "mDNS discovery start failed code=" + errorCode);
                scheduleDiscoveryRetry();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                if (!isCurrent()) return;
                discoveryRunning = false;
                discoveryListener = null;
                cancelDiscoveryTimeout();
                releaseMulticastLock();
                Log.e(TAG, "mDNS discovery stop failed code=" + errorCode);
            }
        };

        discoveryListener = listener;
        discoveryRunning = true;
        scheduleDiscoveryTimeout(session);
        try {
            nsdManager.discoverServices(
                    SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException error) {
            if (session == discoverySession && discoveryListener == listener) {
                discoveryRunning = false;
                discoveryListener = null;
                resolving.set(false);
                cancelDiscoveryTimeout();
                releaseMulticastLock();
                Log.e(TAG, "mDNS discovery threw exception", error);
                scheduleDiscoveryRetry();
            }
        }
    }

    private void scheduleDiscoveryTimeout(int session) {
        cancelDiscoveryTimeout();
        discoveryTimeoutRunnable = () -> {
            discoveryTimeoutRunnable = null;
            synchronized (MainActivity.this) {
                if (session != discoverySession || !discoveryRunning) return;
            }
            Log.w(TAG, "mDNS discovery timed out session=" + session);
            safeStopDiscovery();
            scheduleDiscoveryRetry();
        };
        handler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_WINDOW_MS);
    }

    private void cancelDiscoveryTimeout() {
        if (discoveryTimeoutRunnable != null) {
            handler.removeCallbacks(discoveryTimeoutRunnable);
            discoveryTimeoutRunnable = null;
        }
    }

    private synchronized void stopDiscoveryForResolution(int session) {
        if (session != discoverySession) return;
        NsdManager.DiscoveryListener listener = discoveryListener;
        discoveryListener = null;
        discoveryRunning = false;
        cancelDiscoveryTimeout();
        if (listener != null && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(listener);
            } catch (RuntimeException error) {
                Log.w(TAG, "mDNS stop before resolve failed", error);
            }
        }
        releaseMulticastLock();
    }

    @SuppressWarnings("deprecation")
    private void resolveService(NsdServiceInfo service, int session) {
        try {
            nsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    if (session != discoverySession) return;
                    resolving.set(false);
                    Log.e(TAG, "mDNS resolve failed code=" + errorCode);
                    if (isConnectionActive()) scheduleDiscoveryRetry();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    if (session != discoverySession || !isConnectionActive()) return;
                    resolving.set(false);
                    InetAddress host = serviceInfo.getHost();
                    if (host == null) {
                        Log.e(TAG, "mDNS resolved without host");
                        scheduleDiscoveryRetry();
                        return;
                    }
                    Log.i(TAG, "mDNS resolved host=" + host.getHostAddress()
                            + " advertisedPort=" + serviceInfo.getPort());
                    scanForBridge(
                            host.getHostAddress(), serviceInfo.getPort(), false);
                }
            });
        } catch (RuntimeException error) {
            if (session != discoverySession) return;
            resolving.set(false);
            Log.e(TAG, "mDNS resolve threw exception", error);
            scheduleDiscoveryRetry();
        }
    }

    private synchronized void scanForBridge(
            String host, int preferredPort, boolean fallBackToDiscovery) {
        if (!isConnectionActive()) return;
        cancelHealthProbe();
        int generation = ++scanGeneration;
        Log.i(TAG, "scanning host=" + host + " preferredPort=" + preferredPort
                + " generation=" + generation);
        if (!bridgeUnavailableLowPower) {
            updateDisplayStatus("SEARCHING", "Finding Bridge...");
        }
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
        if (!isConnectionActive() || generation != scanGeneration) return;
        if (port > PORT_END) {
            Log.w(TAG, "no verified Bridge on host=" + host);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove("host").remove("port").apply();
            activeHost = "";
            activePort = 0;
            stopFallbackPolling();
            if (fallBackToDiscovery) handler.post(this::startDiscovery);
            else scheduleDiscoveryRetry();
            return;
        }

        String url = "http://" + safeHost(host) + ":" + port + "/health";
        Request request = new Request.Builder().url(url).build();
        Call call = httpClient.newCall(request);
        synchronized (this) {
            if (!isConnectionActive() || generation != scanGeneration) {
                call.cancel();
                return;
            }
            healthProbeCall = call;
        }
        Log.i(TAG, "probing host:port=" + host + ":" + port);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException error) {
                if (!consumeHealthProbe(failedCall, generation)) return;
                Log.w(TAG, "health probe failed " + host + ":" + port
                        + " error=" + error);
                probePort(
                        host,
                        nextProbePort(port, preferredAttempt),
                        generation,
                        fallBackToDiscovery,
                        false
                );
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
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

                if (!consumeHealthProbe(completedCall, generation)) return;
                if (verified) {
                    Log.i(TAG, "health verified host=" + host + " port=" + port
                            + " version=" + version + " pid=" + pid);
                    onBridgeVerified(host, port);
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

    private synchronized boolean consumeHealthProbe(Call call, int generation) {
        if (healthProbeCall != call) return false;
        healthProbeCall = null;
        return isConnectionActive() && generation == scanGeneration;
    }

    private int nextProbePort(int port, boolean preferredAttempt) {
        if (preferredAttempt && port != PORT_START) return PORT_START;
        return port + 1;
    }

    private void onBridgeVerified(String host, int port) {
        discoveryRetryAttempt = 0;
        cancelDiscoveryRetry();
        cancelDiscoveryTimeout();
        Log.i(TAG, "selected Bridge host=" + host + " port=" + port);
        connectWebSocket(host, port);
    }

    private String safeHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private synchronized void connectWebSocket(String host, int port) {
        if (!isConnectionActive()) return;
        cancelReconnect();
        cancelActiveVerification();
        closeSocket("Superseded");
        activeHost = host;
        activePort = port;
        webSocketConnected = false;
        if (webSocketDisconnectedAtMs == 0L) {
            webSocketDisconnectedAtMs = SystemClock.elapsedRealtime();
        }
        int generation = ++connectionGeneration;
        if (!bridgeUnavailableLowPower) {
            updateDisplayStatus("CONNECTING", "Connecting...");
        }
        startFallbackPolling();

        String url = "ws://" + safeHost(host) + ":" + port + "/ws/phone";
        Log.i(TAG, "WebSocket connecting url=" + url
                + " generation=" + generation);
        Request request = new Request.Builder().url(url).build();
        WebSocket candidate = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                synchronized (MainActivity.this) {
                    if (!isConnectionActive()
                            || generation != connectionGeneration
                            || webSocket != socket) {
                        socket.close(1000, "Superseded");
                        Log.i(TAG, "ignoring superseded WebSocket open");
                        return;
                    }
                    webSocketConnected = true;
                    reconnectAttempt = 0;
                    webSocketDisconnectedAtMs = 0L;
                }
                cancelBridgeUnavailableLowPower();
                stopFallbackPolling();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("host", host).putInt("port", port).apply();
                updateDisplayStatus("READY", "Ready");
                Log.i(TAG, "WebSocket opened host=" + host + " port=" + port);
                socket.send(
                        "{\"type\":\"hello\",\"role\":\"android\",\"version\":\""
                                + APP_VERSION + "\"}"
                );
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                synchronized (MainActivity.this) {
                    if (!isConnectionActive()
                            || generation != connectionGeneration
                            || webSocket != socket
                            || !webSocketConnected) {
                        return;
                    }
                }
                applyStatusPayload(text, false, generation);
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                synchronized (MainActivity.this) {
                    if (webSocket != socket || generation != connectionGeneration) return;
                }
                Log.w(TAG, "WebSocket closing code=" + code + " reason=" + reason);
                socket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                Log.w(TAG, "WebSocket closed code=" + code + " reason=" + reason);
                handleWebSocketEnded(socket, host, port, generation);
            }

            @Override
            public void onFailure(
                    WebSocket socket, Throwable error, Response response) {
                int status = response == null ? 0 : response.code();
                Log.e(TAG, "WebSocket failed host=" + host + " port=" + port
                        + " httpStatus=" + status, error);
                handleWebSocketEnded(socket, host, port, generation);
            }
        });
        webSocket = candidate;
    }

    private void handleWebSocketEnded(
            WebSocket socket, String host, int port, int generation) {
        boolean current;
        synchronized (this) {
            current = webSocket == socket && generation == connectionGeneration;
            if (current) {
                webSocket = null;
                webSocketConnected = false;
                if (webSocketDisconnectedAtMs == 0L) {
                    webSocketDisconnectedAtMs = SystemClock.elapsedRealtime();
                }
            }
        }
        if (current && isConnectionActive()) {
            beginBridgeUnavailableWindow();
            startFallbackPolling();
            scheduleReconnect(host, port);
        }
    }

    private synchronized void scheduleReconnect(String host, int port) {
        if (!isConnectionActive()
                || !host.equals(activeHost)
                || port != activePort) {
            return;
        }
        cancelReconnect();
        int attempt = reconnectAttempt++;
        long baseDelayMs = EnergyPolicy.reconnectDelayMs(attempt);
        long delayMs = baseDelayMs + reconnectJitter.nextInt(1_001);
        boolean verifyBeforeReconnect = attempt >= RECONNECTS_BEFORE_HEALTH_CHECK;
        Log.i(TAG, "reconnect scheduled host=" + host + " port=" + port
                + " attempt=" + attempt + " delayMs=" + delayMs
                + " verify=" + verifyBeforeReconnect);
        if (!bridgeUnavailableLowPower) {
            updateDisplayStatus("SEARCHING", "Reconnecting...");
        }
        reconnectRunnable = () -> {
            synchronized (MainActivity.this) {
                reconnectRunnable = null;
            }
            if (!isConnectionActive()
                    || !host.equals(activeHost)
                    || port != activePort) {
                return;
            }
            if (verifyBeforeReconnect) verifyActiveBridgeOrDiscover(host, port);
            else connectWebSocket(host, port);
        };
        handler.postDelayed(reconnectRunnable, delayMs);
    }

    private void verifyActiveBridgeOrDiscover(String host, int port) {
        if (!isConnectionActive()) return;
        cancelActiveVerification();
        int generation = connectionGeneration;
        String url = "http://" + safeHost(host) + ":" + port + "/health";
        Request request = new Request.Builder().url(url).build();
        Call call = httpClient.newCall(request);
        synchronized (this) {
            activeVerificationCall = call;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException error) {
                if (!consumeActiveVerification(failedCall, host, port, generation)) return;
                Log.w(TAG, "selected Bridge health check failed", error);
                abandonSelectedBridgeAndDiscover(host, port);
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                boolean verified = false;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        verified = json.optBoolean("ok")
                                && "HaloLink".equals(json.optString("product"))
                                && json.optInt("port", port) == port;
                    }
                } catch (Exception error) {
                    Log.w(TAG, "selected Bridge health parse failed", error);
                }
                if (!consumeActiveVerification(
                        completedCall, host, port, generation)) {
                    return;
                }
                if (verified) connectWebSocket(host, port);
                else abandonSelectedBridgeAndDiscover(host, port);
            }
        });
    }

    private synchronized boolean consumeActiveVerification(
            Call call, String host, int port, int generation) {
        if (activeVerificationCall != call) return false;
        activeVerificationCall = null;
        return isConnectionActive()
                && generation == connectionGeneration
                && host.equals(activeHost)
                && port == activePort;
    }

    private void abandonSelectedBridgeAndDiscover(String host, int port) {
        synchronized (this) {
            if (!host.equals(activeHost) || port != activePort) return;
            ++connectionGeneration;
            activeHost = "";
            activePort = 0;
            reconnectAttempt = 0;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("host").remove("port").apply();
        stopFallbackPolling();
        closeSocket("Bridge unavailable");
        if (isConnectionActive()) handler.post(this::startDiscovery);
    }

    private void applyStatusPayload(
            String text, boolean nestedState, int generation) {
        try {
            JSONObject json = new JSONObject(text);
            JSONObject status = nestedState ? json.optJSONObject("state") : json;
            if (status == null || !"status".equals(status.optString("type"))) return;
            String state = status.optString("state", "READY");
            String label = status.optString("label", state);
            if (isConnectionActive() && generation == connectionGeneration) {
                updateDisplayStatus(state, label);
            }
        } catch (Exception error) {
            Log.w(TAG, "status payload parse failed", error);
        }
    }

    private synchronized void startFallbackPolling() {
        boolean bridgeSelected = !activeHost.isEmpty() && activePort > 0;
        if (!EnergyPolicy.shouldPoll(
                isConnectionActive(), webSocketConnected, bridgeSelected)) {
            return;
        }
        if (webSocketDisconnectedAtMs == 0L) {
            webSocketDisconnectedAtMs = SystemClock.elapsedRealtime();
        }
        handler.removeCallbacks(pollRunnable);
        long disconnectedDurationMs = Math.max(
                0L, SystemClock.elapsedRealtime() - webSocketDisconnectedAtMs);
        handler.postDelayed(
                pollRunnable, EnergyPolicy.pollingIntervalMs(disconnectedDurationMs));
        Log.i(TAG, "HTTP fallback polling scheduled");
    }

    private synchronized void stopFallbackPolling() {
        handler.removeCallbacks(pollRunnable);
        cancelPollCall();
    }

    private void pollCurrentState(String host, int port, int generation) {
        String url = "http://" + safeHost(host) + ":" + port + "/api/state";
        Request request = new Request.Builder().url(url).build();
        Call call = httpClient.newCall(request);
        synchronized (this) {
            if (!isConnectionActive()
                    || webSocketConnected
                    || generation != connectionGeneration) {
                call.cancel();
                return;
            }
            pollCall = call;
            pollInFlight = true;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException error) {
                if (!consumePollCall(failedCall)) return;
                Log.w(TAG, "HTTP polling failed host=" + host + " port=" + port
                        + " error=" + error);
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                String text = null;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        text = response.body().string();
                    }
                } catch (Exception error) {
                    Log.w(TAG, "HTTP polling response failed", error);
                }
                if (!consumePollCall(completedCall) || text == null) return;
                if (!isConnectionActive()
                        || webSocketConnected
                        || generation != connectionGeneration
                        || !host.equals(activeHost)
                        || port != activePort) {
                    return;
                }
                cancelBridgeUnavailableLowPower();
                applyStatusPayload(text, true, generation);
            }
        });
    }

    private synchronized boolean consumePollCall(Call call) {
        if (pollCall != call) return false;
        pollCall = null;
        pollInFlight = false;
        return true;
    }

    private synchronized void scheduleDiscoveryRetry() {
        if (!isConnectionActive()) return;
        cancelDiscoveryRetry();
        int attempt = discoveryRetryAttempt++;
        long delayMs = EnergyPolicy.discoveryRetryDelayMs(attempt);
        Log.i(TAG, "mDNS discovery retry scheduled attempt=" + attempt
                + " delayMs=" + delayMs);
        discoveryRetryRunnable = () -> {
            synchronized (MainActivity.this) {
                discoveryRetryRunnable = null;
            }
            startDiscovery();
        };
        handler.postDelayed(discoveryRetryRunnable, delayMs);
    }

    private void cancelDiscoveryRetry() {
        if (discoveryRetryRunnable != null) {
            handler.removeCallbacks(discoveryRetryRunnable);
            discoveryRetryRunnable = null;
        }
    }

    private synchronized void safeStopDiscovery() {
        ++discoverySession;
        NsdManager.DiscoveryListener listener = discoveryListener;
        boolean shouldStop = discoveryRunning && listener != null && nsdManager != null;
        discoveryListener = null;
        discoveryRunning = false;
        resolving.set(false);
        cancelDiscoveryTimeout();
        if (shouldStop) {
            try {
                nsdManager.stopServiceDiscovery(listener);
            } catch (RuntimeException error) {
                Log.w(TAG, "mDNS stop failed", error);
            }
        }
        releaseMulticastLock();
    }

    private synchronized void cancelReconnect() {
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private synchronized void cancelHealthProbe() {
        if (healthProbeCall != null) {
            Call call = healthProbeCall;
            healthProbeCall = null;
            call.cancel();
        }
    }

    private synchronized void cancelActiveVerification() {
        if (activeVerificationCall != null) {
            Call call = activeVerificationCall;
            activeVerificationCall = null;
            call.cancel();
        }
    }

    private synchronized void cancelPollCall() {
        if (pollCall != null) {
            Call call = pollCall;
            pollCall = null;
            pollInFlight = false;
            call.cancel();
        } else {
            pollInFlight = false;
        }
    }

    private synchronized void closeSocket(String reason) {
        WebSocket socket = webSocket;
        webSocket = null;
        webSocketConnected = false;
        if (socket != null) socket.close(1000, reason);
    }

    private void stopConnectionWork() {
        ++scanGeneration;
        ++connectionGeneration;
        cancelReconnect();
        cancelDiscoveryRetry();
        cancelDiscoveryTimeout();
        stopFallbackPolling();
        cancelHealthProbe();
        cancelActiveVerification();
        safeStopDiscovery();
        closeSocket("Connection paused");
        cancelBridgeUnavailableLowPower();
        webSocketDisconnectedAtMs = 0L;
    }

    private boolean isActive() {
        return foreground && !destroyed;
    }

    private boolean isConnectionActive() {
        return isActive() && lanNetworkAvailable;
    }

    private void updateDisplayStatus(String state, String label) {
        runOnUiThread(() -> {
            if (destroyed || haloView == null) return;
            if (!haloView.setStatus(state, label)) return;
            haloView.setMinimalIdleMode(false);
            handler.removeCallbacks(idleLowPowerRunnable);
            handler.removeCallbacks(offlineLowPowerRunnable);
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
            if (!foreground) return;
            if (EnergyPolicy.isIdleState(state)) {
                handler.postDelayed(idleLowPowerRunnable, LOW_POWER_DELAY_MS);
            } else if ("DISCONNECTED".equalsIgnoreCase(state)) {
                handler.postDelayed(offlineLowPowerRunnable, LOW_POWER_DELAY_MS);
            }
        });
    }

    private void enterMinimalLowPowerMode(String reason) {
        if (haloView == null || !haloView.setMinimalIdleMode(true)) return;
        setWindowBrightness(lowPowerBrightness());
        Log.i(TAG, reason + " display entered minimal 5% brightness mode");
    }

    private void cancelOfflineLowPower() {
        handler.removeCallbacks(offlineLowPowerRunnable);
    }

    private void beginBridgeUnavailableWindow() {
        if (!isConnectionActive()
                || webSocketConnected
                || bridgeUnavailableLowPower
                || bridgeUnavailableWindowScheduled) {
            return;
        }
        bridgeUnavailableWindowScheduled = true;
        handler.postDelayed(bridgeUnavailableLowPowerRunnable, LOW_POWER_DELAY_MS);
    }

    private void cancelBridgeUnavailableLowPower() {
        handler.removeCallbacks(bridgeUnavailableLowPowerRunnable);
        bridgeUnavailableWindowScheduled = false;
        bridgeUnavailableLowPower = false;
    }

    private float lowPowerBrightness() {
        float maximum = EnergyPolicy.idleBrightness(true);
        try {
            int systemBrightness = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS
            );
            float normalized = Math.max(0f, Math.min(1f, systemBrightness / 255f));
            return Math.min(maximum, normalized);
        } catch (Settings.SettingNotFoundException ignored) {
            return maximum;
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
        haloView.setMinimalIdleMode(false);
        haloView.setAnimationsEnabled(true);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        updateDisplayStatus("SEARCHING", "Checking local network...");
        Log.i(TAG, "app entered foreground; registering LAN connection");
        registerLanNetworkCallback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onStop() {
        foreground = false;
        unregisterLanNetworkCallback();
        handler.removeCallbacks(idleLowPowerRunnable);
        handler.removeCallbacks(offlineLowPowerRunnable);
        stopConnectionWork();
        haloView.setAnimationsEnabled(false);
        haloView.setMinimalIdleMode(false);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        Log.i(TAG, "app left foreground; all connection and animation work paused");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        foreground = false;
        unregisterLanNetworkCallback();
        stopConnectionWork();
        handler.removeCallbacksAndMessages(null);
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
            httpClient.dispatcher().executorService().shutdown();
        }
        releaseMulticastLock();
        if (haloView != null) {
            haloView.setAnimationsEnabled(false);
            haloView.setMinimalIdleMode(false);
        }
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        super.onDestroy();
    }
}
