package com.lite.drivingtest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private GameView gv;
    private TextView top, msg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        gv = new GameView(this);
        root.addView(gv);

        top = label(22, Color.WHITE);
        root.addView(top, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        msg = label(26, Color.YELLOW);
        msg.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 30;
        root.addView(msg, lp);
        setContentView(root);

        gv.setHud(new GameView.Hud() {
            @Override public void show(final String t, final String m) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        top.setText(t);
                        msg.setText(m);
                        msg.setVisibility(m.length() == 0 ? View.GONE : View.VISIBLE);
                    }
                });
            }
        });
    }

    private TextView label(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setBackgroundColor(0x88000000);
        t.setPadding(20, 8, 20, 8);
        return t;
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onPause() { super.onPause(); gv.onPause(); }
    @Override protected void onResume() { super.onResume(); gv.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); gv.release(); }

    private boolean key(int k, boolean down) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: case KeyEvent.KEYCODE_CHANNEL_UP:
                gv.up = down; return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                gv.down = down; return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A:
                gv.left = down; return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D:
                gv.right = down; return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_SPACE:
                if (down) { if (gv.isFinished()) gv.restart = true; else gv.horn = true; }
                return true;
        }
        return false;
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) { return key(k, true) || super.onKeyDown(k, e); }
    @Override public boolean onKeyUp(int k, KeyEvent e) { return key(k, false) || super.onKeyUp(k, e); }
}
