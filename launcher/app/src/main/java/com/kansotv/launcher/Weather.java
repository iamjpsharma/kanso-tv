package com.kansotv.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One approximate-location lookup plus one Open-Meteo request per launcher start.
 * No continuous location tracking, no API key, and no weather work while an app is open.
 */
final class Weather {

    interface Listener {
        void onWeather(String text);
    }

    private static final String TAG = "Weather";
    private static final long LOCATION_TIMEOUT_MS = 7_000;
    private static final int HTTP_TIMEOUT_MS = 8_000;

    private Weather() {
    }

    static void load(Context c, Handler main, ExecutorService io, Listener listener) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "coarse location not granted; weather hidden");
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        Location last = lastKnown(lm);
        if (last != null) {
            fetch(io, main, last, listener);
            return;
        }

        String provider = requestProvider(lm);
        if (provider == null) {
            Log.w(TAG, "no location provider available; weather hidden");
            return;
        }

        AtomicBoolean done = new AtomicBoolean();
        LocationListener[] holder = new LocationListener[1];
        Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                try {
                    lm.removeUpdates(holder[0]);
                } catch (Exception ignored) {
                }
                Log.w(TAG, "location timed out; weather hidden");
            }
        };
        holder[0] = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (!done.compareAndSet(false, true)) return;
                main.removeCallbacks(timeout);
                try {
                    lm.removeUpdates(this);
                } catch (Exception ignored) {
                }
                fetch(io, main, location, listener);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            // Deprecated on newer APIs but still the simplest one-shot request on API 28.
            lm.requestSingleUpdate(provider, holder[0], Looper.getMainLooper());
            main.postDelayed(timeout, LOCATION_TIMEOUT_MS);
        } catch (SecurityException | IllegalArgumentException e) {
            done.set(true);
            Log.w(TAG, "location request failed", e);
        }
    }

    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (!lm.isProviderEnabled(p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    private static String requestProvider(LocationManager lm) {
        for (String p : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) return p;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static void fetch(ExecutorService io, Handler main, Location location,
                              Listener listener) {
        io.execute(() -> {
            String weatherText = null;
            String aqiText = null;
            HttpURLConnection conn = null;
            try {
                // 1. Weather (temperature + condition)
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code&timezone=auto",
                        location.getLatitude(), location.getLongitude());
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "kansotv/1.0");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) body.append(line);
                    }
                    JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                    int temp = (int) Math.round(current.getDouble("temperature_2m"));
                    weatherText = temp + "°C · " + describe(current.optInt("weather_code", -1));
                }
                conn.disconnect();
                conn = null;

                // 2. Air quality (dedicated endpoint — returns real data)
                String aqiUrl = String.format(Locale.US,
                        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f"
                                + "&current=us_aqi,pm2_5,pm10&timezone=auto",
                        location.getLatitude(), location.getLongitude());
                conn = (HttpURLConnection) new URL(aqiUrl).openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "kansotv/1.0");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) body.append(line);
                    }
                    JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                    if (!current.isNull("us_aqi")) {
                        int aqi = current.getInt("us_aqi");
                        aqiText = "AQI " + aqi;
                    } else if (!current.isNull("pm2_5")) {
                        double pm = current.getDouble("pm2_5");
                        aqiText = "PM2.5 " + Math.round(pm) + " μg/m³";
                    } else if (!current.isNull("pm10")) {
                        double pm = current.getDouble("pm10");
                        aqiText = "PM10 " + Math.round(pm) + " μg/m³";
                    }
                }
                String text = weatherText + (aqiText != null ? "  •  " + aqiText : "");
                final String result = text;
                main.post(() -> listener.onWeather(result));
            } catch (Exception e) {
                Log.w(TAG, "weather request failed", e);
                final String result = weatherText; // still show temperature if AQI failed
                main.post(() -> listener.onWeather(result));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }
}