package com.aimruler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "aim", KEY_THEME = "theme"; // 0 = как на устройстве, 1 = светлая, 2 = тёмная
    Button toggle;
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

        TextView mode = new TextView(this);
        mode.setText("Режим: ручной. Точки и кольца вы двигаете сами.");
        mode.setTextSize(14);
        ll.addView(mode, lp(24));

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
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        OverlayService.running = true;
        refresh();
        moveTaskToBack(true);
    }

    void showHelp() {
        String text = "Линейка рисуется поверх любой игры.\n\n"
            + "1. Нажмите «Включить» и разрешите показ поверх других приложений.\n"
            + "2. Откройте игру.\n"
            + "3. Синие точки 1 и 2: поставьте на внутренние углы стола (верхний левый и нижний правый).\n"
            + "4. Кнопками R- и R+ внизу подгоните кольца под размер шаров.\n"
            + "5. Белая точка (Б): биток. Красная (Ш): шар, который бьёте. Жёлтая (+): направление удара.\n"
            + "6. Зелёная линия: шар идёт в лузу. Жёлтая: не идёт.\n"
            + "7. «Отскоки»: сколько раз линия отражается от бортов (0-3).\n"
            + "8. «Скрыть» убирает линейку, чтобы играть, «Показать» возвращает. «Выход» закрывает её совсем.\n\n"
            + "Выключить линейку можно и здесь, кнопкой «Выключить».";
        new AlertDialog.Builder(this).setTitle("Как пользоваться").setMessage(text).setPositiveButton("Понятно", null).show();
    }
}
