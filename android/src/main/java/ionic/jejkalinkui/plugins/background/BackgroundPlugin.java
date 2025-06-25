package ionic.jejkalinkui.plugins.background;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Build;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import android.app.Activity;

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

@CapacitorPlugin(name = "Background")
public class BackgroundPlugin extends Plugin {

    private ScheduledExecutorService scheduler = null;
    private String accessToken = null;
    private String refreshToken = null;

    private String notificationChannel = "background_plugin_channel";
    private Integer notificationId = 1001;

    private void showNotification(String content) {
        Context context = getContext();

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                this.notificationChannel,
                "Background Plugin Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(context, this.notificationChannel)
            .setContentTitle("Glikemija")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setOngoing(true)
            .build();

            notificationManager.notify(this.notificationId, notification);
    }

    @PluginMethod
    public void updateNotification(PluginCall call) {
        String content = call.getString("content");
        if (content == null) {
            call.reject("Missing 'content' parameter");
            return;
        }

        this.showNotification(content);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        Activity activity = getActivity();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12345);
            }
        }

        JSObject result = new JSObject();
        result.put("granted", true); // naive - you could listen for the result code too
        call.resolve(result);
    }

    @PluginMethod
    public void hasNotificationPermission(PluginCall call) {
        boolean granted = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Activity activity = getActivity();
            granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
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

        this.doLogg("Polling started");
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            this.doLogg("Polling: attempting token refresh");
            try {
                refreshTokenSilently(); // 👇 see below
            } catch (Exception e) {
                this.doLogg("Polling: error token refresh");
                this.doLogg(e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);

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

    private void refreshTokenSilently() {
        if (this.refreshToken == null) {
            this.doLogg("No refresh token available.");
            return;
        }

        try {
            URL url = new URL("https://mdtlogin-ocl.medtronic.com/mmcl/auth/oauth/v2/token");

            this.doLogg("Polling: preparing token refresh");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            StringBuilder body = new StringBuilder();
            body.append("grant_type=refresh_token");
            body.append("&refresh_token=").append(URLEncoder.encode(this.refreshToken, "UTF-8"));
            body.append("&client_id=4fb211b8-f130-4398-b51e-28900bf68527");

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes());
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
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
                Log.e("BackgroundPlugin", "Refresh failed: " + response.toString());
            }

        } catch (Exception e) {
            Log.e("BackgroundPlugin", "Exception in background refresh", e);
        }
    }

    private void doLogg(String message) {
        JSObject result = new JSObject();
        result.put("message", message);
        notifyListeners("onLogged", result);
    }

    private void getData() {
        if (this.accessToken == null) {
            Log.e("BackgroundPlugin", "No access token set. Skipping getData().");
            return;
        }

        new Thread(() -> {
            try {
                Log.i("BackgroundPlugin", "Calling getData() with access token");

                URL url = new URL("https://clcloud.minimed.eu/connect/carepartner/v11/display/message"); // replace with real endpoint
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + this.accessToken);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; Nexus 5X Build/QQ3A.200805.001)");
                conn.setRequestProperty("mag-identifier", getMagIdentifier(this.accessToken)); // 👈 optional helper

                conn.setDoOutput(true);

                // Body: { "username": "jejka3006", "role": "patient" }
                JSONObject payload = new JSONObject();
                payload.put("username", "jejka3006");
                payload.put("role", "patient");

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
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                String responseBody = response.toString();
                Log.i("BackgroundPlugin", "getData() response: " + responseBody);

                // Send to Ionic
                JSObject result = new JSObject();
                result.put("data", responseBody); // or parse to JSON first if needed
                notifyListeners("onDataFetched", result);

                JSONObject jsonData = new JSONObject(responseBody);// from getData()
                JSONObject nestedPatientData = (JSONObject) jsonData.get("patientData");

                notifyListeners("onLogged", this.convertJSONObjectToJSObject(nestedPatientData));
                JSONObject last = getLastGlicemia(nestedPatientData);
                String timeSince = this.getTimeSinceLastGS(nestedPatientData);

                double sg = 0;
                String status = "";

                // Safely extract and parse the SG value
                try {
                    sg = Double.parseDouble(last.optString("sg", "0"));
                    if (sg < 5.0) status = " (LOW)";
                    else if (sg > 8.0) status = " (HIGH)";
                } catch (Exception e) {
                    e.printStackTrace();
                    sg = 0;
                    status = "";
                }

                // Format SG to 1 decimal place
                String sgFormatted = String.format("%.1f", sg);

                // Construct the notification text
                String text = "SG: " + sgFormatted + timeSince + status;
                this.showNotification(text);

            } catch (Exception e) {
                Log.e("BackgroundPlugin", "Error in getData()", e);

                JSObject error = new JSObject();
                error.put("error", "getData failed");
                error.put("message", e.getMessage());
                notifyListeners("onDataFetchError", error);
            }
        }).start();
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
            if (parts.length < 2) return "";

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
                if (lastSG != null && lastSG.has("sg")) {
                    sgObject = lastSG;
                }
            }
    
            if (sgObject == null && data.has("sgs")) {
                JSONArray sgs = data.optJSONArray("sgs");
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
                result.put("timestamp", rawTimestamp);  // return as raw number
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
            } catch (JSONException ignored) {}
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
            sdf.setTimeZone(TimeZone.getDefault());  // ✅ LOCAL time, since your input has no TZ
            return sdf.parse(isoTime).getTime();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
    

}
