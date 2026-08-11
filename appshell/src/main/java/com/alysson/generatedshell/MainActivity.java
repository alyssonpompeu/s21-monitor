package com.alysson.generatedshell;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** A deliberately tiny offline runtime whose UI/logic lives in assets/index.html. */
public final class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return scheme != null && !scheme.equals("file") && !scheme.equals("about") && !scheme.equals("data");
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
