package com.yashgumaste.fitnesstracker;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Switches the launcher icon between the accent-colour aliases declared in the manifest. */
@CapacitorPlugin(name = "AppIcon")
public class AppIconPlugin extends Plugin {
    static final String[] NAMES = {"cyan","teal","mint","lime","lemon","amber","coral","rose","pink","orchid","violet","sky"};

    @PluginMethod
    public void set(PluginCall call) {
        String name = call.getString("name", "cyan");
        boolean known = false;
        for (String n : NAMES) if (n.equals(name)) known = true;
        if (!known) { call.reject("Unknown icon " + name); return; }
        PackageManager pm = getContext().getPackageManager();
        String pkg = getContext().getPackageName();
        // enable the new one first so the app always has a launcher entry
        setState(pm, pkg, name, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        for (String n : NAMES) if (!n.equals(name)) setState(pm, pkg, n, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        call.resolve();
    }

    @PluginMethod
    public void get(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        String pkg = getContext().getPackageName();
        String current = "cyan";
        for (String n : NAMES) {
            int s = pm.getComponentEnabledSetting(new ComponentName(pkg, pkg + ".Icon_" + n));
            if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) { current = n; break; }
        }
        JSObject r = new JSObject();
        r.put("name", current);
        call.resolve(r);
    }

    /**
     * Where the app's page sits on the screen, in CSS pixels: the page's top edge and the full screen height.
     * Android centres its splash logo on the whole screen; the page sits between the status and navigation
     * bars, so the app's own loading screen uses these to put its logo in exactly the same spot.
     */
    @PluginMethod
    public void geometry(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            float d = getContext().getResources().getDisplayMetrics().density;
            int[] loc = new int[2];
            getBridge().getWebView().getLocationOnScreen(loc);
            android.view.View root = getActivity().getWindow().getDecorView();
            JSObject r = new JSObject();
            r.put("top", loc[1] / d);
            r.put("screenH", root.getHeight() / d);
            r.put("webH", getBridge().getWebView().getHeight() / d);
            call.resolve(r);
        });
    }

    private void setState(PackageManager pm, String pkg, String n, int state) {
        ComponentName c = new ComponentName(pkg, pkg + ".Icon_" + n);
        if (pm.getComponentEnabledSetting(c) != state) pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP);
    }
}
