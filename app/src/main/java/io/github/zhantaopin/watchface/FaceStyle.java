package io.github.zhantaopin.watchface;

import android.content.SharedPreferences;

/**
 * 表盘样式：3 种布局 × 4 套配色，共 12 套内置。
 * 选中项的 id 存入 prefs "wf_face_style"，重启后保留。
 */
public final class FaceStyle {

    public static final int LAYOUT_A = 0; // 纵向三段：时间在上、血糖居中、电量在下
    public static final int LAYOUT_B = 1; // 双栏卡片：时间/电量置顶，分割线分区
    public static final int LAYOUT_C = 2; // 圆环聚焦：时间收进圆环

    public final String id;
    public final String name;
    public final int layout;
    public final int bg;   // 无背景图时的底色
    public final int fg;   // 时间/主文字
    public final int sub;  // 日期
    public final int meta; // 时效/IOB/电量等次要信息
    public final int good; // 血糖正常
    public final int bad;  // 血糖超范围

    private FaceStyle(String id, String name, int layout,
                      int bg, int fg, int sub, int meta, int good, int bad) {
        this.id = id;
        this.name = name;
        this.layout = layout;
        this.bg = bg;
        this.fg = fg;
        this.sub = sub;
        this.meta = meta;
        this.good = good;
        this.bad = bad;
    }

    /** 每套配色：底色 / 主文字 / 日期 / 次要 / 正常 / 超范围 */
    private static final int[][] PALETTES = {
            {0xFF000000, 0xFFEBEBEB, 0xFFCCCCCC, 0xFFA0A0A0, 0xFF00C853, 0xFFFF5252},
            {0xFF1C1C1E, 0xFFF2F2F2, 0xFFC7C7CC, 0xFF9A9A9E, 0xFF4CAF50, 0xFFFF6B6B},
            {0xFFF2F2F2, 0xFF1C1C1E, 0xFF3A3A3C, 0xFF6B6B70, 0xFF2E7D32, 0xFFC62828},
            {0xFF000000, 0xFF9BE15D, 0xFF7FB84A, 0xFF5C8A3A, 0xFF9BE15D, 0xFFFF8A65},
    };
    private static final String[] PALETTE_NAMES = {"深空黑", "石墨灰", "奶白", "夜光绿"};
    private static final String[] LAYOUT_NAMES = {"纵向", "双栏", "圆环"};
    private static final String[] LAYOUT_IDS = {"a", "b", "c"};

    public static final FaceStyle[] ALL = build();

    private static FaceStyle[] build() {
        FaceStyle[] out = new FaceStyle[LAYOUT_NAMES.length * PALETTE_NAMES.length];
        int i = 0;
        for (int l = 0; l < LAYOUT_NAMES.length; l++) {
            for (int c = 0; c < PALETTE_NAMES.length; c++) {
                int[] pal = PALETTES[c];
                out[i++] = new FaceStyle(LAYOUT_IDS[l] + "_" + c,
                        LAYOUT_NAMES[l] + " · " + PALETTE_NAMES[c],
                        l, pal[0], pal[1], pal[2], pal[3], pal[4], pal[5]);
            }
        }
        return out;
    }

    public static FaceStyle byId(String id) {
        if (id != null) {
            for (FaceStyle s : ALL) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
        }
        return ALL[0];
    }

    public static FaceStyle current(SharedPreferences p) {
        return byId(p.getString("wf_face_style", ALL[0].id));
    }
}
