package ionic.jejkalinkui.plugins.background;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import android.util.Log;

@CapacitorPlugin(name = "Background")
public class BackgroundPlugin extends Plugin {

    private Background implementation = new Background();

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        Log.i("BackgroundPlugin", "Echo called with value: " + value);
        JSObject ret = new JSObject();
        ret.put("value", implementation.echo(value));

        notifyListeners("onDataFetched", ret);
        call.resolve(ret);
    }
}
