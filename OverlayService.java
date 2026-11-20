package com.aimruler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class OverlayService extends Service {
    WindowManager wm;
    float d, r;
    int bounces = 1;
    boolean shown = true;
    static volatile boolean running;
    boolean auto;
    int mode;                         // 0 = ручной, 1 = скан по кнопке, 2 = автоскан
    boolean have, busy, wantShot;     // есть ли результат скана / идёт ли обработка / просили скан
    int failStreak, sw, sh;
    long lastScan;
    float sTL, sTT, sBR, sBB, sR, cX, cY, aX, aY;
    float[] bX = new float[0], bY = new float[0];
    ImageReader stream;
    Runnable watch;
    String lastMsg;                   // почему сейчас нет прицела (для автоскана)
    Vision.Result pend;               // «скачок» результата ждёт подтверждения следующим кадром
    final Runnable acceptRun = new Runnable() { @Override public void run() { acceptPendNow(); } };
    MediaProjection proj;
    VirtualDisplay vd;
    final Handler ui = new Handler(Looper.getMainLooper());
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
        mode = in != null ? in.getIntExtra("mode", 0) : 0;
        auto = mode != 0;
        if (Build.VERSION.SDK_INT >= 34) {
            int ft = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (auto) ft |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(1, n, ft);
        } else if (auto && Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
        if (auto) {
            try {
                Intent data = in.getParcelableExtra("data");
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                proj = mpm.getMediaProjection(in.getIntExtra("code", 0), data);
                proj.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() {
                        proj = null; vd = null;
                        toast("Запись экрана остановлена. Включите линейку заново.");
                    }
                }, ui);
            } catch (Exception e) {
                proj = null;
                toast("Не удалось начать запись экрана: " + e);
            }
        }
        setup();
        if (auto && proj != null) startStream();
        running = true;
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
        if (mode == 0) {
            tl = new Handle(0xFF4FA3FF, "1", w * .12f, h * .22f);
            br = new Handle(0xFF4FA3FF, "2", w * .88f, h * .85f);
            cue = new Handle(0xFFFFFFFF, "Б", w * .30f, h * .62f);
            obj = new Handle(0xFFFF4B3E, "Ш", w * .55f, h * .48f);
            aim = new Handle(0xFFF2C94C, "+", w * .72f, h * .36f);
        }

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
        if (mode == 1) { Button bA = btn("Скан"); bA.setOnClickListener(v -> scan()); panel.addView(bA); }
        panel.addView(bB);
        if (mode == 0) { panel.addView(bM); panel.addView(bP); }
        panel.addView(bS); panel.addView(bX);
        LayoutParams pp = new LayoutParams(-2, -2, type(), LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        wm.addView(panel, pp);
    }

    Button btn(String t) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(12); if (Build.VERSION.SDK_INT >= 28) b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding((int) (8 * d), (int) (6 * d), (int) (8 * d), (int) (6 * d));
        return b;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        running = false;
        ui.removeCallbacksAndMessages(null);
        try { if (stream != null) stream.close(); } catch (Exception e) {}
        stream = null;
        try { if (vd != null) vd.release(); } catch (Exception e) {}
        vd = null;
        try { if (proj != null) { MediaProjection p = proj; proj = null; p.stop(); } } catch (Exception e) {}
        if (wm == null) return;
        for (View v : all) try { wm.removeView(v); } catch (Exception e) {}
        try { wm.removeView(panel); } catch (Exception e) {}
        wm = null;
    }

    void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_LONG).show(); }

    // ---- Авто-режимы: поток кадров экрана, распознавание, расчёт траектории ----

    void scan() {                      // кнопка «Скан»
        if (proj == null) { toast("Нет доступа к экрану. Выключите и включите линейку заново."); return; }
        if (busy) return;
        wantShot = true;
        ui.postDelayed(() -> { if (wantShot) { wantShot = false; toast("Не получен кадр экрана. Попробуйте ещё раз."); } }, 2500);
    }

    void buildStream() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sw = dm.widthPixels; sh = dm.heightPixels;
        ImageReader old = stream;
        stream = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2);
        if (vd == null) {
            vd = proj.createVirtualDisplay("aimruler", sw, sh, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, stream.getSurface(), null, null);
        } else {
            vd.resize(sw, sh, dm.densityDpi);
            vd.setSurface(stream.getSurface());
        }
        stream.setOnImageAvailableListener(this::onFrame, ui);
        if (old != null) { try { old.close(); } catch (Exception e) {} }
    }

    void startStream() {
        try { buildStream(); } catch (Throwable e) { toast("Ошибка захвата экрана: " + e); return; }
        watch = new Runnable() {
            @Override public void run() {
                if (wm == null || proj == null) return;
                try {
                    DisplayMetrics dm = new DisplayMetrics();
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    if (dm.widthPixels != sw || dm.heightPixels != sh) buildStream();   // экран повернули
                } catch (Throwable e) {}
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(watch, 1000);
    }

    void onFrame(ImageReader rd) {
        Image img = null;
        try { img = rd.acquireLatestImage(); } catch (Throwable e) { img = null; }
        if (img == null) return;
        long now = SystemClock.uptimeMillis();
        boolean take = !busy && (wantShot || (mode == 2 && now - lastScan > 300));
        if (!take) { try { img.close(); } catch (Exception e) {} return; }
        busy = true; wantShot = false; lastScan = now;
        final int w = img.getWidth(), h = img.getHeight();
        int[] pixels = null;
        try {
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int ps = pl.getPixelStride(), rs = pl.getRowStride();
            Bitmap bmp = Bitmap.createBitmap(rs / ps, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            bmp.recycle();
        } catch (Throwable e) { pixels = null; }
        try { img.close(); } catch (Exception e) {}
        final int[] px = pixels;
        new Thread(() -> {
            Vision.Result res = null;
            try { if (px != null) res = Vision.detect(px, w, h); } catch (Throwable e) { res = null; }
            final Vision.Result fr = res;
            ui.post(() -> finishScan(fr));
        }).start();
    }

    void apply(Vision.Result res) {
        have = true;
        sTL = res.l; sTT = res.t; sBR = res.rr; sBB = res.b; sR = res.r;
        cX = res.cx; cY = res.cy; aX = res.ax; aY = res.ay;
        int n = res.nb + (res.hasObj ? 1 : 0);
        float[] nx = new float[n], ny = new float[n]; int k = 0;
        for (int i = 0; i < res.nb; i++) { nx[k] = res.bx[i]; ny[k] = res.by[i]; k++; }
        if (res.hasObj) {
            // Шар-цель: берём центр из общего поиска шаров (он точнее грубой оценки у «призрака»)
            int di = -1; double dBest = 0.7f * res.r;
            for (int i = 0; i < res.nb; i++) {
                double dd = Math.hypot(res.bx[i] - res.ox, res.by[i] - res.oy);
                if (dd < dBest) { dBest = dd; di = i; }
            }
            if (di >= 0) {
                // Расстояние «призрак» -> шар задаёт реальную дистанцию касания игры (2R): подгоняем радиус
                double dist = Math.hypot(nx[di] - res.ax, ny[di] - res.ay);
                sR = (float) Math.max(0.88f * res.r, Math.min(1.06f * res.r, dist / 2));
            } else { nx[k] = res.ox; ny[k] = res.oy; k++; }
        }
        bX = java.util.Arrays.copyOf(nx, k); bY = java.util.Arrays.copyOf(ny, k);
    }

    void acceptPendNow() {
        Vision.Result p = pend; pend = null;
        if (p == null || wm == null) return;
        apply(p);
        lines.invalidate();
    }

    // Новый результат сильно отличается от прежнего (биток сдвинулся или линия резко повернулась)?
    boolean jumped(Vision.Result n) {
        if (Math.hypot(n.cx - cX, n.cy - cY) > 0.8f * sR) return true;
        double a0 = Math.atan2(aY - cY, aX - cX), a1 = Math.atan2(n.ay - n.cy, n.ax - n.cx);
        double dd = Math.abs(a1 - a0);
        if (dd > Math.PI) dd = 2 * Math.PI - dd;
        return Math.toDegrees(dd) > 10;
    }

    void finishScan(Vision.Result res) {
        busy = false;
        if (wm == null) return;
        lastMsg = (res == null || res.ok) ? null : res.msg;
        if (res != null && res.ok) {
            failStreak = 0;
            if (mode == 2 && have && jumped(res)) {
                // Резкий скачок может быть сбоем распознавания одного кадра.
                // Если следующий кадр тоже такой же «скачок» (или кадров больше нет 0.45 с) — принимаем.
                if (pend != null) { pend = null; ui.removeCallbacks(acceptRun); apply(res); }
                else { pend = res; ui.postDelayed(acceptRun, 450); }
            } else {
                pend = null; ui.removeCallbacks(acceptRun);
                apply(res);
            }
        } else {
            if (mode == 1) {
                toast(res == null ? "Не удалось обработать кадр экрана." : res.msg);
                have = false;
            } else if (have) {
                // Игра может прятать свою линию, пока вы не касаетесь экрана. Если биток стоит на месте —
                // значит, вы просто думаете: оставляем последний прицел как есть.
                boolean same = res != null && res.cueOk && Math.hypot(res.cx - cX, res.cy - cY) < 0.6f * sR;
                if (same) failStreak = 0;
                else if (++failStreak >= 3) { have = false; pend = null; ui.removeCallbacks(acceptRun); }
            } else {
                failStreak++;
            }
        }
        lines.invalidate();
    }

    float[][] pocketsN() {
        float cx = (sTL + sBR) / 2, cr = 2.5f * sR, sr = 2.2f * sR;
        return new float[][]{{sTL, sTT, cr}, {sBR, sTT, cr}, {sTL, sBB, cr}, {sBR, sBB, cr}, {cx, sTT, sr}, {cx, sBB, sr}};
    }

    // Траектория шара с отскоками от бортов; останавливается на лузе или на первом шаре (кроме skip)
    Tr traceN(float x, float y, float dx, float dy, int maxB, int skip, float maxLen) {
        Tr t = new Tr();
        t.pts.add(new float[]{x, y});
        float l = sTL + sR, rr = sBR - sR, tp = sTT + sR, bt = sBB - sR;
        float[][] pk = pocketsN();
        float step = Math.max(1f, sR * 0.08f), len = 0;
        int b = 0;
        while (len < maxLen) {
            x += dx * step; y += dy * step; len += step;
            for (int i = 0; i < 6; i++) if (Math.hypot(x - pk[i][0], y - pk[i][1]) < pk[i][2]) { t.pocket = i; break; }
            if (t.pocket >= 0) break;
            for (int i = 0; i < bX.length; i++) {
                if (i == skip) continue;
                if (Math.hypot(x - bX[i], y - bY[i]) <= 2 * sR) { t.hi = i; break; }
            }
            if (t.hi >= 0) break;
            boolean bn = false;
            if (x < l) { x = 2 * l - x; dx = -dx; bn = true; } else if (x > rr) { x = 2 * rr - x; dx = -dx; bn = true; }
            if (y < tp) { y = 2 * tp - y; dy = -dy; bn = true; } else if (y > bt) { y = 2 * bt - y; dy = -dy; bn = true; }
            if (bn) { b++; t.pts.add(new float[]{x, y}); if (b > maxB) break; }
        }
        t.pts.add(new float[]{x, y});
        t.dx = dx; t.dy = dy; t.x = x; t.y = y;
        return t;
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
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        int pocket = -1, hi = -1; boolean hit; float dx, dy, x, y;
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
        void drawClean(Canvas c) {
            final int PINK = 0xFFFF4FB8, GREEN = 0xFF5FE08A, YELLOW = 0xFFF2C94C, RED = 0xFFFF6B5E;
            tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(18 * d); tx.setShadowLayer(4 * d, 0, 0, 0xFF000000);
            if (!have) {
                if (mode == 1) { tx.setColor(0xFFFFFFFF); c.drawText("Прицельтесь и нажмите «Скан»", getWidth() / 2f, 40 * d, tx); }
                else if (mode == 2 && lastMsg != null) { tx.setColor(0xFFFFD54F); tx.setTextSize(14 * d); c.drawText(lastMsg, getWidth() / 2f, 40 * d, tx); }
                return;
            }
            float R = sR;
            float dx = aX - cX, dy = aY - cY, n = (float) Math.hypot(dx, dy);
            if (n < 1) n = 1; dx /= n; dy /= n;
            Tr ct = traceN(cX, cY, dx, dy, bounces, -1, 8000);
            boolean bank = ct.pts.size() > 2;
            p.setPathEffect(null); p.setStyle(Paint.Style.STROKE);
            // первый отрезок до касания рисует сама игра; мы рисуем всё, что после него (отскоки от бортов)
            if (bank) {
                p.setStrokeWidth(2.5f * d); p.setColor(PINK);
                c.drawPath(path(new ArrayList<>(ct.pts.subList(1, ct.pts.size()))), p);
            }
            String msg; int mc = 0xFFFFFFFF;
            if (ct.pocket >= 0) { msg = "Биток упадёт в лузу"; mc = RED; }
            else if (ct.hi < 0) msg = bank ? "После отскока биток не касается шаров" : "Биток не касается шаров";
            else {
                float ox = bX[ct.hi], oy = bY[ct.hi];
                if (bank) {
                    p.setStrokeWidth(1.5f * d); p.setColor(PINK);
                    p.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
                    c.drawCircle(ct.x, ct.y, R, p); p.setPathEffect(null);
                }
                float nx = ox - ct.x, ny = oy - ct.y, nn = (float) Math.hypot(nx, ny);
                if (nn < 1) nn = 1; nx /= nn; ny /= nn;
                Tr ot = trace2(ox, oy, nx, ny, ct.hi);
                boolean good = ot.pocket >= 0;
                p.setStrokeWidth(3 * d); p.setColor(good ? GREEN : YELLOW);
                c.drawPath(path(ot.pts), p);
                if (good) {
                    float[] pt = ot.pts.get(ot.pts.size() - 1);
                    p.setStyle(Paint.Style.FILL); c.drawCircle(pt[0], pt[1], 6 * d, p); p.setStyle(Paint.Style.STROKE);
                }
                float cs = ct.dx * nx + ct.dy * ny;
                float px = ct.dx - cs * nx, py = ct.dy - cs * ny, pm = (float) Math.hypot(px, py);
                if (pm > 0.05f) {
                    Tr post = traceN(ct.x, ct.y, px / pm, py / pm, 1, ct.hi, 12 * R);
                    p.setStrokeWidth(1.5f * d); p.setColor(PINK);
                    p.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
                    c.drawPath(path(post.pts), p); p.setPathEffect(null);
                }
                int cut = (int) Math.round(Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cs)))));
                msg = (bank ? "После отскока: " : "") + (good ? (bank ? "шар попадёт в лузу" : "Шар попадёт в лузу") : (bank ? "шар не попадёт" : "Шар не попадёт")) + " · угол " + cut + "°";
                mc = good ? GREEN : RED;
            }
            tx.setColor(mc);
            c.drawText(msg, getWidth() / 2f, 40 * d, tx);
        }

        Tr trace2(float x, float y, float dx, float dy, int skip) { return traceN(x, y, dx, dy, bounces, skip, 8000); }

        @Override protected void onDraw(Canvas c) {
            if (mode != 0) { drawClean(c); return; }
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


/** Распознавание стола, битка, линии прицела игры и прицельного шара по снимку экрана. Чистая Java. */
class Vision {
    public static class Result {
        public boolean ok;
        public boolean tableOk, cueOk;        // стол найден / биток найден (даже если линии прицела игры сейчас нет)
        public String msg = "";
        public float l, t, rr, b, r;          // внутренняя область сукна и радиус шара (пиксели экрана)
        public float cx, cy;                  // биток
        public float ax, ay;                  // центр «призрачного» битка: где линия прицела игры заканчивается
        public boolean hasObj;
        public float ox, oy;                  // шар, в который целимся
        public int nb;                        // все остальные шары на столе (без битка)
        public float[] bx = new float[0], by = new float[0];
    }

    public static boolean DEBUG = false;

    static int R(int p) { return (p >> 16) & 255; }
    static int G(int p) { return (p >> 8) & 255; }
    static int B(int p) { return p & 255; }

    /** hsv: [0]=оттенок 0..360, [1]=насыщенность, [2]=яркость */
    static void hsv(int p, float[] o) {
        float r = R(p) / 255f, g = G(p) / 255f, b = B(p) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        float h;
        if (d == 0) h = 0;
        else if (mx == r) h = 60f * (((g - b) / d) % 6f);
        else if (mx == g) h = 60f * ((b - r) / d + 2f);
        else h = 60f * ((r - g) / d + 4f);
        if (h < 0) h += 360f;
        o[0] = h; o[1] = mx == 0 ? 0 : d / mx; o[2] = mx;
    }

    // 0 = сукно ищем по оттенку (цветные столы), 1 = по близости к основному цвету (серые, чёрные, бледные столы)
    static int clothMode = 0;
    static float refR, refG, refB, refV;

    static int med(int[] c, int tot) {
        int acc = 0;
        for (int i = 0; i < c.length; i++) { acc += c[i]; if (acc * 2 >= tot) return i; }
        return 0;
    }

    static boolean cloth(int p, float h0, float[] t) {
        if (clothMode == 1) {
            int r = R(p), g = G(p), b = B(p);
            float v = Math.max(r, Math.max(g, b));
            if (v < refV * 0.55f || v > refV * 1.45f) return false;
            float sc = refV / Math.max(1f, v);
            return Math.abs(r * sc - refR) + Math.abs(g * sc - refG) + Math.abs(b * sc - refB) <= 60f;
        }
        hsv(p, t);
        float dh = Math.abs(t[0] - h0);
        if (dh > 180) dh = 360 - dh;
        return dh <= 14f && t[1] > 0.35f && t[2] > 0.30f;
    }

    static boolean whiteish(int p) {
        int r = R(p), g = G(p), b = B(p);
        return r >= 165 && g >= 160 && b >= 130 && r - b <= 75 && Math.abs(r - g) <= 30;
    }

    static boolean line(int p) { return Math.min(R(p), Math.min(G(p), B(p))) >= 225; }

    /** Заливка: возвращает метки компонент (1..n), 0 = фон. */
    static int[] label(boolean[] m, int w, int h) {
        int[] lab = new int[w * h];
        int[] stack = new int[w * h];
        int n = 0;
        for (int i = 0; i < w * h; i++) {
            if (!m[i] || lab[i] != 0) continue;
            n++;
            int sp = 0; stack[sp++] = i; lab[i] = n;
            while (sp > 0) {
                int q = stack[--sp];
                int x = q % w, y = q / w;
                if (x > 0 && m[q - 1] && lab[q - 1] == 0) { lab[q - 1] = n; stack[sp++] = q - 1; }
                if (x < w - 1 && m[q + 1] && lab[q + 1] == 0) { lab[q + 1] = n; stack[sp++] = q + 1; }
                if (y > 0 && m[q - w] && lab[q - w] == 0) { lab[q - w] = n; stack[sp++] = q - w; }
                if (y < h - 1 && m[q + w] && lab[q + w] == 0) { lab[q + w] = n; stack[sp++] = q + w; }
            }
        }
        return lab;
    }

    static boolean[] erode(boolean[] m, int w, int h, int e) {
        boolean[] a = new boolean[w * h], o = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ok = true;
            for (int k = -e; k <= e && ok; k++) { int xx = x + k; if (xx < 0 || xx >= w || !m[y * w + xx]) ok = false; }
            a[y * w + x] = ok;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ok = true;
            for (int k = -e; k <= e && ok; k++) { int yy = y + k; if (yy < 0 || yy >= h || !a[yy * w + x]) ok = false; }
            o[y * w + x] = ok;
        }
        return o;
    }

    public static Result detect(int[] px, int w, int h) {
        clothMode = 0;
        Result r1 = detectImpl(px, w, h);
        if (r1.tableOk) return r1;
        clothMode = 1;   // запасной вариант: серое, тёмное или бледное сукно
        Result r2 = detectImpl(px, w, h);
        if (r2.tableOk) return r2;
        clothMode = 0;
        return r1;
    }

    static Result detectImpl(int[] px, int w, int h) {
        Result res = new Result();
        float[] t = new float[3];

        // 1. Цвет сукна: самый частый оттенок в центре экрана
        int[] hist = new int[72];
        int cxs = w / 2, cys = h / 2, cnt0 = 0;
        for (int y = cys - 60; y <= cys + 60; y += 2) for (int x = cxs - 120; x <= cxs + 120; x += 2) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            hsv(px[y * w + x], t);
            if (t[1] > 0.35f && t[2] > 0.30f) { hist[((int) (t[0] / 5f)) % 72]++; cnt0++; }
        }
        float h0;
        if (clothMode == 1) {
            int[] qr = new int[256], qg = new int[256], qb = new int[256]; int tot = 0;
            for (int y = cys - 60; y <= cys + 60; y += 2) for (int x = cxs - 120; x <= cxs + 120; x += 2) {
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int pp = px[y * w + x];
                qr[R(pp)]++; qg[G(pp)]++; qb[B(pp)]++; tot++;
            }
            if (tot < 200) { res.msg = "Не вижу сукно по центру экрана."; return res; }
            refR = med(qr, tot); refG = med(qg, tot); refB = med(qb, tot);
            refV = Math.max(refR, Math.max(refG, refB));
            if (refV < 20) { res.msg = "Сукно слишком тёмное для распознавания."; return res; }
            h0 = 0;
        } else {
            if (cnt0 < 200) { res.msg = "Не вижу сукно по центру экрана. Откройте игру со столом."; return res; }
            int bi = 0, bv = -1;
            for (int i = 0; i < 72; i++) {
                int v = hist[i] * 2 + hist[(i + 71) % 72] + hist[(i + 1) % 72];
                if (v > bv) { bv = v; bi = i; }
            }
            h0 = bi * 5f + 2.5f;
        }

        // 2. Стол: самая большая область цвета сукна (в половинном разрешении)
        int mw = w / 2, mh = h / 2;
        boolean[] cm = new boolean[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) cm[y * mw + x] = cloth(px[(y * 2) * w + x * 2], h0, t);
        int[] lab = label(cm, mw, mh);
        int best = -1, bestCnt = 0;
        int nlab = 0;
        for (int v : lab) if (v > nlab) nlab = v;
        int[] cnt = new int[nlab + 1]; int[] bx0 = new int[nlab + 1], bx1 = new int[nlab + 1], by0 = new int[nlab + 1], by1 = new int[nlab + 1];
        for (int i = 1; i <= nlab; i++) { bx0[i] = mw; by0[i] = mh; bx1[i] = -1; by1[i] = -1; }
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int v = lab[y * mw + x]; if (v == 0) continue;
            cnt[v]++; if (x < bx0[v]) bx0[v] = x; if (x > bx1[v]) bx1[v] = x; if (y < by0[v]) by0[v] = y; if (y > by1[v]) by1[v] = y;
        }
        for (int i = 1; i <= nlab; i++) if (cnt[i] > bestCnt) { bestCnt = cnt[i]; best = i; }
        if (best < 0) { res.msg = "Стол не найден."; return res; }
        int oL = bx0[best] * 2, oR = bx1[best] * 2 + 1, oT = by0[best] * 2, oB = by1[best] * 2 + 1;
        float ow = oR - oL, oh = oB - oT;
        if (ow < w * 0.35f || ow / oh < 1.3f || ow / oh > 2.6f) { res.msg = "Стол найден неверно. Откройте игру на весь экран (альбомная ориентация)."; return res; }
        int k = Math.round(0.0191f * ow); // ширина бортовой полосы
        res.l = oL + k; res.rr = oR - k; res.t = oT + k; res.b = oB - k;
        float il = res.l, ir = res.rr, it = res.t, ib = res.b;
        float r0 = 0.0151f * (ir - il);
        res.tableOk = true;
        res.l = il; res.rr = ir; res.t = it; res.b = ib; res.r = r0;

        // 3. Биток: белое круглое пятно. Считаем долю «белых» пикселей в квадрате по интегральному изображению.
        int W1 = w + 1;
        int[] ii = new int[W1 * (h + 1)];
        for (int y = 0; y < h; y++) {
            int row = 0;
            for (int x = 0; x < w; x++) {
                if (whiteish(px[y * w + x])) row++;
                ii[(y + 1) * W1 + x + 1] = ii[y * W1 + x + 1] + row;
            }
        }
        int hw = Math.round(0.62f * r0);
        // кандидаты в биток: лучшие места по доле белого (с подавлением соседей)
        int K = 8;
        double[] cf = new double[K]; int[] cxs2 = new int[K], cys2 = new int[K]; int nc = 0;
        for (int y = (int) it + hw; y <= (int) ib - hw; y += 3) {
            for (int x = (int) il + hw; x <= (int) ir - hw; x += 3) {
                int sum = ii[(y + hw + 1) * W1 + x + hw + 1] - ii[(y - hw) * W1 + x + hw + 1] - ii[(y + hw + 1) * W1 + x - hw] + ii[(y - hw) * W1 + x - hw];
                double fr = sum / (double) ((2 * hw + 1) * (2 * hw + 1));
                if (fr < 0.60) continue;
                int near = -1;
                for (int i = 0; i < nc; i++) { double ddx = cxs2[i] - x, ddy = cys2[i] - y; if (ddx * ddx + ddy * ddy < 1.5 * 1.5 * r0 * r0) { near = i; break; } }
                if (near >= 0) { if (fr > cf[near]) { cf[near] = fr; cxs2[near] = x; cys2[near] = y; } }
                else if (nc < K) { cf[nc] = fr; cxs2[nc] = x; cys2[nc] = y; nc++; }
                else { int mi = 0; for (int i = 1; i < K; i++) if (cf[i] < cf[mi]) mi = i; if (fr > cf[mi]) { cf[mi] = fr; cxs2[mi] = x; cys2[mi] = y; } }
            }
        }
        if (nc == 0) { res.msg = "Не вижу белый шар (биток)."; return res; }
        float r = r0;
        res.r = r;
        double[] bestFit = null; double bestLen = 0;
        double bestWhite = 0; float wx = 0, wy = 0;
        for (int ci = 0; ci < nc; ci++) {
            float cx = cxs2[ci], cy = cys2[ci];
            for (int iter = 0; iter < 3; iter++) {
                double sx = 0, sy = 0; int n = 0;
                int rr0 = Math.round(0.95f * r0);
                for (int y = Math.round(cy) - rr0; y <= Math.round(cy) + rr0; y++) for (int x = Math.round(cx) - rr0; x <= Math.round(cx) + rr0; x++) {
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > rr0 * rr0) continue;
                    if (whiteish(px[y * w + x])) { sx += x; sy += y; n++; }
                }
                if (n > 20) { cx = (float) (sx / n); cy = (float) (sy / n); }
            }
            if (cf[ci] > bestWhite) { bestWhite = cf[ci]; wx = cx; wy = cy; }
            double[] fit = aimFrom(px, w, h, cx, cy, r, ow + 40);
            if (DEBUG) System.out.printf("[dbg] кандидат %d: (%.0f,%.0f) белое=%.2f длина линии=%.0f%n", ci, cx, cy, cf[ci], fit == null ? 0 : fit[0]);
            if (fit != null && fit[0] > bestLen) { bestLen = fit[0]; bestFit = fit; }
        }
        if (bestFit == null || bestLen < 3 * r) {
            if (bestWhite >= 0.65) { res.cueOk = true; res.cx = wx; res.cy = wy; }
            res.msg = "Не вижу линию прицела игры. Наведите кий на шар, чтобы игра показала линию.";
            return res;
        }
        res.cx = (float) bestFit[2]; res.cy = (float) bestFit[3];
        double ang = bestFit[1];
        double dx = Math.cos(ang), dy = Math.sin(ang);
        // линия игры заканчивается в центре «призрачного» битка
        res.ax = (float) bestFit[4]; res.ay = (float) bestFit[5];
        if (DEBUG) System.out.printf("[dbg] угол=%.2f° длина линии=%.0f%n", Math.toDegrees(ang), bestLen);

        // 5. Шар, в который целимся: касается «призрачного» битка (расстояние 2r), в стороне удара
        double bestScore = -1, bestDelta = 9, bestPhi = 0;
        for (double dphi = -100; dphi <= 100; dphi += 1.5) {
            double phi = ang + Math.toRadians(dphi);
            float pxc = (float) (res.ax + 2 * r * Math.cos(phi)), pyc = (float) (res.ay + 2 * r * Math.sin(phi));
            if (pxc < il - 4 || pxc > ir + 4 || pyc < it - 4 || pyc > ib + 4) continue;
            double sc = diskNonCloth(px, w, h, pxc, pyc, 0.75f * r, h0, t);
            double dl = Math.abs(dphi);
            if (sc > bestScore + 0.01 || (Math.abs(sc - bestScore) <= 0.01 && dl < bestDelta)) { bestScore = sc; bestDelta = dl; bestPhi = phi; }
        }
        if (bestScore >= 0.85) {
            float pxc = (float) (res.ax + 2 * r * Math.cos(bestPhi)), pyc = (float) (res.ay + 2 * r * Math.sin(bestPhi));
            // уточнение: среднее по «плато» вокруг найденной точки
            double sx = 0, sy = 0; int n = 0;
            double maxSc = 0;
            for (int oy = -6; oy <= 6; oy += 2) for (int ox = -6; ox <= 6; ox += 2) {
                double sc = diskNonCloth(px, w, h, pxc + ox, pyc + oy, 0.75f * r, h0, t);
                if (sc > maxSc) maxSc = sc;
            }
            for (int oy = -6; oy <= 6; oy += 2) for (int ox = -6; ox <= 6; ox += 2) {
                double sc = diskNonCloth(px, w, h, pxc + ox, pyc + oy, 0.75f * r, h0, t);
                if (sc >= maxSc - 0.02) { sx += pxc + ox; sy += pyc + oy; n++; }
            }
            res.hasObj = true; res.ox = (float) (sx / n); res.oy = (float) (sy / n);
        }
        findBalls(px, w, h, res, h0);
        res.ok = true;
        res.msg = res.hasObj ? "Готово: стол, биток, прицел и шар найдены." : "Стол, биток и прицел найдены. Шар на линии не найден (линия идёт в борт).";
        return res;
    }

    /** Ищет белую линию прицела, выходящую из точки (cx,cy). Возвращает {длина, угол, cx', cy', конецX, конецY} или null. */
    /** Все шары на столе (кроме битка): «не сукно» -> стирание тонких линий и кия -> центры пятен; слипшиеся пятна делим усиленным стиранием. */
    static void findBalls(int[] px, int w, int h, Result res, float h0) {
        float[] t = new float[3];
        float r = res.r;
        int il = Math.round(res.l), it = Math.round(res.t), ir = Math.round(res.rr), ib = Math.round(res.b);
        int mw = (ir - il) / 2 + 1, mh = (ib - it) / 2 + 1;
        boolean[] m = new boolean[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int X = il + 2 * x, Y = it + 2 * y;
            if (X >= w || Y >= h) continue;
            m[y * mw + x] = !cloth(px[Y * w + X], h0, t);
        }
        int e = Math.max(2, Math.round(0.42f * r / 2f));
        double single = Math.PI * Math.pow(0.9 * r / 2 - e, 2);
        boolean[] er = erode(m, mw, mh, e);
        int[] lab = label(er, mw, mh);
        int n = 0; for (int v : lab) if (v > n) n = v;
        double[] area = new double[n + 1], sx = new double[n + 1], sy = new double[n + 1];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) { int v = lab[y * mw + x]; if (v == 0) continue; area[v]++; sx[v] += x; sy[v] += y; }
        float[] cx = new float[64], cy = new float[64]; int nc = 0;
        // подготовим усиленные стирания лениво
        boolean[][] er2 = new boolean[5][]; int[][] lab2 = new int[5][];
        for (int L = 1; L <= n; L++) {
            if (area[L] < 0.35 * single) continue;
            if (area[L] <= 1.9 * single) {
                if (nc < 64) { cx[nc] = (float) (sx[L] / area[L]); cy[nc] = (float) (sy[L] / area[L]); nc++; }
                continue;
            }
            // большое пятно: несколько слипшихся шаров
            int bestCnt = 0, bestLvl = -1;
            for (int k = 1; k <= 4; k++) {
                if (er2[k] == null) { er2[k] = erode(m, mw, mh, e + k); lab2[k] = label(er2[k], mw, mh); }
                int n2 = 0; for (int v : lab2[k]) if (v > n2) n2 = v;
                double[] a2 = new double[n2 + 1]; boolean[] inL = new boolean[n2 + 1];
                for (int i = 0; i < mw * mh; i++) { int v = lab2[k][i]; if (v != 0 && lab[i] == L) { a2[v]++; inL[v] = true; } }
                int cnt = 0; for (int v = 1; v <= n2; v++) if (inL[v] && a2[v] >= 0.2 * single) cnt++;
                if (cnt > bestCnt) { bestCnt = cnt; bestLvl = k; }
            }
            if (bestCnt >= 2) {
                int k = bestLvl; int n2 = 0; for (int v : lab2[k]) if (v > n2) n2 = v;
                double[] a2 = new double[n2 + 1], x2 = new double[n2 + 1], y2 = new double[n2 + 1]; boolean[] inL = new boolean[n2 + 1];
                for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) { int v = lab2[k][y * mw + x]; if (v != 0 && lab[y * mw + x] == L) { a2[v]++; x2[v] += x; y2[v] += y; inL[v] = true; } }
                for (int v = 1; v <= n2; v++) if (inL[v] && a2[v] >= 0.2 * single && nc < 64) { cx[nc] = (float) (x2[v] / a2[v]); cy[nc] = (float) (y2[v] / a2[v]); nc++; }
            } else if (nc < 64) { cx[nc] = (float) (sx[L] / area[L]); cy[nc] = (float) (sy[L] / area[L]); nc++; }
        }
        float pcx = (res.l + res.rr) / 2f;
        float[] ox = new float[nc], oy = new float[nc]; int no = 0;
        for (int i = 0; i < nc; i++) {
            float X = il + 2 * cx[i], Y = it + 2 * cy[i];
            if (Math.hypot(X - res.cx, Y - res.cy) < 1.2 * r) continue; // это биток
            boolean nearL = X < res.l + 2.6f * r || X > res.rr - 2.6f * r;
            boolean nearT = Y < res.t + 2.6f * r || Y > res.b - 2.6f * r;
            if (nearL && nearT) continue;                                  // угловая лунка
            if (Math.abs(X - pcx) < 2.3f * r && (Y < res.t + 2.3f * r || Y > res.b - 2.3f * r)) continue; // боковая лунка
            ox[no] = X; oy[no] = Y; no++;
        }
        res.nb = no; res.bx = java.util.Arrays.copyOf(ox, no); res.by = java.util.Arrays.copyOf(oy, no);
    }

    static double[] aimFrom(int[] px, int w, int h, float cx, float cy, float r, double maxT) {
        float t0 = 1.25f * r;
        double bestL = 0, bestA = 0;
        int steps = 2400;
        double[] Ls = new double[steps];
        for (int s = 0; s < steps; s++) {
            double a = 2 * Math.PI * s / steps, ca = Math.cos(a), sa = Math.sin(a);
            double L = rayRun(px, w, h, cx, cy, ca, sa, t0, maxT);
            Ls[s] = L;
            if (L > bestL) { bestL = L; bestA = a; }
        }
        if (bestL < 3 * r) return null;
        double sumA = 0; int nA = 0;
        for (int s = 0; s < steps; s++) {
            if (Ls[s] >= bestL * 0.97) {
                double a = 2 * Math.PI * s / steps;
                double d = a - bestA; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI;
                if (Math.abs(d) < 0.03) { sumA += d; nA++; }
            }
        }
        double ang = bestA + (nA > 0 ? sumA / nA : 0);
        double dx0 = Math.cos(ang), dy0 = Math.sin(ang), nx0 = -dy0, ny0 = dx0;
        double sT = 0, sO = 0, sTT = 0, sTO = 0; int m = 0;
        for (double tt = t0 + 6; tt <= t0 + bestL - 6; tt += 6) {
            double so = 0; int c = 0;
            for (int o = -7; o <= 7; o++) {
                int x = (int) Math.round(cx + dx0 * tt + nx0 * o), y = (int) Math.round(cy + dy0 * tt + ny0 * o);
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                if (line(px[y * w + x])) { so += o; c++; }
            }
            if (c >= 1 && c <= 9) { double off = so / c; sT += tt; sO += off; sTT += tt * tt; sTO += tt * off; m++; }
        }
        double ncx = cx, ncy = cy;
        if (m >= 4) {
            double den = m * sTT - sT * sT;
            if (Math.abs(den) > 1e-6) {
                double bsl = (m * sTO - sT * sO) / den, a0 = (sO - bsl * sT) / m;
                ang += Math.atan(bsl);
                ncx += nx0 * a0; ncy += ny0 * a0;
            }
        }
        double dx = Math.cos(ang), dy = Math.sin(ang);
        double tEnd = t0 + bestL;
        return new double[]{bestL, ang, ncx, ncy, ncx + dx * tEnd, ncy + dy * tEnd};
    }

    static double rayRun(int[] px, int w, int h, float cx, float cy, double ca, double sa, float t0, double maxT) {
        // линия должна начинаться сразу у t0
        if (!lineAt(px, w, h, cx, cy, ca, sa, t0) || !lineAt(px, w, h, cx, cy, ca, sa, t0 + 8)) return 0;
        double last = t0; int gap = 0;
        for (double tt = t0; tt < maxT; tt += 1) {
            if (lineAt(px, w, h, cx, cy, ca, sa, tt)) { last = tt; gap = 0; }
            else if (++gap > 3) break;
        }
        return last - t0;
    }

    static boolean lineAt(int[] px, int w, int h, float cx, float cy, double ca, double sa, double tt) {
        int x = (int) Math.round(cx + ca * tt), y = (int) Math.round(cy + sa * tt);
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        return line(px[y * w + x]);
    }

    static double diskNonCloth(int[] px, int w, int h, float cx, float cy, float rad, float h0, float[] t) {
        int tot = 0, non = 0;
        for (float y = -rad; y <= rad; y += 2) for (float x = -rad; x <= rad; x += 2) {
            if (x * x + y * y > rad * rad) continue;
            int xx = Math.round(cx + x), yy = Math.round(cy + y);
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
            tot++;
            if (!cloth(px[yy * w + xx], h0, t)) non++;
        }
        return tot == 0 ? 0 : non / (double) tot;
    }
}
