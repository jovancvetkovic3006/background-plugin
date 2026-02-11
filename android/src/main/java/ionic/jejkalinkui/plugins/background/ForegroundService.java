package ionic.jejkalinkui.plugins.background;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.app.Notification;
import android.util.Log;
import androidx.annotation.Nullable;

public class ForegroundService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("BGPlugin", "Foreground service started");

        // You must show a notification to keep foreground mode active
      Notification notification = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notification = new Notification.Builder(this, "background_plugin_channel")
            .setContentTitle("Background Monitoring Active")
            .setContentText("Polling glucose & token")
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
