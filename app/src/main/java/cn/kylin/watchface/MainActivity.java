package cn.kylin.watchface;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 全屏时钟（主模式）：打开即表盘；右上角小按钮进入编辑界面（设置/嗅探/预览）。
 * ROM 无动态壁纸选择器时的降级方案。
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences p;
    private boolean clockMode = true;
    private int curPage = 0; // 编辑界面当前页：0 预览 1 设置 2 嗅探

    private ClockView clockView;
    private FrameLayout rootLayout;
    private LinearLayout mainBox, dock;
    private ScrollView drawerBox;
    private LinearLayout pickerBox;
    private TextView pickerTitle;
    private LinearLayout pickerList;
    /** 抽屉白名单/快捷入口选择模式：0=抽屉 1=快捷入口 */
    private int pickerMode;
    private final List<String> pickedPkgs = new ArrayList<>();
    private TextView sniffText, srcValue, unitValue, offsetValue, fontValue;
    private Button tabPreview, tabSetting, tabSniff;
    private View previewPage, settingPage, sniffPage;

    private static final int REQ_PICK_IMAGE = 42;
    private static final String SYS_SETTINGS = "sys:settings";
    private static final int MODE_DRAWER = 0;
    private static final int MODE_DOCK = 1;
    private static final float[] UI_FONT_LEVELS = {1.0f, 1.2f, 1.4f}; // 界面字号三档

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
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        // 时钟层（全屏）
        clockView = new ClockView(this);
        rootLayout.addView(clockView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 左滑应用抽屉（九宫格），默认隐藏；位于 dock 之下，抽屉打开时底部快捷行仍可见
        drawerBox = buildDrawer();
        drawerBox.setVisibility(View.GONE);
        rootLayout.addView(drawerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 底部快捷启动行（桌面模式用，编辑界面打开时隐藏）
        dock = buildDock();
        rootLayout.addView(dock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // 应用选择器（白名单/快捷入口），默认隐藏
        pickerBox = buildPicker();
        pickerBox.setVisibility(View.GONE);
        rootLayout.addView(pickerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 编辑界面（预览/设置/嗅探），桌面模式默认隐藏——按 Home 直接进表盘
        mainBox = buildMainBox();
        rootLayout.addView(mainBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainBox.setVisibility(clockMode ? View.GONE : View.VISIBLE);

        // 右上角菜单按钮，始终可见
        Button menu = btn(clockMode ? "≡" : "×");
        menu.setTextSize(18);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(40), dp(40));
        mp.gravity = Gravity.TOP | Gravity.END;
        mp.setMargins(0, dp(4), dp(4), 0);
        menu.setOnClickListener(v -> {
            if (pickerBox.getVisibility() == View.VISIBLE) {
                closePicker();
                return;
            }
            if (!clockMode && drawerBox.getVisibility() == View.VISIBLE) {
                closeDrawer();
            }
            clockMode = !clockMode;
            ((Button) v).setText(clockMode ? "≡" : "×");
            mainBox.setVisibility(clockMode ? View.GONE : View.VISIBLE);
            dock.setVisibility(clockMode ? View.VISIBLE : View.GONE);
            applyImmersive();
            refreshAll();
        });
        rootLayout.addView(menu, mp);

        setContentView(rootLayout);
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
        Button pickBg = btn("更换背景图");
        pickBg.setOnClickListener(v -> pickBgImage());
        pv.addView(pickBg, wide());
        Button resetBg = btn("恢复纯黑背景");
        resetBg.setOnClickListener(v -> resetBg());
        pv.addView(resetBg, wide());
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

        st.addView(title("界面字号（预览/设置/嗅探）"));
        fontValue = btn(uiFontLabel());
        fontValue.setOnClickListener(v -> {
            p.edit().putInt("wf_ui_font_idx",
                    (uiFontIdx() + 1) % UI_FONT_LEVELS.length).apply();
            rebuildUi();
        });
        st.addView(fontValue, wide());
        Button pickDrawer = btn("选择应用（白名单，空=全部）");
        pickDrawer.setOnClickListener(v -> openPicker(MODE_DRAWER));
        st.addView(pickDrawer, wide());

        st.addView(title("快捷入口（底部一行）"));
        Button pickDock = btn("选择快捷入口（最多4个）");
        pickDock.setOnClickListener(v -> openPicker(MODE_DOCK));
        st.addView(pickDock, wide());

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
        sniffText.setTextSize(11 * uiFont());
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
        curPage = idx;
        previewPage.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        settingPage.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        sniffPage.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        refreshAll();
    }

    /** 界面字号等全局 UI 参数变化后重建整套界面（保持桌面/编辑状态与当前页） */
    private void rebuildUi() {
        rootLayout.removeAllViews();
        buildUi();
        if (!clockMode) {
            show(curPage);
        }
        refreshAll();
    }

    private void refreshAll() {
        boolean aaps = "aaps".equals(p.getString("src", "aaps"));
        boolean mmol = "mmol".equals(p.getString("unit", "mmol"));
        srcValue.setText(aaps ? "AAPS" : "欧态");
        unitValue.setText(mmol ? "mmol/L" : "mg/dL");
        offsetValue.setText(String.format(java.util.Locale.US, "%.2f", p.getFloat("offset", 0f)));
        fontValue.setText(uiFontLabel());
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
     * 底部快捷启动行：优先用用户配置（wf_dock_list，竖线分隔包名），
     * 未配置时探测默认候选（AAPS 包名随 fork 不同，逐个探测）。
     */
    private LinearLayout buildDock() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.HORIZONTAL);
        d.setGravity(Gravity.CENTER);
        d.setPadding(dp(4), 0, dp(4), dp(2));

        for (String[] e : dockEntries()) {
            final Intent launch;
            if (SYS_SETTINGS.equals(e[1])) {
                launch = new Intent(android.provider.Settings.ACTION_SETTINGS);
            } else {
                launch = getPackageManager().getLaunchIntentForPackage(e[1]);
            }
            if (launch == null) {
                continue;
            }
            final Intent f = launch;
            TextView t = new TextView(this);
            t.setText(e[0]);
            t.setTextColor(Color.rgb(180, 180, 180));
            t.setTextSize(13);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setOnClickListener(v -> {
                try {
                    f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(f);
                } catch (Throwable ignored) {
                }
            });
            d.addView(t);
        }
        return d;
    }

    private List<String[]> dockEntries() {
        List<String[]> out = new ArrayList<>();
        String saved = p.getString("wf_dock_list", "");
        if (saved.isEmpty()) {
            addDockEntry(out, "AAPS", "info.nightscout.androidaps", "info.nightscout.aaps", "app.aaps");
            addDockEntry(out, "欧态", "com.ottai.tag.watch");
            out.add(new String[]{"设置", SYS_SETTINGS});
            return out;
        }
        for (String e : saved.split("\\|")) {
            if (e.isEmpty()) {
                continue;
            }
            if (SYS_SETTINGS.equals(e)) {
                out.add(new String[]{"设置", SYS_SETTINGS});
            } else {
                String label;
                try {
                    label = getPackageManager().getApplicationInfo(e, 0)
                            .loadLabel(getPackageManager()).toString();
                } catch (Throwable t) {
                    label = e;
                }
                out.add(new String[]{label, e});
            }
        }
        return out;
    }

    private void addDockEntry(List<String[]> out, String label, String... pkgs) {
        for (String pk : pkgs) {
            if (getPackageManager().getLaunchIntentForPackage(pk) != null) {
                out.add(new String[]{label, pk});
                return;
            }
        }
    }

    /** 快捷入口配置变化后重建底部行 */
    private void rebuildDock() {
        rootLayout.removeView(dock);
        dock = buildDock();
        rootLayout.addView(dock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    // ---------------- 应用抽屉 ----------------

    private static class AppInfo {
        final String label, pkg;
        final Drawable icon;

        AppInfo(String l, String pk, Drawable ic) {
            label = l;
            pkg = pk;
            icon = ic;
        }
    }

    /** 可启动应用列表：排除自身，应用白名单过滤，按中文名排序 */
    private List<AppInfo> loadApps() {
        List<AppInfo> out = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> white = p.getStringSet("wf_drawer_apps", null);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pk = ri.activityInfo.packageName;
            if (pk.equals(getPackageName())) {
                continue;
            }
            if (white != null && !white.isEmpty() && !white.contains(pk)) {
                continue;
            }
            Drawable icon = null;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            out.add(new AppInfo(ri.loadLabel(pm).toString(), pk, icon));
        }
        Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        return out;
    }

    private ScrollView buildDrawer() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.argb(248, 10, 10, 10));
        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy)
                                && Math.abs(vx) > 400) {
                            closeDrawer();
                            return true;
                        }
                        return false;
                    }
                });
        sv.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return false;
        });
        return sv;
    }

    private void openDrawer() {
        drawerBox.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(4), dp(44), dp(4), dp(48));
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = new TextView(this);
        head.setText("全部应用（左右滑返回）");
        head.setTextColor(Color.rgb(130, 130, 130));
        head.setTextSize(13);
        headRow.addView(head, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button close = btn("×");
        close.setOnClickListener(v -> closeDrawer());
        headRow.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(headRow, wide());

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (AppInfo ai : loadApps()) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ai.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMargins(0, 0, 0, dp(4));
            cell.addView(iv, ip);
            TextView tv = new TextView(this);
            tv.setText(ai.label);
            tv.setTextColor(Color.rgb(205, 205, 205));
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(tv, new LinearLayout.LayoutParams(
                    dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(v -> {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(ai.pkg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                } catch (Throwable ignored) {
                }
            });
            GridLayout.LayoutParams cp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            cp.width = 0;
            cp.setMargins(dp(2), dp(2), dp(2), dp(4));
            grid.addView(cell, cp);
        }
        wrap.addView(grid, wide());
        drawerBox.addView(wrap);
        drawerBox.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerBox.setVisibility(View.GONE);
    }

    // ---------------- 应用选择器 ----------------

    private LinearLayout buildPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        box.setPadding(dp(8), dp(44), dp(8), dp(8));
        pickerTitle = new TextView(this);
        pickerTitle.setTextColor(Color.WHITE);
        pickerTitle.setTextSize(15 * uiFont());
        pickerTitle.setPadding(0, 0, 0, dp(6));
        box.addView(pickerTitle);
        ScrollView sv = new ScrollView(this);
        pickerList = new LinearLayout(this);
        pickerList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(pickerList);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = btn("取消");
        Button save = btn("保存");
        cancel.setOnClickListener(v -> closePicker());
        save.setOnClickListener(v -> savePicker());
        row.addView(cancel, row());
        row.addView(save, row());
        box.addView(row, wide());
        return box;
    }

    private void openPicker(int mode) {
        pickerMode = mode;
        pickedPkgs.clear();
        if (mode == MODE_DRAWER) {
            pickerTitle.setText("选择应用（抽屉白名单）");
            Set<String> cur = p.getStringSet("wf_drawer_apps", null);
            if (cur != null) {
                pickedPkgs.addAll(cur);
            }
        } else {
            pickerTitle.setText("选择快捷入口（最多4个）");
            String saved = p.getString("wf_dock_list", "");
            if (!saved.isEmpty()) {
                Collections.addAll(pickedPkgs, saved.split("\\|"));
            }
        }
        pickerList.removeAllViews();
        if (mode == MODE_DOCK) {
            addPickerRow("系统设置", SYS_SETTINGS);
        }
        for (AppInfo ai : loadApps()) {
            addPickerRow(ai.label, ai.pkg);
        }
        pickerList.addView(hint(pickerMode == MODE_DOCK
                ? "勾选顺序即为底部排列顺序" : "不勾选任何应用时抽屉显示全部"));
        pickerBox.setVisibility(View.VISIBLE);
        mainBox.setVisibility(View.GONE);
        drawerBox.setVisibility(View.GONE);
        dock.setVisibility(View.GONE);
    }

    private void addPickerRow(String label, final String pkg) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.rgb(210, 210, 210));
        name.setTextSize(13 * uiFont());
        name.setPadding(dp(6), dp(8), dp(4), dp(8));
        CheckBox cb = new CheckBox(this);
        cb.setChecked(pickedPkgs.contains(pkg));
        cb.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            if (on) {
                if (pickerMode == MODE_DOCK && pickedPkgs.size() >= 4) {
                    cb.setChecked(false);
                    Toast.makeText(this, "快捷入口最多4个", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pickedPkgs.contains(pkg)) {
                    pickedPkgs.add(pkg);
                }
            } else {
                pickedPkgs.remove(pkg);
            }
        });
        r.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(cb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pickerList.addView(r, wide());
    }

    private void savePicker() {
        if (pickerMode == MODE_DRAWER) {
            p.edit().putStringSet("wf_drawer_apps", new HashSet<>(pickedPkgs)).apply();
            Toast.makeText(this, pickedPkgs.isEmpty()
                    ? "抽屉将显示全部应用" : "抽屉白名单已保存（" + pickedPkgs.size() + "个）",
                    Toast.LENGTH_SHORT).show();
        } else {
            p.edit().putString("wf_dock_list", String.join("|", pickedPkgs)).apply();
            Toast.makeText(this, pickedPkgs.isEmpty()
                    ? "快捷入口恢复默认" : "快捷入口已保存（" + pickedPkgs.size() + "个）",
                    Toast.LENGTH_SHORT).show();
            rebuildDock();
        }
        closePicker();
    }

    private void closePicker() {
        pickerBox.setVisibility(View.GONE);
        mainBox.setVisibility(clockMode ? View.GONE : View.VISIBLE);
        dock.setVisibility(clockMode ? View.VISIBLE : View.GONE);
    }

    // ---------------- 背景图 ----------------

    private void pickBgImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选择背景图"), REQ_PICK_IMAGE);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQ_PICK_IMAGE || code != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData());
             FileOutputStream out = openFileOutput("bg.png", MODE_PRIVATE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制图片失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        p.edit().putString("wf_bg", new File(getFilesDir(), "bg.png").getAbsolutePath()).apply();
        FaceDrawer.invalidateBg();
        clockView.invalidate();
        try {
            WallpaperPushService.pushOnce(this);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "背景已更换", Toast.LENGTH_SHORT).show();
    }

    private void resetBg() {
        new File(getFilesDir(), "bg.png").delete();
        p.edit().remove("wf_bg").apply();
        FaceDrawer.invalidateBg();
        clockView.invalidate();
        try {
            WallpaperPushService.pushOnce(this);
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已恢复纯黑背景", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 控件工具 ----------------

    private class ClockView extends View {

        private final GestureDetector gd;

        ClockView(Activity a) {
            super(a);
            // 必须可点击：否则 DOWN 返回 false 后系统不再派发后续触摸事件，手势失效
            setClickable(true);
            gd = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2,
                                       float vx, float vy) {
                    if (e1 == null || e2 == null) {
                        return false;
                    }
                    float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy)
                            && Math.abs(vx) > 400) {
                        openDrawer();
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            FaceDrawer.draw(canvas, getContext(), true);
        }

        /** 表盘左右滑动呼出应用抽屉 */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (gd.onTouchEvent(e)) {
                return true;
            }
            return super.onTouchEvent(e);
        }
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13 * uiFont());
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
        t.setTextSize(12 * uiFont());
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(120, 120, 120));
        t.setTextSize(10 * uiFont());
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

    private int uiFontIdx() {
        return p.getInt("wf_ui_font_idx", 0);
    }

    private float uiFont() {
        return UI_FONT_LEVELS[Math.min(uiFontIdx(), UI_FONT_LEVELS.length - 1)];
    }

    private String uiFontLabel() {
        int i = uiFontIdx();
        return i == 0 ? "界面字号：标准" : i == 1 ? "界面字号：大" : "界面字号：特大";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
