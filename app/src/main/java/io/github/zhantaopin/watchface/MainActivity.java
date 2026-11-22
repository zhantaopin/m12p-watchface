package io.github.zhantaopin.watchface;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
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
import android.widget.HorizontalScrollView;
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
    private HorizontalScrollView pager;
    private LinearLayout drawerList;
    private LinearLayout pickerBox;
    private TextView pickerTitle;
    private LinearLayout pickerList;
    /** 抽屉白名单/快捷入口选择模式：0=抽屉 1=快捷入口 */
    private int pickerMode;
    private final List<String> pickedPkgs = new ArrayList<>();
    private TextView sniffText, srcValue, unitValue, offsetValue, fontValue, bgInfo;
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

        // 双屏横向翻页容器：第0屏=表盘，第1屏=应用列表；跟手拖动、松手吸附
        pager = new HorizontalScrollView(this);
        pager.setFillViewport(true);
        pager.setHorizontalScrollBarEnabled(false);
        pager.setBackgroundColor(Color.BLACK);

        clockView = new ClockView(this);
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        int pw = getResources().getDisplayMetrics().widthPixels;
        pages.addView(clockView, new LinearLayout.LayoutParams(
                pw, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.addView(buildDrawerPage(), new LinearLayout.LayoutParams(
                pw, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(pages, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 松手后按滚动位置吸附到最近一屏，翻到应用屏时重建列表
        pager.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pager.postDelayed(this::snapPager, 120);
            }
            return false;
        });
        rootLayout.addView(pager, new FrameLayout.LayoutParams(
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

        // 预览页：桌面模式 + 背景图 + 提示
        LinearLayout pv = new LinearLayout(this);
        pv.setOrientation(LinearLayout.VERTICAL);
        pv.setPadding(dp(8), dp(8), dp(8), 0);
        Button setHome = btn("设为主屏幕（桌面模式）");
        setHome.setOnClickListener(v -> setAsHome());
        pv.addView(setHome, wide());
        Button pickBg = btn("更换背景图");
        pickBg.setOnClickListener(v -> pickBgImage());
        pv.addView(pickBg, wide());
        Button resetBg = btn("恢复纯黑背景");
        resetBg.setOnClickListener(v -> resetBg());
        pv.addView(resetBg, wide());
        Button bgMode = btn(bgModeLabel());
        bgMode.setOnClickListener(v -> {
            String cur = p.getString("wf_bg_mode", "cover");
            String next = "cover".equals(cur) ? "contain"
                    : "contain".equals(cur) ? "stretch" : "cover";
            p.edit().putString("wf_bg_mode", next).apply();
            bgMode.setText(bgModeLabel());
            updateBgInfo();
            clockView.invalidate();
        });
        pv.addView(bgMode, wide());
        bgInfo = hint("");
        pv.addView(bgInfo);
        pv.addView(title("调整背景位置（铺满模式生效）"));
        LinearLayout posRow = new LinearLayout(this);
        posRow.setOrientation(LinearLayout.HORIZONTAL);
        Button bLeft = btn("◀");
        bLeft.setOnClickListener(v -> nudgeBg(-0.05f, 0f));
        Button bRight = btn("▶");
        bRight.setOnClickListener(v -> nudgeBg(0.05f, 0f));
        Button bUp = btn("▲");
        bUp.setOnClickListener(v -> nudgeBg(0f, -0.05f));
        Button bDown = btn("▼");
        bDown.setOnClickListener(v -> nudgeBg(0f, 0.05f));
        posRow.addView(bLeft, row());
        posRow.addView(bRight, row());
        posRow.addView(bUp, row());
        posRow.addView(bDown, row());
        pv.addView(posRow, wide());
        Button resetPos = btn("重置背景位置");
        resetPos.setOnClickListener(v -> {
            p.edit().putFloat("wf_bg_dx", 0f).putFloat("wf_bg_dy", 0f).apply();
            clockView.invalidate();
        });
        pv.addView(resetPos, wide());
        pv.addView(hint("桌面模式：按 Home 键即回表盘；左右拖动在表盘/应用列表两屏间切换；表盘血糖旁的眼睛图标可隐藏/显示血糖"));
        previewPage = scrollWrap(pv);

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

        settingPage = scrollWrap(st);

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
        sniffPage = scrollWrap(sn);

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
        updateBgInfo();
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

    /** 跳转系统"主屏幕应用"设置页；无此页面时提示按 Home 键选择 */
    private void setAsHome() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "无主屏幕设置页：请按 Home 键，在弹出选择框中选「表盘」",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 双屏翻页吸附：按当前滚动位置回弹到表盘屏或应用屏 */
    private void snapPager() {
        int pw = getResources().getDisplayMetrics().widthPixels;
        int page = pager.getScrollX() > pw / 2 ? 1 : 0;
        pager.smoothScrollTo(page * pw, 0);
        if (page == 1) {
            rebuildDrawerPage();
        }
    }

    /** 背景显示位置微调（铺满模式生效），范围 ±0.4 屏宽/屏高，步进 0.05 */
    private void nudgeBg(float dx, float dy) {
        float x = p.getFloat("wf_bg_dx", 0f) + dx;
        float y = p.getFloat("wf_bg_dy", 0f) + dy;
        if (x < -0.4f) x = -0.4f;
        if (x > 0.4f) x = 0.4f;
        if (y < -0.4f) y = -0.4f;
        if (y > 0.4f) y = 0.4f;
        p.edit().putFloat("wf_bg_dx", x).putFloat("wf_bg_dy", y).apply();
        clockView.invalidate();
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
    private List<AppInfo> loadApps(boolean applyWhitelist) {
        List<AppInfo> out = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> white = p.getStringSet("wf_drawer_apps", null);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pk = ri.activityInfo.packageName;
            if (pk.equals(getPackageName())) {
                continue;
            }
            if (applyWhitelist && white != null && !white.isEmpty() && !white.contains(pk)) {
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

    /** 第1屏：应用列表（九宫格，白名单过滤，空=全部） */
    private ScrollView buildDrawerPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setPadding(dp(4), dp(44), dp(4), dp(52));
        sv.addView(drawerList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuildDrawerPage();
        return sv;
    }

    /** 重建应用网格（白名单/安装变化后调用） */
    private void rebuildDrawerPage() {
        if (drawerList == null) {
            return;
        }
        drawerList.removeAllViews();
        TextView head = new TextView(this);
        head.setText("全部应用（左右拖动切换屏幕）");
        head.setTextColor(Color.rgb(130, 130, 130));
        head.setTextSize(13);
        head.setGravity(Gravity.CENTER);
        drawerList.addView(head, wide());

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        for (AppInfo ai : loadApps(true)) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ai.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(52), dp(52));
            ip.setMargins(0, 0, 0, dp(4));
            cell.addView(iv, ip);
            TextView tv = new TextView(this);
            tv.setText(ai.label);
            tv.setTextColor(Color.rgb(205, 205, 205));
            tv.setTextSize(15);
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
            cell.setOnLongClickListener(v -> {
                showAppMenu(ai);
                return true;
            });
            GridLayout.LayoutParams cp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            cp.width = 0;
            cp.setMargins(dp(2), dp(2), dp(2), dp(4));
            grid.addView(cell, cp);
        }
        drawerList.addView(grid, wide());
    }

    // ---------------- 长按应用菜单 ----------------

    /** 长按图标：固定到快捷入口 / 应用信息 / 卸载 */
    private void showAppMenu(AppInfo ai) {
        if (SYS_SETTINGS.equals(ai.pkg)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(ai.label)
                .setItems(new String[]{"固定到快捷入口", "应用信息", "卸载"},
                        (d, w) -> {
                            if (w == 0) {
                                pinToDock(ai.pkg);
                            } else if (w == 1) {
                                openAppInfo(ai.pkg);
                            } else {
                                openUninstall(ai.pkg);
                            }
                        })
                .show();
    }

    /** 加入底部快捷行（沿用默认项初始化，上限 4 个） */
    private void pinToDock(String pkg) {
        List<String> list = new ArrayList<>();
        String saved = p.getString("wf_dock_list", "");
        if (saved.isEmpty()) {
            for (String[] e : dockEntries()) {
                if (!SYS_SETTINGS.equals(e[1]) && !list.contains(e[1])) {
                    list.add(e[1]);
                }
            }
        } else {
            Collections.addAll(list, saved.split("\\|"));
        }
        if (list.contains(pkg)) {
            Toast.makeText(this, "已在快捷入口", Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.size() >= 4) {
            Toast.makeText(this, "快捷入口最多 4 个", Toast.LENGTH_SHORT).show();
            return;
        }
        list.add(pkg);
        p.edit().putString("wf_dock_list", String.join("|", list)).apply();
        rebuildDock();
        Toast.makeText(this, "已固定到快捷入口", Toast.LENGTH_SHORT).show();
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(android.provider.Settings
                    .ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开应用信息", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + pkg)));
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动卸载", Toast.LENGTH_SHORT).show();
        }
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
        for (AppInfo ai : loadApps(false)) {
            addPickerRow(ai.label, ai.pkg);
        }
        pickerList.addView(hint(pickerMode == MODE_DOCK
                ? "勾选顺序即为底部排列顺序" : "不勾选任何应用时抽屉显示全部"));
        pickerBox.setVisibility(View.VISIBLE);
        mainBox.setVisibility(View.GONE);
        rebuildDrawerPage();
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
        java.io.File tmp = new java.io.File(getCacheDir(), "bg_src");
        // 部分精简 ROM 的图库返回的是缩略图 URI，先换成原图 URI 再读
        Uri srcUri = resolveOriginalUri(data.getData());
        try (InputStream in = getContentResolver().openInputStream(srcUri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制图片失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        // 解码 + 应用 EXIF 方向（旋转/镜像/转置），压缩保存
        try {
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), bo);
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inSampleSize = Math.max(1, Math.max(bo.outWidth, bo.outHeight) / 1600);
            android.graphics.Bitmap raw = BitmapFactory.decodeFile(tmp.getAbsolutePath(), b);
            if (raw == null) {
                Toast.makeText(this, "无法解码这张图片", Toast.LENGTH_LONG).show();
                tmp.delete();
                return;
            }
            android.graphics.Matrix m = exifMatrix(tmp);
            if (m != null) {
                raw = android.graphics.Bitmap.createBitmap(
                        raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            }
            FileOutputStream out = openFileOutput("bg.png", MODE_PRIVATE);
            raw.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out);
            out.close();
        } catch (Throwable t) {
            Toast.makeText(this, "处理图片失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        tmp.delete();
        p.edit().putString("wf_bg", new File(getFilesDir(), "bg.png").getAbsolutePath()).apply();
        FaceDrawer.invalidateBg();
        clockView.invalidate();
        updateBgInfo();
        int[] sz = FaceDrawer.bgPixelSize(this);
        Toast.makeText(this, sz == null ? "背景已更换"
                : "背景已更换（" + sz[0] + "×" + sz[1] + "）", Toast.LENGTH_SHORT).show();
    }

    /** EXIF 方向 → 变换矩阵（旋转 + 镜像 + 转置全量处理），无变换返回 null */
    private android.graphics.Matrix exifMatrix(File f) {
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(f.getAbsolutePath());
            int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.setScale(-1, 1);
                    return m;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                    m.setRotate(180);
                    return m;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.setRotate(180);
                    m.postScale(-1, 1);
                    return m;
                case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                    m.setRotate(90);
                    m.postScale(-1, 1);
                    return m;
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                    m.setRotate(90);
                    return m;
                case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                    m.setRotate(-90);
                    m.postScale(-1, 1);
                    return m;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                    m.setRotate(-90);
                    return m;
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 把图库返回的 URI 还原成原图 URI。
     * 部分精简 ROM 返回 .../images/thumbnails/123（缩略图），直接读会得到裁切/低清图；
     * 这里按媒体 ID 换成 .../images/media/123 再读。
     */
    private Uri resolveOriginalUri(Uri u) {
        try {
            if (u == null || !"content".equals(u.getScheme())) {
                return u;
            }
            String path = u.getPath() == null ? "" : u.getPath();
            String last = u.getLastPathSegment();
            if (last == null || !path.contains("/images/")) {
                return u;
            }
            if (last.contains(":")) {
                last = last.substring(last.lastIndexOf(':') + 1);
            }
            long id = Long.parseLong(last);
            return android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        } catch (Throwable ignored) {
            return u;
        }
    }

    private void resetBg() {
        new File(getFilesDir(), "bg.png").delete();
        p.edit().remove("wf_bg").apply();
        FaceDrawer.invalidateBg();
        clockView.invalidate();
        updateBgInfo();
        Toast.makeText(this, "已恢复纯黑背景", Toast.LENGTH_SHORT).show();
    }

    /** 页面内容套 ScrollView，竖向可滑动（设置/嗅探内容超出屏幕） */
    private ScrollView scrollWrap(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
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

                /** 点眼睛图标隐藏/显示血糖 */
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (FaceDrawer.eyeRect().contains(e.getX(), e.getY())) {
                        toggleBgHide();
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

    /** 切换血糖隐私隐藏状态（眼睛图标） */
    private void toggleBgHide() {
        p.edit().putBoolean("wf_bg_hide", !p.getBoolean("wf_bg_hide", false)).apply();
        if (clockView != null) {
            clockView.invalidate();
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

    private String bgModeLabel() {
        String m = p.getString("wf_bg_mode", "cover");
        String name = "cover".equals(m) ? "铺满全屏（裁剪）"
                : "contain".equals(m) ? "完整显示（留边）" : "拉伸铺满（变形）";
        return "背景显示模式：" + name;
    }

    /** 预览页背景诊断：实际像素尺寸 + 模式 + 缩放比，用于判断选进来的图是否被图库缩成缩略图 */
    private void updateBgInfo() {
        if (bgInfo == null) {
            return;
        }
        int[] sz = FaceDrawer.bgPixelSize(this);
        if (sz == null) {
            bgInfo.setText("背景：纯黑（未设置图片）");
            return;
        }
        String m = p.getString("wf_bg_mode", "cover");
        if ("stretch".equals(m)) {
            bgInfo.setText(String.format(Locale.US, "背景：%d×%d / 拉伸（变形）", sz[0], sz[1]));
            return;
        }
        int w = clockView != null ? clockView.getWidth() : 0;
        int h = clockView != null ? clockView.getHeight() : 0;
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        float s = "cover".equals(m)
                ? Math.max(w / (float) sz[0], h / (float) sz[1])
                : Math.min(w / (float) sz[0], h / (float) sz[1]);
        bgInfo.setText(String.format(Locale.US, "背景：%d×%d / %s / 缩放 %.2f",
                sz[0], sz[1], "cover".equals(m) ? "铺满" : "完整", s));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
