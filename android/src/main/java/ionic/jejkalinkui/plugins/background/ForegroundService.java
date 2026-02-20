package ionic.jejkalinkui.plugins.background;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;
import androidx.annotation.Nullable;

public class ForegroundService extends Service {

    private static final String CHANNEL_NORMAL = "bg_plugin_normal_v2";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("BGPlugin", "Foreground service started");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Ensure channel exists before building notification
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(CHANNEL_NORMAL) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_NORMAL,
                        "CareLink Monitoring",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.setShowBadge(true);
                channel.setDescription("Glucose monitoring updates visible on lock screen");
                manager.createNotificationChannel(channel);
                Log.d("BGPlugin", "ForegroundService created notification channel");
            }
        }

        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_NORMAL)
                    .setContentTitle("JejkaLink")
                    .setContentText("Monitoring aktivan")
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .build();
        }

        startForeground(1001, notification);

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d("BGPlugin", "Foreground service stopped");
        stopForeground(true);
        super.onDestroy();
    }
}
