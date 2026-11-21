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
    // --- подсказка и Lucky Shot (только автоскан) ---
    boolean hintOn, luckyOn, planning;
    int group;                        // какие шары мои: 0 любые, 1 целые, 2 полосатые
    Planner.Hint curHint; Planner.Lucky curLucky;
    Vision.Result planRes;            // кадр, по которому построен план
    String planMsg;
    long lastSig, planSig; int stable;
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
        if (mode == 2) {
            final android.content.SharedPreferences sp = getSharedPreferences("aim", MODE_PRIVATE);
            Planner.L0MAX = sp.getFloat("l0max", 6000f);
            final Button bH = btn("Подсказка: выкл");
            bH.setOnClickListener(v -> {
                hintOn = !hintOn; bH.setText(hintOn ? "Подсказка: вкл" : "Подсказка: выкл");
                curHint = null; planSig = 0; stable = 0; lines.invalidate();
            });
            final Button bL = btn("Lucky: выкл");
            bL.setOnClickListener(v -> {
                luckyOn = !luckyOn; bL.setText(luckyOn ? "Lucky: вкл" : "Lucky: выкл");
                curLucky = null; planSig = 0; stable = 0; lines.invalidate();
            });
            final Button bG = btn("Шары: любые");
            bG.setOnClickListener(v -> {
                group = (group + 1) % 3; bG.setText(group == 0 ? "Шары: любые" : group == 1 ? "Шары: целые" : "Шары: полосатые");
                curHint = null; planSig = 0; stable = 0; lines.invalidate();
            });
            // Калибровка силы: «Сила+» показывает нужную силу больше, «Сила-» меньше
            Button bSu = btn("Сила+"); bSu.setOnClickListener(v -> {
                Planner.L0MAX *= 0.92f; sp.edit().putFloat("l0max", Planner.L0MAX).apply(); curHint = null; curLucky = null; planSig = 0; stable = 0;
                Toast.makeText(this, "Нужная сила в подсказках стала больше", Toast.LENGTH_SHORT).show(); lines.invalidate();
            });
            Button bSd = btn("Сила-"); bSd.setOnClickListener(v -> {
                Planner.L0MAX /= 0.92f; sp.edit().putFloat("l0max", Planner.L0MAX).apply(); curHint = null; curLucky = null; planSig = 0; stable = 0;
                Toast.makeText(this, "Нужная сила в подсказках стала меньше", Toast.LENGTH_SHORT).show(); lines.invalidate();
            });
            panel.addView(bH); panel.addView(bG); panel.addView(bL); panel.addView(bSd); panel.addView(bSu);
        }
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

    long sigOf(Vision.Result r) {
        long h = Math.round(r.cx / 5f) * 73856093L ^ Math.round(r.cy / 5f) * 19349663L;
        for (int i = 0; i < r.nb; i++) h += (Math.round(r.bx[i] / 5f) * 73856093L) ^ (Math.round(r.by[i] / 5f) * 19349663L) * 31L;
        return h * 1000003L + r.nb;
    }

    // Подсказка и Lucky Shot считаем, когда шары стоят (два одинаковых кадра подряд), в фоне
    void maybePlan(Vision.Result res) {
        if (mode != 2 || (!hintOn && !luckyOn)) return;
        if (res == null || !res.tableOk || !res.cueOk || res.nb < 1) return;
        long sig = sigOf(res);
        if (sig != lastSig) {
            lastSig = sig; stable = 0;
            if (sig != planSig) { curHint = null; curLucky = null; planMsg = null; lines.invalidate(); }
            return;
        }
        stable++;
        if (stable < 1 || sig == planSig || planning) return;
        planning = true;
        final Vision.Result rs = res; final boolean doL = luckyOn, doH = hintOn; final int grp = group; final long fsig = sig;
        new Thread(() -> {
            Planner.Hint hh = null; Planner.Lucky ll = null; String msg = null;
            try {
                Planner.World wd = new Planner.World(rs);
                if (doL) {
                    int gi = -1;
                    for (int i = 0; i < rs.nb; i++) if (rs.gold[i]) gi = i;
                    if (rs.hasTarget && gi >= 0) ll = Planner.lucky(wd, rs.cx, rs.cy, rs.bx, rs.by, gi, rs.tx, rs.ty, 2500);
                    else msg = "Lucky Shot: не вижу мишень или золотой шар";
                }
                if (doH && ll == null && !(doL && rs.hasTarget)) {
                    if (!rs.myTurn) msg = "Подсказка: сейчас ход соперника";
                    else {
                        hh = Planner.hint(wd, rs.cx, rs.cy, rs.bx, rs.by, rs.bt, grp);
                        if (hh == null) msg = "Подсказка: подходящих ударов не нашла";
                    }
                }
            } catch (Throwable e) { msg = "Ошибка расчёта удара"; }
            final Planner.Hint fh = hh; final Planner.Lucky fl = ll; final String fm = msg;
            ui.post(() -> {
                planning = false; planSig = fsig; planRes = rs; curHint = fh; curLucky = fl; planMsg = fm;
                if (lines != null) lines.invalidate();
            });
        }).start();
    }

    void finishScan(Vision.Result res) {
        busy = false;
        if (wm == null) return;
        maybePlan(res);
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
        void tick(Canvas c, float frac, float sx, float sy, int col) {
            // шкала силы слева: 0% сверху, 100% снизу (в разметке экрана 2400x1080)
            float y = (345f + frac * 550f) * sy;
            p.setPathEffect(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(col);
            c.drawLine(150 * sx, y, 266 * sx, y, p);
            tx.setColor(col); tx.setTextAlign(Paint.Align.LEFT); tx.setTextSize(16 * d);
            c.drawText(Math.round(frac * 100) + "%", 276 * sx, y + 6 * d, tx);
            tx.setTextAlign(Paint.Align.CENTER);
        }

        String turnText(float planAng) {
            if (!have) return "";
            double cur = Math.atan2(aY - cY, aX - cX), dd = planAng - cur;
            while (dd > Math.PI) dd -= 2 * Math.PI;
            while (dd < -Math.PI) dd += 2 * Math.PI;
            double deg = Math.toDegrees(dd);
            if (Math.abs(deg) < 0.3) return " · угол совпал";
            return String.format(java.util.Locale.US, " · довернуть %s на %.1f°", deg > 0 ? "по часовой" : "против часовой", Math.abs(deg));
        }

        void drawPlan(Canvas c) {
            if (mode != 2 || (!hintOn && !luckyOn)) return;
            Vision.Result rs = planRes;
            float sx = getWidth() / 2400f, sy = getHeight() / 1080f;
            tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(17 * d); tx.setShadowLayer(4 * d, 0, 0, 0xFF000000);
            p.setPathEffect(null); p.setStyle(Paint.Style.STROKE);
            if (curLucky != null && rs != null) {
                Planner.Lucky L = curLucky;
                p.setStrokeWidth(2f * d); p.setColor(0xFF7CFC00);
                p.setPathEffect(new DashPathEffect(new float[]{10 * d, 7 * d}, 0));
                c.drawLine(rs.cx, rs.cy, rs.cx + (float) Math.cos(L.ang) * 1500 * sx, rs.cy + (float) Math.sin(L.ang) * 1500 * sx, p);
                p.setPathEffect(null);
                if (L.tn > 1) {
                    ArrayList<float[]> pts = new ArrayList<>();
                    for (int i = 0; i < L.tn; i++) pts.add(new float[]{L.trail[2 * i], L.trail[2 * i + 1]});
                    p.setStrokeWidth(3 * d); p.setColor(0xFFFFD700); c.drawPath(path(pts), p);
                }
                p.setStrokeWidth(2.5f * d); p.setColor(0xFFFFFFFF); c.drawCircle(L.fx, L.fy, sR, p);
                p.setColor(0xFF00E5FF); c.drawCircle(rs.tx, rs.ty, rs.tr, p);
                float f = Planner.powerFrac(L.L0);
                tick(c, f, sx, sy, 0xFF7CFC00);
                tx.setColor(L.ok ? 0xFF7CFC00 : 0xFFFFD54F);
                c.drawText("Lucky Shot: сила " + Math.round(f * 100) + "%" + turnText(L.ang) + (L.ok ? "" : " (решение неточное)"), getWidth() / 2f, 76 * d, tx);
            } else if (curHint != null && rs != null) {
                Planner.Hint H = curHint;
                p.setStrokeWidth(2.5f * d); p.setColor(0xFF7CFC00);
                c.drawLine(rs.cx, rs.cy, H.gx, H.gy, p);
                p.setColor(0xFFFFFFFF); c.drawCircle(H.gx, H.gy, sR, p);
                if (H.tn > 1) {
                    ArrayList<float[]> pts = new ArrayList<>();
                    for (int i = 0; i < H.tn; i++) pts.add(new float[]{H.trail[2 * i], H.trail[2 * i + 1]});
                    p.setStrokeWidth(3 * d); p.setColor(0xFFFFD54F); c.drawPath(path(pts), p);
                }
                float[] pk = new Planner.World(rs).pk[H.pocket];
                p.setStrokeWidth(3 * d); p.setColor(0xFF00E5FF); c.drawCircle(pk[0], pk[1], 1.6f * sR, p);
                float f = Planner.powerFrac(H.L0);
                tick(c, f, sx, sy, 0xFF7CFC00);
                tx.setColor(0xFF7CFC00);
                c.drawText("Подсказка: сила " + Math.round(f * 100) + "%, надёжность " + Math.round(H.rate * 100) + "%" + turnText(H.ang), getWidth() / 2f, 76 * d, tx);
            } else if (planMsg != null) {
                tx.setColor(0xFFFFD54F); c.drawText(planMsg, getWidth() / 2f, 76 * d, tx);
            } else if (planning) {
                tx.setColor(0xFFFFFFFF); c.drawText("Считаю удар…", getWidth() / 2f, 76 * d, tx);
            }
        }

        void drawClean(Canvas c) {
            drawPlan(c);
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
        public int[] bt = new int[0];         // тип каждого шара: 0 целый, 1 полосатый, 2 восьмёрка
        public boolean[] gold = new boolean[0]; // золотой шар (жёлтый оттенок): нужен только для Lucky Shot
        public boolean hasTarget;             // мишень Lucky Shot (жёлтый центр)
        public float tx, ty, tr;
        public boolean myTurn = true;         // ход игрока (зелёная рамка у левого аватара); если не видно - считаем, что мой
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

    // ---- Угловые лузы: опора для геометрии стола, одинаковая для всех скинов ----
    static float[] pocketFrame(int[] px, int w, int h) {
        // от самого строгого порога «чёрного» к мягкому: у тёмных столов края сукна не должны слипаться с лузами
        int[] thr = {26, 12, 40};
        float[] sizes = {0.026f, 0.020f};   // у чёрных столов ядро лузы меньше
        for (float sz : sizes) for (int v : thr) {
            float[] f = pocketFrameT(px, w, h, v, sz);
            if (f != null) return f;
        }
        return null;
    }

    static float[] pocketFrameT(int[] px, int w, int h, int thr, float minSize) {
        int mw = w / 2, mh = h / 2;
        boolean[] dk = new boolean[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int p = px[(y * 2) * w + x * 2];
            dk[y * mw + x] = Math.max(R(p), Math.max(G(p), B(p))) < thr;
        }
        int[] lab = label(dk, mw, mh);
        int n = 0; for (int v : lab) if (v > n) n = v;
        if (n == 0 || n > 6000) return null;
        int[] cnt = new int[n + 1], x0 = new int[n + 1], x1 = new int[n + 1], y0 = new int[n + 1], y1 = new int[n + 1];
        double[] sx = new double[n + 1], sy = new double[n + 1];
        for (int i = 1; i <= n; i++) { x0[i] = mw; y0[i] = mh; x1[i] = -1; y1[i] = -1; }
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int v = lab[y * mw + x]; if (v == 0) continue;
            cnt[v]++; sx[v] += x; sy[v] += y;
            if (x < x0[v]) x0[v] = x; if (x > x1[v]) x1[v] = x; if (y < y0[v]) y0[v] = y; if (y > y1[v]) y1[v] = y;
        }
        ArrayList<float[]> c = new ArrayList<>();
        for (int v = 1; v <= n; v++) {
            float bw = (x1[v] - x0[v] + 1) * 2f, bh = (y1[v] - y0[v] + 1) * 2f;
            if (bw < minSize * w || bw > 0.045f * w || bh < minSize * w || bh > 0.045f * w) continue;   // размер угловой лузы
            if (cnt[v] * 4f < 0.40f * bw * bh) continue;
            float asp = bw / bh; if (asp < 0.6f || asp > 1.67f) continue;
            c.add(new float[]{(float) (sx[v] / cnt[v]) * 2f, (float) (sy[v] / cnt[v]) * 2f});
            if (c.size() >= 14) break;
        }
        int m = c.size();
        float bestW = 0; float[] out = null;
        float tx = 0.012f * w, ty = 0.02f * h;
        for (int a = 0; a < m; a++) for (int b = 0; b < m; b++) for (int d = 0; d < m; d++) for (int e = 0; e < m; e++) {
            if (a == b || a == d || a == e || b == d || b == e || d == e) continue;
            float[] tl = c.get(a), tr = c.get(b), bl = c.get(d), br = c.get(e);
            if (tl[0] >= tr[0] || bl[0] >= br[0] || tl[1] >= bl[1] || tr[1] >= br[1]) continue;
            if (Math.abs(tl[1] - tr[1]) > ty || Math.abs(bl[1] - br[1]) > ty) continue;
            if (Math.abs(tl[0] - bl[0]) > tx || Math.abs(tr[0] - br[0]) > tx) continue;
            float wd = (tr[0] + br[0] - tl[0] - bl[0]) / 2f, ht = (bl[1] + br[1] - tl[1] - tr[1]) / 2f;
            if (wd < 0.4f * w || ht <= 0 || wd / ht < 1.85f || wd / ht > 2.1f) continue;
            if (wd > bestW) {
                bestW = wd;
                out = new float[]{(tl[0] + bl[0]) / 2f + 0.0068f * wd, (tr[0] + br[0]) / 2f - 0.0068f * wd,
                                  (tl[1] + tr[1]) / 2f + 0.0070f * wd, (bl[1] + br[1]) / 2f - 0.0070f * wd};
            }
        }
        if (out == null) {
            // запасной вариант: две угловые лузы одной стороны + известное соотношение сторон стола (1477:750)
            for (int a = 0; a < m; a++) for (int b = 0; b < m; b++) {
                if (a == b) continue;
                float[] p1 = c.get(a), p2 = c.get(b);
                if (p1[0] >= p2[0] || Math.abs(p1[1] - p2[1]) > ty) continue;
                float wd = p2[0] - p1[0];
                if (wd < 0.4f * w || wd > 0.9f * w) continue;
                float ht = wd / 1.9693f, yy = (p1[1] + p2[1]) / 2f;
                float yTop = yy < h / 2f ? yy : yy - ht, yBot = yTop + ht;
                if (yTop < 0 || yBot > h) continue;
                if (wd > bestW) {
                    bestW = wd;
                    out = new float[]{p1[0] + 0.0068f * wd, p2[0] - 0.0068f * wd, yTop + 0.0070f * wd, yBot - 0.0070f * wd};
                }
            }
        }
        return out;
    }

    // ---- Локальная модель цвета сукна: ловит шары того же оттенка, что и сукно (красные на красном, зелёные на зелёном) ----
    static final float CELL = 48f;
    static boolean useModel = false;
    static float[] mdl; static int mdlW, mdlH; static float mdlX0, mdlY0;

    static void buildModel(int[] px, int w, int h, float il, float it, float ir, float ib, float h0) {
        float[] t = new float[3];
        mdlX0 = il; mdlY0 = it;
        mdlW = (int) ((ir - il) / CELL) + 2; mdlH = (int) ((ib - it) / CELL) + 2;
        float[] g = new float[mdlW * mdlH * 3];
        boolean[] ok = new boolean[mdlW * mdlH];
        int[] hr = new int[256], hg = new int[256], hb = new int[256];
        int good = 0;
        for (int j = 0; j < mdlH; j++) for (int i = 0; i < mdlW; i++) {
            // только внутри игровой зоны: борта и их тени в модель сукна не попадают
            int xa = Math.max(Math.max(0, (int) il), (int) (il + (i - 0.5f) * CELL)), xb = Math.min(Math.min(w, (int) ir), (int) (il + (i + 0.5f) * CELL));
            int ya = Math.max(Math.max(0, (int) it), (int) (it + (j - 0.5f) * CELL)), yb = Math.min(Math.min(h, (int) ib), (int) (it + (j + 0.5f) * CELL));
            java.util.Arrays.fill(hr, 0); java.util.Arrays.fill(hg, 0); java.util.Arrays.fill(hb, 0);
            int cn = 0;
            for (int y = ya; y < yb; y += 2) for (int x = xa; x < xb; x += 2) {
                int p = px[y * w + x];
                if (!cloth(p, h0, t)) continue;
                hr[R(p)]++; hg[G(p)]++; hb[B(p)]++; cn++;
            }
            if (cn < 30) continue;
            int q = (j * mdlW + i) * 3;
            g[q] = med(hr, cn); g[q + 1] = med(hg, cn); g[q + 2] = med(hb, cn);
            ok[j * mdlW + i] = true; good++;
        }
        if (good < 6) { useModel = false; return; }
        // пустые ячейки: среднее по заполненным соседям
        for (int pass = 0; pass < 8; pass++) {
            boolean any = false;
            boolean[] nok = ok.clone();
            for (int j = 0; j < mdlH; j++) for (int i = 0; i < mdlW; i++) {
                if (ok[j * mdlW + i]) continue;
                float sr = 0, sg = 0, sb = 0; int nn = 0;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int jj = j + dj, ii = i + di;
                    if (jj < 0 || ii < 0 || jj >= mdlH || ii >= mdlW || !ok[jj * mdlW + ii]) continue;
                    int q = (jj * mdlW + ii) * 3; sr += g[q]; sg += g[q + 1]; sb += g[q + 2]; nn++;
                }
                if (nn > 0) { int q = (j * mdlW + i) * 3; g[q] = sr / nn; g[q + 1] = sg / nn; g[q + 2] = sb / nn; nok[j * mdlW + i] = true; any = true; }
            }
            ok = nok;
            if (!any) break;
        }
        // медианный фильтр 3x3: убирает ячейки, где шар закрыл больше половины
        float[] f = new float[g.length];
        float[] buf = new float[9];
        for (int j = 0; j < mdlH; j++) for (int i = 0; i < mdlW; i++) for (int ch = 0; ch < 3; ch++) {
            int nn = 0;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int jj = Math.max(0, Math.min(mdlH - 1, j + dj)), ii = Math.max(0, Math.min(mdlW - 1, i + di));
                buf[nn++] = g[(jj * mdlW + ii) * 3 + ch];
            }
            java.util.Arrays.sort(buf, 0, nn);
            f[(j * mdlW + i) * 3 + ch] = buf[nn / 2];
        }
        mdl = f; useModel = true;
    }

    /** Не сукно: либо оттенок не тот, либо пиксель заметно отличается от местного цвета сукна (ярче или другой цветовой тон). */
    static boolean nonCloth(int p, int x, int y, float h0, float[] t) {
        if (!cloth(p, h0, t)) return true;
        if (!useModel) return false;
        float fx = (x - mdlX0) / CELL, fy = (y - mdlY0) / CELL;
        fx = Math.max(0f, Math.min(mdlW - 1.001f, fx)); fy = Math.max(0f, Math.min(mdlH - 1.001f, fy));
        int ix = (int) fx, iy = (int) fy; float ax = fx - ix, ay = fy - iy;
        float[] mc = new float[3];
        for (int ch = 0; ch < 3; ch++) {
            float a = mdl[(iy * mdlW + ix) * 3 + ch], b = mdl[(iy * mdlW + ix + 1) * 3 + ch];
            float c = mdl[((iy + 1) * mdlW + ix) * 3 + ch], d = mdl[((iy + 1) * mdlW + ix + 1) * 3 + ch];
            mc[ch] = a * (1 - ax) * (1 - ay) + b * ax * (1 - ay) + c * (1 - ax) * ay + d * ax * ay;
        }
        float pr = R(p), pg = G(p), pb = B(p);
        float np = (float) Math.sqrt(pr * pr + pg * pg + pb * pb), nm = (float) Math.sqrt(mc[0] * mc[0] + mc[1] * mc[1] + mc[2] * mc[2]);
        if (nm < 1f || np < 1f) return false;
        if (np > 1.33f * nm) return true;                  // ярче сукна: блик, шар
        if (np < 0.58f * nm) return true;                  // заметно темнее сукна: тёмный шар (бордовый, коричневый)
        float dr = pr / np - mc[0] / nm, dg = pg / np - mc[1] / nm, db = pb / np - mc[2] / nm;
        return dr * dr + dg * dg + db * db > 0.12f * 0.12f; // другой цветовой тон
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
        return dh <= 14f && t[1] > 0.35f && t[2] > 0.12f;   // тёмные края сукна у бортов тоже сукно
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
        r1.msg = r1.msg + " [" + w + "x" + h + ", центр " + centerInfo(px, w, h) + "; запасной: " + r2.msg + "]";
        return r1;
    }

    static String centerInfo(int[] px, int w, int h) {
        int[] qr = new int[256], qg = new int[256], qb = new int[256]; int tot = 0;
        for (int y = h / 2 - 60; y <= h / 2 + 60; y += 2) for (int x = w / 2 - 120; x <= w / 2 + 120; x += 2) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            int pp = px[y * w + x]; qr[R(pp)]++; qg[G(pp)]++; qb[B(pp)]++; tot++;
        }
        if (tot == 0) return "?";
        return "RGB " + med(qr, tot) + "," + med(qg, tot) + "," + med(qb, tot);
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

        // 2. Стол. Сначала по угловым лузам (тёмные круги: не зависят от цвета стола),
        //    запасной вариант: самая большая область цвета сукна (в половинном разрешении)
        useModel = false;
        float il, ir, it, ib, ow;
        float[] pf = pocketFrame(px, w, h);
        if (pf != null) {
            il = pf[0]; ir = pf[1]; it = pf[2]; ib = pf[3];
            ow = (ir - il) + 58f;
        } else {
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
            ow = oR - oL;
            float oh = oB - oT;
            if (ow < w * 0.35f || ow / oh < 1.3f || ow / oh > 2.6f) { res.msg = "Стол найден неверно. Откройте игру на весь экран (альбомная ориентация)."; return res; }
            int k = Math.round(0.0191f * ow); // ширина бортовой полосы
            il = oL + k; ir = oR - k; it = oT + k; ib = oB - k;
        }
        float r0 = 0.0151f * (ir - il);
        res.tableOk = true;
        res.l = il; res.rr = ir; res.t = it; res.b = ib; res.r = r0;
        buildModel(px, w, h, il, it, ir, ib, h0);

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
            // Линии игры нет (игрок не касается экрана). Шары всё равно собираем: они нужны подсказке и Lucky Shot.
            peakT = 6.8f * r;
            float[][] sp0 = shapePeaks(px, w, h, res, false);
            int ci = -1; float cBest = 0;
            for (int i = 0; i < sp0.length; i++) {
                float[] st = ballStats(px, w, h, sp0[i][0], sp0[i][1], r);
                float wt = whiteTotal(px, w, h, sp0[i][0], sp0[i][1], r);
                if (wt >= 0.72f && wt > cBest) { cBest = wt; ci = i; }
            }
            if (ci >= 0) { res.cueOk = true; res.cx = sp0[ci][0]; res.cy = sp0[ci][1]; }
            else if (bestWhite >= 0.65) { res.cueOk = true; res.cx = wx; res.cy = wy; }
            if (sp0.length >= 2) {
                float pcx0 = (res.l + res.rr) / 2f;
                float[] qx = new float[sp0.length], qy = new float[sp0.length]; int nq = 0;
                for (int i = 0; i < sp0.length; i++) {
                    float X = sp0[i][0], Y = sp0[i][1];
                    if (i == ci) continue;
                    if (res.cueOk && Math.hypot(X - res.cx, Y - res.cy) < 1.2f * r) continue;
                    boolean nearL = X < res.l + 2.6f * r || X > res.rr - 2.6f * r, nearT = Y < res.t + 2.6f * r || Y > res.b - 2.6f * r;
                    if (nearL && nearT) continue;
                    if (Math.abs(X - pcx0) < 2.3f * r && (Y < res.t + 2.3f * r || Y > res.b - 2.3f * r)) continue;
                    qx[nq] = X; qy[nq] = Y; nq++;
                }
                res.nb = nq; res.bx = java.util.Arrays.copyOf(qx, nq); res.by = java.util.Arrays.copyOf(qy, nq);
            }
            finishExtras(px, w, h, res);
            res.msg = "Не вижу линию прицела игры. Наведите кий на шар, чтобы игра показала линию.";
            return res;
        }
        res.cx = (float) bestFit[2]; res.cy = (float) bestFit[3];
        double ang = bestFit[1];
        double dx = Math.cos(ang), dy = Math.sin(ang);
        // линия игры заканчивается в центре «призрачного» битка
        res.ax = (float) bestFit[4]; res.ay = (float) bestFit[5];
        if (DEBUG) System.out.printf("[dbg] угол=%.2f° длина линии=%.0f%n", Math.toDegrees(ang), bestLen);

        // 5. Шары по форме (круги радиуса R): не зависит от цвета сукна. Цветовой поиск ниже - только запасной вариант.
        boolean shapeOk = false;
        peakT = 6.8f * r;
        float[][] sp = shapePeaks(px, w, h, res, false);
        if (sp.length >= 3) {
            // кольцо «призрачного» битка: его центр точнее конца белой линии
            int ri = -1; double rd = 0.7f * r;
            for (int i = 0; i < sp.length; i++) {
                double d0 = Math.hypot(sp[i][0] - res.ax, sp[i][1] - res.ay);
                if (d0 < rd) { rd = d0; ri = i; }
            }
            if (ri >= 0) { res.ax = sp[ri][0]; res.ay = sp[ri][1]; }
            float pcx = (res.l + res.rr) / 2f;
            float[] sox = new float[sp.length], soy = new float[sp.length]; int no = 0;
            int oi = -1; double oBest = 1e9; float dTarget = 1.923f * r;
            for (int i = 0; i < sp.length; i++) {
                if (i == ri) continue;
                float X = sp[i][0], Y = sp[i][1];
                if (Math.hypot(X - res.cx, Y - res.cy) < 1.2f * r) continue;   // биток
                double dg = Math.hypot(X - res.ax, Y - res.ay);
                if (dg < 0.8f * r) continue;                                    // кольцо
                boolean nearL = X < res.l + 2.6f * r || X > res.rr - 2.6f * r;
                boolean nearT = Y < res.t + 2.6f * r || Y > res.b - 2.6f * r;
                if (nearL && nearT) continue;                                   // угловая лунка
                if (Math.abs(X - pcx) < 2.3f * r && (Y < res.t + 2.3f * r || Y > res.b - 2.3f * r)) continue; // боковая лунка
                sox[no] = X; soy[no] = Y;
                double ahead = (X - res.ax) * dx + (Y - res.ay) * dy;
                double dev = Math.abs(dg - dTarget);
                if (dg > 1.5f * r && dg < 2.6f * r && ahead > 0 && dev < oBest) { oBest = dev; oi = no; }
                no++;
            }
            res.nb = no; res.bx = java.util.Arrays.copyOf(sox, no); res.by = java.util.Arrays.copyOf(soy, no);
            if (oi >= 0) { res.hasObj = true; res.ox = sox[oi]; res.oy = soy[oi]; }
            shapeOk = true;
        }
        if (!shapeOk) {
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
        }
        finishExtras(px, w, h, res);
        res.ok = true;
        res.msg = res.hasObj ? "Готово: стол, биток, прицел и шар найдены." : "Стол, биток и прицел найдены. Шар на линии не найден (линия идёт в борт).";
        return res;
    }

    /** Ищет белую линию прицела, выходящую из точки (cx,cy). Возвращает {длина, угол, cx', cy', конецX, конецY} или null. */
    /** Все шары на столе (кроме битка): «не сукно» -> стирание тонких линий и кия -> центры пятен; слипшиеся пятна делим усиленным стиранием. */
    /** Доля белого в круге 0.95R: у битка почти весь шар белый. */
    static float whiteTotal(int[] px, int w, int h, float cx, float cy, float r) {
        int rad = Math.round(0.95f * r), tot = 0, wh = 0;
        for (int y = Math.round(cy) - rad; y <= Math.round(cy) + rad; y++) for (int x = Math.round(cx) - rad; x <= Math.round(cx) + rad; x++) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > rad * rad) continue;
            int p = px[y * w + x]; int rr = R(p), gg = G(p), bb = B(p);
            tot++;
            if (rr >= 190 && gg >= 185 && bb >= 160 && rr - bb <= 80 && Math.abs(rr - gg) <= 35) wh++;
        }
        return tot == 0 ? 0 : (float) wh / tot;
    }

    /** Тип шара: 0 целый, 1 полосатый, 2 восьмёрка. Полосатые отличаются белыми «шапками» у края шара. */
    static int ballType(int[] px, int w, int h, float cx, float cy, float r) {
        int tot = 0, ann = 0, annW = 0, dk = 0, all = 0;
        int rad = Math.round(0.95f * r);
        for (int y = Math.round(cy) - rad; y <= Math.round(cy) + rad; y++) for (int x = Math.round(cx) - rad; x <= Math.round(cx) + rad; x++) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            float d = (float) Math.hypot(x - cx, y - cy);
            if (d > 0.95f * r) continue;
            int p = px[y * w + x]; int rr = R(p), gg = G(p), bb = B(p);
            all++;
            if (Math.max(rr, Math.max(gg, bb)) < 55) dk++;
            if (d > 0.55f * r) {
                ann++;
                if (rr >= 190 && gg >= 185 && bb >= 160 && rr - bb <= 80 && Math.abs(rr - gg) <= 35) annW++;
            }
        }
        if (all == 0) return 0;
        if ((float) dk / all > 0.5f) return 2;
        return ann > 0 && (float) annW / ann >= 0.17f ? 1 : 0;
    }

    /** Золотой шар Lucky Shot: жёлтый оттенок и высокая насыщенность. */
    static boolean isGold(int[] px, int w, int h, float cx, float cy, float r) {
        float[] st = ballStats(px, w, h, cx, cy, r);
        return st[2] >= 33 && st[2] <= 52 && st[3] > 0.6f && st[4] > 0.7f;
    }

    /** Ход игрока: у активного игрока вокруг аватара зелёная рамка (в разметке экрана 2400x1080). */
    static boolean turnMine(int[] px, int w, int h) {
        float sx = w / 2400f, sy = h / 1080f;
        int left = 0, right = 0;
        for (int y = Math.round(15 * sy); y < Math.round(165 * sy); y += 2) {
            for (int x = Math.round(955 * sx); x < Math.round(1105 * sx); x += 2) {
                int p = px[y * w + x]; if (G(p) >= 200 && R(p) <= 70 && B(p) <= 70) left++;
            }
            for (int x = Math.round(1295 * sx); x < Math.round(1445 * sx); x += 2) {
                int p = px[y * w + x]; if (G(p) >= 200 && R(p) <= 70 && B(p) <= 70) right++;
            }
        }
        if (right > left + 60) return false;
        return true;
    }

    /** Мишень Lucky Shot: самый большой ярко-жёлтый круг на столе. */
    static void findTarget(int[] px, int w, int h, Result res) {
        int il = Math.round(res.l), it = Math.round(res.t), ir = Math.round(res.rr), ib = Math.round(res.b);
        int mw = (ir - il) / 2 + 1, mh = (ib - it) / 2 + 1;
        boolean[] m = new boolean[mw * mh];
        float[] t = new float[3]; int cnt = 0;
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int X = il + 2 * x, Y = it + 2 * y; if (X >= w || Y >= h) continue;
            hsv(px[Y * w + X], t);
            if (t[0] >= 46 && t[0] <= 66 && t[1] >= 0.55f && t[2] >= 0.85f) { m[y * mw + x] = true; cnt++; }
        }
        if (cnt < 200) return;
        int[] lab = label(m, mw, mh);
        int n = 0; for (int v : lab) if (v > n) n = v;
        int[] c = new int[n + 1]; double[] sx = new double[n + 1], sy = new double[n + 1];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) { int v = lab[y * mw + x]; if (v == 0) continue; c[v]++; sx[v] += x; sy[v] += y; }
        int best = -1; double bestA = 0;
        for (int v = 1; v <= n; v++) { double a = c[v] * 4.0; if (a > bestA) { bestA = a; best = v; } }
        double rr = Math.sqrt(bestA / Math.PI);
        if (best < 0 || rr < 1.2f * res.r || rr > 3.2f * res.r) return;
        res.hasTarget = true; res.tx = (float) (il + 2 * sx[best] / c[best]); res.ty = (float) (it + 2 * sy[best] / c[best]); res.tr = (float) rr;
    }

    /** Дополнительные данные для подсказки и Lucky Shot: типы шаров, мишень, чей ход. */
    static void finishExtras(int[] px, int w, int h, Result res) {
        res.bt = new int[res.nb]; res.gold = new boolean[res.nb];
        for (int i = 0; i < res.nb; i++) {
            res.bt[i] = ballType(px, w, h, res.bx[i], res.by[i], res.r);
            res.gold[i] = res.bt[i] != 2 && isGold(px, w, h, res.bx[i], res.by[i], res.r);
        }
        if (res.tableOk) findTarget(px, w, h, res);
        res.myTurn = turnMine(px, w, h);
    }

    /** Цветовая статистика шара в круге радиуса 0.85R: {доля белого, доля тёмного, оттенок, насыщенность, яркость цветной части}. */
    static float[] ballStats(int[] px, int w, int h, float cx, float cy, float r) {
        int rad = Math.round(0.85f * r), tot = 0, wh = 0, dk = 0, col = 0;
        double sh = 0, ss = 0, sv = 0, hx = 0, hy = 0;
        float[] t = new float[3];
        for (int y = Math.round(cy) - rad; y <= Math.round(cy) + rad; y++) for (int x = Math.round(cx) - rad; x <= Math.round(cx) + rad; x++) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > rad * rad) continue;
            int p = px[y * w + x]; int rr = R(p), gg = G(p), bb = B(p);
            tot++;
            if (rr >= 190 && gg >= 185 && bb >= 160 && rr - bb <= 80 && Math.abs(rr - gg) <= 35) { wh++; continue; }
            if (Math.max(rr, Math.max(gg, bb)) < 55) { dk++; continue; }
            hsv(p, t);
            if (t[1] < 0.25f) continue;
            col++; ss += t[1]; sv += t[2];
            hx += Math.cos(Math.toRadians(t[0])); hy += Math.sin(Math.toRadians(t[0]));
        }
        if (tot == 0) return new float[]{0, 0, 0, 0, 0};
        float hue = col > 0 ? (float) ((Math.toDegrees(Math.atan2(hy, hx)) + 360) % 360) : 0;
        return new float[]{(float) wh / tot, (float) dk / tot, hue, col > 0 ? (float) (ss / col) : 0, col > 0 ? (float) (sv / col) : 0};
    }

    /** Шары по форме, а не по цвету: каждая точка края голосует за центр круга радиуса R по направлению градиента. */
    static float edgeT = 110f, peakT = 60f;
    static float[][] shapePeaks(int[] px, int w, int h, Result res, boolean debug) {
        int il = Math.round(res.l), it = Math.round(res.t), ir = Math.round(res.rr), ib = Math.round(res.b);
        float r = res.r;
        int mw = (ir - il) / 2 + 1, mh = (ib - it) / 2 + 1;
        float[] acc = new float[mw * mh];
        for (int y = 1; y < mh - 1; y++) for (int x = 1; x < mw - 1; x++) {
            int X = il + 2 * x, Y = it + 2 * y;
            if (X + 2 >= w || Y + 2 >= h || X - 2 < 0 || Y - 2 < 0) continue;
            int a = px[(Y - 2) * w + X - 2], b = px[(Y - 2) * w + X], c = px[(Y - 2) * w + X + 2];
            int d = px[Y * w + X - 2], f = px[Y * w + X + 2];
            int g = px[(Y + 2) * w + X - 2], hh = px[(Y + 2) * w + X], k = px[(Y + 2) * w + X + 2];
            float bgx = 0, bgy = 0, bm = 0;
            for (int sh = 0; sh <= 16; sh += 8) {
                int gx = (((c >> sh) & 255) + 2 * ((f >> sh) & 255) + ((k >> sh) & 255)) - (((a >> sh) & 255) + 2 * ((d >> sh) & 255) + ((g >> sh) & 255));
                int gy = (((g >> sh) & 255) + 2 * ((hh >> sh) & 255) + ((k >> sh) & 255)) - (((a >> sh) & 255) + 2 * ((b >> sh) & 255) + ((c >> sh) & 255));
                float m2 = gx * gx + gy * gy;
                if (m2 > bm) { bm = m2; bgx = gx; bgy = gy; }
            }
            float gm = (float) Math.sqrt(bm);
            if (gm < edgeT) continue;
            float ux = bgx / gm, uy = bgy / gm;
            for (int sg = -1; sg <= 1; sg += 2) for (int q = -1; q <= 1; q++) {
                float rr = (r + 1.5f * q) / 2f;
                int cx = Math.round(x + sg * rr * ux), cy = Math.round(y + sg * rr * uy);
                if (cx < 0 || cy < 0 || cx >= mw || cy >= mh) continue;
                acc[cy * mw + cx] += 1f;
            }
        }
        // сглаживание 3x3
        float[] sm = new float[mw * mh];
        for (int y = 1; y < mh - 1; y++) for (int x = 1; x < mw - 1; x++) {
            float v = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) v += acc[(y + dy) * mw + x + dx];
            sm[y * mw + x] = v;
        }
        // локальные максимумы
        ArrayList<float[]> pk = new ArrayList<>();
        int rad = Math.max(2, Math.round(r / 2f * 0.6f));
        for (int y = rad; y < mh - rad; y++) for (int x = rad; x < mw - rad; x++) {
            float v = sm[y * mw + x];
            if (v < peakT) continue;
            boolean mx = true;
            for (int dy = -rad; dy <= rad && mx; dy++) for (int dx = -rad; dx <= rad; dx++) {
                if (sm[(y + dy) * mw + x + dx] > v || (sm[(y + dy) * mw + x + dx] == v && (dy < 0 || (dy == 0 && dx < 0)))) { mx = false; break; }
            }
            if (!mx) continue;
            // уточнение центра по взвешенному центроиду 5x5
            float sx = 0, sy = 0, sw = 0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= mw || yy >= mh) continue;
                float q = sm[yy * mw + xx]; q = q * q; sx += q * xx; sy += q * yy; sw += q;
            }
            pk.add(new float[]{il + 2 * sx / sw, it + 2 * sy / sw, v});
        }
        pk.sort((p1, p2) -> Float.compare(p2[2], p1[2]));
        ArrayList<float[]> out = new ArrayList<>();
        for (float[] c : pk) {
            boolean far = true;
            for (float[] o : out) if (Math.hypot(c[0] - o[0], c[1] - o[1]) < 1.6f * r) { far = false; break; }
            if (far) out.add(c);
        }
        float[][] res2 = out.toArray(new float[0][]);
        for (float[] c : res2) { float[] q = refineCircle(px, w, h, c[0], c[1], r); c[0] = q[0]; c[1] = q[1]; }
        return res2;
    }

    /** Точное положение центра: средний «голос» точек края вокруг шара (полное разрешение, несколько итераций). */
    static float[] refineCircle(int[] px, int w, int h, float cx, float cy, float r) {
        for (int iter = 0; iter < 4; iter++) {
            double sx = 0, sy = 0; int n = 0;
            int x0 = Math.max(2, (int) (cx - 1.4f * r)), x1 = Math.min(w - 3, (int) (cx + 1.4f * r));
            int y0 = Math.max(2, (int) (cy - 1.4f * r)), y1 = Math.min(h - 3, (int) (cy + 1.4f * r));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 0.7f * r || d > 1.3f * r) continue;
                int a = px[(y - 1) * w + x - 1], b = px[(y - 1) * w + x], c = px[(y - 1) * w + x + 1];
                int dd = px[y * w + x - 1], f = px[y * w + x + 1];
                int g = px[(y + 1) * w + x - 1], hh = px[(y + 1) * w + x], k = px[(y + 1) * w + x + 1];
                float bgx = 0, bgy = 0, bm = 0;
                for (int sh = 0; sh <= 16; sh += 8) {
                    int gx = (((c >> sh) & 255) + 2 * ((f >> sh) & 255) + ((k >> sh) & 255)) - (((a >> sh) & 255) + 2 * ((dd >> sh) & 255) + ((g >> sh) & 255));
                    int gy = (((g >> sh) & 255) + 2 * ((hh >> sh) & 255) + ((k >> sh) & 255)) - (((a >> sh) & 255) + 2 * ((b >> sh) & 255) + ((c >> sh) & 255));
                    float m2 = gx * gx + gy * gy;
                    if (m2 > bm) { bm = m2; bgx = gx; bgy = gy; }
                }
                float gm = (float) Math.sqrt(bm);
                if (gm < 0.8f * edgeT) continue;
                float ux = bgx / gm, uy = bgy / gm;
                float dot = (ux * dx + uy * dy) / d;
                if (Math.abs(dot) < 0.85f) continue;                 // край должен быть направлен по радиусу
                float sg = dot > 0 ? -1f : 1f;
                sx += x + sg * r * ux; sy += y + sg * r * uy; n++;
            }
            if (n < 20) break;
            cx = (float) (sx / n); cy = (float) (sy / n);
        }
        return new float[]{cx, cy};
    }

    static void findBalls(int[] px, int w, int h, Result res, float h0) {
        float[] t = new float[3];
        float r = res.r;
        int il = Math.round(res.l), it = Math.round(res.t), ir = Math.round(res.rr), ib = Math.round(res.b);
        int mw = (ir - il) / 2 + 1, mh = (ib - it) / 2 + 1;
        boolean[] m = new boolean[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int X = il + 2 * x, Y = it + 2 * y;
            if (X >= w || Y >= h) continue;
            m[y * mw + x] = nonCloth(px[Y * w + X], X, Y, h0, t);
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
            if (nonCloth(px[yy * w + xx], xx, yy, h0, t)) non++;
        }
        return tot == 0 ? 0 : non / (double) tot;
    }
}

/** Расчёт ударов: упрощённая физика (постоянное торможение, упругие столкновения шаров, отскок от бортов, лузы). */
class Planner {
    static float EB = 0.96f;        // упругость шар-шар
    static float EC = 0.75f;        // упругость борта
    static float L0MAX = 6000f;     // расстояние, которое пролетает битка при 100% силы (калибруется)
    static final float DT = 0.02f;  // шаг времени; торможение A = 1, пробег = v^2 / 2

    static class World {
        float l, t, r, b, R;        // границы для центра шара
        float[][] pk;               // лузы: {x, y, радиус захвата}
        World(Vision.Result res) {
            R = 0.96f * res.r;
            l = res.l + R; t = res.t + R; r = res.rr - R; b = res.b - R;
            float cx = (res.l + res.rr) / 2f, cr = 2.5f * R, sr = 2.2f * R;
            pk = new float[][]{{res.l, res.t, cr}, {res.rr, res.t, cr}, {res.l, res.b, cr}, {res.rr, res.b, cr}, {cx, res.t, sr}, {cx, res.b, sr}};
        }
    }

    static class Sim {
        final World w; final int n;
        final float[] x, y, vx, vy; final boolean[] in; final int[] pocket;
        int tn;
        Sim(World w, int n) { this.w = w; this.n = n; x = new float[n]; y = new float[n]; vx = new float[n]; vy = new float[n]; in = new boolean[n]; pocket = new int[n]; }
        void reset(float[] sx, float[] sy) {
            for (int i = 0; i < n; i++) { x[i] = sx[i]; y[i] = sy[i]; vx[i] = 0; vy[i] = 0; in[i] = false; pocket[i] = -1; }
        }
        void shoot(float ang, float L0) {
            float v = (float) Math.sqrt(2f * L0);
            vx[0] = (float) Math.cos(ang) * v; vy[0] = (float) Math.sin(ang) * v;
        }
        void run(int maxSteps, int ti, float[] trail) {
            float d2 = 4 * w.R * w.R; tn = 0;
            for (int step = 0; step < maxSteps; step++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    if (in[i]) continue;
                    float sp2 = vx[i] * vx[i] + vy[i] * vy[i];
                    if (sp2 < 1e-6f) { vx[i] = 0; vy[i] = 0; continue; }
                    float sp = (float) Math.sqrt(sp2), ns = sp - DT;
                    if (ns <= 0.05f) { vx[i] = 0; vy[i] = 0; continue; }
                    any = true;
                    float k = ns / sp; vx[i] *= k; vy[i] *= k;
                    x[i] += vx[i] * DT; y[i] += vy[i] * DT;
                    for (int q = 0; q < 6; q++) {
                        float dx = x[i] - w.pk[q][0], dy = y[i] - w.pk[q][1];
                        if (dx * dx + dy * dy < w.pk[q][2] * w.pk[q][2]) { in[i] = true; pocket[i] = q; vx[i] = 0; vy[i] = 0; break; }
                    }
                    if (in[i]) continue;
                    if (x[i] < w.l) { x[i] = w.l; if (vx[i] < 0) vx[i] = -vx[i] * EC; } else if (x[i] > w.r) { x[i] = w.r; if (vx[i] > 0) vx[i] = -vx[i] * EC; }
                    if (y[i] < w.t) { y[i] = w.t; if (vy[i] < 0) vy[i] = -vy[i] * EC; } else if (y[i] > w.b) { y[i] = w.b; if (vy[i] > 0) vy[i] = -vy[i] * EC; }
                }
                if (trail != null && step % 4 == 0 && 2 * tn + 1 < trail.length && !in[ti]) { trail[2 * tn] = x[ti]; trail[2 * tn + 1] = y[ti]; tn++; }
                if (!any) break;
                for (int i = 0; i < n; i++) {
                    if (in[i]) continue;
                    for (int j = i + 1; j < n; j++) {
                        if (in[j]) continue;
                        float dx = x[j] - x[i], dy = y[j] - y[i];
                        float dd = dx * dx + dy * dy;
                        if (dd >= d2 || dd < 1e-6f) continue;
                        float rvx = vx[i] - vx[j], rvy = vy[i] - vy[j];
                        float d = (float) Math.sqrt(dd), nx = dx / d, ny = dy / d;
                        float rv = rvx * nx + rvy * ny;
                        if (rv <= 0) continue;                       // расходятся
                        float imp = (1f + EB) * 0.5f * rv;
                        vx[i] -= imp * nx; vy[i] -= imp * ny; vx[j] += imp * nx; vy[j] += imp * ny;
                        float push = (2 * w.R - d) * 0.5f + 0.01f;
                        x[i] -= nx * push; y[i] -= ny * push; x[j] += nx * push; y[j] += ny * push;
                    }
                }
            }
            if (trail != null && !in[ti] && 2 * tn + 1 < trail.length) { trail[2 * tn] = x[ti]; trail[2 * tn + 1] = y[ti]; tn++; }
        }
    }

    static float powerFrac(float L0) { return Math.min(1f, (float) Math.sqrt(L0 / L0MAX)); }

    // ---------------- Lucky Shot ----------------
    static class Lucky {
        float ang, L0, cost, robust, fx, fy;
        float[] trail = new float[0]; int tn;
        boolean ok;
    }

    static float luckyCost(Sim s, int g, float tx, float ty) {
        if (s.in[g]) return 1e6f;
        float c = (float) Math.hypot(s.x[g] - tx, s.y[g] - ty);
        if (s.in[0]) c += 300f;            // биток в лузе: плохо
        return c;
    }

    static Lucky lucky(World wd, float cx, float cy, float[] bx, float[] by, int gi, float tx, float ty, long budgetMs) {
        long t0 = System.nanoTime();
        int n = 1 + bx.length, g = gi + 1;
        float[] sx = new float[n], sy = new float[n];
        sx[0] = cx; sy[0] = cy;
        for (int i = 0; i < bx.length; i++) { sx[i + 1] = bx[i]; sy[i + 1] = by[i]; }
        Sim s = new Sim(wd, n);
        float gx0 = sx[g], gy0 = sy[g];
        // грубый перебор: углы и силы
        ArrayList<float[]> cand = new ArrayList<>();   // {cost, ang, L0}
        int na = 240;
        for (int ia = 0; ia < na; ia++) {
            float ang = (float) (2 * Math.PI * ia / na);
            s.reset(sx, sy); s.shoot(ang, L0MAX); s.run(9000, 0, null);
            boolean touched = false;
            for (int i = 1; i < n; i++) if (Math.hypot(s.x[i] - sx[i], s.y[i] - sy[i]) > 1f || s.in[i]) { touched = true; break; }
            if (!touched) continue;
            for (float L0 = 100f; L0 <= L0MAX; L0 *= 1.25f) {
                s.reset(sx, sy); s.shoot(ang, L0); s.run(9000, 0, null);
                float c = luckyCost(s, g, tx, ty);
                cand.add(new float[]{c, ang, L0});
            }
            if ((System.nanoTime() - t0) / 1000000 > budgetMs * 0.5) break;
        }
        cand.sort((p, q) -> Float.compare(p[0], q[0]));
        // уточнение лучших кандидатов (разные по углу)
        ArrayList<float[]> top = new ArrayList<>();
        for (float[] c : cand) {
            boolean dup = false;
            for (float[] o : top) if (Math.abs(o[1] - c[1]) < 0.03f) { dup = true; break; }
            if (!dup) top.add(c);
            if (top.size() >= 10) break;
        }
        Lucky best = null;
        for (float[] c : top) {
            float ba = c[1], bl = c[2], bc = c[0];
            for (int round = 0; round < 3; round++) {
                float da = (float) Math.toRadians(1.2f) / (1 + 2 * round), dl = 0.2f / (1 + 2 * round);
                float na2 = ba, nl2 = bl;
                for (int ia = -5; ia <= 5; ia++) for (int il = -5; il <= 5; il++) {
                    float a = ba + da * ia / 5f, L = bl * (1f + dl * il / 5f);
                    s.reset(sx, sy); s.shoot(a, L); s.run(9000, 0, null);
                    float cc = luckyCost(s, g, tx, ty);
                    if (cc < bc) { bc = cc; na2 = a; nl2 = L; }
                }
                ba = na2; bl = nl2;
                if ((System.nanoTime() - t0) / 1000000 > budgetMs) break;
            }
            // устойчивость: небольшие ошибки в угле и силе
            float rob = 0; int cnt = 0;
            for (int da = -1; da <= 1; da++) for (int dl = -1; dl <= 1; dl++) {
                s.reset(sx, sy); s.shoot(ba + (float) Math.toRadians(0.2f) * da, bl * (1f + 0.03f * dl)); s.run(9000, 0, null);
                rob += luckyCost(s, g, tx, ty); cnt++;
            }
            rob /= cnt;
            if (best == null || rob < best.robust) {
                best = new Lucky(); best.ang = ba; best.L0 = bl; best.cost = bc; best.robust = rob;
            }
            if ((System.nanoTime() - t0) / 1000000 > budgetMs) break;
        }
        if (best == null) return null;
        float[] trail = new float[2 * 1200];
        s.reset(sx, sy); s.shoot(best.ang, best.L0); s.run(9000, g, trail);
        best.trail = trail; best.tn = s.tn; best.fx = s.x[g]; best.fy = s.y[g];
        best.ok = !s.in[g] && !s.in[0] && best.cost < 40f;
        return best;
    }

    // ---------------- Подсказка для обычной партии ----------------
    static class Hint {
        int ball = -1, pocket = -1;
        float ang, L0, score, rate, gx, gy;     // gx, gy: куда должен встать биток в момент касания
        float[] trail = new float[0]; int tn;
        float[] cueTrail = new float[0]; int ctn;
    }

    /** group: 0 любые (кроме восьмёрки), 1 целые, 2 полосатые. Цели считаются по типам: 0 целый, 1 полосатый, 2 восьмёрка. */
    static Hint hint(World wd, float cx, float cy, float[] bx, float[] by, int[] bt, int group) {
        int nb = bx.length, n = 1 + nb;
        boolean[] cand = new boolean[nb];
        int cnt = 0;
        for (int i = 0; i < nb; i++) {
            boolean ok = bt[i] != 2 && (group == 0 || (group == 1 && bt[i] == 0) || (group == 2 && bt[i] == 1));
            cand[i] = ok; if (ok) cnt++;
        }
        if (cnt == 0) for (int i = 0; i < nb; i++) if (bt[i] == 2) { cand[i] = true; cnt++; }   // свои шары закончились: чёрный
        float[] sx = new float[n], sy = new float[n];
        sx[0] = cx; sy[0] = cy;
        for (int i = 0; i < nb; i++) { sx[i + 1] = bx[i]; sy[i + 1] = by[i]; }
        Sim s = new Sim(wd, n);
        Hint best = null;
        float R = wd.R;
        for (int i = 0; i < nb; i++) {
            if (!cand[i]) continue;
            for (int q = 0; q < 6; q++) {
                float px0 = wd.pk[q][0], py0 = wd.pk[q][1];
                float tx = bx[i], ty = by[i];
                float ux = px0 - tx, uy = py0 - ty, ul = (float) Math.hypot(ux, uy);
                if (ul < 3 * R) continue;
                ux /= ul; uy /= ul;
                float gx = tx - ux * 2 * R, gy = ty - uy * 2 * R;
                if (gx < wd.l - 2 || gx > wd.r + 2 || gy < wd.t - 2 || gy > wd.b + 2) continue;
                float dx = gx - cx, dy = gy - cy, d1 = (float) Math.hypot(dx, dy);
                if (d1 < 1.5f * R) continue;
                float cos = (dx / d1) * ux + (dy / d1) * uy;
                if (cos < 0.17f) continue;                    // слишком тонкий рез
                float ang = (float) Math.atan2(dy, dx);
                float L0est = (d1 + ul / (cos * cos)) * 1.0f;
                // подбираем наименьшую силу, при которой шар падает в лузу
                float useL = -1;
                for (float m = 0.85f; m <= 2.6f; m *= 1.12f) {
                    float L0 = L0est * m + 60f;
                    if (L0 > L0MAX) break;
                    s.reset(sx, sy); s.shoot(ang, L0); s.run(9000, 0, null);
                    if (s.in[i + 1] && !s.in[0]) { useL = L0; break; }
                }
                if (useL < 0) continue;
                useL = Math.min(L0MAX, useL * 1.12f);          // небольшой запас по силе
                // устойчивость к ошибкам руки и положению шара
                int okc = 0, tot = 0;
                for (int da = -1; da <= 1; da++) for (int dl = -1; dl <= 1; dl += 2) {
                    s.reset(sx, sy); s.shoot(ang + (float) Math.toRadians(0.25f) * da, useL * (1f + 0.05f * dl)); s.run(9000, 0, null);
                    tot++; if (s.in[i + 1] && !s.in[0]) okc++;
                }
                float rate = (float) okc / tot;
                // итоговое положение битка
                s.reset(sx, sy); s.shoot(ang, useL); s.run(9000, 0, null);
                float score = rate * 100f - 0.01f * useL;
                if (!s.in[0]) {
                    // биток лучше оставить подальше от лузы и не у борта
                    float minP = 1e9f;
                    for (int k = 0; k < 6; k++) minP = Math.min(minP, (float) Math.hypot(s.x[0] - wd.pk[k][0], s.y[0] - wd.pk[k][1]));
                    score += Math.min(minP, 6 * R) * 0.02f;
                }
                if (best == null || score > best.score) {
                    best = new Hint(); best.ball = i; best.pocket = q; best.ang = ang; best.L0 = useL; best.score = score; best.rate = rate; best.gx = gx; best.gy = gy;
                }
            }
        }
        if (best != null) {
            float[] trail = new float[2 * 600], ct = new float[2 * 600];
            s.reset(sx, sy); s.shoot(best.ang, best.L0); s.run(9000, best.ball + 1, trail);
            best.trail = trail; best.tn = s.tn;
            s.reset(sx, sy); s.shoot(best.ang, best.L0); s.run(9000, 0, ct);
            best.cueTrail = ct; best.ctn = s.tn;
        }
        return best;
    }
}
