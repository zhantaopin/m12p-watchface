package cn.kylin.watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 血糖广播接收器（manifest 声明，定向/隐式广播均可收到）。
 * 支持：
 *  1) info.nightscout.androidaps.status  — AAPS Tizen 插件状态广播（glucoseMgdl 已含固定偏移）
 *  2) cn.diyaps.sharing.OT_APP / info.nightscout.androidaps.action.OTTAI_APP — 补丁版欧态
 */
public class BgReceiver extends BroadcastReceiver {

    /** 界面注册的刷新回调，同一进程内静态传递 */
    public static Runnable uiRefresh;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        SharedPreferences p = context.getSharedPreferences("wf", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();

        if ("info.nightscout.androidaps.status".equals(action)) {
            handleAapsStatus(intent, p, ed);
        } else if ("cn.diyaps.sharing.OT_APP".equals(action)
                || "info.nightscout.androidaps.action.OTTAI_APP".equals(action)) {
            handleOttai(intent, p, ed);
        } else {
            return;
        }
        ed.apply();
        MAIN.post(() -> {
            if (uiRefresh != null) {
                uiRefresh.run();
            }
        });
    }

    /** AAPS 状态广播：数值为 mg/dL，已含用户的固定偏移 */
    private void handleAapsStatus(Intent it, SharedPreferences p, SharedPreferences.Editor ed) {
        double mgdl = it.getDoubleExtra("glucoseMgdl", -1);
        long time = it.getLongExtra("glucoseTimeStamp", 0);
        String arrow = it.getStringExtra("slopeArrow");
        double delta = it.getDoubleExtra("deltaMgdl", Double.NaN);
        double iob = it.getDoubleExtra("iob", Double.NaN);
        double cob = it.getDoubleExtra("cob", Double.NaN);
        if (mgdl <= 0) {
            return;
        }
        ed.putFloat("bg_mgdl", (float) mgdl);
        ed.putLong("bg_time", time > 0 ? time : System.currentTimeMillis());
        ed.putString("bg_arrow", arrow == null ? "NONE" : arrow);
        if (!Double.isNaN(delta)) {
            ed.putFloat("bg_delta", (float) delta);
        }
        ed.putBoolean("bg_has_delta", !Double.isNaN(delta));
        ed.putFloat("iob", Double.isNaN(iob) ? -1f : (float) iob);
        ed.putFloat("cob", Double.isNaN(cob) ? -1f : (float) cob);
        ed.putString("bg_src", "aaps");
        ed.putString("sniff", "AAPS status\n"
                + "glucoseMgdl=" + mgdl + "\n"
                + "slopeArrow=" + arrow + "\n"
                + "deltaMgdl=" + (Double.isNaN(delta) ? "?" : delta) + "\n"
                + "iob=" + (Double.isNaN(iob) ? "?" : iob)
                + " cob=" + (Double.isNaN(cob) ? "?" : cob));
    }

    /** 欧态补丁版广播：collection/data，data 为 JSON 数组，sgv 为 mg/dL 原始值（不含偏移） */
    private void handleOttai(Intent it, SharedPreferences p, SharedPreferences.Editor ed) {
        String collection = it.getStringExtra("collection");
        String data = it.getStringExtra("data");
        if (data == null) {
            return;
        }
        String sniff = "Ottai " + it.getAction() + "\ncollection=" + collection + "\ndata="
                + (data.length() > 260 ? data.substring(0, 260) + "…" : data);
        ed.putString("sniff", sniff);
        if (!"entries".equals(collection)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(data);
            JSONObject last = null;
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                if ("sgv".equals(o.optString("type"))) {
                    last = o;
                    break;
                }
            }
            if (last == null) {
                return;
            }
            double mgdl = last.optDouble("sgv", -1);
            long date = last.optLong("date", 0);
            String dir = last.optString("direction", "NONE");
            if (mgdl <= 0) {
                return;
            }
            ed.putFloat("bg_mgdl", (float) mgdl);
            ed.putLong("bg_time", date > 0 ? date : System.currentTimeMillis());
            ed.putString("bg_arrow", dir);
            ed.putString("bg_src", "ottai");
        } catch (Exception ignored) {
            // JSON 解析失败保留旧数据
        }
    }
}
