package io.github.zhantaopin.watchface;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 表盘绘制：按 {@link FaceStyle} 输出 3 种布局（纵向 / 双栏 / 圆环）× 4 套配色。
 * 适配任意尺寸（按画布短边缩放），设计基准 368x448。
 */
public final class FaceDrawer {

    /** TrendArrow 枚举 text → 符号（与 AAPS core/data TrendArrow.kt 对齐） */
    private static final Map<String, String> ARROWS = new HashMap<>();

    static {
        ARROWS.put("TripleUp", "↑↑");
        ARROWS.put("DoubleUp", "↑↑");
        ARROWS.put("SingleUp", "↑");
        ARROWS.put("FortyFiveUp", "↗");
        ARROWS.put("Flat", "→");
        ARROWS.put("FortyFiveDown", "↘");
        ARROWS.put("SingleDown", "↓");
        ARROWS.put("DoubleDown", "↓↓");
        ARROWS.put("TripleDown", "↓↓");
        ARROWS.put("NONE", "?");
    }

    private FaceDrawer() {
    }

    /** 背景图静态缓存：key = 路径+文件时间戳，避免每分钟重绘反复解码 */
    private static Bitmap sBg;
    private static String sBgKey;

    /** 眼睛图标命中区域（每次 draw 更新，供点击隐藏/显示血糖） */
    private static final RectF sEyeRect = new RectF();

    public static RectF eyeRect() {
        return sEyeRect;
    }

    /** 换背景后调用，强制下次重绘重新解码 */
    public static void invalidateBg() {
        sBg = null;
        sBgKey = null;
    }

    /** 背景图实际像素尺寸（只读文件头，不解码像素），无背景返回 null */
    public static int[] bgPixelSize(Context ctx) {
        String path = ctx.getSharedPreferences("wf", Context.MODE_PRIVATE)
                .getString("wf_bg", null);
        if (path == null) {
            return null;
        }
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            return null;
        }
        return new int[]{o.outWidth, o.outHeight};
    }

    private static Bitmap background(Context ctx) {
        String path = ctx.getSharedPreferences("wf", Context.MODE_PRIVATE)
                .getString("wf_bg", null);
        if (path == null) {
            return null;
        }
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        String key = path + ":" + f.lastModified();
        if (sBg == null || !key.equals(sBgKey)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            sBg = BitmapFactory.decodeFile(path, o);
            sBgKey = sBg != null ? key : null;
        }
        return sBg;
    }

    /** 趋势枚举 → 箭头符号，未知返回 ? */
    public static String arrowText(String name) {
        String a = ARROWS.get(name);
        return a != null ? a : "?";
    }

    /** 桌面时钟：读真实数据按当前样式绘制（reserveBottom=true 时给底部快捷入口留位置） */
    public static void draw(Canvas c, Context ctx, boolean reserveBottom) {
        SharedPreferences p = ctx.getSharedPreferences("wf", Context.MODE_PRIVATE);
        FaceStyle style = FaceStyle.current(p);
        int w = c.getWidth(), h = c.getHeight();
        float u = Math.min(w, h) / 448f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        drawBackground(c, ctx, p, style, w, h, paint);
        sEyeRect.setEmpty();
        render(c, paint, style, Data.load(p, battery(ctx)), w, h, u, reserveBottom, false);
    }

    /** 样式选择页缩略图：固定样例数据、不读背景图 */
    public static void drawPreview(Canvas c, FaceStyle style) {
        int w = c.getWidth(), h = c.getHeight();
        float u = Math.min(w, h) / 448f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(style.bg);
        c.drawRect(0, 0, w, h, paint);
        render(c, paint, style, Data.sample(), w, h, u, false, true);
    }

    private static void render(Canvas c, Paint p, FaceStyle s, Data d,
                               int w, int h, float u, boolean reserveBottom, boolean preview) {
        if (s.layout == FaceStyle.LAYOUT_B) {
            layoutB(c, p, s, d, w, h, u, preview);
        } else if (s.layout == FaceStyle.LAYOUT_C) {
            layoutC(c, p, s, d, w, h, u, reserveBottom, preview);
        } else {
            layoutA(c, p, s, d, w, h, u, reserveBottom, preview);
        }
    }

    // ---------------- 三种布局 ----------------

    /** A 纵向三段：时间在上、血糖居中、电量底部居中 */
    private static void layoutA(Canvas c, Paint p, FaceStyle s, Data d,
                                int w, int h, float u, boolean reserveBottom, boolean preview) {
        float cx = w / 2f;
        float timeY = h * 0.24f;
        txt(c, p, d.time, cx, timeY, 96 * u, s.fg, true, Paint.Align.CENTER);
        txt(c, p, d.date, cx, timeY + 46 * u, 32 * u, s.sub, false, Paint.Align.CENTER);

        float bgY = h * 0.56f;
        drawGlucose(c, p, s, d, w, cx, bgY, u, preview, 84);
        drawGlucoseMeta(c, p, s, d, cx, bgY, u, 24, 20, true);

        float by = reserveBottom ? h - 66 * u : h - 24 * u;
        drawBattery(c, p, cx, by, u, d.battery, batteryColor(s, d), 24 * u, Paint.Align.CENTER);
    }

    /** B 双栏卡片：时间左上、电量右上、分割线、血糖主区、IOB/COB 底部左右 */
    private static void layoutB(Canvas c, Paint p, FaceStyle s, Data d,
                                int w, int h, float u, boolean preview) {
        float left = 24 * u;
        float timeY = h * 0.20f;
        txt(c, p, d.time, left, timeY, 72 * u, s.fg, true, Paint.Align.LEFT);
        txt(c, p, d.date, left, timeY + 30 * u, 24 * u, s.sub, false, Paint.Align.LEFT);
        drawBattery(c, p, w - 20 * u, h * 0.155f, u, d.battery,
                batteryColor(s, d), 22 * u, Paint.Align.RIGHT);

        // 分割线
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.5f * u));
        p.setColor(withAlpha(s.meta, 90));
        c.drawLine(20 * u, h * 0.31f, w - 20 * u, h * 0.31f, p);
        p.setStyle(Paint.Style.FILL);

        float bgY = h * 0.56f;
        drawGlucose(c, p, s, d, w, w / 2f, bgY, u, preview, 84);
        // IOB/COB 已在底部左右分列，这里只画时效/Delta
        drawGlucoseMeta(c, p, s, d, w / 2f, bgY, u, 22, 18, false);

        // IOB / COB 底部左右分列
        float metaY = h * 0.83f;
        if (d.iobText != null && d.cobText != null) {
            txt(c, p, d.iobText, w * 0.26f, metaY, 22 * u, s.meta, false, Paint.Align.CENTER);
            txt(c, p, d.cobText, w * 0.74f, metaY, 22 * u, s.meta, false, Paint.Align.CENTER);
        } else if (d.iobText != null || d.cobText != null) {
            txt(c, p, d.iobText != null ? d.iobText : d.cobText,
                    w / 2f, metaY, 22 * u, s.meta, false, Paint.Align.CENTER);
        }
    }

    /** C 圆环聚焦：时间收进顶部圆环，血糖在环下，电量底部居中 */
    private static void layoutC(Canvas c, Paint p, FaceStyle s, Data d,
                                int w, int h, float u, boolean reserveBottom, boolean preview) {
        float cx = w / 2f;
        float cy = h * 0.22f;
        float r = 104 * u;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 2.5f * u));
        p.setColor(s.fg);
        c.drawCircle(cx, cy, r, p);

        // 12 个刻度，12 点位置用主色点缀
        p.setStrokeWidth(Math.max(1f, 2f * u));
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30.0);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            p.setColor(i == 0 ? s.good : withAlpha(s.sub, 150));
            c.drawLine(cx + cos * r, cy + sin * r,
                    cx + cos * (r - 6 * u), cy + sin * (r - 6 * u), p);
        }
        p.setStyle(Paint.Style.FILL);

        txt(c, p, d.time, cx, cy + 22 * u, 60 * u, s.fg, true, Paint.Align.CENTER);
        txt(c, p, d.date, cx, cy + 44 * u, 20 * u, s.sub, false, Paint.Align.CENTER);

        float bgY = h * 0.56f;
        drawGlucose(c, p, s, d, w, cx, bgY, u, preview, 84);
        drawGlucoseMeta(c, p, s, d, cx, bgY, u, 22, 18, true);

        float by = reserveBottom ? h - 66 * u : h - 24 * u;
        drawBattery(c, p, cx, by, u, d.battery, batteryColor(s, d), 22 * u, Paint.Align.CENTER);
    }

    // ---------------- 绘制部件 ----------------

    /** 血糖大字（含眼睛图标与隐私隐藏） */
    private static void drawGlucose(Canvas c, Paint p, FaceStyle s, Data d,
                                    float w, float cx, float bgY, float u, boolean preview,
                                    float valueSize) {
        if (!d.hasBg) {
            txt(c, p, "未收到血糖", cx, bgY, 28 * u, s.meta, false, Paint.Align.CENTER);
            return;
        }
        drawEye(c, w, bgY, u, d.hidden, s.meta, preview);
        if (d.hidden) {
            return;
        }
        txt(c, p, d.valueText(), cx, bgY, valueSize * u, d.valueColor(s),
                true, Paint.Align.CENTER);
    }

    /** 血糖下方的时效 / Delta / IOB / COB */
    private static void drawGlucoseMeta(Canvas c, Paint p, FaceStyle s, Data d,
                                        float cx, float bgY, float u,
                                        float line2Size, float iobSize, boolean showIob) {
        if (!d.hasBg || d.hidden) {
            return;
        }
        txt(c, p, d.line2(), cx, bgY + 38 * u, line2Size * u, s.meta,
                false, Paint.Align.CENTER);
        if (showIob && d.iobCob() != null) {
            txt(c, p, d.iobCob(), cx, bgY + 68 * u, iobSize * u, s.meta,
                    false, Paint.Align.CENTER);
        }
    }

    /** 眼睛图标：血糖块右上角，点击隐藏/显示血糖（闭眼态加斜线） */
    private static void drawEye(Canvas c, float w, float bgY, float u,
                                boolean hidden, int color, boolean preview) {
        float ew = 40 * u, eh = 28 * u;
        float left = w - 14 * u - ew, top = bgY - 54 * u;
        if (!preview) {
            sEyeRect.set(left, top, left + ew, top + eh);
        }
        Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
        ep.setColor(color);
        ep.setStyle(Paint.Style.STROKE);
        ep.setStrokeWidth(3 * u);
        c.drawOval(new RectF(left, top, left + ew, top + eh), ep);
        ep.setStyle(Paint.Style.FILL);
        c.drawCircle(left + ew / 2f, top + eh / 2f, 5 * u, ep);
        if (hidden) {
            ep.setStyle(Paint.Style.STROKE);
            c.drawLine(left - 2 * u, top + eh + 2 * u,
                    left + ew + 2 * u, top - 2 * u, ep);
        }
    }

    /** 电池图标 + 百分比；align 决定整组相对 anchorX 的对齐方式 */
    private static void drawBattery(Canvas c, Paint p, float anchorX, float baseline, float u,
                                    int pct, int color, float fontSize, Paint.Align align) {
        String label = pct >= 0 ? pct + "%" : "?";
        p.setTextSize(fontSize);
        p.setTypeface(Typeface.DEFAULT);
        float tw = p.measureText(label);
        float ih = fontSize * 0.62f;
        float iw = ih * 2.05f;
        float gap = fontSize * 0.3f;
        float cap = ih * 0.14f;
        float gw = iw + cap + gap + tw;
        float left = align == Paint.Align.CENTER ? anchorX - gw / 2f
                : align == Paint.Align.RIGHT ? anchorX - gw : anchorX;
        float top = baseline - fontSize * 0.72f;

        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.5f * u));
        float bodyRight = left + iw;
        c.drawRoundRect(new RectF(left, top, bodyRight, top + ih), ih * 0.25f, ih * 0.25f, p);
        c.drawRect(bodyRight, top + ih * 0.28f, bodyRight + cap, top + ih * 0.72f, p);

        if (pct > 0) {
            float pad = Math.max(1f, 1.2f * u);
            float fw = (iw - pad * 2) * Math.min(100, pct) / 100f;
            p.setStyle(Paint.Style.FILL);
            c.drawRect(left + pad, top + pad, left + pad + fw, top + ih - pad, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, bodyRight + cap + gap, baseline, p);
    }

    private static void drawBackground(Canvas c, Context ctx, SharedPreferences p,
                                       FaceStyle style, int w, int h, Paint paint) {
        Bitmap bg = background(ctx);
        paint.setStyle(Paint.Style.FILL);
        if (bg != null) {
            String mode = p.getString("wf_bg_mode", "cover");
            paint.setColor(Color.BLACK);
            c.drawRect(0, 0, w, h, paint);
            paint.setFilterBitmap(true);
            float bw = bg.getWidth(), bh = bg.getHeight();
            if ("stretch".equals(mode)) {
                // 拉伸铺满：宽高独立缩放，整张图都在屏内，代价是比例变形
                c.drawBitmap(bg, null, new RectF(0, 0, w, h), paint);
            } else {
                boolean cover = !"contain".equals(mode);
                float s = cover ? Math.max(w / bw, h / bh) : Math.min(w / bw, h / bh);
                float dw = bw * s, dh = bh * s;
                float offX = (w - dw) / 2f;
                float offY = (h - dh) / 2f;
                if (cover) {
                    offX += p.getFloat("wf_bg_dx", 0f) * w;
                    offY += p.getFloat("wf_bg_dy", 0f) * h;
                }
                // 必须按缩放后的目标矩形绘制；此前按原尺寸贴图，导致只显示左上角一块
                c.drawBitmap(bg, null, new RectF(offX, offY, offX + dw, offY + dh), paint);
            }
            paint.setFilterBitmap(false);
        } else {
            paint.setColor(style.bg);
            c.drawRect(0, 0, w, h, paint);
        }
    }

    // ---------------- 工具 ----------------

    private static void txt(Canvas c, Paint p, String s, float x, float y, float size,
                            int color, boolean bold, Paint.Align align) {
        p.setColor(color);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setTextSize(size);
        p.setTextAlign(align);
        c.drawText(s, x, y, p);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int batteryColor(FaceStyle s, Data d) {
        return d.battery >= 0 && d.battery < 20 ? s.bad : s.meta;
    }

    private static int battery(Context ctx) {
        try {
            Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) {
                return -1;
            }
            int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return level >= 0 && scale > 0 ? level * 100 / scale : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 一次绘制所需的数据快照（真实数据或缩略图样例） */
    private static final class Data {
        String time, date, arrow;
        boolean hasBg, hidden, mmol, hasDelta;
        double mmolValue, shown;
        long ageMin;
        String deltaText;
        String iobText, cobText;
        int battery;

        static Data load(SharedPreferences p, int battery) {
            Data d = new Data();
            Date now = new Date();
            d.time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
            d.date = new SimpleDateFormat("M月d日 E", Locale.CHINA).format(now);
            d.battery = battery;
            d.hidden = p.getBoolean("wf_bg_hide", false);
            d.hasBg = p.contains("bg_mgdl");
            if (!d.hasBg) {
                return d;
            }
            double mgdl = p.getFloat("bg_mgdl", 0);
            boolean fromAaps = "aaps".equals(p.getString("bg_src", "aaps"));
            float offsetMmol = p.getFloat("offset", 0f);
            // AAPS 源已含偏移；欧态源叠加用户偏移
            double effMgdl = fromAaps ? mgdl : mgdl + offsetMmol * 18.0;
            d.mmolValue = effMgdl / 18.0;
            d.mmol = "mmol".equals(p.getString("unit", "mmol"));
            d.shown = d.mmol ? d.mmolValue : effMgdl;
            d.arrow = arrowText(p.getString("bg_arrow", "NONE"));
            d.ageMin = (System.currentTimeMillis() - p.getLong("bg_time", 0)) / 60000L;
            d.hasDelta = p.getBoolean("bg_has_delta", false);
            if (d.hasDelta) {
                double deltaMgdl = p.getFloat("bg_delta", 0) + (fromAaps ? 0 : offsetMmol * 18);
                d.deltaText = d.mmol
                        ? String.format(Locale.US, "%+.1f", deltaMgdl / 18.0)
                        : String.format(Locale.US, "%+.0f", deltaMgdl);
            }
            float iob = p.getFloat("iob", -1);
            float cob = p.getFloat("cob", -1);
            if (iob >= 0) {
                d.iobText = "IOB " + String.format(Locale.US, "%.1fU", iob);
            }
            if (cob >= 0) {
                d.cobText = "COB " + String.format(Locale.US, "%.0fg", cob);
            }
            return d;
        }

        static Data sample() {
            Data d = new Data();
            d.time = "19:52";
            d.date = "11月22日 周日";
            d.hasBg = true;
            d.mmol = true;
            d.mmolValue = 6.4;
            d.shown = 6.4;
            d.arrow = "↑";
            d.hasDelta = true;
            d.deltaText = "+0.3";
            d.ageMin = 12;
            d.iobText = "IOB 1.2U";
            d.cobText = "COB 15g";
            d.battery = 78;
            return d;
        }

        String valueText() {
            return (mmol ? String.format(Locale.US, "%.1f", shown)
                    : String.valueOf((int) Math.round(shown))) + " " + arrow;
        }

        int valueColor(FaceStyle s) {
            return mmolValue < 3.9 || mmolValue > 13.9 ? s.bad : s.good;
        }

        String line2() {
            return hasDelta ? deltaText + "  " + ageMin + "分钟前" : ageMin + "分钟前";
        }

        String iobCob() {
            if (iobText == null) {
                return cobText;
            }
            return cobText == null ? iobText : iobText + "  " + cobText;
        }
    }
}
