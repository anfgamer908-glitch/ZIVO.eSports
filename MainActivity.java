package com.zivo.esports;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestCounter = new AtomicInteger(1);

    private static final String SUPABASE_URL = "https://nfenaejooajlrblhjhsw.supabase.co";
    private static final String SUPABASE_KEY = "sb_publishable_wEiecpH-gyo9SX7He9PrXA_NxISD-n0";

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        web.addJavascriptInterface(new SupabaseBridge(), "AndroidSupabase");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String mime = "*/*";
                String[] types = params.getAcceptTypes();
                if (types != null) {
                    for (String t : types) {
                        if (t != null && !t.trim().isEmpty()) {
                            mime = t.trim().split(",")[0];
                            break;
                        }
                    }
                }
                intent.setType(mime);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                        fallback.addCategory(Intent.CATEGORY_OPENABLE);
                        fallback.setType(mime);
                        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                        startActivityForResult(fallback, FILE_CHOOSER_REQUEST);
                    } catch (Exception ignored) {
                        if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                        filePathCallback = null;
                        return false;
                    }
                }
                return true;
            }
        });

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private class SupabaseBridge {
        @JavascriptInterface
        public void request(final String id, final String method, final String path, final String body, final String headersJson) {
            networkExecutor.execute(() -> {
                int status = 0;
                String responseBody = "";
                try {
                    String cleanPath = path == null ? "" : path;
                    if (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1);
                    URL url = new URL(SUPABASE_URL + "/rest/v1/" + cleanPath);
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    c.setRequestMethod(method == null ? "GET" : method.toUpperCase());
                    c.setRequestProperty("apikey", SUPABASE_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + SUPABASE_KEY);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Content-Type", "application/json");

                    try {
                        JSONObject h = new JSONObject(headersJson == null ? "{}" : headersJson);
                        if (h.has("Prefer")) c.setRequestProperty("Prefer", h.optString("Prefer"));
                        if (h.has("prefer")) c.setRequestProperty("Prefer", h.optString("prefer"));
                    } catch (Exception ignored) {}

                    if (body != null && !body.isEmpty() && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                        c.setDoOutput(true);
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        c.setFixedLengthStreamingMode(bytes.length);
                        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
                    }

                    status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                            responseBody = sb.toString();
                        }
                    }
                    c.disconnect();
                } catch (Exception e) {
                    responseBody = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                }

                final int finalStatus = status;
                final String finalBody = responseBody;
                mainHandler.post(() -> {
                    if (web == null) return;
                    String js = "window.__nativeSupabaseResponse(" + JSONObject.quote(id) + "," + finalStatus + "," + JSONObject.quote(finalBody) + ")";
                    web.evaluateJavascript(js, null);
                });
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        networkExecutor.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
