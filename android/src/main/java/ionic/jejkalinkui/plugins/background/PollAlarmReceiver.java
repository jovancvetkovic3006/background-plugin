package ionic.jejkalinkui.plugins.background;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * AlarmManager wakeup: restart the FGS (if needed) and poll immediately.
 * Matches xDrip CareLinkFollowService.scheduleWakeUp / WakeLockTrampoline.
 */
public class PollAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("PollAlarmReceiver", "wakeup action=" + (intent != null ? intent.getAction() : "null"));
        try {
            Intent fgs = new Intent(context, ForegroundService.class);
            fgs.setAction(ForegroundService.ACTION_START);
            ContextCompat.startForegroundService(context, fgs);
        } catch (Exception e) {
            Log.w("PollAlarmReceiver", "FGS start failed, polling in-process", e);
            BackgroundPlugin.startBackgroundPolling(context.getApplicationContext());
        }
        BackgroundPlugin.requestImmediatePoll();
    }
}
