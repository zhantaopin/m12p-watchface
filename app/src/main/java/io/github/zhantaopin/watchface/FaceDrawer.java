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
 * 表盘绘制：黑底、大号时间、日期、血糖块（值/箭头/时效/delta）、IOB/COB、电量。
 * 适配任意尺寸（按画布宽缩放），设计基准 368x448。
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

    private static final int GREEN = Color.rgb(0, 200, 83);
    private static final int RED = Color.rgb(255, 82, 82);
    private static final int GRAY = Color.rgb(160, 160, 160);
    private static final int WHITE = Color.rgb(235, 235, 235);

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

    private static Bitmap background(Context ctx, int w, int h) {
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

    public static void draw(Canvas c, Context ctx, boolean reserveBottom) {
        SharedPreferences p = ctx.getSharedPreferences("wf", Context.MODE_PRIVATE);
        int w = c.getWidth(), h = c.getHeight();
        float unit = Math.min(w, h) / 448f; // 缩放基准
        boolean hideBg = p.getBoolean("wf_bg_hide", false); // 血糖隐私隐藏

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 背景：自定义图（铺满裁剪可调位置 / 完整留边，用户可选）或纯黑
        Bitmap bg = background(ctx, w, h);
        if (bg != null) {
            boolean cover = "cover".equals(p.getString("wf_bg_mode", "cover"));
            float s = cover
                    ? Math.max(w / (float) bg.getWidth(), h / (float) bg.getHeight())
                    : Math.min(w / (float) bg.getWidth(), h / (float) bg.getHeight());
            paint.setColor(Color.BLACK);
            c.drawRect(0, 0, w, h, paint);
            paint.setFilterBitmap(true);
            float offX = (w - bg.getWidth() * s) / 2f;
            float offY = (h - bg.getHeight() * s) / 2f;
            if (cover) {
                offX += p.getFloat("wf_bg_dx", 0f) * w;
                offY += p.getFloat("wf_bg_dy", 0f) * h;
            }
            c.drawBitmap(bg, offX, offY, paint);
            paint.setFilterBitmap(false);
        } else {
            paint.setColor(Color.BLACK);
            c.drawRect(0, 0, w, h, paint);
        }

        // ---- 时间 ----
        String now = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        paint.setColor(WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(96 * unit);
        paint.setTextAlign(Paint.Align.CENTER);
        float timeY = h * 0.24f;
        c.drawText(now, w / 2f, timeY, paint);

        // ---- 日期 ----
        paint.setColor(GRAY);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(24 * unit);
        String date = new SimpleDateFormat("MM-dd E", Locale.CHINA).format(new Date());
        c.drawText(date, w / 2f, timeY + 40 * unit, paint);

        // ---- 血糖块 ----
        float bgY = h * 0.56f;
        boolean hasBg = p.contains("bg_mgdl");
        if (!hasBg) {
            paint.setColor(GRAY);
            paint.setTextSize(28 * unit);
            c.drawText("未收到血糖", w / 2f, bgY, paint);
        } else {
            drawEye(c, w, bgY, unit, hideBg);
            if (!hideBg) {
                double mgdl = p.getFloat("bg_mgdl", 0);
                boolean mmol = "mmol".equals(p.getString("unit", "mmol"));
                float offsetMmol = p.getFloat("offset", 0f);
                boolean fromAaps = "aaps".equals(p.getString("bg_src", "aaps"));
                // AAPS 源已含偏移；欧态源叠加用户偏移
                double effMgdl = fromAaps ? mgdl : mgdl + offsetMmol * 18.0;
                double shown = mmol ? effMgdl / 18.0 : effMgdl;

                double mmolVal = effMgdl / 18.0;
                int color = (mmolVal < 3.9 || mmolVal > 13.9) ? RED : GREEN;

                String arrow = ARROWS.containsKey(p.getString("bg_arrow", "NONE"))
                        ? ARROWS.get(p.getString("bg_arrow", "NONE")) : "?";

                paint.setColor(color);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(84 * unit);
                String valText = (mmol ? String.format(Locale.US, "%.1f", shown)
                        : String.valueOf((int) Math.round(shown))) + " " + arrow;
                c.drawText(valText, w / 2f, bgY, paint);

                // 时效 + delta
                long ageMin = (System.currentTimeMillis() - p.getLong("bg_time", 0)) / 60000L;
                String line2;
                if (p.getBoolean("bg_has_delta", false)) {
                    double deltaMgdl = p.getFloat("bg_delta", 0) + (fromAaps ? 0 : offsetMmol * 18);
                    String d = mmol ? String.format(Locale.US, "%+.1f", deltaMgdl / 18.0)
                            : String.format(Locale.US, "%+.0f", deltaMgdl);
                    line2 = d + "  " + ageMin + "分钟前";
                } else {
                    line2 = ageMin + "分钟前";
                }
                paint.setColor(GRAY);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(24 * unit);
                c.drawText(line2, w / 2f, bgY + 38 * unit, paint);

                // IOB/COB
                float iob = p.getFloat("iob", -1);
                float cob = p.getFloat("cob", -1);
                if (iob >= 0 || cob >= 0) {
                    StringBuilder sb = new StringBuilder();
                    if (iob >= 0) {
                        sb.append("IOB ").append(String.format(Locale.US, "%.1fU", iob));
                    }
                    if (cob >= 0) {
                        if (sb.length() > 0) {
                            sb.append("  ");
                        }
                        sb.append("COB ").append(String.format(Locale.US, "%.0fg", cob));
                    }
                    paint.setTextSize(24 * unit);
                    c.drawText(sb.toString(), w / 2f, bgY + 68 * unit, paint);
                }
            }
        }

        // ---- 电量 ----
        float footY = reserveBottom ? h - 72 * unit : h - 16 * unit;
        int batt = battery(ctx);
        paint.setColor(GRAY);
        paint.setTextSize(20 * unit);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("电量 " + (batt >= 0 ? batt + "%" : "?"), 16 * unit, footY, paint);

        // ---- 数据源 ----
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(p.getString("bg_src", "aaps"), w - 16 * unit, footY, paint);
    }

    /** 眼睛图标：血糖块右上角，点击隐藏/显示血糖（闭眼态加斜线） */
    private static void drawEye(Canvas c, int w, float bgY, float unit, boolean hidden) {
        float ew = 40 * unit, eh = 28 * unit;
        sEyeRect.set(w - 14 * unit - ew, bgY - 54 * unit,
                w - 14 * unit, bgY - 54 * unit + eh);
        Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
        ep.setColor(hidden ? GRAY : Color.rgb(200, 200, 200));
        ep.setStyle(Paint.Style.STROKE);
        ep.setStrokeWidth(3 * unit);
        c.drawOval(sEyeRect, ep);
        ep.setStyle(Paint.Style.FILL);
        c.drawCircle(sEyeRect.centerX(), sEyeRect.centerY(), 5 * unit, ep);
        if (hidden) {
            ep.setStyle(Paint.Style.STROKE);
            c.drawLine(sEyeRect.left - 2 * unit, sEyeRect.bottom + 2 * unit,
                    sEyeRect.right + 2 * unit, sEyeRect.top - 2 * unit, ep);
        }
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
}
