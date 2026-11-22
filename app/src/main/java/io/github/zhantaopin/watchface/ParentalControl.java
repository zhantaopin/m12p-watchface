package io.github.zhantaopin.watchface;

import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 家长控制：4 位 PIN（加盐 SHA-256 存储）+ 固定 8 位恢复码。
 * 连续输错 5 次锁定 30 秒，避免穷举。
 */
public final class ParentalControl {

    /** 固定恢复码：不写入公开文档，仅家长自己留存（忘记 PIN 时重置用） */
    private static final String RECOVERY_CODE = "53922762";

    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 30_000L;

    private ParentalControl() {
    }

    public static boolean enabled(SharedPreferences p) {
        return p.getBoolean("pc_on", false);
    }

    /** 保护范围：apps=仅应用管理，all=全部设置 */
    public static String scope(SharedPreferences p) {
        return p.getString("pc_scope", "apps");
    }

    public static void setPin(SharedPreferences p, String pin) {
        String salt = randomSalt();
        p.edit().putBoolean("pc_on", true)
                .putString("pc_salt", salt)
                .putString("pc_hash", sha256(salt + ":" + pin))
                .putInt("pc_fail", 0)
                .putLong("pc_lock_until", 0L)
                .apply();
    }

    public static boolean checkPin(SharedPreferences p, String pin) {
        if (lockRemainMs(p) > 0) {
            return false;
        }
        String salt = p.getString("pc_salt", "");
        String hash = p.getString("pc_hash", "");
        if (!hash.isEmpty() && hash.equals(sha256(salt + ":" + pin))) {
            resetFail(p);
            return true;
        }
        fail(p);
        return false;
    }

    public static boolean checkRecovery(SharedPreferences p, String code) {
        if (lockRemainMs(p) > 0) {
            return false;
        }
        if (RECOVERY_CODE.equals(code)) {
            resetFail(p);
            return true;
        }
        fail(p);
        return false;
    }

    public static int remainTries(SharedPreferences p) {
        return Math.max(0, MAX_FAILS - p.getInt("pc_fail", 0));
    }

    public static long lockRemainMs(SharedPreferences p) {
        long left = p.getLong("pc_lock_until", 0L) - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    private static void fail(SharedPreferences p) {
        int f = p.getInt("pc_fail", 0) + 1;
        SharedPreferences.Editor ed = p.edit().putInt("pc_fail", f);
        if (f >= MAX_FAILS) {
            ed.putInt("pc_fail", 0)
                    .putLong("pc_lock_until", System.currentTimeMillis() + LOCK_MS);
        }
        ed.apply();
    }

    private static void resetFail(SharedPreferences p) {
        p.edit().putInt("pc_fail", 0).putLong("pc_lock_until", 0L).apply();
    }

    private static String randomSalt() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
