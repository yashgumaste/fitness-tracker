package com.yashgumaste.fitnesstracker;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

/**
 * Downloads a new APK with Android's DownloadManager (progress in the notification shade)
 * and opens the system installer when it finishes. The browser tab route can stall at 100%.
 * Events to JS: "update" with status = "downloading" | "installing" | "failed".
 */
@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {
    private static final String APK = "application/vnd.android.package-archive";
    private long pending = -1;
    private boolean installed = false;
    private BroadcastReceiver receiver;

    private DownloadManager dm() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @PluginMethod
    public void download(PluginCall call) {
        String url = call.getString("url");
        String name = call.getString("name", "FitnessTracker-update.apk");
        if (url == null || url.isEmpty()) { call.reject("No download link"); return; }
        Context ctx = getContext();
        try {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) { File old = new File(dir, name); if (old.exists()) old.delete(); }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                    .setTitle("Fitness Tracker update")
                    .setDescription(name)
                    .setMimeType(APK)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name);
            pending = dm().enqueue(req);
            installed = false;
            listen();
            emit("downloading");
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not start the download: " + e.getMessage());
        }
    }

    private void listen() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent in) {
                long id = in.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == pending) finish(id);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else getContext().registerReceiver(receiver, f);
    }

    /** Opens the installer if the download succeeded. */
    private void finish(long id) {
        if (installed) return;
        int status = -1;
        try (Cursor c = dm().query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception ignored) { }
        if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) return;
        if (status != DownloadManager.STATUS_SUCCESSFUL) { pending = -1; emit("failed"); return; }
        try {
            Uri uri = dm().getUriForDownloadedFile(id);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, APK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(i);
            installed = true;
            emit("installing");
        } catch (Exception e) {
            emit("failed");
        }
    }

    /** If the download finished while the app was in the background, open the installer on return. */
    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (pending != -1 && !installed) finish(pending);
    }

    @Override
    protected void handleOnDestroy() {
        if (receiver != null) { try { getContext().unregisterReceiver(receiver); } catch (Exception ignored) { } receiver = null; }
        super.handleOnDestroy();
    }

    private void emit(String status) {
        JSObject o = new JSObject();
        o.put("status", status);
        notifyListeners("update", o);
    }
}
