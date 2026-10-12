package vn.cuong.tftboost.pro;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Chay lenh voi quyen shell (ADB, uid 2000) thong qua Shizuku. */
final class Shell {

    static final class Result {
        int code = -1;
        String out = "";

        boolean ok() {
            return code == 0;
        }
    }

    private Shell() {
    }

    static boolean alive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean granted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAll(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    /** Shizuku.newProcess la ham private tu API 13 nen goi bang reflection. */
    static Result run(String cmd) {
        Result res = new Result();
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null,
                    new Object[]{new String[]{"sh", "-c", cmd + " 2>&1"}, null, null});
            res.out = readAll(p.getInputStream());
            res.code = p.waitFor();
            p.destroy();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            res.out = String.valueOf(c);
        }
        return res;
    }

    /** Doc gia tri 1 dong (vd. settings get ...). Tra ve null neu lenh loi. */
    static String get(String cmd) {
        Result r = run(cmd);
        if (!r.ok()) {
            return null;
        }
        String v = r.out.trim();
        int nl = v.indexOf('\n');
        return nl < 0 ? v : v.substring(0, nl).trim();
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String f = i < 0 ? s : s.substring(0, i);
        return f.length() > 140 ? f.substring(0, 140) + "…" : f;
    }

    /** Dat gia tri vao dau nhay don cho shell (vd. gia tri device_config co dau ':' ','). */
    static String quote(String v) {
        return "'" + v.replace("'", "'\\''") + "'";
    }
}
