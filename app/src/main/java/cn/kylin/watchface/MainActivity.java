package cn.kylin.watchface;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全屏时钟（主模式）：打开即表盘；右上角小按钮进入编辑界面（设置/嗅探/预览）。
 * ROM 无动态壁纸选择器时的降级方案。
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences p;
    private boolean clockMode = true;

    private ClockView clockView;
    private LinearLayout mainBox, dock;
    private TextView sniffText, srcValue, unitValue, offsetValue;
    private Button tabPreview, tabSetting, tabSniff;
    private View previewPage, settingPage, sniffPage;

    /** 每分钟走时 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (clockView != null && clockMode) {
                clockView.invalidate();
            }
            ui.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        p = getSharedPreferences("wf", MODE_PRIVATE);

        BgReceiver.engineRedraw = WatchfaceService::requestRedraw;
        BgReceiver.uiRefresh = () -> ui.post(this::refreshAll);

        buildUi();
        refreshAll();
        ui.postDelayed(tick, 60_000 - (System.currentTimeMillis() % 60_000) + 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        BgReceiver.uiRefresh = null;
        BgReceiver.engineRedraw = null;
    }

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------------- UI ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 时钟层（全屏）
        clockView = new ClockView(this);
        root.addView(clockView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部快捷启动行（桌面模式用，编辑界面打开时隐藏）
        dock = buildDock();
        root.addView(dock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // 编辑界面（预览/设置/嗅探）
        mainBox = buildMainBox();
        root.addView(mainBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右上角菜单按钮，始终可见
        Button menu = btn(clockMode ? "≡" : "×");
        menu.setTextSize(18);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(40), dp(40));
        mp.gravity = Gravity.TOP | Gravity.END;
        mp.setMargins(0, dp(4), dp(4), 0);
        menu.setOnClickListener(v -> {
            clockMode = !clockMode;
            ((Button) v).setText(clockMode ? "≡" : "×");
            mainBox.setVisibility(clockMode ? View.GONE : View.VISIBLE);
            dock.setVisibility(clockMode ? View.VISIBLE : View.GONE);
            applyImmersive();
            refreshAll();
        });
        root.addView(menu, mp);

        setContentView(root);
    }

    private LinearLayout buildMainBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        box.setPadding(dp(4), dp(48), dp(4), dp(4));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabPreview = btn("预览");
        tabSetting = btn("设置");
        tabSniff = btn("嗅探");
        tabs.addView(tabPreview, row());
        tabs.addView(tabSetting, row());
        tabs.addView(tabSniff, row());
        tabPreview.setOnClickListener(v -> show(0));
        tabSetting.setOnClickListener(v -> show(1));
        tabSniff.setOnClickListener(v -> show(2));
        box.addView(tabs);

        // 预览页：壁纸尝试 + 提示
        LinearLayout pv = new LinearLayout(this);
        pv.setOrientation(LinearLayout.VERTICAL);
        pv.setPadding(dp(8), dp(8), dp(8), 0);
        Button setWp = btn("设为壁纸（自动走时）");
        setWp.setOnClickListener(v -> setWallpaper());
        pv.addView(setWp, wide());
        Button setHome = btn("设为主屏幕（桌面模式）");
        setHome.setOnClickListener(v -> setAsHome());
        pv.addView(setHome, wide());
        pv.addView(hint("桌面模式：按 Home 键即回表盘，图标不遮挡；壁纸模式会被应用图标覆盖"));
        previewPage = pv;

        // 设置页
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setPadding(dp(8), dp(8), dp(8), 0);

        st.addView(title("数据源"));
        srcValue = btn("AAPS");
        srcValue.setOnClickListener(v -> {
            boolean toOttai = "aaps".equals(p.getString("src", "aaps"));
            p.edit().putString("src", toOttai ? "ottai" : "aaps").apply();
            refreshAll();
        });
        st.addView(srcValue, wide());
        st.addView(hint("AAPS: 需在手表 AAPS 配置中启用 Tizen 同步插件；欧态: 原始值需配合偏移"));

        st.addView(title("显示单位"));
        unitValue = btn("mmol/L");
        unitValue.setOnClickListener(v -> {
            boolean toMgdl = "mmol".equals(p.getString("unit", "mmol"));
            p.edit().putString("unit", toMgdl ? "mgdl" : "mmol").apply();
            refreshAll();
        });
        st.addView(unitValue, wide());

        st.addView(title("偏移（mmol/L，欧态源使用，默认 0）"));
        LinearLayout offRow = new LinearLayout(this);
        offRow.setOrientation(LinearLayout.HORIZONTAL);
        Button minus = btn("-");
        Button plus = btn("+");
        offsetValue = btn("0.00");
        minus.setOnClickListener(v -> stepOffset(-0.1f));
        plus.setOnClickListener(v -> stepOffset(0.1f));
        offRow.addView(minus, row());
        offRow.addView(offsetValue, row());
        offRow.addView(plus, row());
        st.addView(offRow, wide());

        st.addView(hint("AAPS 源数值已含固定偏移，此项保持 0 即与 AAPS 显示一致"));

        LinearLayout spacer = new LinearLayout(this);
        spacer.setOrientation(LinearLayout.VERTICAL);
        st.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        settingPage = st;

        // 嗅探页
        LinearLayout sn = new LinearLayout(this);
        sn.setOrientation(LinearLayout.VERTICAL);
        sniffText = new TextView(this);
        sniffText.setTextColor(Color.rgb(220, 220, 220));
        sniffText.setTextSize(11);
        sniffText.setTypeface(Typeface.MONOSPACE);
        sniffText.setPadding(dp(6), dp(8), dp(6), dp(8));
        sn.addView(sniffText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sniffPage = sn;

        box.addView(previewPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(settingPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(sniffPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private void show(int idx) {
        previewPage.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        settingPage.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        sniffPage.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        refreshAll();
    }

    private void refreshAll() {
        boolean aaps = "aaps".equals(p.getString("src", "aaps"));
        boolean mmol = "mmol".equals(p.getString("unit", "mmol"));
        srcValue.setText(aaps ? "AAPS" : "欧态");
        unitValue.setText(mmol ? "mmol/L" : "mg/dL");
        offsetValue.setText(String.format(java.util.Locale.US, "%.2f", p.getFloat("offset", 0f)));
        String sniff = p.getString("sniff", null);
        sniffText.setText(sniff == null
                ? "尚未收到广播\n\n检查：1) AAPS 配置启用 Tizen 同步插件\n2) 或欧态 app 是否在发数据\n收到下一次血糖读数后此页出现原文"
                : sniff);
        if (clockView != null) {
            clockView.invalidate();
        }
    }

    private void stepOffset(float d) {
        float v = Math.round((p.getFloat("offset", 0f) + d) * 100f) / 100f;
        if (v < -2f) v = -2f;
        if (v > 5f) v = 5f;
        p.edit().putFloat("offset", v).apply();
        refreshAll();
    }

    /** v4：直接写入系统静态壁纸并启动每分钟推送服务，无需系统壁纸设置页 */
    private void setWallpaper() {
        try {
            WallpaperPushService.pushOnce(this);
            startForegroundService(new Intent(this, WallpaperPushService.class));
            Toast.makeText(this, "已设为系统壁纸，主屏即表盘（每分钟走时）",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "设置壁纸失败：" + t.getMessage()
                    + "，可继续使用全屏时钟模式", Toast.LENGTH_LONG).show();
        }
    }

    /** 跳转系统"主屏幕应用"设置页；无此页面时提示按 Home 键选择 */
    private void setAsHome() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "无主屏幕设置页：请按 Home 键，在弹出选择框中选「表盘」",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 底部快捷启动行：探测已安装的常用应用，点击直接启动。
     * AAPS 的包名随 fork 不同，逐个候选探测。
     */
    private LinearLayout buildDock() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.HORIZONTAL);
        d.setGravity(Gravity.CENTER);
        d.setPadding(dp(4), 0, dp(4), dp(2));

        String[][] candidates = {
                {"AAPS", "info.nightscout.androidaps", "info.nightscout.aaps", "app.aaps"},
                {"欧态", "com.ottai.tag.watch"},
                {"设置", null}, // 系统设置固定入口
        };
        for (String[] cand : candidates) {
            Intent launch = null;
            if (cand[1] == null) {
                launch = new Intent(android.provider.Settings.ACTION_SETTINGS);
            } else {
                for (int i = 1; i < cand.length; i++) {
                    launch = getPackageManager().getLaunchIntentForPackage(cand[i]);
                    if (launch != null) {
                        break;
                    }
                }
            }
            if (launch != null) {
                final Intent f = launch;
                TextView t = new TextView(this);
                t.setText(cand[0]);
                t.setTextColor(Color.rgb(180, 180, 180));
                t.setTextSize(13);
                t.setPadding(dp(14), dp(8), dp(14), dp(8));
                t.setOnClickListener(v -> {
                    try {
                        f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(f);
                    } catch (Throwable ignored) {
                    }
                });
                d.addView(t);
            }
        }
        return d;
    }

    // ---------------- 控件工具 ----------------

    private class ClockView extends View {

        ClockView(Activity a) {
            super(a);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            FaceDrawer.draw(canvas, getContext(), true);
        }
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        return b;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.GRAY);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(120, 120, 120));
        t.setTextSize(10);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
