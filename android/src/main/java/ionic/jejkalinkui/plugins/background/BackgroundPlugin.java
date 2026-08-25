package ionic.jejkalinkui.plugins.background;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import android.app.Activity;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Typeface;

import android.util.Log;
import android.provider.Settings;
import android.net.Uri;
import android.Manifest;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.lang.ref.WeakReference;

import java.util.Calendar;

import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import com.getcapacitor.JSObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import java.util.List;
import java.util.ArrayList;

@CapacitorPlugin(name = "Background")
public class BackgroundPlugin extends Plugin {

    private static ScheduledExecutorService scheduler = null;
    private static ScheduledFuture<?> nextPollFuture = null;
    private static final Object SCHEDULER_LOCK = new Object();
    private static final AtomicBoolean pollInFlight = new AtomicBoolean(false);

    private static final String PREF_POLL_INTERVAL = "poll_interval_min";
    private static final String PREF_FAILURE_ALERT_AT = "failure_alert_at";
    private static final String PREF_PATIENT_USERNAME = "patient_username";
    private static final String PREF_CONSECUTIVE_FAILURES = "consecutive_failures";
    private static final String PREF_LAST_READING_MS = "last_reading_ms";
    private static final String PREF_COLLECTOR_ALERT_FIRED = "collector_alert_fired";
    private static final int PHASE_MINUTE = 2;
    private static final int PHASE_OFFSET_SEC = 30;

    private static volatile int pollIntervalMin = 5;
    private static volatile int failureAlertAt = 3;
    private static volatile String patientUsername = "jejka3006";
    private static volatile int consecutiveFailures = 0;
    private static volatile long lastReadingTsMs = 0;
    private static volatile long backoffUntilMs = 0;
    private static volatile boolean collectorAlertFired = false;

    private static volatile String accessToken = null;
    private static volatile String refreshToken = null;
    private static volatile Context appContext = null;
    private static WeakReference<BackgroundPlugin> pluginRef = null;

    /** Detached host so polls keep working after the Capacitor Bridge is destroyed. */
    private static final BackgroundPlugin HEADLESS = new BackgroundPlugin();

    private static final String TOKEN_PREFS = "bg_plugin_tokens";
    private static final String PREF_ACCESS = "access_token";
    private static final String PREF_REFRESH = "refresh_token";
    private static final String OAUTH_AUDIENCE = "carepartner.patient.ous";
    private static final int ACCESS_EXPIRY_BUFFER_SEC = 300;

    private static final String CHANNEL_NORMAL = "bg_plugin_normal_v2";
    private static final String CHANNEL_ALERT = "bg_plugin_alert";
    private static final String CHANNEL_CRITICAL = "bg_plugin_critical";
    private static final int NOTIFICATION_ID = ForegroundService.NOTIFICATION_ID;
    private static final int CRITICAL_NOTIFICATION_ID = 1002;
    private static final int STATUS_NOTIFICATION_ID = 1003;
    private static final int ALARM_NOTIFICATION_BASE = 2100;

    private static final String PREF_ALARM_LOW = "alarm_low";
    private static final String PREF_ALARM_HIGH = "alarm_high";
    private static final String PREF_ALARM_URGENT = "alarm_urgent_low";

    // User thresholds from Ionic Alarms settings (mmol/L)
    private static volatile double alarmLow = 3.9;
    private static volatile double alarmHigh = 10.0;
    private static volatile double alarmUrgentLow = 3.0;

    // State tracking for one-time alerts (true = alert already fired)
    private static boolean alertedReservoirLow = false;
    private static boolean alertedSensorExpiring = false;
    private static boolean alertedPumpBatteryLow = false;
    private static boolean alertedSensorBatteryLow = false;

    @Override
    public void load() {
        super.load();
        pluginRef = new WeakReference<>(this);
        try {
            appContext = getContext().getApplicationContext();
        } catch (Exception ignored) {
        }
        loadPersistedTokens();
        loadPersistedAlarmThresholds();
        loadPersistedCollectorConfig();
    }

    private void loadPersistedCollectorConfig() {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            android.content.SharedPreferences prefs = context
                    .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE);
            pollIntervalMin = Math.max(5, Math.min(15, prefs.getInt(PREF_POLL_INTERVAL, 5)));
            failureAlertAt = Math.max(1, Math.min(20, prefs.getInt(PREF_FAILURE_ALERT_AT, 3)));
            patientUsername = prefs.getString(PREF_PATIENT_USERNAME, "jejka3006");
            if (patientUsername == null || patientUsername.isEmpty()) {
                patientUsername = "jejka3006";
            }
            consecutiveFailures = prefs.getInt(PREF_CONSECUTIVE_FAILURES, 0);
            lastReadingTsMs = prefs.getLong(PREF_LAST_READING_MS, 0);
            collectorAlertFired = prefs.getBoolean(PREF_COLLECTOR_ALERT_FIRED, false);
        } catch (Exception e) {
            this.doLogg("loadPersistedCollectorConfig failed: " + e.getMessage());
        }
    }

    private void persistCollectorState() {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_POLL_INTERVAL, pollIntervalMin)
                    .putInt(PREF_FAILURE_ALERT_AT, failureAlertAt)
                    .putString(PREF_PATIENT_USERNAME, patientUsername)
                    .putInt(PREF_CONSECUTIVE_FAILURES, consecutiveFailures)
                    .putLong(PREF_LAST_READING_MS, lastReadingTsMs)
                    .putBoolean(PREF_COLLECTOR_ALERT_FIRED, collectorAlertFired)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void handleOnDestroy() {
        // Keep FGS + scheduler alive; only drop the bridge-bound reference.
        if (pluginRef != null && pluginRef.get() == this) {
            pluginRef.clear();
        }
        super.handleOnDestroy();
    }

    private static BackgroundPlugin host() {
        BackgroundPlugin live = pluginRef != null ? pluginRef.get() : null;
        return live != null ? live : HEADLESS;
    }

    private Context ctx() {
        try {
            Context c = getContext();
            if (c != null) {
                return c;
            }
        } catch (Exception ignored) {
        }
        BackgroundPlugin live = pluginRef != null ? pluginRef.get() : null;
        if (live != null && live != this) {
            try {
                Context c = live.getContext();
                if (c != null) {
                    return c;
                }
            } catch (Exception ignored) {
            }
        }
        return appContext;
    }

    private void safeNotify(String event, JSObject data) {
        BackgroundPlugin live = pluginRef != null ? pluginRef.get() : null;
        if (live == null) {
            return;
        }
        try {
            live.notifyListeners(event, data);
        } catch (Exception e) {
            Log.d("BackgroundPlugin", "safeNotify " + event + ": " + e.getMessage());
        }
    }

    private void loadPersistedAlarmThresholds() {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            android.content.SharedPreferences prefs = context
                    .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE);
            alarmLow = prefs.getFloat(PREF_ALARM_LOW, 3.9f);
            alarmHigh = prefs.getFloat(PREF_ALARM_HIGH, 10.0f);
            alarmUrgentLow = prefs.getFloat(PREF_ALARM_URGENT, 3.0f);
        } catch (Exception e) {
            this.doLogg("loadPersistedAlarmThresholds failed: " + e.getMessage());
        }
    }

    private void loadPersistedTokens() {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            android.content.SharedPreferences prefs = context
                    .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE);
            String access = prefs.getString(PREF_ACCESS, null);
            String refresh = prefs.getString(PREF_REFRESH, null);
            if (access != null && !access.isEmpty()) {
                accessToken = access;
            }
            if (refresh != null && !refresh.isEmpty()) {
                refreshToken = refresh;
            }
            if (accessToken != null) {
                this.doLogg("loadPersistedTokens: restored session");
            }
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "loadPersistedTokens failed", e);
        }
    }

    private void persistTokens() {
        try {
            if (accessToken == null && refreshToken == null) {
                return;
            }
            Context context = ctx();
            if (context == null) {
                return;
            }
            android.content.SharedPreferences.Editor editor = context
                    .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
                    .edit();
            if (accessToken != null) {
                editor.putString(PREF_ACCESS, accessToken);
            }
            if (refreshToken != null) {
                editor.putString(PREF_REFRESH, refreshToken);
            }
            editor.apply();
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "persistTokens failed", e);
        }
    }

    private void clearPersistedTokens() {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {
        }
    }

    private boolean isAccessTokenExpiringSoon(String token, int bufferSeconds) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return true;
            }
            String payload = parts[1];
            int pad = (4 - payload.length() % 4) % 4;
            for (int i = 0; i < pad; i++) {
                payload += "=";
            }
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!obj.has("exp")) {
                return true;
            }
            long exp = obj.getLong("exp");
            long now = System.currentTimeMillis() / 1000L;
            return exp < (now + bufferSeconds);
        } catch (Exception e) {
            return true;
        }
    }

    /** Create notification channels (does not start the FGS). */
    private void ensureNotificationChannels() {
        Context context = ctx();
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        // Delete old channel so it doesn't linger
        manager.deleteNotificationChannel("bg_plugin_normal");

        NotificationChannel normalChannel = new NotificationChannel(
                CHANNEL_NORMAL,
                "CareLink Monitoring",
                NotificationManager.IMPORTANCE_DEFAULT);
        normalChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        normalChannel.setShowBadge(true);
        normalChannel.setDescription("Glucose monitoring updates visible on lock screen");
        normalChannel.enableLights(true);
        manager.createNotificationChannel(normalChannel);

        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT,
                "CareLink Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alertChannel.setShowBadge(true);
        manager.createNotificationChannel(alertChannel);

        NotificationChannel criticalChannel = new NotificationChannel(
                CHANNEL_CRITICAL,
                "CareLink Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        criticalChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        criticalChannel.setShowBadge(true);
        criticalChannel.setBypassDnd(true);
        criticalChannel.enableVibration(true);
        criticalChannel.setVibrationPattern(new long[] { 0, 500, 200, 500, 200, 500 });
        manager.createNotificationChannel(criticalChannel);
    }

    /** Called from {@link ForegroundService} after startForeground. */
    public static void startBackgroundPolling(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
        BackgroundPlugin h = host();
        h.loadPersistedTokens();
        h.loadPersistedAlarmThresholds();
        h.loadPersistedCollectorConfig();
        h.ensureNotificationChannels();

        synchronized (SCHEDULER_LOCK) {
            if (scheduler != null && !scheduler.isShutdown()) {
                h.scheduleNextPoll(0);
                return;
            }
            Log.i("BackgroundPlugin", "Starting phase-aligned poll scheduler");
            scheduler = Executors.newSingleThreadScheduledExecutor();
            h.scheduleNextPoll(0);
        }
    }

    public static void stopBackgroundPolling() {
        synchronized (SCHEDULER_LOCK) {
            if (nextPollFuture != null) {
                nextPollFuture.cancel(false);
                nextPollFuture = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
                Log.i("BackgroundPlugin", "Background poll scheduler stopped");
            }
        }
    }

    private void scheduleNextPoll(long delayMs) {
        synchronized (SCHEDULER_LOCK) {
            if (scheduler == null || scheduler.isShutdown()) {
                return;
            }
            if (nextPollFuture != null) {
                nextPollFuture.cancel(false);
            }
            if (delayMs < 0) {
                delayMs = computeNextPollDelayMs();
            }
            delayMs = Math.max(1000L, delayMs);
            nextPollFuture = scheduler.schedule(() -> {
                try {
                    pollTick();
                } catch (Exception e) {
                    Log.e("BackgroundPlugin", "Poll tick failed", e);
                } finally {
                    scheduleNextPoll(-1);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private long computeNextPollDelayMs() {
        long now = System.currentTimeMillis();
        if (backoffUntilMs > now) {
            return backoffUntilMs - now;
        }
        if (lastReadingTsMs > 0) {
            long target = lastReadingTsMs + pollIntervalMin * 60_000L + PHASE_OFFSET_SEC * 1000L;
            if (target > now) {
                return target - now;
            }
        }
        return nextPhaseSlotMs(now) - now;
    }

    /** Next sensor-aligned slot (:02 + 30s within poll interval). */
    private long nextPhaseSlotMs(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        for (int i = 0; i < 24 * 60; i++) {
            if (i > 0) {
                cal.add(Calendar.MINUTE, 1);
            }
            int minute = cal.get(Calendar.MINUTE);
            if ((minute - PHASE_MINUTE + 60) % pollIntervalMin != 0) {
                continue;
            }
            cal.set(Calendar.SECOND, PHASE_OFFSET_SEC);
            cal.set(Calendar.MILLISECOND, 0);
            long target = cal.getTimeInMillis();
            if (target > now + 2000L) {
                return target;
            }
        }
        return now + pollIntervalMin * 60_000L + PHASE_OFFSET_SEC * 1000L;
    }

    private void recordPollSuccess(long readingTsMs) {
        consecutiveFailures = 0;
        backoffUntilMs = 0;
        collectorAlertFired = false;
        if (readingTsMs > 0) {
            lastReadingTsMs = readingTsMs;
        }
        persistCollectorState();
    }

    private void recordPollFailure(String reason) {
        consecutiveFailures++;
        int exp = Math.min(consecutiveFailures, 6);
        int backoffMin = Math.min(pollIntervalMin * (1 << exp), 60);
        backoffUntilMs = System.currentTimeMillis() + backoffMin * 60_000L;
        persistCollectorState();
        this.doLogg("Poll failure #" + consecutiveFailures + " backoff " + backoffMin + "m: " + reason);
        if (consecutiveFailures >= failureAlertAt && !collectorAlertFired) {
            collectorAlertFired = true;
            persistCollectorState();
            fireCollectorFailureAlert(consecutiveFailures);
        }
    }

    private void fireCollectorFailureAlert(int failures) {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ALERT)
                    .setContentTitle("Collector failing")
                    .setContentText(failures + " consecutive CareLink failures. Open the app to check session.")
                    .setSmallIcon(getNotificationIcon(context))
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            nm.notify(STATUS_NOTIFICATION_ID + 1, builder.build());
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "fireCollectorFailureAlert failed", e);
        }
    }

    /** Notification Refresh action / manual poke. */
    public static void requestImmediatePoll() {
        new Thread(() -> {
            try {
                host().pollTick();
            } catch (Exception e) {
                Log.e("BackgroundPlugin", "Immediate poll failed", e);
            }
        }, "carelink-immediate-poll").start();
    }

    private void pollTick() {
        if (!pollInFlight.compareAndSet(false, true)) {
            this.doLogg("Polling: skip overlapping tick");
            return;
        }
        try {
            if (accessToken != null && refreshToken != null
                    && !isAccessTokenExpiringSoon(accessToken, ACCESS_EXPIRY_BUFFER_SEC)) {
                this.doLogg("Polling: access token valid, fetching data");
                this.getData();
            } else {
                this.doLogg("Polling: access token expiring or missing, refreshing");
                refreshTokenSilently();
            }
        } finally {
            pollInFlight.set(false);
        }
    }

    private Bitmap createGlucoseIcon(double sgValue) {
        int size = 128;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Background circle color based on glucose level (user thresholds)
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (sgValue > 0 && sgValue < alarmLow) {
            bgPaint.setColor(Color.parseColor("#C2255C")); // red - low
        } else if (sgValue > 0 && sgValue <= alarmHigh) {
            bgPaint.setColor(Color.parseColor("#0E9B8A")); // green - in range
        } else if (sgValue > 0) {
            bgPaint.setColor(Color.parseColor("#C77C1E")); // orange - high
        } else {
            bgPaint.setColor(Color.parseColor("#6C757D"));
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        // Glucose text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        String text = String.format(java.util.Locale.US, "%.1f", sgValue);
        textPaint.setTextSize(text.length() > 3 ? 38f : 44f);
        float yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(text, size / 2f, yPos, textPaint);

        return bitmap;
    }

    private String getTrendArrow(JSONObject json) {
        try {
            String trend = json.optString("lastSGTrend", "");
            switch (trend) {
                case "UP":
                    return " \u2197";
                case "UP_DOUBLE":
                    return " \u2191\u2191";
                case "DOWN":
                    return " \u2198";
                case "DOWN_DOUBLE":
                    return " \u2193\u2193";
                default:
                    return " \u2192";
            }
        } catch (Exception e) {
            return "";
        }
    }

    @PluginMethod
    public void showNotificationFromIonic(PluginCall call) {
        try {
            JSObject data = call.getData();
            JSONObject json = new JSONObject(data.toString());

            if (json != null && json.has("sgs")) {
                JSONArray originalSgs = json.optJSONArray("sgs");
                if (originalSgs != null) {
                    JSONArray cleanedSortedSgs = cleanAndSortSgs(originalSgs);
                    json.put("sgs", cleanedSortedSgs);
                }
            }

            this.doLogg(json.toString());
            JSONObject last = getLastGlicemia(json);
            String since = getTimeSinceLastGS(json);
            double sgValue = Double.parseDouble(last.getString("sg"));
            String trend = json.optString("lastSGTrend", "");

            boolean sensorConnected = true;
            if (json.has("conduitSensorInRange")) {
                sensorConnected = json.optBoolean("conduitSensorInRange", false);
            }

            long readingTs = last.optLong("timestamp", 0);
            boolean hasValidReading = sgValue > 0 && readingTs > 0;
            boolean playSound = hasValidReading && (sgValue < alarmLow || sgValue > alarmHigh);

            if (!sensorConnected || !hasValidReading) {
                showStaleOrDisconnected(sensorConnected ? "No fresh reading" : "Sensor disconnected");
            } else {
                String trendArrow = getTrendArrow(json);
                String title = last.getString("sg") + " mmol/L" + trendArrow + "  \u00b7  " + since.trim();
                String statusText = "";
                if (sgValue < alarmUrgentLow)
                    statusText = "Urgent low";
                else if (sgValue < alarmLow)
                    statusText = "Low glucose";
                else if (sgValue > alarmHigh)
                    statusText = "High glucose";
                // Build body with status + details
                StringBuilder body = new StringBuilder();
                if (!statusText.isEmpty()) {
                    body.append(statusText);
                }
                // Details section with top padding
                StringBuilder details = new StringBuilder();
                try {
                    JSONObject ai = json.optJSONObject("activeInsulin");
                    Log.i("BackgroundPlugin", "activeInsulin from Ionic: " + (ai != null ? ai.toString() : "null"));
                    if (ai != null) {
                        double amount = ai.optDouble("amount", 0);
                        details.append("Active insulin: ")
                                .append(String.format(java.util.Locale.US, "%.1f", amount)).append(" U");
                    }
                } catch (Exception ignored) {
                }
                double reservoir = json.optDouble("reservoirRemainingUnits", -1);
                if (reservoir >= 0) {
                    if (details.length() > 0)
                        details.append("\n");
                    details.append("Reservoir: ")
                            .append(String.format(java.util.Locale.US, "%.0f", reservoir)).append(" U");
                }
                boolean isTempBasal = json.optBoolean("isTempBasal", false);
                if (isTempBasal) {
                    if (details.length() > 0)
                        details.append("\n");
                    details.append("Temp basal: active");
                }
                if (details.length() > 0) {
                    if (body.length() > 0)
                        body.append("\n");
                    body.append(details);
                }
                showNotification(title, body.toString().trim(), sgValue, playSound);
                updateWidget(last.getString("sg"), trendArrow, since.trim(), statusText, sgValue, readingTs);
            }

            // Check for one-time status alerts
            checkStatusAlerts(json);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            e.printStackTrace();
            call.reject("Failed to show notification: " + e.getMessage());
        }
    }

    private void showNotification(String title, String body, double sgValue, boolean playSound) {
        try {
            Context context = ctx();
            if (context == null) {
                this.doLogg("showNotification: context is null, skipping");
                return;
            }

            NotificationManager notificationManager = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);

            String channelId = playSound ? CHANNEL_ALERT : CHANNEL_NORMAL;
            ensureNotificationChannels();

            // Open app on tap
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Refresh action button
            Intent refreshIntent = new Intent("ionic.jejkalinkui.ACTION_REFRESH");
            refreshIntent.setPackage(context.getPackageName());
            PendingIntent refreshPending = PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Color based on glucose level (user thresholds)
            int accentColor;
            if (sgValue > 0 && sgValue < alarmLow) {
                accentColor = Color.parseColor("#C2255C");
            } else if (sgValue > 0 && sgValue <= alarmHigh) {
                accentColor = Color.parseColor("#0E9B8A");
            } else if (sgValue > 0) {
                accentColor = Color.parseColor("#C77C1E");
            } else {
                accentColor = Color.parseColor("#6C757D");
            }

            // Large icon with glucose value
            Bitmap largeIcon = createGlucoseIcon(sgValue);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(getNotificationIcon(context))
                    .setLargeIcon(largeIcon)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(!playSound)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setColor(accentColor)
                    .addAction(android.R.drawable.ic_popup_sync, "Refresh", refreshPending);

            // Expanded style with extra info
            if (body != null && !body.isEmpty()) {
                builder.setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(body)
                        .setBigContentTitle(title));
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build());
            this.doLogg("showNotification: notified OK");

            // When Ionic bridge is gone, deliver a critical backup for lows (AlarmsService can't run).
            boolean bridgeAlive = pluginRef != null && pluginRef.get() != null;
            if (!bridgeAlive && sgValue > 0 && sgValue < alarmLow) {
                String critTitle = sgValue < alarmUrgentLow ? "Urgent low" : "Low glucose";
                showCriticalNotification(context, notificationManager, critTitle, title, pendingIntent);
            }
        } catch (Exception e) {
            this.doLogg("showNotification CRASHED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showCriticalNotification(Context context, NotificationManager notificationManager,
            String critTitle, String detail, PendingIntent tapIntent) {
        NotificationCompat.Builder critical = new NotificationCompat.Builder(context, CHANNEL_CRITICAL)
                .setContentTitle(critTitle)
                .setContentText(detail)
                .setSmallIcon(getNotificationIcon(context))
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(tapIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setColor(Color.parseColor("#C2255C"))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setFullScreenIntent(tapIntent, true);

        notificationManager.notify(CRITICAL_NOTIFICATION_ID, critical.build());
    }

    /**
     * Fire a rule-based alarm from Ionic AlarmsService (user thresholds / projection / stale).
     * critical=true → DND-bypass channel.
     */
    @PluginMethod
    public void fireAlarmAlert(PluginCall call) {
        try {
            Context context = ctx();
            if (context == null) {
                call.reject("No context");
                return;
            }
            String title = call.getString("title", "Alarm");
            String body = call.getString("body", "");
            boolean critical = call.getBoolean("critical", false);
            String rule = call.getString("rule", "alarm");

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String channelId = critical ? CHANNEL_CRITICAL : CHANNEL_ALERT;
            int notifId = ALARM_NOTIFICATION_BASE + Math.abs(rule.hashCode() % 80);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setContentTitle(title)
                    .setContentText(body == null || body.isEmpty() ? title : body)
                    .setSmallIcon(getNotificationIcon(context))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setPriority(critical ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL);

            if (body != null && !body.isEmpty()) {
                builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(title));
            }
            if (critical) {
                builder.setFullScreenIntent(pendingIntent, true);
                builder.setColor(Color.parseColor("#C2255C"));
            }

            nm.notify(notifId, builder.build());
            this.doLogg("fireAlarmAlert: " + rule + " critical=" + critical);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("fireAlarmAlert failed: " + e.getMessage());
        }
    }

    /** Persist / apply user alarm thresholds from Ionic Settings/Alarms. */
    @PluginMethod
    public void setAlarmThresholds(PluginCall call) {
        try {
            Double low = call.getDouble("low");
            Double high = call.getDouble("high");
            Double urgent = call.getDouble("urgentLow");
            if (low != null)
                alarmLow = low;
            if (high != null)
                alarmHigh = high;
            if (urgent != null)
                alarmUrgentLow = urgent;

            Context context = ctx();
            if (context == null) {
                call.reject("No context");
                return;
            }
            android.content.SharedPreferences prefs = context
                    .getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putFloat(PREF_ALARM_LOW, (float) alarmLow)
                    .putFloat(PREF_ALARM_HIGH, (float) alarmHigh)
                    .putFloat(PREF_ALARM_URGENT, (float) alarmUrgentLow)
                    .apply();

            this.doLogg("setAlarmThresholds: low=" + alarmLow + " high=" + alarmHigh + " urgent=" + alarmUrgentLow);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("setAlarmThresholds failed: " + e.getMessage());
        }
    }

    /** Persist collector settings from Ionic Settings. Reschedules polls when interval changes. */
    @PluginMethod
    public void setCollectorConfig(PluginCall call) {
        try {
            Integer interval = call.getInt("pollIntervalMin");
            Integer alertAt = call.getInt("failureAlertAt");
            String patient = call.getString("patientUsername");
            if (interval != null) {
                pollIntervalMin = Math.max(5, Math.min(15, interval));
            }
            if (alertAt != null) {
                failureAlertAt = Math.max(1, Math.min(20, alertAt));
            }
            if (patient != null && !patient.trim().isEmpty()) {
                patientUsername = patient.trim();
            }
            persistCollectorState();
            this.doLogg("setCollectorConfig: interval=" + pollIntervalMin + "m alertAt="
                    + failureAlertAt + " patient=" + patientUsername);
            scheduleNextPoll(0);
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("setCollectorConfig failed: " + e.getMessage());
        }
    }

    private void checkStatusAlerts(JSONObject json) {
        try {
            java.util.List<String> messages = new java.util.ArrayList<>();

            // Reservoir < 20 units
            double reservoir = json.optDouble("reservoirRemainingUnits", -1);
            if (reservoir >= 0 && reservoir < 20) {
                if (!alertedReservoirLow) {
                    alertedReservoirLow = true;
                    messages.add(String.format(java.util.Locale.US, "%.0f units left in reservoir", reservoir));
                }
            } else {
                alertedReservoirLow = false;
            }

            // Sensor duration < 1 day (1440 minutes)
            int sensorMinutes = json.optInt("sensorDurationMinutes", -1);
            if (sensorMinutes >= 0 && sensorMinutes < 1440) {
                if (!alertedSensorExpiring) {
                    alertedSensorExpiring = true;
                    int h = sensorMinutes / 60;
                    int m = sensorMinutes % 60;
                    messages.add("Sensor expires in " + h + "h " + m + "m");
                }
            } else {
                alertedSensorExpiring = false;
            }

            // Pump battery < 20%
            int pumpBattery = json.optInt("conduitBatteryLevel", -1);
            if (pumpBattery >= 0 && pumpBattery < 20) {
                if (!alertedPumpBatteryLow) {
                    alertedPumpBatteryLow = true;
                    messages.add("Pump battery: " + pumpBattery + "%");
                }
            } else {
                alertedPumpBatteryLow = false;
            }

            // Sensor battery < 20%
            int sensorBattery = json.optInt("gstBatteryLevel", -1);
            if (sensorBattery >= 0 && sensorBattery < 20) {
                if (!alertedSensorBatteryLow) {
                    alertedSensorBatteryLow = true;
                    messages.add("Sensor battery: " + sensorBattery + "%");
                }
            } else {
                alertedSensorBatteryLow = false;
            }

            if (!messages.isEmpty()) {
                StringBuilder body = new StringBuilder();
                for (String msg : messages) {
                    if (body.length() > 0)
                        body.append("\n");
                    body.append(msg);
                }
                showStatusAlert(body.toString());
            }
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "checkStatusAlerts error", e);
        }
    }

    private void showStatusAlert(String body) {
        Context context = ctx();
        if (context == null) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setContentTitle("Status alert")
                .setContentText(body)
                .setSmallIcon(getNotificationIcon(context))
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(Color.parseColor("#C77C1E"))
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE);

        if (body.contains("\n")) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        }

        nm.notify(STATUS_NOTIFICATION_ID, builder.build());
    }

    @PluginMethod
    public void updateNotification(PluginCall call) {
        String content = call.getString("content");
        if (content == null) {
            call.reject("Missing 'content' parameter");
            return;
        }

        this.showNotification(content, "", 0, false);
    }

    @PluginMethod
    public void requestFullScreenPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            if (!nm.canUseFullScreenIntent()) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                JSObject result = new JSObject();
                result.put("granted", false);
                result.put("opened_settings", true);
                call.resolve(result);
                return;
            }
        }
        JSObject result = new JSObject();
        result.put("granted", true);
        call.resolve(result);
    }

    private PluginCall pendingPermissionCall = null;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 12345;

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        Activity activity = getActivity();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

                pendingPermissionCall = call;
                ActivityCompat.requestPermissions(activity,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        NOTIFICATION_PERMISSION_REQUEST_CODE);
                return; // wait for onRequestPermissionsResult
            }
        }

        // Already granted or pre-TIRAMISU
        JSObject result = new JSObject();
        result.put("granted", true);
        call.resolve(result);
    }

    @Override
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE && pendingPermissionCall != null) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            JSObject result = new JSObject();
            result.put("granted", granted);
            pendingPermissionCall.resolve(result);
            pendingPermissionCall = null;
        }
    }

    @PluginMethod
    public void hasNotificationPermission(PluginCall call) {
        boolean granted = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Activity activity = getActivity();
            granted = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }

    @PluginMethod
    public void setTokens(PluginCall call) {
        String access = call.getString("accessToken");
        String refresh = call.getString("refreshToken");

        if (access == null || refresh == null) {
            call.reject("Missing token(s)");
            return;
        }

        accessToken = access;
        refreshToken = refresh;
        this.persistTokens();
        this.doLogg("setTokens: tokens updated from Ionic, fetching data immediately...");

        // Immediately fetch data with new tokens to update notification + widget
        new Thread(() -> {
            try {
                this.getData();
            } catch (Exception e) {
                this.doLogg("setTokens: getData after token update failed: " + e.getMessage());
            }
        }).start();

        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void startPolling(PluginCall call) {
        Context context = ctx();
        if (context == null) {
            call.reject("No context");
            return;
        }

        pluginRef = new WeakReference<>(this);
        appContext = context.getApplicationContext();
        ensureNotificationChannels();

        Intent intent = new Intent(context, ForegroundService.class);
        intent.setAction(ForegroundService.ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
            this.doLogg("startPolling: ForegroundService start requested");
        } catch (Exception e) {
            this.doLogg("startPolling: FGS start failed, falling back to in-process scheduler: " + e.getMessage());
            startBackgroundPolling(context);
        }

        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    @PluginMethod
    public void stopPolling(PluginCall call) {
        Context context = ctx();
        if (context != null) {
            Intent intent = new Intent(context, ForegroundService.class);
            intent.setAction(ForegroundService.ACTION_STOP);
            try {
                context.startService(intent);
            } catch (Exception e) {
                Log.w("BackgroundPlugin", "stopPolling startService STOP failed", e);
            }
            try {
                context.stopService(new Intent(context, ForegroundService.class));
            } catch (Exception e) {
                Log.w("BackgroundPlugin", "stopPolling stopService failed", e);
            }
        }
        stopBackgroundPolling();

        JSObject result = new JSObject();
        result.put("stopped", true);
        call.resolve(result);
    }

    @PluginMethod
    public void unlockOrientation(PluginCall call) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> activity
                    .setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        }
        call.resolve();
    }

    @PluginMethod
    public void lockPortrait(PluginCall call) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> activity
                    .setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }
        call.resolve();
    }

    @PluginMethod
    public void getTokens(PluginCall call) {
        JSObject result = new JSObject();
        result.put("accessToken", accessToken != null ? accessToken : "");
        result.put("refreshToken", refreshToken != null ? refreshToken : "");
        call.resolve(result);
    }

    @PluginMethod
    public void requestTokenRefresh(PluginCall call) {
        new Thread(() -> {
            boolean ok;
            if (accessToken != null && refreshToken != null
                    && !isAccessTokenExpiringSoon(accessToken, ACCESS_EXPIRY_BUFFER_SEC)) {
                ok = true;
                this.doLogg("requestTokenRefresh: access token still valid");
            } else {
                ok = refreshTokenSilently();
            }
            JSObject result = new JSObject();
            result.put("success", ok);
            result.put("accessToken", accessToken != null ? accessToken : "");
            result.put("refreshToken", refreshToken != null ? refreshToken : "");
            call.resolve(result);
        }).start();
    }

    private boolean refreshTokenSilently() {
        if (refreshToken == null) {
            this.doLogg("No refresh token available.");
            recordPollFailure("no refresh token");
            return false;
        }

        try {
            URL url = new URL("https://carelink-login.minimed.eu/oauth/token");

            this.doLogg("Polling: preparing token refresh");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            StringBuilder body = new StringBuilder();
            body.append("grant_type=refresh_token");
            body.append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"));
            body.append("&client_id=PeAhkbhQWlQRxJiQxWfcFBiGus1lxfe9");
            body.append("&redirect_uri=").append(URLEncoder.encode("com.medtronic.carepartner:/sso", "UTF-8"));
            body.append("&audience=").append(URLEncoder.encode(OAUTH_AUDIENCE, "UTF-8"));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes());
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                response.append(line);
            reader.close();

            this.doLogg("Polling: success token refresh: " + responseCode);
            if (responseCode == 200) {
                try {
                    this.doLogg("Polling: parsing response");
                    JSONObject json = new JSONObject(response.toString());

                    this.doLogg("Polling: success tokens: " + json.toString());
                    accessToken = json.getString("access_token");
                    refreshToken = json.optString("refresh_token", refreshToken);
                    this.persistTokens();

                    this.doLogg("Polling: token refresh OK");
                    JSObject result = new JSObject();
                    result.put("access_token", accessToken);
                    result.put("refresh_token", refreshToken);
                    safeNotify("onTokenRefreshed", result);
                    this.getData(); // fetch data after refresh
                    return true;
                } catch (Exception e) {
                    this.doLogg("Polling: error parsing response: " + e.getMessage());
                    return false;
                }

            } else {
                this.doLogg("Polling: refresh FAILED status=" + responseCode + " body="
                        + response.toString().substring(0, Math.min(200, response.length())));
                Log.e("BackgroundPlugin", "Refresh failed: " + response.toString());

                if (accessToken != null && !isAccessTokenExpiringSoon(accessToken, 60)) {
                    this.doLogg("Polling: refresh failed, trying getData with existing access token...");
                    this.getData();
                    return true;
                }

                recordPollFailure("token refresh HTTP " + responseCode);
                JSObject error = new JSObject();
                error.put("error", "token_refresh_failed");
                error.put("status", responseCode);
                error.put("message", response.toString().substring(0, Math.min(200, response.length())));
                safeNotify("onTokenRefreshFailed", error);

                showNotification("Session expired", "Open the app to sign in again", 0, false);
                updateWidget("--", "", "Session expired", "", 0);
                return false;
            }

        } catch (Exception e) {
            this.doLogg("Polling: exception: " + e.getMessage());
            Log.e("BackgroundPlugin", "Exception in background refresh", e);
            return false;
        }
    }

    private void updateWidget(String glucoseValue, String trendArrow, String timeSince, String status, double sgValue) {
        updateWidget(glucoseValue, trendArrow, timeSince, status, sgValue, 0L);
    }

    private void updateWidget(String glucoseValue, String trendArrow, String timeSince, String status,
            double sgValue, long readingTsMs) {
        try {
            Context context = ctx();
            if (context == null) {
                return;
            }
            android.content.SharedPreferences prefs = context.getSharedPreferences("glucose_widget_prefs",
                    Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = prefs.edit()
                    .putString("glucose_value", glucoseValue)
                    .putString("trend_arrow", trendArrow)
                    .putString("time_since", timeSince)
                    .putString("status", status)
                    .putString("sg_double", String.valueOf(sgValue));
            if (readingTsMs > 0 && sgValue > 0) {
                ed.putLong("last_good_reading_ms", readingTsMs);
            }
            ed.apply();

            Intent intent = new Intent("ionic.jejkalinkui.UPDATE_GLUCOSE_WIDGET");
            intent.setComponent(new android.content.ComponentName(context, "ionic.jejkalinkui.GlucoseWidgetProvider"));
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "Widget update failed", e);
        }
    }

    /** Keep last real glucose on screen when CareLink has no fresh in-range reading. */
    private void showStaleOrDisconnected(String reason) {
        Context context = ctx();
        if (context == null) {
            showNotification(reason, "", 0, false);
            return;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("glucose_widget_prefs",
                Context.MODE_PRIVATE);
        String glucose = prefs.getString("glucose_value", "--");
        String trendArrow = prefs.getString("trend_arrow", "");
        double sg = 0;
        try {
            sg = Double.parseDouble(prefs.getString("sg_double", "0"));
        } catch (Exception ignored) {
        }
        long lastMs = prefs.getLong("last_good_reading_ms", 0);
        if (lastMs <= 0) {
            lastMs = lastReadingTsMs;
        }
        String since = formatAgeEn(lastMs);
        String title;
        if (sg > 0 && glucose != null && !glucose.equals("--")) {
            title = glucose + " mmol/L" + trendArrow + "  \u00b7  " + since + "  \u00b7  " + reason;
            showNotification(title, reason + "\nLast reading " + since, sg, false);
            updateWidget(glucose, trendArrow, since, reason, sg);
        } else {
            showNotification(reason, "", 0, false);
            updateWidget("--", "", since, reason, 0);
        }
    }

    private String formatAgeEn(long readingTsMs) {
        if (readingTsMs <= 0) {
            return "--";
        }
        int minutes = (int) Math.max(0L, (System.currentTimeMillis() - readingTsMs) / 60000L);
        if (minutes == 0) {
            return "just now";
        }
        return formatMinutesHhMm(minutes) + " ago";
    }

    private String formatMinutesHhMm(int minutes) {
        int m = Math.max(0, minutes);
        int h = m / 60;
        int rem = m % 60;
        return String.format(Locale.US, "%02d:%02d", h, rem);
    }

    private int getNotificationIcon(Context context) {
        int iconId = context.getResources().getIdentifier("ic_notification_glucose", "drawable",
                context.getPackageName());
        return iconId != 0 ? iconId : android.R.drawable.ic_dialog_info;
    }

    private void doLogg(String message) {
        Log.i("BackgroundPlugin", message);
        JSObject result = new JSObject();
        result.put("message", message);
        safeNotify("onLogged", result);
    }

    private String getUsernameFromToken() {
        try {
            if (accessToken == null || accessToken.isEmpty()) {
                return "";
            }
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2)
                return "";
            String payload = parts[1];
            // Pad base64
            int pad = (4 - payload.length() % 4) % 4;
            for (int i = 0; i < pad; i++)
                payload += "=";
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (obj.has("token_details")) {
                JSONObject td = obj.getJSONObject("token_details");
                if (td.has("preferred_username"))
                    return td.getString("preferred_username");
            }
            if (obj.has("preferred_username"))
                return obj.getString("preferred_username");
            if (obj.has("sub"))
                return obj.getString("sub");
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private void getData() {
        if (accessToken == null) {
            Log.e("BackgroundPlugin", "No access token set. Skipping getData().");
            return;
        }

        new Thread(() -> {
            try {
                Log.i("BackgroundPlugin", "Calling getData() with access token");

                URL url = new URL("https://clcloud.minimed.eu/connect/carepartner/v13/display/message");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent",
                        "Dalvik/2.1.0 (Linux; U; Android 10; Nexus 5X Build/QQ3A.200805.001)");

                conn.setDoOutput(true);

                String tokenUsername = getUsernameFromToken();
                JSONObject payload = new JSONObject();
                payload.put("username", tokenUsername.isEmpty() ? patientUsername : tokenUsername);
                payload.put("role", "carepartner");
                payload.put("patientId", patientUsername);

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.i("BackgroundPlugin", "getData() response code: " + responseCode);

                if (responseCode == 401) {
                    this.doLogg("getData: 401 unauthorized — session expired");
                    recordPollFailure("401 unauthorized");
                    updateWidget("--", "", "Sign in required", "", 0);
                    JSObject authError = new JSObject();
                    authError.put("error", "unauthorized");
                    authError.put("status", 401);
                    safeNotify("onDataFetchError", authError);
                    return;
                }

                if (responseCode != 200) {
                    this.doLogg("getData: unexpected status " + responseCode);
                    recordPollFailure("HTTP " + responseCode);
                    updateWidget("--", "", "Data error", "", 0);
                    JSObject err = new JSObject();
                    err.put("error", "http_error");
                    err.put("status", responseCode);
                    safeNotify("onDataFetchError", err);
                    return;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                    response.append(line);
                reader.close();

                String responseBody = response.toString();
                Log.i("BackgroundPlugin", "getData() response: " + responseBody);

                // Send to Ionic
                JSObject result = new JSObject();
                result.put("data", responseBody); // or parse to JSON first if needed
                safeNotify("onDataFetched", result);

                JSONObject jsonData = new JSONObject(responseBody);// from getData()
                JSONObject nestedPatientData = (JSONObject) jsonData.get("patientData");

                if (nestedPatientData != null && nestedPatientData.has("sgs")) {
                    JSONArray originalSgs = nestedPatientData.optJSONArray("sgs");

                    if (originalSgs != null) {
                        JSONArray cleanedSortedSgs = cleanAndSortSgs(originalSgs);

                        // Update nestedPatientData.sgs
                        nestedPatientData.put("sgs", cleanedSortedSgs);
                    }
                }

                safeNotify("onLogged", this.convertJSONObjectToJSObject(nestedPatientData));
                JSONObject last = getLastGlicemia(nestedPatientData);
                long readingTs = last.optLong("timestamp", 0);
                recordPollSuccess(readingTs);
                String timeSince = this.getTimeSinceLastGS(nestedPatientData);

                double sg = 0;
                try {
                    sg = Double.parseDouble(last.optString("sg", "0"));
                } catch (Exception e) {
                    sg = 0;
                }

                boolean sensorConnected = nestedPatientData.optBoolean("conduitSensorInRange", false);
                boolean hasValidReading = sg > 0 && readingTs > 0;
                boolean playSound = hasValidReading && (sg < alarmLow || sg > alarmHigh);

                if (!sensorConnected || !hasValidReading) {
                    showStaleOrDisconnected(sensorConnected ? "No fresh reading" : "Sensor disconnected");
                } else {
                    String trendArrow = getTrendArrow(nestedPatientData);
                    String title = last.optString("sg", "0") + " mmol/L" + trendArrow + "  \u00b7  " + timeSince.trim();
                    String statusText = "";
                    if (sg > 0 && sg < alarmUrgentLow)
                        statusText = "Urgent low";
                    else if (sg > 0 && sg < alarmLow)
                        statusText = "Low glucose";
                    else if (sg > alarmHigh)
                        statusText = "High glucose";
                    StringBuilder body = new StringBuilder();
                    if (!statusText.isEmpty()) {
                        body.append(statusText);
                    }
                    StringBuilder details = new StringBuilder();
                    try {
                        JSONObject ai = nestedPatientData.optJSONObject("activeInsulin");
                        Log.i("BackgroundPlugin",
                                "activeInsulin from getData: " + (ai != null ? ai.toString() : "null"));
                        if (ai != null) {
                            double amount = ai.optDouble("amount", 0);
                            details.append("Active insulin: ")
                                    .append(String.format(java.util.Locale.US, "%.1f", amount)).append(" U");
                        }
                    } catch (Exception ignored) {
                    }
                    double reservoir = nestedPatientData.optDouble("reservoirRemainingUnits", -1);
                    if (reservoir >= 0) {
                        if (details.length() > 0)
                            details.append("\n");
                        details.append("Reservoir: ")
                                .append(String.format(java.util.Locale.US, "%.0f", reservoir)).append(" U");
                    }
                    boolean isTempBasal = nestedPatientData.optBoolean("isTempBasal", false);
                    if (isTempBasal) {
                        if (details.length() > 0)
                            details.append("\n");
                        details.append("Temp basal: active");
                    }
                    if (details.length() > 0) {
                        if (body.length() > 0)
                            body.append("\n");
                        body.append(details);
                    }
                    showNotification(title, body.toString().trim(), sg, playSound);
                    updateWidget(last.optString("sg", "--"), trendArrow, timeSince.trim(), statusText, sg, readingTs);
                }

            } catch (Exception e) {
                Log.e("BackgroundPlugin", "Error in getData()", e);
                recordPollFailure(e.getMessage() != null ? e.getMessage() : "exception");

                JSObject error = new JSObject();
                error.put("error", "getData failed");
                error.put("message", e.getMessage());
                safeNotify("onDataFetchError", error);
            }
        }).start();
    }

    private JSONArray cleanAndSortSgs(JSONArray sgsArray) {
        try {
            List<JSONObject> sgList = new ArrayList<>();

            // Step 1: Reverse and filter (sg > 0 && has timestamp)
            for (int i = sgsArray.length() - 1; i >= 0; i--) {
                JSONObject sg = sgsArray.optJSONObject(i);
                if (sg != null && sg.has("sg") && sg.optInt("sg", 0) > 0 && sg.has("timestamp")) {
                    sgList.add(sg);
                }
            }

            // Step 2: Sort descending by ISO timestamp
            sgList.sort((a, b) -> {
                try {
                    String tsA = a.optString("timestamp");
                    String tsB = b.optString("timestamp");

                    long timeA = parseIso8601ToMillis(tsA);
                    long timeB = parseIso8601ToMillis(tsB);

                    return Long.compare(timeB, timeA); // descending
                } catch (Exception e) {
                    return 0;
                }
            });

            // Step 3: Return as JSONArray
            JSONArray result = new JSONArray();
            for (JSONObject sg : sgList) {
                result.put(sg);
            }
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return new JSONArray(); // fallback
        }
    }

    public JSObject convertJSONObjectToJSObject(JSONObject jsonObject) {
        try {
            // Convert JSONObject to String
            String jsonString = jsonObject.toString();

            // Parse string into JSObject
            return JSObject.fromJSONObject(new JSONObject(jsonString));
        } catch (Exception e) {
            e.printStackTrace();
            return new JSObject();
        }
    }

    private String getMagIdentifier(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2)
                return "";

            String payload = parts[1];
            byte[] decodedBytes = Base64.getUrlDecoder().decode(payload);
            String json = new String(decodedBytes, StandardCharsets.UTF_8);

            JSONObject obj = new JSONObject(json);
            return obj.optString("mag-identifier", "true");
        } catch (Exception e) {
            return "true";
        }
    }

    private JSONObject getLastGlicemia(JSONObject data) {
        try {
            JSONObject sgObject = null;

            if (data.has("lastSG")) {
                JSONObject lastSG = data.optJSONObject("lastSG");

                if (lastSG != null && lastSG.has("sg") && lastSG.optInt("sg", 0) > 0) {
                    sgObject = lastSG;
                }
            }

            if (sgObject == null && data.has("sgs")) {
                JSONArray sgs = data.optJSONArray("sgs");
                this.doLogg("Polling: get sgs");
                this.doLogg(sgs.toString());
                if (sgs != null && sgs.length() > 0) {
                    JSONObject firstSG = sgs.optJSONObject(0);
                    if (firstSG != null) {
                        sgObject = firstSG;
                    }
                }
            }

            if (sgObject != null && sgObject.has("sg")) {
                int sgMg = sgObject.optInt("sg", 0);
                double sgMmol = sgMg / 18.0182;

                // extract raw timestamp
                long rawTimestamp = 0;
                if (sgObject.has("timestamp")) {
                    Object ts = sgObject.get("timestamp");
                    if (ts instanceof Number) {
                        rawTimestamp = ((Number) ts).longValue();
                    } else if (ts instanceof String) {
                        // Try to parse ISO if it's a string
                        rawTimestamp = parseIso8601ToMillis((String) ts);
                    }
                }

                if (rawTimestamp == 0) {
                    // Do not invent "now" — callers treat 0 as invalid / no fresh reading
                    rawTimestamp = 0;
                }

                JSONObject result = new JSONObject();
                result.put("sg", String.format(Locale.US, "%.1f", sgMmol));
                result.put("timestamp", rawTimestamp);
                return result;
            }

            // Fallback
            JSONObject fallback = new JSONObject();
            fallback.put("sg", "0.0");
            fallback.put("timestamp", 0);
            return fallback;

        } catch (Exception e) {
            e.printStackTrace();
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("sg", "0.0");
                fallback.put("timestamp", 0);
            } catch (JSONException ignored) {
            }
            return fallback;
        }
    }

    private String getTimeSinceLastGS(JSONObject data) {
        try {
            JSONObject last = getLastGlicemia(data);
            if (last == null || !last.has("timestamp")) {
                return "--";
            }

            long lastTime = last.optLong("timestamp", 0);
            if (lastTime <= 0) {
                return "--";
            }
            return formatAgeEn(lastTime);
        } catch (Exception e) {
            e.printStackTrace();
            return "--";
        }
    }

    private String formatToIso8601(long timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private long parseIso8601ToMillis(String isoTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault()); // ✅ LOCAL time, since your input has no TZ
            return sdf.parse(isoTime).getTime();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

}
