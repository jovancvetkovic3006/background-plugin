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
import android.Manifest;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    private ScheduledExecutorService scheduler = null;
    private String accessToken = null;
    private String refreshToken = null;

    private static final String CHANNEL_NORMAL = "bg_plugin_normal";
    private static final String CHANNEL_ALERT = "bg_plugin_alert";
    private Integer notificationId = 1001;

    private void startForegroundService() {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);

            NotificationChannel normalChannel = new NotificationChannel(
                    CHANNEL_NORMAL,
                    "CareLink Monitoring",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(normalChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ALERT,
                    "CareLink Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private Bitmap createGlucoseIcon(double sgValue) {
        int size = 128;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Background circle color based on glucose level
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (sgValue < 4.5) {
            bgPaint.setColor(Color.parseColor("#C62828")); // red - low
        } else if (sgValue <= 7.5) {
            bgPaint.setColor(Color.parseColor("#2E7D32")); // green - in range
        } else {
            bgPaint.setColor(Color.parseColor("#E65100")); // orange - high
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

            boolean playSound = (sgValue < 4.5 || sgValue > 10.0);

            if (!sensorConnected) {
                showNotification("Senzor nije povezan", "", 0, false);
                updateWidget("--", "", "Senzor nije povezan", "", 0);
            } else {
                String trendArrow = getTrendArrow(json);
                String title = last.getString("sg") + " mmol/L" + trendArrow + "  \u00b7  " + since.trim();
                String statusText = "";
                if (sgValue < 4.5)
                    statusText = "\u2757 Niska glikemija!";
                else if (sgValue > 10.0)
                    statusText = "\u26a1 Visoka glikemija!";
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
                        details.append("Aktivni insulin: ")
                                .append(String.format(java.util.Locale.US, "%.1f", amount)).append(" U");
                    }
                } catch (Exception ignored) {
                }
                double reservoir = json.optDouble("reservoirRemainingUnits", -1);
                if (reservoir >= 0) {
                    if (details.length() > 0)
                        details.append("\n");
                    details.append("Rezervoar: ")
                            .append(String.format(java.util.Locale.US, "%.0f", reservoir)).append(" U");
                }
                boolean isTempBasal = json.optBoolean("isTempBasal", false);
                if (isTempBasal) {
                    if (details.length() > 0)
                        details.append("\n");
                    details.append("Temporalni bazal: aktivan");
                }
                if (details.length() > 0) {
                    if (body.length() > 0)
                        body.append("\n");
                    body.append(details);
                }
                showNotification(title, body.toString().trim(), sgValue, playSound);
                updateWidget(last.getString("sg"), trendArrow, since.trim(), statusText, sgValue);
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            e.printStackTrace();
            call.reject("Failed to show notification: " + e.getMessage());
        }
    }

    private void showNotification(String title, String body, double sgValue, boolean playSound) {
        Context context = getContext();

        NotificationManager notificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId = playSound ? CHANNEL_ALERT : CHANNEL_NORMAL;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService();
        }

        // Open app on tap
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(getNotificationIcon(context))
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS);

        // Expanded style with extra info
        if (body != null && !body.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(body)
                    .setBigContentTitle(title));
        }

        // Color the notification accent
        if (sgValue < 4.5) {
            builder.setColor(Color.parseColor("#C62828"));
        } else if (sgValue <= 7.5) {
            builder.setColor(Color.parseColor("#2E7D32"));
        } else if (sgValue > 7.5) {
            builder.setColor(Color.parseColor("#E65100"));
        }

        notificationManager.notify(this.notificationId, builder.build());
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

        this.accessToken = access;
        this.refreshToken = refresh;
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
        if (scheduler != null && !scheduler.isShutdown()) {
            call.resolve(); // already running
            return;
        }
        this.startForegroundService();

        this.doLogg("Polling started");
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            this.doLogg("Polling: attempting token refresh");
            try {
                refreshTokenSilently();
            } catch (Exception e) {
                this.doLogg("Polling: error token refresh");
                this.doLogg(e.getMessage());
            }
        }, 0, 2, TimeUnit.MINUTES);

        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    @PluginMethod
    public void stopPolling(PluginCall call) {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }

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

    private void refreshTokenSilently() {
        if (this.refreshToken == null) {
            this.doLogg("No refresh token available.");
            return;
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
            body.append("&refresh_token=").append(URLEncoder.encode(this.refreshToken, "UTF-8"));
            body.append("&client_id=PeAhkbhQWlQRxJiQxWfcFBiGus1lxfe9");
            body.append("&redirect_uri=").append(URLEncoder.encode("com.medtronic.carepartner:/sso", "UTF-8"));

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
                    this.accessToken = json.getString("access_token");
                    this.refreshToken = json.optString("refresh_token", this.refreshToken);

                    this.doLogg("Polling: success tokens: " + json.toString());
                    JSObject result = new JSObject();
                    result.put("access_token", this.accessToken);
                    result.put("refresh_token", this.refreshToken);
                    notifyListeners("onTokenRefreshed", result);
                    this.getData(); // fetch data after refresh
                    this.doLogg("Token refreshed successfully");
                } catch (Exception e) {
                    this.doLogg("Polling: error parsing response: " + e.getMessage());
                }

            } else {
                this.doLogg("Polling: refresh FAILED status=" + responseCode + " body="
                        + response.toString().substring(0, Math.min(200, response.length())));
                Log.e("BackgroundPlugin", "Refresh failed: " + response.toString());

                // Try getData with existing token — Ionic may have refreshed it already
                if (this.accessToken != null) {
                    this.doLogg("Polling: refresh failed, trying getData with existing token...");
                    try {
                        this.getData();
                        return; // getData succeeded, no need to show error
                    } catch (Exception e) {
                        this.doLogg("Polling: getData with existing token also failed: " + e.getMessage());
                    }
                }

                JSObject error = new JSObject();
                error.put("error", "token_refresh_failed");
                error.put("status", responseCode);
                error.put("message", response.toString().substring(0, Math.min(200, response.length())));
                notifyListeners("onTokenRefreshFailed", error);

                showNotification("Token istekao", "Otvorite aplikaciju za ponovnu prijavu", 0, false);
                updateWidget("--", "", "Token istekao", "", 0);
            }

        } catch (Exception e) {
            this.doLogg("Polling: exception: " + e.getMessage());
            Log.e("BackgroundPlugin", "Exception in background refresh", e);
        }
    }

    private void updateWidget(String glucoseValue, String trendArrow, String timeSince, String status, double sgValue) {
        try {
            Context context = getContext();
            android.content.SharedPreferences prefs = context.getSharedPreferences("glucose_widget_prefs",
                    Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("glucose_value", glucoseValue)
                    .putString("trend_arrow", trendArrow)
                    .putString("time_since", timeSince)
                    .putString("status", status)
                    .putString("sg_double", String.valueOf(sgValue))
                    .apply();

            Intent intent = new Intent("ionic.jejkalinkui.UPDATE_GLUCOSE_WIDGET");
            intent.setComponent(new android.content.ComponentName(context, "ionic.jejkalinkui.GlucoseWidgetProvider"));
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e("BackgroundPlugin", "Widget update failed", e);
        }
    }

    private int getNotificationIcon(Context context) {
        int iconId = context.getResources().getIdentifier("ic_notification_glucose", "drawable",
                context.getPackageName());
        return iconId != 0 ? iconId : android.R.drawable.ic_dialog_info;
    }

    private void doLogg(String message) {
        JSObject result = new JSObject();
        result.put("message", message);
        notifyListeners("onLogged", result);
    }

    private String getUsernameFromToken() {
        try {
            String[] parts = this.accessToken.split("\\.");
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
        if (this.accessToken == null) {
            Log.e("BackgroundPlugin", "No access token set. Skipping getData().");
            return;
        }

        new Thread(() -> {
            try {
                Log.i("BackgroundPlugin", "Calling getData() with access token");

                URL url = new URL("https://clcloud.minimed.eu/connect/carepartner/v13/display/message");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + this.accessToken);
                conn.setRequestProperty("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent",
                        "Dalvik/2.1.0 (Linux; U; Android 10; Nexus 5X Build/QQ3A.200805.001)");

                conn.setDoOutput(true);

                String tokenUsername = getUsernameFromToken();
                String patientUsername = "jejka3006"; // TODO: pass from Ionic
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

                InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
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
                notifyListeners("onDataFetched", result);

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

                notifyListeners("onLogged", this.convertJSONObjectToJSObject(nestedPatientData));
                JSONObject last = getLastGlicemia(nestedPatientData);
                String timeSince = this.getTimeSinceLastGS(nestedPatientData);

                double sg = 0;
                try {
                    sg = Double.parseDouble(last.optString("sg", "0"));
                } catch (Exception e) {
                    sg = 0;
                }

                boolean sensorConnected = nestedPatientData.optBoolean("conduitSensorInRange", false);
                boolean playSound = (sg < 4.5 || sg > 10.0);

                if (!sensorConnected) {
                    showNotification("Senzor nije povezan", "", 0, false);
                    updateWidget("--", "", "Senzor nije povezan", "", 0);
                } else {
                    String trendArrow = getTrendArrow(nestedPatientData);
                    String title = last.optString("sg", "0") + " mmol/L" + trendArrow + "  \u00b7  " + timeSince.trim();
                    String statusText = "";
                    if (sg < 4.5)
                        statusText = "\u2757 Niska glikemija!";
                    else if (sg > 10.0)
                        statusText = "\u26a1 Visoka glikemija!";
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
                            details.append("Aktivni insulin: ")
                                    .append(String.format(java.util.Locale.US, "%.1f", amount)).append(" U");
                        }
                    } catch (Exception ignored) {
                    }
                    double reservoir = nestedPatientData.optDouble("reservoirRemainingUnits", -1);
                    if (reservoir >= 0) {
                        if (details.length() > 0)
                            details.append("\n");
                        details.append("Rezervoar: ")
                                .append(String.format(java.util.Locale.US, "%.0f", reservoir)).append(" U");
                    }
                    boolean isTempBasal = nestedPatientData.optBoolean("isTempBasal", false);
                    if (isTempBasal) {
                        if (details.length() > 0)
                            details.append("\n");
                        details.append("Temporalni bazal: aktivan");
                    }
                    if (details.length() > 0) {
                        if (body.length() > 0)
                            body.append("\n");
                        body.append(details);
                    }
                    showNotification(title, body.toString().trim(), sg, playSound);
                    updateWidget(last.optString("sg", "--"), trendArrow, timeSince.trim(), statusText, sg);
                }

            } catch (Exception e) {
                Log.e("BackgroundPlugin", "Error in getData()", e);

                JSObject error = new JSObject();
                error.put("error", "getData failed");
                error.put("message", e.getMessage());
                notifyListeners("onDataFetchError", error);
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
                double sgMmol = sgMg / 18.0;

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
                    rawTimestamp = System.currentTimeMillis();
                }

                JSONObject result = new JSONObject();
                result.put("sg", String.format(Locale.US, "%.1f", sgMmol));
                result.put("timestamp", rawTimestamp); // return as raw number
                return result;
            }

            // Fallback
            JSONObject fallback = new JSONObject();
            fallback.put("sg", "0.0");
            fallback.put("timestamp", System.currentTimeMillis());
            return fallback;

        } catch (Exception e) {
            e.printStackTrace();
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("sg", "0.0");
                fallback.put("timestamp", System.currentTimeMillis());
            } catch (JSONException ignored) {
            }
            return fallback;
        }
    }

    private String getTimeSinceLastGS(JSONObject data) {
        try {
            JSONObject last = getLastGlicemia(data);
            if (last == null || !last.has("timestamp")) {
                return "No last SG data";
            }

            long now = System.currentTimeMillis();
            long lastTime = last.optLong("timestamp", now);

            long diffMs = now - lastTime;
            int minutes = (int) (diffMs / 60000);
            int hours = minutes / 60;
            int remainingMinutes = minutes % 60;

            return (hours > 0)
                    ? String.format(" pre %dh %dm", hours, remainingMinutes)
                    : String.format(" pre %dm", minutes);

        } catch (Exception e) {
            e.printStackTrace();
            return "No valid SG data";
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
