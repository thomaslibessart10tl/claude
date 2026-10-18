package fr.canallibre.iptv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur plein écran natif (Media3 / ExoPlayer + décodeur audio FFmpeg).
 * Reçoit en JSON : { kind: "live"|"vod"|"series"|"replay", index, items: [{url, alt, title, sub, key, fromStart}] }
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_JSON = "json";

    private ExoPlayer player;
    private PlayerView view;
    private LinearLayout topBar;
    private TextView titleView, subView, formatBtn, toastView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences progress;

    private JSONArray items = new JSONArray();
    private String kind = "live";
    private int index = 0;
    private final List<String[]> candidates = new ArrayList<>(); // {url, forceHls}
    private int attempt = 0;
    private int resizeMode = 0;
    private GestureDetector gestures;

    private final Runnable hideToast = () -> toastView.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress = getSharedPreferences("progress", MODE_PRIVATE);

        try {
            JSONObject o = new JSONObject(getIntent().getStringExtra(EXTRA_JSON));
            kind = o.optString("kind", "live");
            items = o.optJSONArray("items");
            if (items == null) items = new JSONArray();
            index = Math.max(0, Math.min(o.optInt("index", 0), items.length() - 1));
        } catch (Exception e) {
            finish();
            return;
        }
        if (items.length() == 0) {
            finish();
            return;
        }

        buildUi();
        buildPlayer();
        immersive();
        load(index, false);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(18));
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setShowSubtitleButton(true);
        view.setKeepScreenOn(true);
        view.setControllerShowTimeoutMs(3500);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        boolean live = "live".equals(kind);
        if (live) {
            view.setShowFastForwardButton(false);
            view.setShowRewindButton(false);
        }
        view.setShowNextButton(items.length() > 1);
        view.setShowPreviousButton(items.length() > 1);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bandeau du haut : titre, sous-titre, format d'image, zapping
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(20), dp(16), dp(16), dp(24));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(shade);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        subView = new TextView(this);
        subView.setTextColor(0xFFCCD2DC);
        subView.setTextSize(13);
        subView.setSingleLine(true);
        texts.addView(titleView);
        texts.addView(subView);
        topBar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (live && items.length() > 1) {
            TextView prev = chip("▲");
            prev.setOnClickListener(v -> zap(-1));
            TextView next = chip("▼");
            next.setOnClickListener(v -> zap(1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            topBar.addView(prev, lp);
            topBar.addView(next, lp);
        }
        formatBtn = chip("Format : adapté");
        formatBtn.setOnClickListener(v -> cycleResize());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMarginStart(dp(8));
        topBar.addView(formatBtn, flp);

        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        toastView = new TextView(this);
        toastView.setTextColor(Color.WHITE);
        toastView.setTextSize(16);
        toastView.setGravity(Gravity.CENTER);
        toastView.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xDD141922);
        tb.setCornerRadius(dp(12));
        toastView.setBackground(tb);
        toastView.setVisibility(View.GONE);
        toastView.setOnClickListener(v -> {
            toastView.setVisibility(View.GONE);
            attempt = 0;
            startCandidate(0);
        });
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tlp.leftMargin = dp(24);
        tlp.rightMargin = dp(24);
        root.addView(toastView, tlp);

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> topBar.setVisibility(visibility));

        setContentView(root);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!"live".equals(kind) || items.length() < 2 || e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) > dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    zap(dy < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    private void buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(MainActivity.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http), extractors);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        view.setPlayer(new androidx.media3.common.ForwardingPlayer(player) {
            // Les boutons précédent / suivant du lecteur zappent dans notre liste
            @Override
            public boolean isCommandAvailable(int command) {
                if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                        || command == COMMAND_SEEK_TO_PREVIOUS || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    return items.length() > 1;
                }
                return super.isCommandAvailable(command);
            }

            @Override
            public Player.Commands getAvailableCommands() {
                Player.Commands.Builder b = super.getAvailableCommands().buildUpon();
                if (items.length() > 1) {
                    b.addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);
                }
                return b.build();
            }

            @Override
            public void seekToNext() { zap(1); }

            @Override
            public void seekToNextMediaItem() { zap(1); }

            @Override
            public void seekToPrevious() {
                if (!"live".equals(kind) && player.getCurrentPosition() > 5000) player.seekTo(0);
                else zap(-1);
            }

            @Override
            public void seekToPreviousMediaItem() { zap(-1); }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                handleError(error);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(hideToast);
                    if (toastView.getText().toString().startsWith("Nouvel essai")) toastView.setVisibility(View.GONE);
                } else if (state == Player.STATE_ENDED) {
                    if ("series".equals(kind) && index < items.length() - 1) {
                        saveProgress();
                        load(index + 1, true);
                    } else if (!"live".equals(kind)) {
                        saveProgress();
                        finish();
                    }
                }
            }
        });
    }

    private JSONObject item(int i) {
        return items.optJSONObject(i);
    }

    private void load(int i, boolean announce) {
        index = i;
        JSONObject it = item(i);
        if (it == null) return;
        titleView.setText(it.optString("title", ""));
        subView.setText(it.optString("sub", ""));
        if (announce) showToast(it.optString("title", ""), 1800);

        candidates.clear();
        String url = it.optString("url", "");
        String alt = it.optString("alt", "");
        candidates.add(new String[]{url, "0"});
        if (!alt.isEmpty() && !alt.equals(url)) candidates.add(new String[]{alt, "0"});
        if (!url.toLowerCase().contains(".m3u8")) candidates.add(new String[]{url, "1"});
        attempt = 0;

        long resume = 0;
        if (("vod".equals(kind) || "series".equals(kind)) && !it.optBoolean("fromStart", false)) {
            try {
                String saved = progress.getString(key(it), null);
                if (saved != null) {
                    JSONObject p = new JSONObject(saved);
                    long pos = p.optLong("p", 0), dur = p.optLong("d", 0);
                    if (pos > 30000 && (dur <= 0 || pos < dur - 90000)) resume = pos;
                }
            } catch (Exception ignored) {
            }
        }
        startCandidate(resume);
    }

    private void startCandidate(long positionMs) {
        String[] c = candidates.get(Math.min(attempt, candidates.size() - 1));
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(c[0]));
        if ("1".equals(c[1])) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        if (positionMs > 0) player.setMediaItem(b.build(), positionMs);
        else player.setMediaItem(b.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void handleError(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (attempt < candidates.size() - 1) {
            attempt++;
            showToast("Nouvel essai dans un autre format…", 2500);
            long pos = "live".equals(kind) ? 0 : Math.max(0, player.getCurrentPosition());
            startCandidate(pos);
            return;
        }
        showToast(describe(e) + "\n\nTouchez ce message pour réessayer.", 0);
    }

    private String describe(PlaybackException e) {
        Throwable cause = e.getCause();
        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
            int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            if (code == 401 || code == 403)
                return "Le serveur refuse la lecture (erreur " + code + ") : abonnement expiré, ou trop d'appareils connectés en même temps.";
            if (code == 404) return "Cette chaîne ou cette vidéo n'existe plus sur le serveur (erreur 404).";
            if (code == 509 || code == 429) return "Trop de connexions en même temps sur votre abonnement (erreur " + code + ").";
            return "Le serveur a répondu avec l'erreur " + code + ".";
        }
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "Connexion au serveur impossible. Vérifiez votre connexion Internet.";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Le serveur ne répond pas.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "Le flux reçu n'est pas une vidéo lisible. La chaîne est peut-être hors service.";
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                return "Cet appareil ne sait pas décoder cette vidéo.";
            default:
                return "Lecture impossible (" + e.getErrorCodeName() + ").";
        }
    }

    private void showToast(String text, long durationMs) {
        handler.removeCallbacks(hideToast);
        toastView.setText(text);
        toastView.setVisibility(View.VISIBLE);
        if (durationMs > 0) handler.postDelayed(hideToast, durationMs);
    }

    private void zap(int delta) {
        if (items.length() < 2) return;
        saveProgress();
        int n = items.length();
        load(((index + delta) % n + n) % n, true);
    }

    private void cycleResize() {
        resizeMode = (resizeMode + 1) % 3;
        int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};
        String[] labels = {"Format : adapté", "Format : zoom", "Format : étiré"};
        view.setResizeMode(modes[resizeMode]);
        formatBtn.setText(labels[resizeMode]);
    }

    private String key(JSONObject it) {
        String k = it.optString("key", "");
        return k.isEmpty() ? it.optString("url", "") : k;
    }

    private void saveProgress() {
        if (player == null || !("vod".equals(kind) || "series".equals(kind))) return;
        JSONObject it = item(index);
        if (it == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) pos = dur;
        if (pos < 10000) return;
        try {
            JSONObject p = new JSONObject();
            p.put("p", pos);
            p.put("d", dur);
            p.put("t", System.currentTimeMillis());
            p.put("title", it.optString("title", ""));
            progress.edit().putString(key(it), p.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && "live".equals(kind) && items.length() > 1) {
            int k = event.getKeyCode();
            boolean controllerShown = view.isControllerFullyVisible();
            if (k == KeyEvent.KEYCODE_CHANNEL_UP || (k == KeyEvent.KEYCODE_DPAD_UP && !controllerShown)) {
                zap(k == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || (k == KeyEvent.KEYCODE_DPAD_DOWN && !controllerShown)) {
                zap(k == KeyEvent.KEYCODE_CHANNEL_DOWN ? -1 : 1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        saveProgress();
        if (player != null) player.setPlayWhenReady(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            saveProgress();
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
