package fr.canallibre.iptv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import javax.net.ssl.SSLException;

public class MainActivity extends Activity {

    private static final int FILE_REQUEST = 42;
    private static final String START_URL = "file:///android_asset/index.html";

    private WebView web;
    private FrameLayout fullscreenLayer;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0c0f14"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0c0f14"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenLayer = new FrameLayout(this);
        fullscreenLayer.setBackgroundColor(Color.BLACK);
        fullscreenLayer.setVisibility(View.GONE);
        root.addView(fullscreenLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        // La page locale peut lire les playlists et flux de n'importe quel serveur (pas de blocage CORS)
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient() {
            // Toutes les requêtes HTTP(S) passent par le réseau natif d'Android :
            // pas de blocage CORS, redirections suivies, identité de lecteur IPTV.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
                if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                return nativeFetch(request.getUrl().toString(), request.getRequestHeaders());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternally(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternally(Uri.parse(url));
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                fullscreenLayer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenLayer.setVisibility(View.VISIBLE);
                web.setVisibility(View.GONE);
                applyImmersive(true);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choisir une playlist M3U"), FILE_REQUEST);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // Évite l'icône grise par défaut de la WebView avant la lecture
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";

    static WebResourceResponse nativeFetch(String url, Map<String, String> reqHeaders) {
        try {
            return doFetch(url, reqHeaders);
        } catch (IOException e) {
            // Beaucoup de serveurs IPTV ne parlent que HTTP : on retente sans le "s"
            if (url.startsWith("https://")) {
                try {
                    return doFetch("http://" + url.substring(8), reqHeaders);
                } catch (IOException ignored) {
                }
            }
            return textResponse(599, describe(e));
        }
    }

    private static WebResourceResponse doFetch(String url, Map<String, String> reqHeaders) throws IOException {
        String current = url;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            if (reqHeaders != null) {
                for (Map.Entry<String, String> h : reqHeaders.entrySet()) {
                    if ("Range".equalsIgnoreCase(h.getKey())) c.setRequestProperty("Range", h.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirection sans destination");
                current = new URL(new URL(current), loc).toString();
                continue;
            }

            String type = c.getContentType();
            String mime = "application/octet-stream";
            String charset = null;
            if (type != null) {
                String[] parts = type.split(";");
                mime = parts[0].trim();
                for (String part : parts) {
                    String t = part.trim();
                    if (t.toLowerCase().startsWith("charset=")) charset = t.substring(8).replace("\"", "");
                }
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);

            // Playlist HLS reçue après redirection : on rend ses liens absolus,
            // sinon les segments seraient cherchés sur le serveur d'origine.
            boolean redirected = !current.equals(url);
            boolean looksHls = mime.toLowerCase().contains("mpegurl") || current.toLowerCase().contains(".m3u8");
            if (redirected && looksHls && code < 400) {
                String body = new String(readAll(in), StandardCharsets.UTF_8);
                if (body.contains("#EXT-X-")) body = absolutize(body, current);
                in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                charset = "utf-8";
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-cache");
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = code < 400 ? "OK" : "Error";
            return new WebResourceResponse(mime, charset, code, reason, headers, in);
        }
        throw new IOException("Trop de redirections");
    }

    private static String absolutize(String playlist, String base) throws IOException {
        URL b = new URL(base);
        StringBuilder out = new StringBuilder();
        for (String line : playlist.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.append(new URL(b, t).toString());
            } else if (t.startsWith("#") && t.contains("URI=\"")) {
                int i = t.indexOf("URI=\"") + 5;
                int j = t.indexOf('"', i);
                if (j > i) {
                    out.append(t, 0, i).append(new URL(b, t.substring(i, j)).toString()).append(t.substring(j));
                } else out.append(t);
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String describe(IOException e) {
        if (e instanceof UnknownHostException)
            return "Serveur introuvable : vérifiez l'adresse (et votre connexion Internet).";
        if (e instanceof SocketTimeoutException)
            return "Le serveur ne répond pas : vérifiez l'adresse et le port, ou réessayez plus tard.";
        if (e instanceof ConnectException)
            return "Connexion refusée : le port est sans doute incorrect, ou le serveur est arrêté.";
        if (e instanceof SSLException)
            return "Connexion sécurisée impossible : utilisez http:// au lieu de https://.";
        return "Connexion impossible (" + e.getClass().getSimpleName() + (e.getMessage() != null ? " : " + e.getMessage() : "") + ").";
    }

    private static WebResourceResponse textResponse(int code, String text) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("text/plain", "utf-8", code, "Network Error", headers,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Passerelle appelée par l'interface (JavaScript) */
    class Bridge {
        @JavascriptInterface
        public void play(String json) {
            runOnUiThread(() -> {
                // Direct et replay : moteur VLC (par défaut) ; films et séries : ExoPlayer
                Class<?> target = PlayerActivity.class;
                try {
                    JSONObject o = new JSONObject(json);
                    String kind = o.optString("kind", "live");
                    boolean liveLike = "live".equals(kind) || "replay".equals(kind);
                    if (liveLike && !"exo".equals(o.optString("engine", "vlc"))) target = VlcPlayerActivity.class;
                } catch (Exception ignored) {
                }
                Intent i = new Intent(MainActivity.this, target);
                i.putExtra(PlayerActivity.EXTRA_JSON, json);
                startActivity(i);
            });
        }

        @JavascriptInterface
        public String progress() {
            JSONObject out = new JSONObject();
            try {
                for (Map.Entry<String, ?> e : getSharedPreferences("progress", MODE_PRIVATE).getAll().entrySet()) {
                    out.put(e.getKey(), new JSONObject(String.valueOf(e.getValue())));
                }
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public void clearProgress(String key) {
            getSharedPreferences("progress", MODE_PRIVATE).edit().remove(key).apply();
        }

        @JavascriptInterface
        public String version() {
            return "2.0";
        }
    }

    private boolean openExternally(Uri uri) {
        if ("file".equals(uri.getScheme())) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreenLayer.removeView(customView);
        fullscreenLayer.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        applyImmersive(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else {
            // L'interface ferme d'abord ses panneaux ouverts ; sinon on quitte
            web.evaluateJavascript("(window.onBack&&window.onBack())?'1':'0'", v -> {
                if (!"\"1\"".equals(v)) finish();
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.onAppResume&&window.onAppResume()", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
