package ionic.jejkalinkui.plugins.background;

import android.util.Log;

public class Background {

    public String echo(String value) {
        Log.i("BackgroundService", "Echo value: " + value);
        return value;
    }
}
