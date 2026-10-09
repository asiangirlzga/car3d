package com.lite.drivingtest;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Finds assets: 1) already downloaded  2) bundled in APK (build-time download)  3) null -> built-in fallback. */
final class Assets {
    static final String[] TEX = {"road", "grass", "building"};
    static final String[] SOUNDS = {"engine", "crash", "beep", "horn", "success", "fail"};

    static File dir(Context c) { File d = new File(c.getFilesDir(), "dl"); d.mkdirs(); return d; }

    /** Returns a real file for this key, or null if not available. */
    static File materialize(Context c, String key) {
        File f = new File(dir(c), key);
        if (f.length() > 0) return f;
        try (InputStream in = c.getAssets().open("dl/" + key); FileOutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } catch (IOException e) { f.delete(); return null; }
        return f.length() > 0 ? f : null;
    }

    /** Install/first-launch download of anything still missing (reads assets.conf). */
    static void fetchMissing(final Context ctx, final Runnable onNew) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean got = false;
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("assets.conf")));
                    String base = "", line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (line.startsWith("@base=")) { base = line.substring(6).trim(); continue; }
                        int e = line.indexOf('=');
                        if (e < 1) continue;
                        String key = line.substring(0, e).trim(), val = line.substring(e + 1).trim();
                        if (val.isEmpty() || materialize(ctx, key) != null) continue;
                        String url = val.startsWith("http") ? val
                                : (base.isEmpty() ? null : base.replaceAll("/+$", "") + "/" + val);
                        if (url != null && download(ctx, key, url)) got = true;
                    }
                    r.close();
                } catch (Throwable ignored) { }
                if (got && onNew != null) onNew.run();
            }
        }, "assets-dl").start();
    }

    static boolean download(Context c, String key, String url) {
        HttpURLConnection h = null;
        File tmp = new File(dir(c), key + ".tmp");
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            if (h.getResponseCode() != 200) return false;
            try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[8192]; int n; long tot = 0;
                while ((n = in.read(b)) > 0) {
                    tot += n;
                    if (tot > 2000000) throw new IOException("too big");
                    o.write(b, 0, n);
                }
            }
            return tmp.renameTo(new File(dir(c), key));
        } catch (Throwable t) {
            tmp.delete();
            return false;
        } finally { if (h != null) h.disconnect(); }
    }
}
