package com.alysson.internetchat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int REQ_PHONE_STATE = 9001;

    private FrameLayout root;
    private WebView webView;
    private TextView gateView;
    private ConnectivityManager connectivityManager;
    private TelephonyManager telephonyManager;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private Network cellularNetwork;
    private TelephonyDisplayInfo lastDisplayInfo;
    private PhoneStateListener phoneStateListener;
    private ModernTelephonyCallback modernTelephonyCallback;
    private boolean pageLoaded = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        root = new FrameLayout(this);
        setContentView(root);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        gateView = new TextView(this);
        gateView.setGravity(Gravity.CENTER);
        gateView.setTextSize(18f);
        gateView.setPadding(48, 48, 48, 48);
        gateView.setText("Conversa 5G\n\nEste app funciona somente pela rede móvel 5G.\nAtive os dados móveis 5G e conceda a permissão de estado do telefone.");
        root.addView(gateView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE_STATE);
        } else {
            startNetworkGate();
        }
    }

    private void startNetworkGate() {
        registerTelephonyState();
        requestCellularNetwork();
        reevaluateGate();
    }

    private void requestCellularNetwork() {
        if (cellularCallback != null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellularNetwork = network;
                reevaluateGate();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(cellularNetwork)) {
                    cellularNetwork = null;
                    reevaluateGate();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    cellularNetwork = network;
                    reevaluateGate();
                }
            }
        };

        try {
            connectivityManager.requestNetwork(request, cellularCallback);
        } catch (Exception e) {
            gateView.setText("Não foi possível solicitar a rede celular.\n\n" + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void registerTelephonyState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernTelephonyCallback = new ModernTelephonyCallback();
            telephonyManager.registerTelephonyCallback(getMainExecutor(), modernTelephonyCallback);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
                    lastDisplayInfo = info;
                    reevaluateGate();
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED);
        }
    }

    private class ModernTelephonyCallback extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener {
        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            lastDisplayInfo = info;
            reevaluateGate();
        }
    }

    private boolean is5gReported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (telephonyManager.getDataNetworkType() == TelephonyManager.NETWORK_TYPE_NR) {
                    return true;
                }
            } catch (SecurityException ignored) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lastDisplayInfo != null) {
            int override = lastDisplayInfo.getOverrideNetworkType();
            if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return true;
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R
                    && override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE) return true;
        }
        return false;
    }

    private void reevaluateGate() {
        runOnUiThread(() -> {
            boolean allowed = cellularNetwork != null && is5gReported();
            if (allowed) {
                try {
                    connectivityManager.bindProcessToNetwork(cellularNetwork);
                    gateView.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                    if (!pageLoaded) {
                        pageLoaded = true;
                        webView.loadUrl("file:///android_asset/index.html");
                    }
                } catch (Exception e) {
                    blockChat("Falha ao vincular o app à rede 5G.\n\n" + e.getMessage());
                }
            } else {
                blockChat("Conversa 5G\n\nConexão bloqueada.\nEste app funciona somente quando o telefone está conectado à rede móvel 5G/NR.\n\nWi‑Fi, 4G/LTE, 3G e outras redes não são aceitas.");
            }
        });
    }

    private void blockChat(String message) {
        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (Exception ignored) {
        }
        if (pageLoaded) {
            webView.loadUrl("about:blank");
            pageLoaded = false;
        }
        webView.setVisibility(View.GONE);
        gateView.setText(message);
        gateView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHONE_STATE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNetworkGate();
            } else {
                gateView.setText("Permissão necessária.\n\nSem acesso ao estado da rede móvel o app não consegue confirmar o 5G e permanece bloqueado.");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onDestroy() {
        if (cellularCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cellularCallback);
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modernTelephonyCallback != null) {
            telephonyManager.unregisterTelephonyCallback(modernTelephonyCallback);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (Exception ignored) {
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
