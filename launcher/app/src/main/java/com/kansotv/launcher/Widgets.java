package com.kansotv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Zero-auth widgets: holidays, crypto, wallpapers.
 * One fetch per widget per interval; cached in SharedPreferences.
 */
final class Widgets {

    private static final String TAG = "Widgets";
    private static final long HTTP_TIMEOUT_MS = 8_000;

    private static final String PREFS = "widgets";
    private static final String KEY_HOLIDAY_NAME = "holiday_name";
    private static final String KEY_HOLIDAY_DATE = "holiday_date";
    private static final String KEY_CRYPTO_DATA = "crypto_data";
    private static final String KEY_CRYPTO_TIME = "crypto_time";
    private static final String KEY_WALLPAPER_URL = "wallpaper_url";
    private static final String KEY_WALLPAPER_TIME = "wallpaper_time";

    // Intervals
    private static final long HOLIDAY_INTERVAL_MS = 24 * 60 * 60 * 1000; // daily
    private static final long CRYPTO_INTERVAL_MS = 5 * 60 * 1000; // 5 min
    private static final long WALLPAPER_INTERVAL_MS = 30 * 60 * 1000; // 30 min

    private Widgets() {}

    interface HolidayListener { void onHoliday(String name, String date); }
    interface CryptoListener { void onCrypto(String text); }
    interface WallpaperListener { void onWallpaper(String url); }

    /** Load holiday for today (India). Uses cached if fresh, else fetches. */
    static void loadHoliday(Context c, Handler main, ExecutorService io, HolidayListener listener) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cachedName = p.getString(KEY_HOLIDAY_NAME, null);
        long cachedDate = p.getLong(KEY_HOLIDAY_DATE, 0);
        long now = System.currentTimeMillis();

        // Return cached if fresh (same day)
        if (cachedName != null && isSameDay(cachedDate, now)) {
            listener.onHoliday(cachedName, formatDate(cachedDate));
            return;
        }

        io.execute(() -> {
            String name = fetchHoliday();
            final String result = name;
            main.post(() -> {
                if (result != null) {
                    long ts = System.currentTimeMillis();
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_HOLIDAY_NAME, result)
                            .putLong(KEY_HOLIDAY_DATE, ts)
                            .apply();
                    listener.onHoliday(result, formatDate(ts));
                }
            });
        });
    }

    private static boolean isSameDay(long ts1, long ts2) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        return sdf.format(new Date(ts1)).equals(sdf.format(new Date(ts2)));
    }

    private static String formatDate(long ts) {
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ts));
    }

    private static String fetchHoliday() {
        HttpURLConnection conn = null;
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String[] parts = today.split("-");
            String url = "https://date.nager.at/api/v3/PublicHolidays/" + parts[0] + "/IN";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout((int) HTTP_TIMEOUT_MS);
            conn.setReadTimeout((int) HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "kansotv/1.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                }
                JSONArray arr = new JSONArray(body.toString());
                String todayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject h = arr.getJSONObject(i);
                    if (todayFmt.equals(h.optString("date"))) {
                        return h.optString("name", "Holiday");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "holiday fetch failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /** Load crypto prices (BTC and USDT/INR). */
    static void loadCrypto(Context c, Handler main, ExecutorService io, CryptoListener listener) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString(KEY_CRYPTO_DATA, null);
        long cachedTime = p.getLong(KEY_CRYPTO_TIME, 0);
        long now = System.currentTimeMillis();

        if (cached != null && now - cachedTime < CRYPTO_INTERVAL_MS) {
            listener.onCrypto(cached);
            return;
        }

        io.execute(() -> {
            String text = fetchCrypto();
            final String result = text;
            main.post(() -> {
                if (result != null) {
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_CRYPTO_DATA, result)
                            .putLong(KEY_CRYPTO_TIME, System.currentTimeMillis())
                            .apply();
                    listener.onCrypto(result);
                } else if (cached != null) {
                    listener.onCrypto(cached);
                }
            });
        });
    }

    private static String fetchCrypto() {
        HttpURLConnection conn = null;
        try {
            String url = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,tether&vs_currencies=inr,usd&include_24hr_change=true";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout((int) HTTP_TIMEOUT_MS);
            conn.setReadTimeout((int) HTTP_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "kansotv/1.0");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                }
                JSONObject o = new JSONObject(body.toString());
                StringBuilder sb = new StringBuilder();
                appendCoin(sb, o, "bitcoin", "₿");
                appendCoin(sb, o, "tether", "$");
                return sb.length() > 0 ? sb.toString() : null;
            }
        } catch (Exception e) {
            Log.w(TAG, "crypto fetch failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static void appendCoin(StringBuilder sb, JSONObject o, String id, String symbol) {
        try {
            JSONObject coin = o.getJSONObject(id);
            double inr = coin.getDouble("inr");
            double change = coin.optDouble("inr_24h_change", 0);
            String changeStr = change >= 0 ? " ▲" + String.format(Locale.US, "%.1f", change) + "%" : " ▼" + String.format(Locale.US, "%.1f", -change) + "%";
            if (sb.length() > 0) sb.append("  •  ");
            sb.append(symbol).append(" ₹").append(formatCompact(inr)).append(changeStr);
        } catch (Exception ignored) {}
    }

    private static String formatCompact(double v) {
        if (v >= 1_00_00_000) return String.format(Locale.US, "%.2fCr", v / 1_00_00_000);
        if (v >= 1_00_000) return String.format(Locale.US, "%.2fL", v / 1_00_000);
        if (v >= 1_000) return String.format(Locale.US, "%.1fK", v / 1_000);
        return String.format(Locale.US, "%.0f", v);
    }

    /** Load a fresh Unsplash wallpaper URL. */
    static void loadWallpaper(Context c, Handler main, ExecutorService io, WallpaperListener listener) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString(KEY_WALLPAPER_URL, null);
        long cachedTime = p.getLong(KEY_WALLPAPER_TIME, 0);
        long now = System.currentTimeMillis();

        if (cached != null && now - cachedTime < WALLPAPER_INTERVAL_MS) {
            listener.onWallpaper(cached);
            return;
        }

        io.execute(() -> {
            // Unsplash Source returns a redirect; we just use the source URL directly
            // Format: https://source.unsplash.com/1280x720/?nature,landscape,minimal
            String url = "https://source.unsplash.com/1280x720/?nature,landscape,minimal,architecture";
            final String result = url;
            main.post(() -> {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_WALLPAPER_URL, result)
                        .putLong(KEY_WALLPAPER_TIME, System.currentTimeMillis())
                        .apply();
                listener.onWallpaper(result);
            });
        });
    }
}