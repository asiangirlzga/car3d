package com.lite.drivingtest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Lite 3D driving test: OpenGL ES 2.0, 128x128 textures (downloaded or generated), synthesized/downloaded sound. */
public class GameView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface Hud { void show(String top, String msg); }

    public volatile boolean up, down, left, right, restart, horn;
    public volatile boolean finished;
    private volatile boolean reloadReq;
    private Hud hud;
    public void setHud(Hud h) { hud = h; }
    public boolean isFinished() { return finished; }

    // ---- course (d = distance driven forward = -z) ----
    static final int CONES = 6;
    static final float STOP_D = 340f, LIGHT_D = 430f, PARK_C = 528f, PARK_X = -4.2f, ROAD_HALF = 7f;
    static final float SPEED_ZONE_A = 200f, SPEED_ZONE_B = 320f;

    // ---- state ----
    float x, z, h, v, steer, score, time, hudT, msgT;
    boolean[] hit = new boolean[CONES];
    boolean stopSeen, stopDone, lightDone, passed;
    String msg = "";
    int limit = 60;
    long last;

    // ---- GL / audio ----
    int prog, aPos, aNor, aUV, uMVP, uCol, uRep;
    int[] tex = new int[4]; // 0 white, 1 road, 2 grass, 3 building
    int curTex = -1;
    FloatBuffer vb;
    final float[] proj = new float[16], view = new float[16], vp = new float[16],
            model = new float[16], mvp = new float[16];
    final Sfx sfx;

    public GameView(Context c) {
        super(c);
        sfx = new Sfx(c);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(5, 6, 5, 0, 16, 0); // RGB565 + 16-bit depth = low RAM
        setRenderer(this);
        reset();
        // install / first-launch download of missing graphics & sounds (see assets.conf)
        Assets.fetchMissing(c.getApplicationContext(), new Runnable() {
            @Override public void run() { reloadReq = true; }
        });
    }

    @Override public void onPause() { sfx.pause(); super.onPause(); }
    @Override public void onResume() { super.onResume(); sfx.resume(); }
    public void release() { sfx.release(); }

    void reset() {
        x = -3.5f; z = 0; h = 0; v = 0; steer = 0; score = 100; time = 0;
        hit = new boolean[CONES];
        stopSeen = stopDone = lightDone = passed = false;
        finished = false;
        say("UP=Gas  DOWN=Brake  LEFT/RIGHT=Steer  OK=Horn. Keep LEFT. Stop at STOP sign, obey red light, park in yellow box", 8);
    }

    void say(String s, float sec) { msg = s; msgT = sec; }

    // ================= LOGIC =================
    void update(float dt) {
        if (horn) { horn = false; if (!finished) sfx.play("horn"); }
        if (finished) { v = Math.max(0, v - 14 * dt); sfx.engine(v / 22f); return; }
        time += dt;

        float target = (left ? -1 : 0) + (right ? 1 : 0);
        float rate = target == 0 ? 5f : 3.5f;
        if (steer < target) steer = Math.min(target, steer + rate * dt);
        else steer = Math.max(target, steer - rate * dt);

        if (up) v += 5f * dt;
        if (down) v -= 14f * dt;
        v -= (0.8f + 0.02f * v) * dt;
        v = Math.max(0, Math.min(22f, v));
        sfx.engine(up ? Math.max(v / 22f, 0.15f) : v / 22f);

        h += steer * Math.min(v / 3f, 1f) * 0.9f / (1f + v / 10f) * dt;
        h = Math.max(-1.3f, Math.min(1.3f, h));
        x += (float) Math.sin(h) * v * dt;
        z -= (float) Math.cos(h) * v * dt;
        if (x > 12) x = 12;
        if (x < -12) x = -12;
        if (z > 5) z = 5;

        float d = -z, kmh = v * 3.6f;

        if (Math.abs(x) > ROAD_HALF + 0.5f) {
            score -= 3f * dt; v = Math.max(0, v - 8f * dt);
            if (msgT <= 0) { say("Off road! -3/sec", 1); sfx.play("beep"); }
        }
        limit = (d > SPEED_ZONE_A && d < SPEED_ZONE_B) ? 30 : 60;
        if (kmh > limit + 3) {
            score -= 1.5f * dt;
            if (msgT <= 0) { say("Over speed limit (" + limit + ")! -1.5/sec", 1); sfx.play("beep"); }
        }
        for (int i = 0; i < CONES; i++) {
            if (hit[i]) continue;
            float dx = x - coneX(i), dz = z + coneD(i);
            if (dx * dx + dz * dz < 2.5f) { hit[i] = true; score -= 5; say("Cone hit! -5", 2); sfx.play("crash"); }
        }
        if (d > STOP_D - 18 && d <= STOP_D && v < 0.8f) stopSeen = true;
        if (!stopDone && d > STOP_D) {
            stopDone = true;
            if (!stopSeen) { score -= 20; say("STOP sign ignored! -20", 3); sfx.play("beep"); }
            else say("Stop OK", 2);
        }
        if (!lightDone && d > LIGHT_D) {
            lightDone = true;
            if (lightPhase() >= 10) { score -= 20; say("Red light jumped! -20", 3); sfx.play("beep"); }
        }
        if (d > 505 && v < 0.3f) {
            boolean in = Math.abs(x - PARK_X) < 1.2f && Math.abs(d - PARK_C) < 5f && Math.abs(h) < 0.2f;
            if (in) finish(true);
            else if (msgT <= 0) say("Park straight inside the yellow box", 1);
        }
        if (d > 575) finish(false);
        if (score <= 0) { score = 0; finish(false); }
    }

    void finish(boolean parked) {
        finished = true;
        passed = parked && score >= 70;
        String r = passed ? "TEST PASSED" : (parked ? "FAILED (score < 70)" : "FAILED");
        say(r + "  Score " + (int) score + "   Press OK to restart", 1e9f);
        sfx.play(passed ? "success" : "fail");
    }

    float lightPhase() { return time % 18f; } // 0-8 green, 8-10 yellow, 10-18 red
    static float coneX(int i) { return (i % 2 == 0) ? -3.5f : 1.5f; }
    static float coneD(int i) { return 70 + i * 20; }

    // ================= GL =================
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig c) {
        GLES20.glClearColor(0.62f, 0.78f, 0.92f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        String vs = "uniform mat4 uMVP;uniform vec2 uRep;uniform vec3 uCol;attribute vec3 aPos;attribute vec3 aNor;"
                + "attribute vec2 aUV;varying vec3 vC;varying vec2 vUV;varying float vF;"
                + "void main(){gl_Position=uMVP*vec4(aPos,1.0);"
                + "float l=0.55+0.45*max(dot(aNor,normalize(vec3(0.4,1.0,0.3))),0.0);"
                + "vC=uCol*l;vUV=aUV*uRep;vF=clamp(gl_Position.w/200.0,0.0,1.0);}";
        String fs = "precision mediump float;uniform sampler2D uTex;varying vec3 vC;varying vec2 vUV;varying float vF;"
                + "void main(){vec3 c=vC*texture2D(uTex,vUV).rgb;"
                + "gl_FragColor=vec4(mix(c,vec3(0.62,0.78,0.92),vF*vF),1.0);}";
        prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(prog);
        GLES20.glUseProgram(prog);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aNor = GLES20.glGetAttribLocation(prog, "aNor");
        aUV = GLES20.glGetAttribLocation(prog, "aUV");
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP");
        uCol = GLES20.glGetUniformLocation(prog, "uCol");
        uRep = GLES20.glGetUniformLocation(prog, "uRep");

        // unit cube: 36 verts * (pos3 + normal3 + uv2)
        float[] d = new float[36 * 8];
        int k = 0;
        float[][] q = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int a = 0; a < 3; a++) for (int s = -1; s <= 1; s += 2) {
            int u = (a + 1) % 3, w = (a + 2) % 3;
            for (float[] p : q) {
                float[] pos = new float[3], nor = new float[3];
                pos[a] = 0.5f * s; pos[u] = 0.5f * p[0]; pos[w] = 0.5f * p[1];
                nor[a] = s;
                for (int i = 0; i < 3; i++) d[k++] = pos[i];
                for (int i = 0; i < 3; i++) d[k++] = nor[i];
                d[k++] = (p[0] + 1) / 2; d[k++] = (p[1] + 1) / 2;
            }
        }
        vb = ByteBuffer.allocateDirect(d.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(d).position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aNor);
        GLES20.glEnableVertexAttribArray(aUV);
        vb.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 32, vb);
        vb.position(3); GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 32, vb);
        vb.position(6); GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 32, vb);
        tex = new int[4];
        loadTextures();
        last = System.nanoTime();
    }

    static int sh(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    // ---------- textures ----------
    void loadTextures() {
        if (tex[0] != 0) { GLES20.glDeleteTextures(4, tex, 0); tex = new int[4]; }
        GLES20.glGenTextures(4, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        ByteBuffer w = ByteBuffer.allocateDirect(4);
        w.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, w);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        for (int i = 0; i < 3; i++) {
            Bitmap b = null;
            try { b = decode(Assets.TEX[i]); } catch (Throwable ignored) { }
            if (b == null) b = proc(i);
            upload(tex[i + 1], b);
        }
        curTex = -1;
    }

    Bitmap decode(String key) {
        File f = Assets.materialize(getContext(), key);
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int ss = 1;
        while (o.outWidth / ss > 512) ss *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = ss;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    void upload(int id, Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 128, 128, true); // power of two: needed for REPEAT + mipmaps
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, s, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        if (s != b) b.recycle();
        s.recycle();
    }

    /** Built-in fallback textures (used when nothing is downloaded). */
    Bitmap proc(int i) {
        int S = 64;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Random r = new Random(7 + i);
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int c;
            if (i == 0) { int g = 62 + r.nextInt(22); c = Color.rgb(g, g, g + 3); }
            else if (i == 1) c = Color.rgb(70 + r.nextInt(30), 130 + r.nextInt(45), 55 + r.nextInt(25));
            else {
                int mx = x % 16, my = y % 16;
                if (mx >= 4 && mx < 12 && my >= 4 && my < 13)
                    c = (mx == 7 || my == 8) ? Color.rgb(170, 190, 210) : Color.rgb(70, 95, 125);
                else { int g = 225 + r.nextInt(15); c = Color.rgb(g, g, g); }
            }
            b.setPixel(x, y, c);
        }
        return b;
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int hgt) {
        GLES20.glViewport(0, 0, w, hgt);
        Matrix.perspectiveM(proj, 0, 60f, (float) w / hgt, 1f, 200f);
    }

    @Override public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        if (reloadReq) { reloadReq = false; loadTextures(); sfx.init(); } // new downloads arrived
        if (restart) { restart = false; if (finished) reset(); }
        update(dt);
        if (msgT > 0 && msgT < 1e8f) msgT -= dt;

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        float dx = (float) Math.sin(h), dz = -(float) Math.cos(h);
        Matrix.setLookAtM(view, 0, x - dx * 8, 3.8f, z - dz * 8, x + dx * 12, 1f, z + dz * 12, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        curTex = -1;
        drawWorld();

        hudT += dt;
        if (hudT > 0.25f && hud != null) {
            hudT = 0;
            hud.show((int) (v * 3.6f) + " km/h   Limit " + limit + "   Score " + (int) Math.max(0, score)
                    + "   Dist " + (int) -z + "/530m", msgT > 0 ? msg : "");
        }
        long el = (System.nanoTime() - now) / 1000000L; // cap ~30 fps
        if (el < 30) try { Thread.sleep(30 - el); } catch (InterruptedException ignored) { }
    }

    void boxT(float cx, float cy, float cz, float sx, float sy, float sz, float yaw,
              float r, float g, float b, int t, float ru, float rv) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cx, cy, cz);
        if (yaw != 0) Matrix.rotateM(model, 0, yaw, 0, 1, 0);
        Matrix.scaleM(model, 0, sx, sy, sz);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniform3f(uCol, r, g, b);
        if (curTex != t) { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[t]); curTex = t; }
        GLES20.glUniform2f(uRep, ru, rv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    void box(float cx, float cy, float cz, float sx, float sy, float sz, float yaw, float r, float g, float b) {
        boxT(cx, cy, cz, sx, sy, sz, yaw, r, g, b, 0, 1, 1);
    }

    void carBox(float lx, float ly, float lf, float sx, float sy, float sz, float r, float g, float b) {
        float c = (float) Math.cos(h), s = (float) Math.sin(h);
        box(x + lx * c + lf * s, ly, z + lx * s - lf * c, sx, sy, sz, (float) Math.toDegrees(-h), r, g, b);
    }

    boolean vis(float zz) { return zz < z + 15 && zz > z - 170; }

    static final float[][] PAL = {{.80f, .72f, .60f}, {.60f, .70f, .80f}, {.85f, .60f, .55f}, {.70f, .78f, .60f}, {.78f, .78f, .82f}};

    void drawWorld() {
        float mid = -290f;
        // textured ground + road (top face: u along z, v along x)
        boxT(0, -0.65f, mid, 300, 1f, 700, 0, 1f, 1f, 1f, 2, 700 / 8f, 300 / 8f);
        boxT(0, -0.10f, mid, ROAD_HALF * 2, 0.2f, 700, 0, 1f, 1f, 1f, 1, 700 / 5f, 14 / 5f);
        box(-6.7f, 0.05f, mid, 0.25f, 0.1f, 700, 0, .95f, .95f, .95f);
        box(6.7f, 0.05f, mid, 0.25f, 0.1f, 700, 0, .95f, .95f, .95f);
        for (int i = 0; i < 60; i++) {
            float zz = -i * 10f - 5;
            if (vis(zz)) box(0, 0.05f, zz, 0.25f, 0.1f, 4, 0, 1f, .95f, .4f);
        }
        // buildings (textured windows)
        for (int k = 0; k < 26; k++) {
            float zz = -20 - k * 26f;
            if (!vis(zz)) continue;
            float bh = 6 + ((k * 7) % 5) * 4;
            float[] cl = PAL[(k * 3) % 5], cr = PAL[(k * 3 + 2) % 5];
            boxT(-20, bh / 2, zz, 12, bh, 16, 0, cl[0], cl[1], cl[2], 3, 3, 3);
            boxT(20, bh / 2 + 1, zz, 12, bh + 2, 16, 0, cr[0], cr[1], cr[2], 3, 3, 3);
        }
        // roadside trees
        for (int k = 0; k < 55; k++) {
            float zz = -8 - k * 12f;
            if (!vis(zz)) continue;
            float sc = 0.8f + (k % 3) * 0.25f;
            float tx = (k % 2 == 0) ? -10.5f : 10.5f;
            box(tx, 1f * sc, zz, 0.4f, 2f * sc, 0.4f, 0, .4f, .27f, .15f);
            box(tx, 3.1f * sc, zz, 2.4f * sc, 2.2f * sc, 2.4f * sc, 0, .15f, .5f, .18f);
            box(tx, 4.7f * sc, zz, 1.5f * sc, 1.4f * sc, 1.5f * sc, 0, .2f, .6f, .22f);
        }
        // cones
        for (int i = 0; i < CONES; i++) {
            float cx = coneX(i), cz = -coneD(i);
            if (!vis(cz)) continue;
            if (hit[i]) box(cx + 0.8f, 0.15f, cz - 0.6f, 0.7f, 0.3f, 0.35f, 40, 1f, .45f, .05f);
            else {
                box(cx, 0.05f, cz, 0.6f, 0.1f, 0.6f, 0, .2f, .2f, .2f);
                box(cx, 0.45f, cz, 0.35f, 0.7f, 0.35f, 0, 1f, .45f, .05f);
                box(cx, 0.55f, cz, 0.37f, 0.12f, 0.37f, 0, 1f, 1f, 1f);
            }
        }
        // speed zone signs
        for (int s = 0; s < 2; s++) {
            float sz = -(s == 0 ? SPEED_ZONE_A : SPEED_ZONE_B);
            if (!vis(sz)) continue;
            box(8f, 1.3f, sz, 0.15f, 2.6f, 0.15f, 0, .5f, .5f, .5f);
            box(8f, 2.9f, sz, 1.3f, 1.3f, 0.1f, 0, .85f, .1f, .1f);
            box(8f, 2.9f, sz + 0.06f, 0.9f, 0.9f, 0.1f, 0, 1f, 1f, 1f);
        }
        float sz = -STOP_D;
        if (vis(sz)) {
            box(-3.5f, 0.05f, sz, 7, 0.1f, 0.5f, 0, 1f, 1f, 1f);
            box(-8f, 1.3f, sz, 0.15f, 2.6f, 0.15f, 0, .5f, .5f, .5f);
            box(-8f, 2.9f, sz, 1.2f, 1.2f, 0.1f, 45, .85f, .05f, .05f);
            box(-8f, 2.9f, sz + 0.06f, 0.8f, 0.2f, 0.1f, 0, 1f, 1f, 1f);
        }
        float lz = -LIGHT_D;
        if (vis(lz)) {
            float ph = lightPhase();
            box(-3.5f, 0.05f, lz, 7, 0.1f, 0.5f, 0, 1f, 1f, 1f);
            box(-8f, 2.7f, lz, 0.2f, 5.4f, 0.2f, 0, .3f, .3f, .3f);
            box(-8f, 5.2f, lz, 0.9f, 2.4f, 0.6f, 0, .08f, .08f, .08f);
            boolean red = ph >= 10, yel = ph >= 8 && ph < 10, grn = ph < 8;
            box(-8f, 6.0f, lz + 0.35f, 0.6f, 0.6f, 0.1f, 0, red ? 1f : .25f, 0, 0);
            box(-8f, 5.2f, lz + 0.35f, 0.6f, 0.6f, 0.1f, 0, yel ? 1f : .25f, yel ? .85f : .2f, 0);
            box(-8f, 4.4f, lz + 0.35f, 0.6f, 0.6f, 0.1f, 0, 0, grn ? 1f : .25f, 0);
        }
        float pz = -PARK_C;
        if (vis(pz)) {
            box(PARK_X, 0.05f, pz, 4f, 0.1f, 10f, 0, .12f, .12f, .14f);
            box(PARK_X - 2f, 0.07f, pz, 0.2f, 0.1f, 10f, 0, 1f, .9f, 0);
            box(PARK_X + 2f, 0.07f, pz, 0.2f, 0.1f, 10f, 0, 1f, .9f, 0);
            box(PARK_X, 0.07f, pz - 5f, 4.2f, 0.1f, 0.2f, 0, 1f, .9f, 0);
            box(PARK_X, 0.07f, pz + 5f, 4.2f, 0.1f, 0.2f, 0, 1f, .9f, 0);
        }
        // car (with shadow, brake lights, headlights)
        float br = down ? 1f : .45f;
        carBox(0, 0.02f, 0, 2.3f, 0.04f, 4.5f, .08f, .08f, .09f);
        carBox(0, 0.6f, 0, 2f, 0.7f, 4.2f, .8f, .1f, .1f);
        carBox(0, 1.2f, -0.3f, 1.7f, 0.6f, 2.0f, .7f, .85f, .95f);
        for (int sx = -1; sx <= 1; sx += 2) for (int sf = -1; sf <= 1; sf += 2)
            carBox(sx * 1.0f, 0.35f, sf * 1.4f, 0.3f, 0.7f, 0.7f, .05f, .05f, .05f);
        carBox(-0.7f, 0.7f, -2.12f, 0.4f, 0.2f, 0.05f, br, 0.05f, 0.05f);
        carBox(0.7f, 0.7f, -2.12f, 0.4f, 0.2f, 0.05f, br, 0.05f, 0.05f);
        carBox(-0.7f, 0.7f, 2.12f, 0.4f, 0.2f, 0.05f, 1f, 1f, .8f);
        carBox(0.7f, 0.7f, 2.12f, 0.4f, 0.2f, 0.05f, 1f, 1f, .8f);
    }
}
