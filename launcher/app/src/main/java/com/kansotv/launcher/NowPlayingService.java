package com.kansotv.launcher;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * Listens for YouTube Music/YouTube TV playback notifications and publishes the album art
 * to the launcher so it can be shown as a live wallpaper.
 *
 * Target packages: com.google.android.youtube.tvmusic (YouTube Music)
 *                  com.google.android.youtube.tv (YouTube TV)
 * Also catches generic MediaStyle notifications from any music app.
 *
 * NOTE: Only works when the music app posts a MediaStyle notification.
 * YouTube Music does this; YouTube TV currently does not.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class NowPlayingService extends NotificationListenerService {

    private static final String TAG = "NowPlayingService";
    private static final String YTM_PACKAGE = "com.google.android.youtube.tvmusic";
    private static final String YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv";

    public static final String ACTION_ART_UPDATE = "com.kansotv.launcher.NOW_PLAYING_ART_UPDATE";
    public static final String ACTION_CLEAR = "com.kansotv.launcher.NOW_PLAYING_CLEAR";

    public static final String EXTRA_ART = "art";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_PACKAGE = "package";

    private MediaSessionManager mediaSessionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        Log.d(TAG, "onCreate - NotificationListenerService created");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "onListenerConnected - service is now bound to notification listener");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "onListenerDisconnected - service unbound");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();

        Log.d(TAG, "Notification posted from: " + pkg);

        // We care about YouTube Music, YouTube TV, and any MediaStyle notification with album art
        if (!YTM_PACKAGE.equals(pkg) && !YOUTUBE_TV_PACKAGE.equals(pkg) && !isMediaStyle(n)) return;

        Bitmap art = extractAlbumArt(n);
        if (art != null) {
            Log.d(TAG, "Album art received from " + pkg + " (" + art.getWidth() + "x" + art.getHeight() + ")");
            broadcastArt(art, getTitle(n), getArtist(n), pkg);
        } else {
            // No art in notification; try MediaController as fallback (works because notification listener
            // grants temporary MEDIA_CONTENT_CONTROL when a media notification is posted)
            tryController(pkg);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (YTM_PACKAGE.equals(pkg) || YOUTUBE_TV_PACKAGE.equals(pkg) || isMediaStyle(sbn.getNotification())) {
            // Check if any other media notification is still active
            if (!anyMediaPlaying()) {
                Log.d(TAG, "Playback stopped; clearing now-playing wallpaper");
                broadcastClear();
            }
        }
    }

    private void tryController(String pkg) {
        if (mediaSessionManager == null) return;
        for (MediaController mc : mediaSessionManager.getActiveSessions(null)) {
            if (YTM_PACKAGE.equals(pkg) || YOUTUBE_TV_PACKAGE.equals(pkg)) {
                if (pkg.equals(mc.getPackageName())) {
                    Bitmap art = mc.getMetadata() != null
                            ? mc.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                            : null;
                    if (art != null) {
                        broadcastArt(art,
                                mc.getMetadata().getDescription().getTitle().toString(),
                                mc.getMetadata().getDescription().getSubtitle() != null
                                        ? mc.getMetadata().getDescription().getSubtitle().toString()
                                        : "",
                                pkg);
                        return;
                    }
                }
            }
        }
    }

    private boolean anyMediaPlaying() {
        for (StatusBarNotification sbn : getActiveNotifications()) {
            if (isMediaStyle(sbn.getNotification())) return true;
        }
        return false;
    }

    private static boolean isMediaStyle(Notification n) {
        if (n == null) return false;
        // Check for media session token (present in MediaStyle notifications)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Bundle extras = n.extras;
            if (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                return true;
            }
        }
        return false;
    }

    private static Bitmap extractAlbumArt(Notification n) {
        // 1. Large icon
        if (n.largeIcon != null) return n.largeIcon;

        // 2. MediaStyle artwork from extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Bundle extras = n.extras;
            if (extras != null) {
                Bitmap art = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (art != null) return art;
                art = extras.getParcelable("android.mediaSession.metadata.ART");
                if (art != null) return art;
            }
        }
        return null;
    }

    private static String getTitle(Notification n) {
        Bundle ex = n.extras;
        if (ex != null) {
            CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
            if (t != null) return t.toString();
        }
        return "";
    }

    private static String getArtist(Notification n) {
        Bundle ex = n.extras;
        if (ex != null) {
            CharSequence a = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (a != null) return a.toString();
        }
        return "";
    }

    private void broadcastArt(Bitmap art, String title, String artist, String pkg) {
        Intent i = new Intent(ACTION_ART_UPDATE);
        i.putExtra(EXTRA_ART, art);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_ARTIST, artist);
        i.putExtra(EXTRA_PACKAGE, pkg);
        sendBroadcast(i);
    }

    private void broadcastClear() {
        sendBroadcast(new Intent(ACTION_CLEAR));
    }
}