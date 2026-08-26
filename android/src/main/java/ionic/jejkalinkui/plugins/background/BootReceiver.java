package ionic.jejkalinkui.plugins.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** Restart CareLink polling after reboot / app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i("BootReceiver", "action=" + action);
        try {
            Intent fgs = new Intent(context, ForegroundService.class);
            fgs.setAction(ForegroundService.ACTION_START);
            ContextCompat.startForegroundService(context, fgs);
        } catch (Exception e) {
            Log.w("BootReceiver", "FGS start failed", e);
            BackgroundPlugin.startBackgroundPolling(context.getApplicationContext());
        }
    }
}
