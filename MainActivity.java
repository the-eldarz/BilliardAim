package com.aimruler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "aim", KEY_THEME = "theme", KEY_MODE = "mode"; // режим: 0 = ручной, 1 = авто
    static final int REQ_CAPTURE = 77;
    TextView modeNote; // 0 = как на устройстве, 1 = светлая, 2 = тёмная
    Button toggle;
    static boolean battAsked = false; // спрашиваем про батарею один раз за запуск приложения
    TextView status;

    SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    boolean dark() {
        int t = prefs().getInt(KEY_THEME, 0);
        if (t == 1) return false;
        if (t == 2) return true;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    @Override protected void onCreate(Bundle b) {
        setTheme(dark() ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(b);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(20), dp(40), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText("Линейка прицела");
        title.setTextSize(26);
        ll.addView(title, lp(0));

        status = new TextView(this);
        status.setTextSize(15);
        ll.addView(status, lp(6));

        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setOnClickListener(v -> onToggle());
        ll.addView(toggle, lp(24));

        Button help = new Button(this);
        help.setText("Как пользоваться");
        help.setOnClickListener(v -> showHelp());
        ll.addView(help, lp(8));

        TextView mt = new TextView(this);
        mt.setText("Режим прицела");
        mt.setTextSize(16);
        ll.addView(mt, lp(24));

        RadioGroup mg = new RadioGroup(this);
        String[] mn = {"Ручной", "Скан по кнопке", "Автоскан"};
        int curMode = prefs().getInt(KEY_MODE, 0);
        for (int i = 0; i < mn.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(mn[i]);
            rb.setId(200 + i);
            mg.addView(rb);
            if (i == curMode) rb.setChecked(true);
        }
        modeNote = new TextView(this);
        modeNote.setTextSize(13);
        modeNote.setText(modeText(curMode));
        mg.setOnCheckedChangeListener((g, id) -> {
            int v = id - 200;
            prefs().edit().putInt(KEY_MODE, v).apply();
            modeNote.setText(modeText(v));
        });
        ll.addView(mg, lp(4));
        ll.addView(modeNote, lp(2));

        TextView ex = new TextView(this);
        ex.setText("Дополнительные функции (в режиме «Автоскан»)");
        ex.setTextSize(16);
        ll.addView(ex, lp(24));
        CheckBox cbH = new CheckBox(this);
        cbH.setText("Подсказка: лучший удар в партии");
        cbH.setChecked(prefs().getBoolean("hint_on", false));
        cbH.setOnCheckedChangeListener((v, on) -> prefs().edit().putBoolean("hint_on", on).apply());
        ll.addView(cbH, lp(2));
        CheckBox cbL = new CheckBox(this);
        cbL.setText("Lucky Shot: удар золотым шаром в центр мишени");
        cbL.setChecked(prefs().getBoolean("lucky_on", false));
        cbL.setOnCheckedChangeListener((v, on) -> prefs().edit().putBoolean("lucky_on", on).apply());
        ll.addView(cbL, lp(2));
        TextView exNote = new TextView(this);
        exNote.setTextSize(13);
        exNote.setText("Если включить, в автоскане слева появятся кнопки «Скан подсказка» и «Скан LS». Они разбирают стол по нажатию. Изменения применяются при следующем включении линейки.");
        ll.addView(exNote, lp(2));

        TextView th = new TextView(this);
        th.setText("Тема оформления");
        th.setTextSize(16);
        ll.addView(th, lp(28));

        RadioGroup rg = new RadioGroup(this);
        String[] names = {"Как на устройстве", "Светлая", "Тёмная"};
        int cur = prefs().getInt(KEY_THEME, 0);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(100 + i);
            rg.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            int v = id - 100;
            if (v != prefs().getInt(KEY_THEME, 0)) {
                prefs().edit().putInt(KEY_THEME, v).apply();
                recreate();
            }
        });
        ll.addView(rg, lp(4));

        ScrollView sv = new ScrollView(this);
        sv.addView(ll);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        askBattery();
    }

    boolean ignoringBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // При старте просим не ограничивать приложение батареей, чтобы при низком заряде система не мешала игре
    void askBattery() {
        if (battAsked || ignoringBattery()) return;
        battAsked = true;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Работа при низком заряде")
            .setMessage("Чтобы прицел не пропадал и не тормозил, когда заряд низкий, разрешите приложению работать без ограничений батареи: на следующем экране нажмите «Разрешить».\n\nЕсли на телефоне включён режим энергосбережения, на время игры лучше его выключить.")
            .setPositiveButton("Разрешить", (dlg, w) -> requestIgnoreBattery())
            .setNegativeButton("Позже", null);
        if (Build.VERSION.SDK_INT >= 22) b.setNeutralButton("Энергосбережение", (dlg, w) -> openSaver());
        b.show();
    }

    void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e2) { Toast.makeText(this, "Откройте Настройки, Батарея и отключите ограничения для этого приложения", Toast.LENGTH_LONG).show(); }
        }
    }

    void openSaver() {
        try { startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); }
        catch (Exception e) { Toast.makeText(this, "Откройте Настройки, Батарея и выключите энергосбережение", Toast.LENGTH_LONG).show(); }
    }

    void refresh() {
        boolean on = OverlayService.running;
        toggle.setText(on ? "Выключить" : "Включить");
        status.setText(on ? "Сейчас: включено" : "Сейчас: выключено");
    }

    void onToggle() {
        if (OverlayService.running) {
            stopService(new Intent(this, OverlayService.class));
            OverlayService.running = false;
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Разрешите показ поверх других приложений и нажмите «Включить» снова", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        if (prefs().getInt(KEY_MODE, 0) != 0) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            // На Android 14+ просим только «весь экран»: системное меню выбора приложения не показывается
            Intent ci = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
            startActivityForResult(ci, REQ_CAPTURE);
            return;
        }
        startOverlay(0, 0, null);
    }

    void startOverlay(int mode, int code, Intent data) {
        Intent i = new Intent(this, OverlayService.class);
        i.putExtra("mode", mode);
        if (mode != 0) { i.putExtra("code", code); i.putExtra("data", data); }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        OverlayService.running = true;
        refresh();
        if (!launchGame()) moveTaskToBack(true);
    }

    static final String[] GAME_PKGS = {"com.miniclip.eightballpool"};

    // Сразу открываем 8 Ball Pool после включения линейки
    boolean launchGame() {
        for (String pkg : GAME_PKGS) {
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) continue;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(li); return true; } catch (Exception e) { /* пробуем дальше */ }
        }
        return false;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null) startOverlay(prefs().getInt(KEY_MODE, 0), res, data);
            else Toast.makeText(this, "Без разрешения на запись экрана авто-режим не работает", Toast.LENGTH_LONG).show();
        }
    }

    String modeText(int m) {
        if (m == 1) return "Скан по кнопке: на панели появится «Скан». Нажали, и линейка один раз сама находит шары и считает траекторию. Android спросит разрешение на запись экрана.";
        if (m == 2) return "Автоскан: линейка сама обновляет траекторию, пока вы двигаете прицел. Android спросит разрешение на запись экрана. Используйте только в тренировке или офлайн, не против людей.";
        return "Ручной: точки и кольца вы двигаете сами.";
    }

    void showHelp() {
        String text = "Линейка рисуется поверх любой игры.\n\n"
            + "РУЧНОЙ РЕЖИМ\n"
            + "1. Нажмите «Включить» и разрешите показ поверх других приложений.\n"
            + "2. Откройте игру.\n"
            + "3. Синие точки 1 и 2: на внутренние углы стола (верхний левый и нижний правый).\n"
            + "4. Кнопками R- и R+ подгоните кольца под размер шаров.\n"
            + "5. Белая точка (Б): биток. Красная (Ш): шар, который бьёте. Жёлтая (+): направление удара.\n"
            + "6. Зелёная линия: шар идёт в лузу. Жёлтая: не идёт.\n\n"
            + "СКАН ПО КНОПКЕ И АВТОСКАН\n"
            + "1. Выберите режим и нажмите «Включить». Разрешите запись экрана.\n"
            + "2. В игре наведите кий: игра сама рисует белую линию прицела.\n"
            + "3. В режиме «по кнопке» нажмите «Скан» на панели. В «Автоскане» ничего нажимать не нужно.\n"
            + "4. Линейка рисует только то, что нужно: путь шара и битка после касания, отскоки от бортов. Зелёная линия: шар попадёт в лузу, жёлтая: нет. Розовая: путь битка.\n"
            + "5. Если целитесь в борт, линейка покажет отскок и скажет, попадёт ли биток после него в шар.\n"
            + "6. Если ничего не появилось, наведите кий так, чтобы игра показала линию, и повторите скан.\n"
            + "Используйте только в тренировке или офлайн, не против людей.\n\n"
            + "ПАНЕЛЬ\n"
            + "«Отскоки»: сколько раз линия отражается от бортов (0-3). «Скрыть» убирает линейку, «Показать» возвращает. «Выход» закрывает её совсем.\n\n"
            + "Выключить линейку можно и здесь, кнопкой «Выключить».";
        new AlertDialog.Builder(this).setTitle("Как пользоваться").setMessage(text).setPositiveButton("Понятно", null).show();
    }
}
