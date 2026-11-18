package com.aimruler;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(48, 96, 48, 48);
        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setText("Линейка прицела рисуется поверх любой игры.\n\n"
            + "1. Нажмите «Включить» и разрешите показ поверх других приложений.\n"
            + "2. Откройте игру.\n"
            + "3. Синие точки 1 и 2: поставьте на внутренние углы стола (верхний левый и нижний правый).\n"
            + "4. Кнопками R- и R+ подгоните кольца под размер шаров.\n"
            + "5. Белая точка: биток. Красная: шар, который бьёте. Жёлтая: направление удара.\n"
            + "6. Зелёная линия: шар идёт в лузу. Жёлтая: не идёт.\n"
            + "7. Кнопка «Скрыть» убирает линейку, чтобы играть.\n");
        Button on = new Button(this); on.setText("Включить");
        Button off = new Button(this); off.setText("Выключить");
        on.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "Разрешите показ поверх других приложений и нажмите «Включить» снова", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            Intent i = new Intent(this, OverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            moveTaskToBack(true);
        });
        off.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        ll.addView(tv); ll.addView(on); ll.addView(off);
        ScrollView sv = new ScrollView(this); sv.addView(ll);
        setContentView(sv);
    }
}
