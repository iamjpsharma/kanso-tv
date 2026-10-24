package com.kansotv.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.Bitmap;
import java.net.HttpURLConnection;
import java.net.URL;
import android.util.Log;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Apple TV style home screen. All D-pad navigation is handled here (no Android focus search), so
 * movement is deterministic: zones TOP (pills) / DOCK / GRID, Up/Down move exactly one row.
 */
public class HomeActivity extends Activity implements Wallpapers.Listener {

    private enum Zone { TOP, DOCK, GRID }

    private static final int PILLS = 3;
    /** apps per row, dock and grid (config.xml: columns) */
    private int COLS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    // layout (px)
    private int W, H, side, bandTop, gridTop, rowH;

    private FrameLayout root, content;
    private TextView greeting, clock, date, weather, weatherText, aqiText, holiday, dockTitle, wifiText;
    private LinearLayout cryptoContainer;
    private ImageView weatherIcon;
    private LinearLayout weatherContainer, aqiBadge;
    private LinearLayout pillRow;
    // follower counters under the pills: YouTube, Instagram, Facebook
    private static final String[] SOCIAL_KEYS = {"yt", "ig", "fb"};
    private static final String[] SOCIAL_UNITS = {"subscribers", "followers", "followers"};
    private final String[] socialIds = new String[3];
    private final View[] socialPills = new View[3];
    private final TextView[] socialTexts = new TextView[3];
    private boolean hasSocial;
    private static final long SUBS_REFRESH_MS = 30 * 60_000L;
    private final View[] pills = new View[PILLS];
    private GlassHighlight highlight;
    private View glow;
    private GradientDrawable glowBg;
    private RecyclerView dockRv, gridRv;
    private TileAdapter dockAdapter, gridAdapter;
    private InputsMenu inputs;

    private Zone zone = Zone.DOCK;
    private int topIdx, dockIdx, gridIdx;
    private final RectF hl = new RectF();
    private float hlRadius, hlRadiusNow;
    private boolean hlPlaced;
    private ValueAnimator hlAnim, glowAnim;
    private int glowColor = 0;
    private int lastTitleApp = -1;
    private boolean titleChanging;

    // rotating wallpapers: Files from the photo store (see Wallpapers)
    private final List<Object> wallpapers = new ArrayList<>();
    private final ImageView[] wpViews = new ImageView[2];
    private int wpIdx, wpFront;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        COLS = Math.max(3, Math.min(6, getResources().getInteger(R.integer.columns)));
        zone = Zone.values()[prefs.getInt("zone", Zone.DOCK.ordinal())];
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        if (zone == Zone.TOP) zone = Zone.DOCK;   // come back to apps, not the buttons

        buildUi();
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        time.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(statusReceiver, time);

        IntentFilter pk = new IntentFilter();
        pk.addAction(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pk.addDataScheme("package");
        registerReceiver(packageReceiver, pk);

        // Now-playing wallpaper receiver
        IntentFilter np = new IntentFilter();
        np.addAction(NowPlayingService.ACTION_ART_UPDATE);
        np.addAction(NowPlayingService.ACTION_CLEAR);
        registerReceiver(nowPlayingReceiver, np);

        // Layout change receiver (from phone)
        IntentFilter layoutFilter = new IntentFilter("com.kansotv.launcher.LAYOUT_CHANGED");
        registerReceiver(layoutReceiver, layoutFilter);

        loadApps();

        // Show the last value immediately, then do one fresh location + weather check.
        String cachedWeather = prefs.getString("weather", null);
        String cachedAqi = prefs.getString("aqi", null);
        if (cachedWeather != null) {
            String[] parts = cachedWeather.split("  •  ", 2);
            String weatherPart = parts[0];
            String aqiPart = parts.length > 1 ? parts[1] : (cachedAqi != null ? cachedAqi : "");
            setWeatherUi(weatherPart, aqiPart);
        }
        refreshWeather();
        refreshWidgets();

        Wallpapers.setListener(this);
        startForegroundService(new Intent(this, PhoneService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateWifi();
        if (inputs.isOpen()) inputs.close();
        scheduleMinute();
        scheduleWallpaper();
        scheduleWeather();
        refreshWidgets();
        if (hasSocial && System.currentTimeMillis() - prefs.getLong("counts_at", 0) > SUBS_REFRESH_MS) {
            refreshSubs.run();
        } else if (hasSocial) {
            main.postDelayed(refreshSubs, SUBS_REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        // nothing ticks (and no wallpaper is decoded) while an app is in front
        main.removeCallbacks(minuteTick);
        main.removeCallbacks(nextWallpaper);
        main.removeCallbacks(refreshSubs);
        main.removeCallbacks(weatherRefresh);
        super.onPause();
    }

    /** Some TV firmwares don't deliver ACTION_TIME_TICK, so the clock runs on our own timer. */
    private final Runnable minuteTick = () -> {
        updateClock();
        scheduleMinute();
    };

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        long now = System.currentTimeMillis();
        main.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    private final Runnable weatherRefresh = () -> {
        refreshWeather();
    };

    private void scheduleWeather() {
        main.removeCallbacks(weatherRefresh);
        int minutes = Math.max(0, getResources().getInteger(R.integer.weather_refresh_minutes));
        if (minutes > 0) {
            main.postDelayed(weatherRefresh, minutes * 60_000L);
        }
    }

    private void refreshWeather() {
        Weather.load(this, main, io, text -> {
            if (isFinishing() || isDestroyed() || text == null) return;
            String[] parts = text.split("  •  ", 2);
            String weatherPart = parts[0];
            String aqiPart = parts.length > 1 ? parts[1] : "";
            setWeatherUi(weatherPart, aqiPart);
            prefs.edit().putString("weather", weatherPart).putString("aqi", aqiPart).apply();
            scheduleWeather();
        });
    }

    private void setWeatherUi(String weatherPart, String aqiPart) {
        // Parse weather: "25°C · Clear" or "25°C · Partly cloudy"
        String temp = "", condition = "";
        if (weatherPart.contains("°C · ")) {
            String[] wp = weatherPart.split("°C · ", 2);
            temp = wp[0] + "°C";
            condition = wp.length > 1 ? wp[1] : "";
        } else {
            temp = weatherPart;
        }
        weatherText.setText(temp + (condition.isEmpty() ? "" : "  " + condition));
        weatherIcon.setImageResource(getWeatherIconRes(condition));
        
        // Show weather container
        weatherContainer.setVisibility(View.VISIBLE);
        
        // Parse and show AQI
        if (aqiPart != null && !aqiPart.isEmpty() && !aqiPart.equals("Weather")) {
            aqiText.setText(aqiPart);
            setAqiBadgeColor(aqiPart);
            aqiBadge.setVisibility(View.VISIBLE);
        } else {
            aqiBadge.setVisibility(View.GONE);
        }
    }

    private void refreshWidgets() {
        // Holiday
        Widgets.loadHoliday(this, main, io, (name, date) -> {
            if (isFinishing() || isDestroyed() || name == null) return;
            holiday.setText("🎉  " + name + "  (" + date + ")");
            holiday.setVisibility(View.VISIBLE);
        });

        // Crypto - single card with BTC and USDT
        Widgets.loadCrypto(this, main, io, text -> {
            if (isFinishing() || isDestroyed() || text == null) return;
            cryptoContainer.removeAllViews();
            View card = createCryptoCard(text);
            cryptoContainer.addView(card);
            cryptoContainer.setVisibility(View.VISIBLE);
        });

        // Wallpaper (Unsplash Source)
        Widgets.loadWallpaper(this, main, io, url -> {
            if (isFinishing() || isDestroyed() || url == null) return;
            applyUnsplashWallpaper(url);
        });
    }

    private void applyUnsplashWallpaper(String url) {
        // Download and set as next wallpaper in rotation
        io.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    final Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) {
                        main.post(() -> {
                            // Add to wallpaper rotation as a temporary entry
                            Wallpapers.setNowPlaying(this, bmp);
                        });
                    }
                }
            } catch (Exception e) {
                Log.w("Widgets", "wallpaper download failed", e);
            }
        });
    }

    /** Creates a single horizontal crypto card showing both BTC and USDT. */
    private View createCryptoCard(String fullText) {
        // fullText format: "₿ ₹79.44L ▲1.0%  •  $ ₹94 ▼0.3%"
        
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.rounded(0x991A1A2E, dp(10), dp(1), 0x33FFFFFF));
        card.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        card.setLayoutParams(lp);

        // Split by " • " to get BTC and USDT parts
        String[] parts = fullText.split(" • ");
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            String[] subParts = part.split(" ");
            if (subParts.length >= 3) {
                if (i > 0) {
                    TextView sep = Ui.text(this, 400, 14, 0x66FFFFFF);
                    sep.setText("  •  ");
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
                    slp.leftMargin = dp(10);
                    slp.rightMargin = dp(10);
                    card.addView(sep, slp);
                }
                
                // Symbol + price
                TextView item = Ui.text(this, 600, 15, 0xFFFFFFFF);
                item.setText(subParts[0] + " " + subParts[1]);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) ilp.leftMargin = dp(8);
                card.addView(item, ilp);
                
                // Change (green up / red down)
                String change = subParts[2] + (subParts.length > 3 ? " " + subParts[3] : "");
                TextView changeTv = Ui.text(this, 600, 14, change.startsWith("▲") ? 0xFF00E676 : 0xFFFF5722);
                changeTv.setText("  " + change);
                card.addView(changeTv, ilp);
            }
        }
        
        return card;
    }

    /** Loads an image asynchronously into an ImageView. */
    private void loadImageAsync(ImageView img, String url) {
        io.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(12_000);
                conn.setInstanceFollowRedirects(true);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    final Bitmap bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) {
                        main.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                img.setImageBitmap(bmp);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.w("Widgets", "image load failed: " + url, e);
            }
        });
    }

    private int getWeatherIconRes(String condition) {
        if (condition == null) return R.drawable.ic_weather_clear;
        String c = condition.toLowerCase();
        if (c.contains("clear")) return R.drawable.ic_weather_clear;
        if (c.contains("mostly clear")) return R.drawable.ic_weather_mostly_clear;
        if (c.contains("partly cloudy")) return R.drawable.ic_weather_partly_cloudy;
        if (c.contains("overcast")) return R.drawable.ic_weather_overcast;
        if (c.contains("fog")) return R.drawable.ic_weather_fog;
        if (c.contains("drizzle")) return R.drawable.ic_weather_drizzle;
        if (c.contains("rain")) return R.drawable.ic_weather_rain;
        if (c.contains("snow")) return R.drawable.ic_weather_snow;
        if (c.contains("shower")) return R.drawable.ic_weather_showers;
        if (c.contains("thunder")) return R.drawable.ic_weather_thunder;
        return R.drawable.ic_weather_clear;
    }

    private void setAqiBadgeColor(String aqiPart) {
        // Parse AQI value: "AQI 137" or "PM2.5 69 μg/m³" or "PM10 80 μg/m³"
        int value = -1;
        if (aqiPart.startsWith("AQI ")) {
            try { value = Integer.parseInt(aqiPart.substring(4).trim()); } catch (Exception ignored) {}
        } else if (aqiPart.startsWith("PM2.5 ")) {
            try { value = Integer.parseInt(aqiPart.substring(6).replaceAll("[^0-9]", "").trim()); } catch (Exception ignored) {}
        } else if (aqiPart.startsWith("PM10 ")) {
            try { value = Integer.parseInt(aqiPart.substring(5).replaceAll("[^0-9]", "").trim()); } catch (Exception ignored) {}
        }
        int color;
        if (value >= 0 && value <= 50) color = 0xFF00E676;           // Good - green
        else if (value <= 100) color = 0xFFFFEB3B;                  // Moderate - yellow
        else if (value <= 150) color = 0xFFFF9800;                  // Unhealthy for Sensitive - orange
        else if (value <= 200) color = 0xFFFF5722;                  // Unhealthy - red
        else if (value <= 300) color = 0xFFD50000;                  // Very Unhealthy - deep red
        else color = 0xFF7B1FA2;                                     // Hazardous - purple
        aqiBadge.setBackground(Ui.rounded(color, dp(10), dp(1), 0xFFFFFFFF));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and return to the dock.
        if (inputs.isOpen()) inputs.close();
        if (zone == Zone.GRID && hasWindowFocus()) {
            zone = Zone.DOCK;
            applyFocus(true);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(nowPlayingReceiver);
        unregisterReceiver(layoutReceiver);
        Wallpapers.setListener(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) updateWifi();
            else updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    /** Receives album art from YouTube Music and shows it as a live wallpaper. */
    private final BroadcastReceiver nowPlayingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (NowPlayingService.ACTION_ART_UPDATE.equals(action)) {
                Bitmap art = i.getParcelableExtra(NowPlayingService.EXTRA_ART);
                if (art != null) {
                    showNowPlaying(art, i.getStringExtra(NowPlayingService.EXTRA_TITLE),
                            i.getStringExtra(NowPlayingService.EXTRA_ARTIST),
                            i.getStringExtra(NowPlayingService.EXTRA_PACKAGE));
                }
            } else if (NowPlayingService.ACTION_CLEAR.equals(action)) {
                hideNowPlaying();
            }
        }
    };

    /** Reloads app layout when changed from phone. */
    private final BroadcastReceiver layoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
            // Also refresh greeting and social IDs
            AppRepo.Layout layout = AppRepo.getLayout(HomeActivity.this);
            String name = layout.greetingName.trim();
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String part;
            if (h >= 5 && h < 12) part = "Good morning";
            else if (h >= 12 && h < 17) part = "Good afternoon";
            else if (h >= 17 && h < 21) part = "Good evening";
            else part = "Good night";
            greeting.setText(name.isEmpty() ? part : part + ", " + name);

            socialIds[0] = layout.ytChannel.trim();
            socialIds[1] = layout.igUser.trim();
            socialIds[2] = layout.fbPage.trim();
            hasSocial = false;
            for (int j = 0; j < 3; j++) {
                if (!socialIds[j].isEmpty()) hasSocial = true;
            }
            // Refresh social counts
            refreshSubs.run();
        }
    };

    private void loadApps() {
        io.execute(() -> {
            AppRepo.Result r = AppRepo.load(getApplicationContext());
            main.post(() -> setApps(r));
        });
    }

    private void setApps(AppRepo.Result r) {
        dockAdapter.setApps(r.dock);
        gridAdapter.setApps(r.grid);
        int rows = (r.grid.size() + COLS - 1) / COLS;
        ViewGroup.LayoutParams glp = gridRv.getLayoutParams();
        glp.height = Math.max(rows, 1) * rowH;
        gridRv.setLayoutParams(glp);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = gridTop + Math.max(rows, 1) * rowH + Ui.dp(this, 40);
        content.setLayoutParams(clp);
        lastTitleApp = -1;
        clampIndices();
        // Wait for the RecyclerViews to lay out before placing the highlight.
        content.post(() -> content.post(() -> applyFocus(hlPlaced)));
    }

    private void clampIndices() {
        int nd = dockAdapter.getItemCount(), ng = gridAdapter.getItemCount();
        dockIdx = Math.max(0, Math.min(dockIdx, nd - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, ng - 1));
        if (zone == Zone.GRID && ng == 0) zone = Zone.DOCK;
        if (zone == Zone.DOCK && nd == 0) zone = ng > 0 ? Zone.GRID : Zone.TOP;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        side = dp(40);
        // cells shrink to fit COLS per row; cards keep the 16:9 TV banner shape
        int gap = dp(24);
        int dockCell = (W - 2 * side - 2 * dp(16)) / COLS, dockCardW = dockCell - gap;
        int dockCardH = dockCardW * 9 / 16;
        int gridCell = (W - 2 * side + gap) / COLS, gridCardW = gridCell - gap;
        int gridCardH = gridCardW * 9 / 16;
        int bandH = dockCardH + dp(28), bandBottom = dp(26);
        bandTop = H - bandBottom - bandH;
        rowH = gridCardH + dp(42);
        gridTop = H + dp(56);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        boolean useWallpaper = getResources().getBoolean(R.bool.use_wallpaper);
        if (useWallpaper) {
            findWallpapers();
            for (int i = 0; i < 2; i++) {
                wpViews[i] = new ImageView(this);
                wpViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                wpViews[i].setAlpha(i == 0 ? 1f : 0f);
                root.addView(wpViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            if (!wallpapers.isEmpty()) {
                wpIdx = prefs.getInt("wallpaper", 0) % wallpapers.size();
                wpViews[0].setImageBitmap(loadWallpaper(wallpapers.get(wpIdx)));
            }
            // darken: evenly, and more towards the bottom where the dock and grid sit
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x59000000, 0x66000000, 0xD9000000}));
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, H * 2));

        // ambient glow behind the dock title
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // circle fades out before the view edges; scaleY flattens it into an ellipse
        glowBg.setGradientRadius(dp(290));
        glowBg.setGradientCenter(0.4f, 0.5f);
        glowBg.setColors(new int[]{0, 0});
        glow.setBackground(glowBg);
        glow.setScaleY(0.45f);
        int glowCx = side + dp(110), glowCy = bandTop - dp(44);
        content.addView(glow, at(dp(900), dp(600), glowCx - dp(360), glowCy - dp(300)));

        // dock band
        View band = new View(this);
        band.setBackground(Ui.rounded(useWallpaper ? 0x8C141416 : 0xFF141416, dp(28), dp(1), 0x14FFFFFF));
        content.addView(band, at(W - 2 * side, bandH, side, bandTop));

        // the single glass highlight (below tiles and pills)
        highlight = new GlassHighlight(this);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        // greeting, clock, date
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 400, 16, 0x99FFFFFF);
        clock = Ui.text(this, 150, 78, 0xFFFFFFFF);
        clock.setLetterSpacing(-0.02f);
        date = Ui.text(this, 300, 17, 0x99FFFFFF);
        tl.addView(greeting);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(4);
        clp.leftMargin = -dp(3);
        tl.addView(clock, clp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(6);
        tl.addView(date, dlp);
        weather = Ui.text(this, 400, 15, 0xB3FFFFFF);
        weather.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = dp(6);
        tl.addView(weather, wlp);
        
        // Enhanced weather/AQI container (initially hidden)
        weatherContainer = new LinearLayout(this);
        weatherContainer.setOrientation(LinearLayout.HORIZONTAL);
        weatherContainer.setGravity(Gravity.CENTER_VERTICAL);
        weatherContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(-2, -2);
        wcLp.topMargin = dp(6);
        tl.addView(weatherContainer, wcLp);
        
        // Weather icon
        weatherIcon = new ImageView(this);
        weatherIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        weatherIcon.setColorFilter(0xFFFFFFFF);
        weatherContainer.addView(weatherIcon);
        
        // Weather text (temp + condition)
        weatherText = Ui.text(this, 400, 15, 0xFFFFFFFF);
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(-2, -2);
        wtLp.leftMargin = dp(6);
        weatherContainer.addView(weatherText, wtLp);
        
        // AQI badge container
        aqiBadge = new LinearLayout(this);
        aqiBadge.setOrientation(LinearLayout.HORIZONTAL);
        aqiBadge.setGravity(Gravity.CENTER_VERTICAL);
        aqiBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        aqiBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams aqiLp = new LinearLayout.LayoutParams(-2, -2);
        aqiLp.leftMargin = dp(10);
        weatherContainer.addView(aqiBadge, aqiLp);
        
        aqiText = Ui.text(this, 600, 13, 0xFFFFFFFF);
        aqiBadge.addView(aqiText);
        
        // Holiday badge (initially hidden)
        holiday = Ui.text(this, 400, 14, 0xCCFFD600); // amber
        holiday.setVisibility(View.GONE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(6);
        tl.addView(holiday, hlp);
        
        // Crypto ticker - horizontal container (left aligned)
        cryptoContainer = new LinearLayout(this);
        cryptoContainer.setOrientation(LinearLayout.HORIZONTAL);
        cryptoContainer.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cryptoContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(-2, dp(36));
        ccp.topMargin = dp(4);
        tl.addView(cryptoContainer, ccp);
        
        content.addView(tl, at(W / 2, -2, side, dp(34)));

        // pills: Inputs, Wi-Fi, Settings
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[0] = pill(R.drawable.ic_input, "Inputs");
        pills[1] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((ViewGroup) pills[1]).getChildAt(1);
        pills[2] = pill(R.drawable.ic_settings, null);
        for (int i = 0; i < PILLS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) lp.leftMargin = dp(10);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(36);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // follower counters, right-aligned under the pills (display only, never focused)
        socialIds[0] = getString(R.string.youtube_channel).trim();
        socialIds[1] = getString(R.string.instagram_user).trim();
        socialIds[2] = getString(R.string.facebook_page).trim();
        // Override with layout values if set
        AppRepo.Layout layout = AppRepo.getLayout(this);
        if (!layout.ytChannel.isEmpty()) socialIds[0] = layout.ytChannel;
        if (!layout.igUser.isEmpty()) socialIds[1] = layout.igUser;
        if (!layout.fbPage.isEmpty()) socialIds[2] = layout.fbPage;
        int[] logos = {R.drawable.ic_youtube, R.drawable.ic_instagram, R.drawable.ic_facebook};
        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.VERTICAL);
        counters.setGravity(Gravity.END);
        for (int i = 0; i < 3; i++) {
            if (socialIds[i].isEmpty()) continue;
            hasSocial = true;
            LinearLayout sp = new LinearLayout(this);
            sp.setOrientation(LinearLayout.HORIZONTAL);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            sp.setBackground(Ui.rounded(0x33000000, dp(16), dp(1), 0x1FFFFFFF));
            sp.setPadding(dp(12), 0, dp(14), 0);
            ImageView logo = new ImageView(this);
            logo.setImageResource(logos[i]);
            sp.addView(logo, new LinearLayout.LayoutParams(dp(20), dp(20)));
            socialTexts[i] = Ui.text(this, 500, 14, 0xFFFFFFFF);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(8);
            sp.addView(socialTexts[i], tlp);
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, dp(32));
            plp2.topMargin = dp(8);
            counters.addView(sp, plp2);
            socialPills[i] = sp;
            showCount(i, prefs.getString("count_" + SOCIAL_KEYS[i], null));
        }
        FrameLayout.LayoutParams clpCounters = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clpCounters.topMargin = dp(36 + 36 + 2);
        clpCounters.rightMargin = side;
        content.addView(counters, clpCounters);

        // focused dock app name
        dockTitle = Ui.text(this, 250, 44, 0xF2FFFFFF);
        dockTitle.setLetterSpacing(-0.01f);
        content.addView(dockTitle, at(W - 2 * side, -2, side + dp(28), bandTop - dp(72)));

        // dock
        dockAdapter = new TileAdapter(dockCell, dockCardW, dockCardH, 0, false);
        dockRv = recycler(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false), dockAdapter);
        content.addView(dockRv, at(dockCell * COLS, dockCardH, side + dp(28) - (dockCell - dockCardW) / 2,
                bandTop + (bandH - dockCardH) / 2));

        // grid
        TextView appsHeader = Ui.text(this, 500, 15, 0x80FFFFFF);
        appsHeader.setText("Apps");
        appsHeader.setLetterSpacing(0.04f);
        content.addView(appsHeader, at(-2, -2, side + dp(2), gridTop - dp(30)));
        gridAdapter = new TileAdapter(gridCell, gridCardW, gridCardH, rowH - gridCardH, true);
        gridRv = recycler(new GridLayoutManager(this, COLS), gridAdapter);
        content.addView(gridRv, at(gridCell * COLS, rowH, side - (gridCell - gridCardW) / 2, gridTop));

        inputs = new InputsMenu(this);
        root.addView(inputs.panel);
    }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private RecyclerView recycler(RecyclerView.LayoutManager lm, TileAdapter a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        rv.setItemAnimator(null);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
        return rv;
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.rounded(0x1FFFFFFF, dp(18), dp(1), 0x26FFFFFF));
        int padH = label == null ? dp(9) : dp(14);
        p.setPadding(padH, 0, padH, 0);
        p.setAlpha(0.8f);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        if (label != null) {
            TextView t = Ui.text(this, 400, 14, 0xFFFFFFFF);
            t.setText(label);
            t.setMaxWidth(dp(170));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            p.addView(t, lp);
        }
        return p;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        // Late night used to fall into "evening"; give it its own greeting.
        String part;
        if (h >= 5 && h < 12) part = "Good morning";
        else if (h >= 12 && h < 17) part = "Good afternoon";
        else if (h >= 17 && h < 21) part = "Good evening";
        else part = "Good night";
        AppRepo.Layout layout = AppRepo.getLayout(this);
        String name = layout.greetingName.trim();
        greeting.setText(name.isEmpty() ? part : part + ", " + name);
        // This TV's owner asked for 24-hour time regardless of the system setting.
        clock.setText(DateFormat.format("H:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    // ---------------------------------------------------------------- wallpapers

    private boolean findWallpapers() {
        // bundled wallpapers are copied into the photo store on first run (see Wallpapers)
        wallpapers.clear();
        wallpapers.addAll(Wallpapers.userPhotos(this));
        return !wallpapers.isEmpty();
    }

    /** Decoded at screen size in RGB_565: about 1.8 MB each on a 720p TV. */
    private android.graphics.Bitmap loadWallpaper(Object src) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(src, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return decode(src, o);
    }

    private android.graphics.Bitmap decode(Object src, android.graphics.BitmapFactory.Options o) {
        if (src instanceof Integer) return android.graphics.BitmapFactory.decodeResource(getResources(), (Integer) src, o);
        return android.graphics.BitmapFactory.decodeFile(((java.io.File) src).getPath(), o);
    }

    /** The phone page added, replaced, deleted or asked to show a photo: update the rotation now. */
    @Override
    public void onWallpapersChanged(java.io.File added) {
        if (wpViews[0] == null) return;
        Object current = wallpapers.isEmpty() ? null : wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
        findWallpapers();
        if (wallpapers.isEmpty()) {
            for (ImageView v : wpViews) v.setImageDrawable(null);
            return;
        }
        int at = added != null ? wallpapers.indexOf(added) : wallpapers.indexOf(current);
        if (at < 0) {
            // the photo on screen was deleted: move on to the next one
            wpIdx = Math.max(0, wpIdx - 1) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else if (added != null) {
            wpIdx = (at - 1 + wallpapers.size()) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else {
            wpIdx = at;
        }
    }

    // ---------------------------------------------------------------- follower counters

    private final Runnable refreshSubs = () -> {
        main.removeCallbacks(this.refreshSubs);
        String[] ids = socialIds.clone();
        io.execute(() -> {
            String[] counts = {
                    ids[0].isEmpty() ? null : SocialCount.youtube(ids[0]),
                    ids[1].isEmpty() ? null : SocialCount.instagram(ids[1]),
                    ids[2].isEmpty() ? null : SocialCount.facebook(ids[2]),
            };
            main.post(() -> {
                boolean allOk = true;
                SharedPreferences.Editor e = prefs.edit();
                for (int i = 0; i < 3; i++) {
                    if (ids[i].isEmpty()) continue;
                    if (counts[i] == null) {
                        allOk = false;   // keep showing the last known number
                        continue;
                    }
                    e.putString("count_" + SOCIAL_KEYS[i], counts[i]);
                    showCount(i, counts[i]);
                }
                if (allOk) e.putLong("counts_at", System.currentTimeMillis());
                e.apply();
                // retry sooner after a failure (e.g. Wi-Fi still connecting after boot)
                if (hasWindowFocus()) main.postDelayed(this.refreshSubs, allOk ? SUBS_REFRESH_MS : 5 * 60_000L);
            });
        });
    };

    private void showCount(int i, String count) {
        if (socialPills[i] == null) return;
        socialPills[i].setVisibility(count == null ? View.GONE : View.VISIBLE);
        if (count != null) socialTexts[i].setText(count + " " + SOCIAL_UNITS[i]);
    }

    private void scheduleWallpaper() {
        main.removeCallbacks(nextWallpaper);
        // Don't rotate photos while now-playing art is shown
        if (Wallpapers.isNowPlaying()) return;
        if (wallpapers.size() < 2 || wpViews[0] == null) return;
        int secs = Math.max(5, getResources().getInteger(R.integer.wallpaper_seconds));
        main.postDelayed(nextWallpaper, secs * 1000L);
    }

    /** Fades the next wallpaper in over the current one, then frees the old bitmap. */
    private final Runnable nextWallpaper = () -> {
        if (wallpapers.isEmpty() || Wallpapers.isNowPlaying()) return;
        wpIdx = (wpIdx + 1) % wallpapers.size();
        prefs.edit().putInt("wallpaper", wpIdx).apply();
        Object src = wallpapers.get(wpIdx);
        io.execute(() -> {
            android.graphics.Bitmap bmp = loadWallpaper(src);
            if (bmp == null) return;
            main.post(() -> {
                ImageView front = wpViews[wpFront], back = wpViews[1 - wpFront];
                back.setImageBitmap(bmp);
                back.animate().alpha(1f).setDuration(2000).start();
                front.animate().alpha(0f).setDuration(2000).withEndAction(() -> front.setImageDrawable(null)).start();
                wpFront = 1 - wpFront;
                if (hasWindowFocus()) scheduleWallpaper();
            });
        });
    };

    // ---------------------------------------------------------------- now-playing wallpaper

    private TextView nowPlayingBadge;

    /** Shows the album art as a full-screen wallpaper, pausing photo rotation. */
    private void showNowPlaying(Bitmap art, String title, String artist, String pkg) {
        // Pause the normal wallpaper rotation
        main.removeCallbacks(nextWallpaper);
        // Fade out current wallpaper views
        for (ImageView v : wpViews) {
            v.animate().alpha(0f).setDuration(800).withEndAction(() -> v.setImageDrawable(null)).start();
        }
        // Create a full-screen ImageView for the album art
        ImageView artView = new ImageView(this);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artView.setAlpha(0f);
        root.addView(artView, 0, new FrameLayout.LayoutParams(-1, -1)); // behind scrim
        artView.setImageBitmap(art);
        artView.animate().alpha(1f).setDuration(800).start();
        // Store reference so we can remove it later
        root.setTag(artView);

        // Show a subtle badge near the clock
        String badgeLabel = "♪ Now Playing";
        if (pkg != null) {
            if (pkg.contains("youtube.tvmusic")) badgeLabel = "♪ YouTube Music";
            else if (pkg.contains("youtube.tv")) badgeLabel = "♪ YouTube";
        }
        if (nowPlayingBadge == null) {
            nowPlayingBadge = Ui.text(this, 300, 14, 0xCCFFD600); // amber
            nowPlayingBadge.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(6);
            // Add it after the weather line in the top-left stack
            ViewGroup tl = (ViewGroup) greeting.getParent();
            tl.addView(nowPlayingBadge, tl.getChildCount() - 1); // before weather
        }
        nowPlayingBadge.setText(badgeLabel + (title != null && !title.isEmpty() ? " · " + title : ""));
        nowPlayingBadge.setVisibility(View.VISIBLE);
    }

    /** Removes the now-playing wallpaper and resumes photo rotation. */
    private void hideNowPlaying() {
        // Remove the album art ImageView
        View artView = (View) root.getTag();
        if (artView != null) {
            artView.animate().alpha(0f).setDuration(800).withEndAction(() -> {
                root.removeView(artView);
                root.setTag(null);
            }).start();
        }
        // Hide the badge
        if (nowPlayingBadge != null) {
            nowPlayingBadge.setVisibility(View.GONE);
        }
        // Resume photo rotation
        scheduleWallpaper();
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String text = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                text = "Ethernet";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                text = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String ssid = wi == null ? null : unquote(wi.getSSID());
                if ((ssid == null || ssid.equals("<unknown ssid>")) && wi != null) {
                    try {
                        List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                        if (cfgs != null) for (WifiConfiguration cfg : cfgs) {
                            if (cfg.networkId == wi.getNetworkId()) ssid = unquote(cfg.SSID);
                        }
                    } catch (SecurityException ignored) {
                    }
                }
                if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) text = ssid;
            }
        }
        wifiText.setText(text);
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        // Intercept the remote's Settings/Menu key so it opens Android Settings
        // instead of being swallowed by the TCL quick-settings overlay.
        if (k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                launch(new Intent(Settings.ACTION_SETTINGS));
                return true;
            }
        }
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (inputs.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) inputs.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) inputs.move(1);
            else if (select && e.getRepeatCount() == 0) {
                Intent i = inputs.selected();
                inputs.close();
                if (i != null) launch(i);
            } else inputs.close();   // back, left, right
            return true;
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != Zone.DOCK && dockAdapter.getItemCount() > 0) {
                    zone = Zone.DOCK;
                    applyFocus(true);
                }
                break;
            default:
                if (e.getRepeatCount() == 0) activate();
        }
        return true;
    }

    private void up() {
        if (zone == Zone.DOCK) {
            zone = Zone.TOP;
        } else if (zone == Zone.GRID) {
            if (gridIdx >= COLS) gridIdx -= COLS;
            else if (dockAdapter.getItemCount() > 0) {
                zone = Zone.DOCK;
                dockIdx = Math.min(gridIdx, dockAdapter.getItemCount() - 1);
            } else zone = Zone.TOP;
        } else return;
        applyFocus(true);
    }

    private void down() {
        int ng = gridAdapter.getItemCount();
        if (zone == Zone.TOP) {
            if (dockAdapter.getItemCount() > 0) zone = Zone.DOCK;
            else if (ng > 0) zone = Zone.GRID;
            else return;
        } else if (zone == Zone.DOCK) {
            if (ng == 0) return;
            zone = Zone.GRID;
            gridIdx = Math.min(dockIdx, ng - 1);
        } else {
            if (gridIdx + COLS < ng) gridIdx += COLS;
            else if (gridIdx / COLS < (ng - 1) / COLS) gridIdx = ng - 1;
            else return;
        }
        applyFocus(true);
    }

    private void left() {
        if (zone == Zone.TOP && topIdx > 0) topIdx--;
        else if (zone == Zone.DOCK && dockIdx > 0) dockIdx--;
        else if (zone == Zone.GRID && gridIdx % COLS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == Zone.TOP && topIdx < PILLS - 1) topIdx++;
        else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount() - 1) dockIdx++;
        else if (zone == Zone.GRID && gridIdx % COLS < COLS - 1 && gridIdx + 1 < gridAdapter.getItemCount()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void activate() {
        if (zone == Zone.TOP) {
            if (topIdx == 0) {
                int[] loc = new int[2];
                pills[0].getLocationInWindow(loc);
                inputs.open(loc[0], loc[1] + pills[0].getHeight() + dp(8));
            } else if (topIdx == 1) {
                launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        } else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), gridAdapter.apps.get(gridIdx)));
        }
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void applyFocus(boolean animate) {
        prefs.edit().putInt("zone", zone.ordinal()).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();

        dockAdapter.setFocused(dockRv, zone == Zone.DOCK ? dockIdx : -1);
        gridAdapter.setFocused(gridRv, zone == Zone.GRID ? gridIdx : -1);
        for (int i = 0; i < PILLS; i++) {
            float a = zone == Zone.TOP && i == topIdx ? 1f : 0.8f;
            pills[i].animate().alpha(a).setDuration(150).start();
        }

        // scroll: dock/top at rest, grid rows bring the grid up one row at a time
        float shift = 0;
        if (zone == Zone.GRID) {
            int row = gridIdx / COLS;
            shift = gridTop - dp(78) + Math.max(0, row - 1) * rowH;
        }
        if (animate) content.animate().translationY(-shift).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        else content.setTranslationY(-shift);

        // dock title + glow follow the dock selection
        if (dockAdapter.getItemCount() > 0 && dockIdx != lastTitleApp) {
            lastTitleApp = dockIdx;
            AppRepo.App a = dockAdapter.apps.get(dockIdx);
            if (animate) {
                titleChanging = true;
                dockTitle.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    dockTitle.setText(a.label);
                    titleChanging = false;
                    dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
                }).start();
            } else {
                dockTitle.setText(a.label);
                dockTitle.setAlpha(titleAlpha());
            }
            setGlow(a.glow, animate);
        } else if (!titleChanging) {
            dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
        }
        glow.animate().alpha(zone == Zone.GRID ? 0f : zone == Zone.TOP ? 0.6f : 1f).setDuration(300).start();

        RectF target = targetRect();
        if (target != null) moveHighlight(target, animate && hlPlaced);
    }

    private float titleAlpha() {
        return zone == Zone.TOP ? 0.55f : 1f;
    }

    private RectF targetRect() {
        if (zone == Zone.TOP) {
            View p = pills[topIdx];
            float x = pillRow.getLeft() + p.getLeft(), y = pillRow.getTop() + p.getTop();
            if (p.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = p.getHeight() / 2f + o;
            return new RectF(x - o, y - o, x + p.getWidth() + o, y + p.getHeight() + o);
        }
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        float cx = rv.getLeft() + vh.itemView.getLeft() + card.getLeft() + card.getWidth() / 2f;
        float cy = rv.getTop() + vh.itemView.getTop() + card.getTop() + card.getHeight() / 2f;
        float hw = card.getWidth() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        float hh = card.getHeight() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        hlRadius = dp(15);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void moveHighlight(RectF to, boolean animate) {
        if (hlAnim != null) hlAnim.cancel();
        final RectF from = new RectF(hl);
        final float r0 = hlRadiusNow, r1 = hlRadius;
        if (!animate) {
            hl.set(to);
            placeHighlight(hl, r1);
            highlight.animate().alpha(1f).setDuration(200).start();
            hlPlaced = true;
            return;
        }
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(340);
        hlAnim.setInterpolator(new OvershootInterpolator(1.15f));
        hlAnim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            hl.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            placeHighlight(hl, r0 + (r1 - r0) * Math.min(1f, t));
        });
        hlAnim.start();
    }

    private void placeHighlight(RectF r, float radius) {
        hlRadiusNow = radius;
        highlight.set(r, radius);
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> paintGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = (c >>> 24) * 0x5C / 0xFF;
        glowBg.setColors(new int[]{(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb});
    }
}
