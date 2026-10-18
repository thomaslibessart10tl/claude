package fr.canallibre.iptv;

import android.app.Activity;
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
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lecteur VLC pour le direct et le replay : gère les flux MPEG-TS imparfaits des serveurs IPTV
 * (sauts d'horloge, coupures) en se resynchronisant au lieu de figer l'image.
 * Même format d'entrée que PlayerActivity.
 */
public class VlcPlayerActivity extends Activity {

    private LibVLC libVlc;
    private MediaPlayer mp;
    private VLCVideoLayout videoLayout;

    private LinearLayout topBar, bottomBar;
    private TextView titleView, subView, formatBtn, toastView, channelView, playBtn, timeView, audioBtn, subsBtn;
    private SeekBar seekBar;
    private ProgressBar spinner;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private JSONArray items = new JSONArray();
    private String kind = "live";
    private int index = 0;
    private final List<String> candidates = new ArrayList<>();
    private int attempt = 0;
    private int reconnects = 0;
    private boolean userPaused = false;
    private boolean toastSticky = false;
    private boolean seeking = false;
    private int scaleMode = 0;
    private GestureDetector gestures;

    private final Runnable hideToast = () -> toastView.setVisibility(View.GONE);
    private final Runnable hideChannel = () -> channelView.setVisibility(View.GONE);
    private final Runnable hideControls = () -> showControls(false);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateTime();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            JSONObject o = new JSONObject(getIntent().getStringExtra(PlayerActivity.EXTRA_JSON));
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

        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=2000");
        opts.add("--live-caching=2000");
        opts.add("--http-reconnect");
        opts.add("--http-user-agent=" + MainActivity.USER_AGENT);
        opts.add("--clock-jitter=0");
        opts.add("--clock-synchro=0");
        opts.add("--audio-time-stretch");
        opts.add("--avcodec-skiploopfilter=1");
        opts.add("--no-stats");
        libVlc = new LibVLC(this, opts);
        mp = new MediaPlayer(libVlc);

        buildUi();
        mp.attachViews(videoLayout, null, false, false);
        mp.setEventListener(this::onVlcEvent);

        immersive();
        load(index, false);
        handler.post(ticker);
        showControls(true);
    }

    // ---------- Interface ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(18));
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, f) -> {
            ((GradientDrawable) v.getBackground()).setColor(f ? 0xFFFFB547 : 0x33FFFFFF);
            ((TextView) v).setTextColor(f ? 0xFF1A1205 : Color.WHITE);
            if (f) bumpControls();
        });
        return t;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        return lp;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        boolean live = "live".equals(kind);

        // Haut : titre, zapping, relance, format
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(20), dp(16), dp(16), dp(28));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
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
            topBar.addView(prev, gap());
            topBar.addView(next, gap());
        }
        TextView reload = chip("↻");
        reload.setContentDescription("Relancer le flux");
        reload.setOnClickListener(v -> {
            attempt = 0;
            reconnects = 0;
            showToast("Relance du flux…", 1500);
            startCandidate();
        });
        topBar.addView(reload, gap());
        formatBtn = chip("Format : adapté");
        formatBtn.setOnClickListener(v -> cycleScale());
        topBar.addView(formatBtn, gap());
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bas : lecture/pause, position (replay), audio, sous-titres
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(16), dp(28), dp(16), dp(16));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        playBtn = chip("❚❚");
        playBtn.setOnClickListener(v -> togglePause());
        bottomBar.addView(playBtn);
        timeView = new TextView(this);
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(13);
        timeView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tvp = gap();
        tvp.setMarginStart(dp(12));
        bottomBar.addView(timeView, tvp);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) bumpControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (mp != null && mp.isSeekable()) mp.setPosition(s.getProgress() / 1000f);
            }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(seekBar, sbp);
        if (live) {
            seekBar.setVisibility(View.INVISIBLE);
            timeView.setText("● DIRECT");
            timeView.setTextColor(0xFFFF5A5A);
        }
        audioBtn = chip("Audio");
        audioBtn.setOnClickListener(v -> cycleAudio());
        bottomBar.addView(audioBtn, gap());
        subsBtn = chip("Sous-titres");
        subsBtn.setOnClickListener(v -> cycleSubs());
        bottomBar.addView(subsBtn, gap());
        root.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        channelView = new TextView(this);
        channelView.setTextColor(Color.WHITE);
        channelView.setTextSize(15);
        channelView.setTypeface(Typeface.DEFAULT_BOLD);
        channelView.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0xB3000000);
        cb.setCornerRadius(dp(10));
        channelView.setBackground(cb);
        channelView.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        clp.leftMargin = dp(24);
        clp.topMargin = dp(20);
        root.addView(channelView, clp);

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
            reconnects = 0;
            startCandidate();
        });
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tlp.leftMargin = dp(24);
        tlp.rightMargin = dp(24);
        root.addView(toastView, tlp);

        setContentView(root);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                showControls(topBar.getVisibility() != View.VISIBLE);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!"live".equals(kind) || items.length() < 2 || e1 == null) return false;
                float dy = e2.getY() - e1.getY(), dx = e2.getX() - e1.getX();
                if (Math.abs(dy) > dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    zap(dy < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        videoLayout.setOnTouchListener((v, ev) -> {
            gestures.onTouchEvent(ev);
            return true;
        });
    }

    private void showControls(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
        handler.removeCallbacks(hideControls);
        if (show) {
            handler.postDelayed(hideControls, 4000);
            playBtn.requestFocus();
        }
    }

    private void bumpControls() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 4000);
    }

    private void showToast(String text, long durationMs) {
        handler.removeCallbacks(hideToast);
        toastSticky = durationMs <= 0;
        toastView.setText(text);
        toastView.setVisibility(View.VISIBLE);
        if (durationMs > 0) handler.postDelayed(hideToast, durationMs);
    }

    // ---------- Lecture ----------

    private void load(int i, boolean announce) {
        index = i;
        JSONObject it = items.optJSONObject(i);
        if (it == null) return;
        titleView.setText(it.optString("title", ""));
        subView.setText(it.optString("sub", ""));
        if (announce) {
            channelView.setText(it.optString("title", ""));
            channelView.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideChannel);
            handler.postDelayed(hideChannel, 2500);
        }
        candidates.clear();
        String url = it.optString("url", "");
        String alt = it.optString("alt", "");
        candidates.add(url);
        if (!alt.isEmpty() && !alt.equals(url)) candidates.add(alt);
        attempt = 0;
        reconnects = 0;
        startCandidate();
    }

    private void startCandidate() {
        String url = candidates.get(Math.min(attempt, candidates.size() - 1));
        spinner.setVisibility(View.VISIBLE);
        userPaused = false;
        Media m = new Media(libVlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        m.addOption(":network-caching=2000");
        m.addOption(":http-user-agent=" + MainActivity.USER_AGENT);
        mp.setMedia(m);
        m.release();
        mp.play();
        playBtn.setText("❚❚");
    }

    private void onVlcEvent(MediaPlayer.Event ev) {
        switch (ev.type) {
            case MediaPlayer.Event.Buffering:
                spinner.setVisibility(ev.getBuffering() >= 100f ? View.GONE : View.VISIBLE);
                break;
            case MediaPlayer.Event.Playing:
                spinner.setVisibility(View.GONE);
                playBtn.setText("❚❚");
                if (toastView.getVisibility() == View.VISIBLE && !toastSticky) {
                    handler.removeCallbacks(hideToast);
                    handler.postDelayed(hideToast, 600);
                }
                break;
            case MediaPlayer.Event.Vout:
                if (ev.getVoutCount() > 0) {
                    spinner.setVisibility(View.GONE);
                    reconnects = 0;
                }
                break;
            case MediaPlayer.Event.Paused:
                playBtn.setText("▶");
                break;
            case MediaPlayer.Event.EndReached:
                if ("live".equals(kind)) reconnect("Flux interrompu : reconnexion…");
                else finish();
                break;
            case MediaPlayer.Event.EncounteredError:
                reconnect(null);
                break;
            default:
                break;
        }
    }

    /** Coupure du serveur : on se reconnecte, puis on essaie l'autre format, puis on affiche l'erreur. */
    private void reconnect(String message) {
        if (isFinishing()) return;
        if (reconnects < 2) {
            reconnects++;
            if (message != null) showToast(message, 2000);
            handler.postDelayed(this::startCandidate, 800);
        } else if (attempt < candidates.size() - 1) {
            attempt++;
            reconnects = 0;
            showToast("Nouvel essai dans un autre format…", 2500);
            handler.postDelayed(this::startCandidate, 300);
        } else {
            spinner.setVisibility(View.GONE);
            showToast("Impossible de lire cette chaîne. Elle est peut-être hors service, ou votre abonnement "
                    + "a atteint son nombre de connexions.\n\nTouchez ce message pour réessayer.", 0);
        }
    }

    private void togglePause() {
        if (mp == null) return;
        if (mp.isPlaying()) {
            userPaused = true;
            mp.pause();
        } else {
            userPaused = false;
            if ("live".equals(kind)) startCandidate(); // en direct, on repart au présent
            else mp.play();
        }
        bumpControls();
    }

    private void zap(int delta) {
        if (items.length() < 2) return;
        int n = items.length();
        load(((index + delta) % n + n) % n, true);
    }

    private void cycleScale() {
        scaleMode = (scaleMode + 1) % 3;
        MediaPlayer.ScaleType[] modes = {MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, MediaPlayer.ScaleType.SURFACE_FILL};
        String[] labels = {"Format : adapté", "Format : zoom", "Format : étiré"};
        mp.setVideoScale(modes[scaleMode]);
        formatBtn.setText(labels[scaleMode]);
        bumpControls();
    }

    private void cycleAudio() {
        MediaPlayer.TrackDescription[] tracks = mp.getAudioTracks();
        if (tracks == null || tracks.length <= 1) {
            showToast("Une seule piste audio sur cette chaîne.", 1800);
            return;
        }
        int cur = mp.getAudioTrack(), pos = 0;
        for (int i = 0; i < tracks.length; i++) if (tracks[i].id == cur) pos = i;
        // On saute l'entrée « Désactiver » (id -1)
        for (int k = 1; k <= tracks.length; k++) {
            MediaPlayer.TrackDescription t = tracks[(pos + k) % tracks.length];
            if (t.id != -1) {
                mp.setAudioTrack(t.id);
                showToast("Audio : " + t.name, 1800);
                break;
            }
        }
        bumpControls();
    }

    private void cycleSubs() {
        MediaPlayer.TrackDescription[] tracks = mp.getSpuTracks();
        if (tracks == null || tracks.length <= 1) {
            showToast("Pas de sous-titres sur cette chaîne.", 1800);
            return;
        }
        int cur = mp.getSpuTrack(), pos = 0;
        for (int i = 0; i < tracks.length; i++) if (tracks[i].id == cur) pos = i;
        MediaPlayer.TrackDescription t = tracks[(pos + 1) % tracks.length];
        mp.setSpuTrack(t.id);
        showToast("Sous-titres : " + (t.id == -1 ? "désactivés" : t.name), 1800);
        bumpControls();
    }

    private void updateTime() {
        if (mp == null || "live".equals(kind)) return;
        long t = mp.getTime(), len = mp.getLength();
        timeView.setText(fmt(t) + (len > 0 ? " / " + fmt(len) : ""));
        if (!seeking && len > 0) seekBar.setProgress((int) (mp.getPosition() * 1000));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.FRANCE, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.FRANCE, "%02d:%02d", s / 60, s % 60);
    }

    // ---------- Système ----------

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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            boolean controls = topBar.getVisibility() == View.VISIBLE;
            boolean live = "live".equals(kind) && items.length() > 1;
            if (live && (k == KeyEvent.KEYCODE_CHANNEL_UP || (k == KeyEvent.KEYCODE_DPAD_UP && !controls))) {
                zap(k == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            }
            if (live && (k == KeyEvent.KEYCODE_CHANNEL_DOWN || (k == KeyEvent.KEYCODE_DPAD_DOWN && !controls))) {
                zap(k == KeyEvent.KEYCODE_CHANNEL_DOWN ? -1 : 1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                togglePause();
                return true;
            }
            if (!controls && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MENU)) {
                showControls(true);
                return true;
            }
            if (controls) bumpControls();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (topBar.getVisibility() == View.VISIBLE) {
            showControls(false);
            return;
        }
        super.onBackPressed();
    }

    private boolean stoppedInBackground = false;

    @Override
    protected void onStart() {
        super.onStart();
        if (stoppedInBackground && mp != null) {
            stoppedInBackground = false;
            if ("live".equals(kind)) startCandidate();
            else if (!userPaused) mp.play();
        }
    }

    @Override
    protected void onStop() {
        if (mp != null) {
            stoppedInBackground = true;
            if ("live".equals(kind)) mp.stop();
            else mp.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (mp != null) {
            mp.setEventListener(null);
            mp.stop();
            mp.detachViews();
            mp.release();
            mp = null;
        }
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        super.onDestroy();
    }
}
