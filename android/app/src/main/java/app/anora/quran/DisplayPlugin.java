package app.anora.quran;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** The screen's refresh rate: the top rate while the page moves, the system's choice while it rests. */
@CapacitorPlugin(name = "Display")
public class DisplayPlugin extends Plugin {

    @PluginMethod
    public void fast(PluginCall call) {
        MainActivity a = MainActivity.inst;
        if (a != null) a.setFast(Boolean.TRUE.equals(call.getBoolean("on", true)));
        call.resolve();
    }

    @PluginMethod
    public void cap60(PluginCall call) {
        MainActivity a = MainActivity.inst;
        if (a != null) a.setCap60(Boolean.TRUE.equals(call.getBoolean("on", true)));
        call.resolve();
    }
}
