package ionic.jejkalinkui.plugins.background;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps the process alive for CareLink polling after the Activity/WebView is
 * backgrounded or destroyed. Polling itself lives in {@link BackgroundPlugin}.
 */
public class ForegroundService extends Service {

    public static final String ACTION_START = "ionic.jejkalinkui.plugins.background.START";
    public static final String ACTION_STOP = "ionic.jejkalinkui.plugins.background.STOP";
    public static final String ACTION_TRIGGER_REFRESH = "ionic.jejkalinkui.TRIGGER_REFRESH";

    /** Must match BackgroundPlugin ongoing notification id. */
    public static final int NOTIFICATION_ID = 1001;

    private static final String CHANNEL_NORMAL = "bg_plugin_normal_v2";
    private static final String TAG = "ForegroundService";

    private BroadcastReceiver refreshReceiver;
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_TRIGGER_REFRESH.equals(intent.getAction())) {
                    Log.i(TAG, "Manual refresh from notification action");
                    BackgroundPlugin.requestImmediatePoll();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TRIGGER_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop requested");
            running = false;
            BackgroundPlugin.stopBackgroundPolling();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildPlaceholderNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            // Still try to keep polling if channels/permission glitch
            startForeground(NOTIFICATION_ID, notification);
        }

        running = true;
        BackgroundPlugin.startBackgroundPolling(getApplicationContext());
        Log.i(TAG, "Foreground service started; polling ensured");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (refreshReceiver != null) {
                unregisterReceiver(refreshReceiver);
            }
        } catch (Exception ignored) {
        }
        running = false;
        BackgroundPlugin.stopBackgroundPolling();
        Log.i(TAG, "Foreground service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.deleteNotificationChannel("bg_plugin_normal");

        NotificationChannel normal = new NotificationChannel(
                CHANNEL_NORMAL,
                "CareLink Monitoring",
                NotificationManager.IMPORTANCE_DEFAULT);
        normal.setDescription("Glucose monitoring updates");
        normal.setShowBadge(true);
        manager.createNotificationChannel(normal);

        NotificationChannel alert = new NotificationChannel(
                "bg_plugin_alert",
                "CareLink Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setShowBadge(true);
        manager.createNotificationChannel(alert);

        NotificationChannel critical = new NotificationChannel(
                "bg_plugin_critical",
                "CareLink Critical Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        critical.setBypassDnd(true);
        critical.enableVibration(true);
        critical.setVibrationPattern(new long[] { 0, 500, 200, 500, 200, 500 });
        manager.createNotificationChannel(critical);
    }

    private Notification buildPlaceholderNotification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int iconId = getResources().getIdentifier(
                "ic_notification_glucose", "drawable", getPackageName());
        if (iconId == 0) {
            iconId = android.R.drawable.ic_dialog_info;
        }

        return new NotificationCompat.Builder(this, CHANNEL_NORMAL)
                .setContentTitle("JejkaLink")
                .setContentText("Monitoring glucose…")
                .setSmallIcon(iconId)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
    }
}
