package com.aimruler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;

public class OverlayService extends Service {
    WindowManager wm;
    float d, r;
    int bounces = 1;
    boolean shown = true;
    Handle cue, obj, aim, tl, br;
    LinesView lines;
    LinearLayout panel;
    final ArrayList<View> all = new ArrayList<>();

    int type() { return Build.VERSION.SDK_INT >= 26 ? LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (wm != null) return START_NOT_STICKY;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("aim", "Линейка прицела", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "aim") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("Линейка прицела включена").setSmallIcon(android.R.drawable.ic_menu_compass).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        setup();
        return START_NOT_STICKY;
    }

    void setup() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics m = getResources().getDisplayMetrics();
        d = m.density; r = 12 * d;
        float w = m.widthPixels, h = m.heightPixels;
        lines = new LinesView();
        LayoutParams lp = new LayoutParams(-1, -1, type(),
            LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCHABLE | LayoutParams.FLAG_LAYOUT_IN_SCREEN | LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(lines, lp);
        all.add(lines);
        tl = new Handle(0xFF4FA3FF, "1", w * .12f, h * .22f);
        br = new Handle(0xFF4FA3FF, "2", w * .88f, h * .85f);
        cue = new Handle(0xFFFFFFFF, "Б", w * .30f, h * .62f);
        obj = new Handle(0xFFFF4B3E, "Ш", w * .55f, h * .48f);
        aim = new Handle(0xFFF2C94C, "+", w * .72f, h * .36f);

        panel = new LinearLayout(this);
        panel.setBackgroundColor(0xCC101A14);
        panel.setPadding(4, 4, 4, 4);
        final Button bB = btn("Отскоки 1");
        bB.setOnClickListener(v -> { bounces = (bounces + 1) % 4; bB.setText("Отскоки " + bounces); lines.invalidate(); });
        Button bM = btn("R-"); bM.setOnClickListener(v -> { r = Math.max(4 * d, r - 1.5f * d); lines.invalidate(); });
        Button bP = btn("R+"); bP.setOnClickListener(v -> { r += 1.5f * d; lines.invalidate(); });
        final Button bS = btn("Скрыть");
        bS.setOnClickListener(v -> {
            shown = !shown;
            for (View x : all) x.setVisibility(shown ? View.VISIBLE : View.GONE);
            bS.setText(shown ? "Скрыть" : "Показать");
        });
        Button bX = btn("Выход"); bX.setOnClickListener(v -> stopSelf());
        panel.addView(bB); panel.addView(bM); panel.addView(bP); panel.addView(bS); panel.addView(bX);
        LayoutParams pp = new LayoutParams(-2, -2, type(), LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        wm.addView(panel, pp);
    }

    Button btn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        return b;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (wm == null) return;
        for (View v : all) try { wm.removeView(v); } catch (Exception e) {}
        try { wm.removeView(panel); } catch (Exception e) {}
        wm = null;
    }

    // Перетаскиваемая точка
    class Handle extends View {
        final int col, S; final String t;
        final PointF pos = new PointF();
        final LayoutParams lp;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Handle(int col, String t, float x, float y) {
            super(OverlayService.this);
            this.col = col; this.t = t; pos.set(x, y); S = (int) (44 * d);
            lp = new LayoutParams(S, S, type(),
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_LAYOUT_IN_SCREEN | LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            move();
            wm.addView(this, lp);
            all.add(this);
        }
        void move() { lp.x = (int) (pos.x - S / 2f); lp.y = (int) (pos.y - S / 2f); }
        @Override protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.FILL); p.setColor((col & 0x00FFFFFF) | 0x66000000);
            c.drawCircle(S / 2f, S / 2f, S / 2f - 3, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(col);
            c.drawCircle(S / 2f, S / 2f, S / 2f - 3, p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(15 * d);
            c.drawText(t, S / 2f, S / 2f + 5 * d, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                pos.set(e.getRawX(), e.getRawY());
                move();
                if (isAttachedToWindow()) wm.updateViewLayout(this, lp);
                lines.invalidate();
            }
            return true;
        }
    }

    static class Tr {
        ArrayList<float[]> pts = new ArrayList<>();
        int pocket = -1; boolean hit; float dx, dy, x, y;
    }

    float[][] pockets() {
        float l = Math.min(tl.pos.x, br.pos.x), rr = Math.max(tl.pos.x, br.pos.x);
        float t = Math.min(tl.pos.y, br.pos.y), b = Math.max(tl.pos.y, br.pos.y);
        float cx = (l + rr) / 2, cy = (t + b) / 2, cr = 2.5f * r, sr = 2.2f * r;
        boolean wide = (rr - l) >= (b - t);
        return new float[][]{
            {l, t, cr}, {rr, t, cr}, {l, b, cr}, {rr, b, cr},
            wide ? new float[]{cx, t, sr} : new float[]{l, cy, sr},
            wide ? new float[]{cx, b, sr} : new float[]{rr, cy, sr}};
    }

    Tr trace(float x, float y, float dx, float dy, int maxB, float[] stop, float maxLen) {
        Tr t = new Tr();
        t.pts.add(new float[]{x, y});
        float l = Math.min(tl.pos.x, br.pos.x) + r, rr = Math.max(tl.pos.x, br.pos.x) - r;
        float tp = Math.min(tl.pos.y, br.pos.y) + r, bt = Math.max(tl.pos.y, br.pos.y) - r;
        float[][] pk = pockets();
        float step = Math.max(1f, r * 0.08f), len = 0;
        int b = 0;
        while (len < maxLen) {
            x += dx * step; y += dy * step; len += step;
            for (int i = 0; i < 6; i++) if (Math.hypot(x - pk[i][0], y - pk[i][1]) < pk[i][2]) { t.pocket = i; break; }
            if (t.pocket >= 0) break;
            if (stop != null && Math.hypot(x - stop[0], y - stop[1]) <= 2 * r) { t.hit = true; break; }
            boolean bn = false;
            if (x < l) { x = 2 * l - x; dx = -dx; bn = true; } else if (x > rr) { x = 2 * rr - x; dx = -dx; bn = true; }
            if (y < tp) { y = 2 * tp - y; dy = -dy; bn = true; } else if (y > bt) { y = 2 * bt - y; dy = -dy; bn = true; }
            if (bn) { b++; t.pts.add(new float[]{x, y}); if (b > maxB) break; }
        }
        t.pts.add(new float[]{x, y});
        t.dx = dx; t.dy = dy; t.x = x; t.y = y;
        return t;
    }

    // Полноэкранный слой с линиями (касания пропускает в игру)
    class LinesView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinesView() { super(OverlayService.this); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        Path path(ArrayList<float[]> pts) {
            Path q = new Path();
            for (int i = 0; i < pts.size(); i++) { float[] a = pts.get(i); if (i == 0) q.moveTo(a[0], a[1]); else q.lineTo(a[0], a[1]); }
            return q;
        }
        @Override protected void onDraw(Canvas c) {
            float l = Math.min(tl.pos.x, br.pos.x), rr = Math.max(tl.pos.x, br.pos.x);
            float t = Math.min(tl.pos.y, br.pos.y), b = Math.max(tl.pos.y, br.pos.y);
            p.setPathEffect(null); p.setStrokeWidth(1.5f * d); p.setColor(0x66FFFFFF);
            c.drawRect(l, t, rr, b, p);
            for (float[] k : pockets()) c.drawCircle(k[0], k[1], k[2], p);

            float dx = aim.pos.x - cue.pos.x, dy = aim.pos.y - cue.pos.y, n = (float) Math.hypot(dx, dy);
            if (n < 1) n = 1; dx /= n; dy /= n;
            Tr ct = trace(cue.pos.x, cue.pos.y, dx, dy, bounces, new float[]{obj.pos.x, obj.pos.y}, 8000);
            p.setStrokeWidth(2 * d); p.setColor(0xFFFFFFFF);
            c.drawPath(path(ct.pts), p);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(cue.pos.x, cue.pos.y, r, p);
            p.setColor(0xFFFF4B3E); c.drawCircle(obj.pos.x, obj.pos.y, r, p);

            String msg; int mc = 0xFFFFFFFF;
            if (ct.pocket >= 0) { msg = "Биток упадёт в лузу"; mc = 0xFFFF6B5E; }
            else if (!ct.hit) msg = "Биток не касается шара";
            else {
                p.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
                p.setColor(0xFFFFFFFF); c.drawCircle(ct.x, ct.y, r, p);
                p.setPathEffect(null);
                float nx = obj.pos.x - ct.x, ny = obj.pos.y - ct.y, nn = (float) Math.hypot(nx, ny);
                if (nn < 1) nn = 1; nx /= nn; ny /= nn;
                Tr ot = trace(obj.pos.x, obj.pos.y, nx, ny, bounces, null, 8000);
                boolean good = ot.pocket >= 0;
                p.setStrokeWidth(2.5f * d); p.setColor(good ? 0xFF5FE08A : 0xFFF2C94C);
                c.drawPath(path(ot.pts), p);
                float cs = ct.dx * nx + ct.dy * ny;
                float px = ct.dx - cs * nx, py = ct.dy - cs * ny, pm = (float) Math.hypot(px, py);
                if (pm > 0.05f) {
                    Tr post = trace(ct.x, ct.y, px / pm, py / pm, 1, null, 12 * r);
                    p.setStrokeWidth(1.5f * d); p.setColor(0x99FFFFFF);
                    p.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
                    c.drawPath(path(post.pts), p); p.setPathEffect(null);
                }
                int cut = (int) Math.round(Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cs)))));
                msg = (good ? "Шар попадёт в лузу" : "Шар не попадёт") + " · угол " + cut + "°";
                mc = good ? 0xFF5FE08A : 0xFFFF6B5E;
            }
            tx.setColor(mc); tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(18 * d);
            tx.setShadowLayer(4 * d, 0, 0, 0xFF000000);
            c.drawText(msg, getWidth() / 2f, 40 * d, tx);
        }
    }
}
