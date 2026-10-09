package com.lite.drivingtest;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

/** SoundPool wrapper. Uses downloaded sounds if present, otherwise synthesizes tiny WAVs (cached). */
final class Sfx {
    private final Context ctx;
    private SoundPool sp;
    private final HashMap<String, Integer> ids = new HashMap<>();
    private final HashSet<Integer> ready = new HashSet<>();
    private int engStream, engId = -1;
    private float lastRate = -1;
    private boolean engOn = true;

    Sfx(Context c) { ctx = c.getApplicationContext(); init(); }

    synchronized void init() {
        release();
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        sp = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(aa).build();
        sp.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override public void onLoadComplete(SoundPool p, int id, int status) {
                synchronized (Sfx.this) {
                    if (status == 0) { ready.add(id); if (id == engId && engOn) startEngine(); }
                }
            }
        });
        for (String k : Assets.SOUNDS) {
            File f = Assets.materialize(ctx, k);
            if (f == null) f = synth(k);
            if (f != null) {
                int id = sp.load(f.getPath(), 1);
                ids.put(k, id);
                if (k.equals("engine")) engId = id;
            }
        }
    }

    private void startEngine() { engStream = sp.play(engId, 0.3f, 0.3f, 1, -1, 1f); lastRate = 1f; }

    synchronized void play(String key) {
        Integer id = ids.get(key);
        if (sp != null && id != null && ready.contains(id)) sp.play(id, 1f, 1f, 1, 0, 1f);
    }

    /** s = speed 0..1 */
    synchronized void engine(float s) {
        if (sp == null || engStream == 0 || !engOn) return;
        float rate = 0.7f + 1.3f * s;
        if (Math.abs(rate - lastRate) > 0.03f) {
            sp.setRate(engStream, rate); lastRate = rate;
            float vol = 0.25f + 0.35f * s;
            sp.setVolume(engStream, vol, vol);
        }
    }

    synchronized void pause() {
        engOn = false;
        if (sp != null && engStream != 0) { sp.stop(engStream); engStream = 0; }
    }

    synchronized void resume() {
        engOn = true;
        if (sp != null && engId >= 0 && ready.contains(engId) && engStream == 0) startEngine();
    }

    synchronized void release() {
        if (sp != null) { sp.release(); sp = null; }
        ids.clear(); ready.clear(); engStream = 0; engId = -1;
    }

    // ---------- tiny synthesizer (11025 Hz, 16-bit mono) ----------
    private static final int R = 11025;
    private static final double TP = 2 * Math.PI;

    private File synth(String k) {
        try {
            File f = new File(ctx.getCacheDir(), "sfx_" + k + ".wav");
            if (f.length() == 0) {
                short[] s = gen(k);
                ByteBuffer b = ByteBuffer.allocate(44 + s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes()).putInt(36 + s.length * 2).put("WAVEfmt ".getBytes()).putInt(16)
                        .putShort((short) 1).putShort((short) 1).putInt(R).putInt(R * 2)
                        .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(s.length * 2);
                for (short x : s) b.putShort(x);
                FileOutputStream o = new FileOutputStream(f);
                o.write(b.array());
                o.close();
            }
            return f;
        } catch (Throwable t) { return null; }
    }

    private static short[] notes(double[] f, double dur, boolean square, double amp) {
        int per = (int) (R * dur);
        short[] s = new short[per * f.length];
        for (int n = 0; n < f.length; n++)
            for (int i = 0; i < per; i++) {
                double t = (double) i / R, e = Math.min(1, t * 80) * Math.min(1, (dur - t) * 25);
                double v = Math.sin(TP * f[n] * t);
                if (square) v = Math.signum(v) * 0.6;
                s[n * per + i] = (short) (v * e * amp);
            }
        return s;
    }

    private static short[] gen(String k) {
        switch (k) {
            case "engine": { // seamless loop: 0.4 s, 50 Hz base = 20 whole cycles
                short[] s = new short[(int) (R * 0.4)];
                for (int i = 0; i < s.length; i++) {
                    double t = (double) i / R;
                    double v = Math.sin(TP * 50 * t) * .5 + Math.sin(TP * 100 * t) * .3 + Math.sin(TP * 150 * t) * .2
                            + .15 * Math.sin(TP * 250 * t) * (.5 + .5 * Math.sin(TP * 10 * t));
                    s[i] = (short) (v * 8000);
                }
                return s;
            }
            case "crash": {
                short[] s = new short[(int) (R * 0.35)];
                Random r = new Random(3);
                for (int i = 0; i < s.length; i++) {
                    double t = (double) i / R;
                    s[i] = (short) ((r.nextDouble() * 2 - 1) * Math.exp(-t * 10) * 12000
                            + Math.sin(TP * 70 * t) * Math.exp(-t * 8) * 9000);
                }
                return s;
            }
            case "beep": return notes(new double[]{880}, 0.15, false, 9000);
            case "horn": {
                short[] s = new short[(int) (R * 0.45)];
                for (int i = 0; i < s.length; i++) {
                    double t = (double) i / R, e = Math.min(1, t * 30) * Math.min(1, (0.45 - t) * 20);
                    s[i] = (short) ((Math.sin(TP * 400 * t) + Math.sin(TP * 505 * t)) * e * 7000);
                }
                return s;
            }
            case "success": return notes(new double[]{523, 659, 784, 1046}, 0.14, false, 9000);
            default: return notes(new double[]{330, 262, 196}, 0.22, true, 9000); // fail
        }
    }
}
