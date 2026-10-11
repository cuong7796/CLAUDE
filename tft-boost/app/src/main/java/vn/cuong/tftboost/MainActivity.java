package vn.cuong.tftboost;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * TFT Boost - toi uu 1 cham cho Dau Truong Chan Ly Mobile tren Galaxy S25+.
 *
 * Muc co ban (khong can gi them): don tien trinh nen, bat Khong lam phien, mo game.
 * Muc nang cao (qua Shizuku, quyen ADB shell): buoc dung app nen, dat Game Mode
 * "performance", che do hieu nang co dinh, ep 120Hz, tat GOS, bien dich toi uu game.
 */
public class MainActivity extends Activity {

    private static final String GAME_PREFIX = "com.riotgames.league.teamfighttactics";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String GOS_PKG = "com.samsung.android.game.gos";
    private static final String GAMING_HUB_PKG = "com.samsung.android.game.gamehome";
    private static final int SHIZUKU_REQ = 7796;

    private static final String K_AUTO = "auto_run";
    private static final String K_DND = "dnd";
    private static final String K_FORCE_STOP = "force_stop";
    private static final String K_FIXED_PERF = "fixed_perf";
    private static final String K_HZ = "hz120";
    private static final String K_GOS = "gos_off";
    private static final String K_KEEP = "keep";

    private static final int C_BG = Color.parseColor("#0E1220");
    private static final int C_CARD = Color.parseColor("#1B2340");
    private static final int C_GOLD = Color.parseColor("#F0B232");
    private static final int C_TEXT = Color.parseColor("#E8ECF8");
    private static final int C_DIM = Color.parseColor("#9AA3BF");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statsView;
    private TextView logView;
    private Button boostBtn;
    private volatile boolean busy = false;

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_REQ) {
                        log(grantResult == PackageManager.PERMISSION_GRANTED
                                ? "✓ Đã được cấp quyền Shizuku."
                                : "✗ Anh đã từ chối quyền Shizuku.");
                        refreshStats();
                    }
                }
            };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            ui.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ vong doi

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tftboost", Context.MODE_PRIVATE);
        setContentView(buildUi());
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        if (savedInstanceState == null && prefs.getBoolean(K_AUTO, false)) {
            // Cho Shizuku kip ket noi binder roi moi chay
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    boost();
                }
            }, 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener);
        } catch (Throwable ignored) {
        }
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ giao dien

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(C_TEXT);
        b.setTextSize(14);
        b.setBackground(round(C_CARD, 12));
        b.setOnClickListener(l);
        return b;
    }

    private Switch toggle(String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_TEXT);
        s.setTextSize(14);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
            }
        });
        return s;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root);

        root.addView(text("TFT Boost", 26, C_GOLD, true));
        root.addView(text("Tối ưu 1 chạm · Đấu Trường Chân Lý · Galaxy S25+", 13, C_DIM, false));

        statsView = text("…", 14, C_TEXT, false);
        statsView.setBackground(round(C_CARD, 14));
        statsView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statsView.setLineSpacing(dp(3), 1f);
        root.addView(statsView, rowParams(16));

        boostBtn = new Button(this);
        boostBtn.setText("⚡ TỐI ƯU & VÀO GAME");
        boostBtn.setAllCaps(false);
        boostBtn.setTextSize(19);
        boostBtn.setTypeface(Typeface.DEFAULT_BOLD);
        boostBtn.setTextColor(C_BG);
        boostBtn.setBackground(round(C_GOLD, 18));
        boostBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boost();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        bp.topMargin = dp(16);
        root.addView(boostBtn, bp);

        root.addView(text("Tùy chọn khi tối ưu", 15, C_GOLD, true), rowParams(22));
        root.addView(toggle("Tự tối ưu ngay khi mở app (đúng 1 chạm)", K_AUTO, false));
        root.addView(toggle("Bật Không làm phiền khi vào game", K_DND, true));
        root.addView(toggle("Buộc dừng hẳn app nền (Shizuku)", K_FORCE_STOP, true));
        root.addView(toggle("Chế độ hiệu năng cố định (Shizuku)", K_FIXED_PERF, true));
        root.addView(toggle("Ép màn hình tối thiểu 120Hz (Shizuku, tốn pin hơn)", K_HZ, false));
        root.addView(toggle("Tắt GOS – bỏ giới hạn hiệu năng Samsung (Shizuku, máy nóng hơn)", K_GOS, false));

        root.addView(text("Thiết lập & công cụ", 15, C_GOLD, true), rowParams(18));
        root.addView(smallButton("Cấp quyền Shizuku", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestShizuku();
            }
        }), rowParams(8));
        root.addView(smallButton("Cấp quyền Không làm phiền", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            }
        }), rowParams(8));
        root.addView(smallButton("Chọn app KHÔNG bị dừng (Zalo, Messenger…)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickKeepApps();
            }
        }), rowParams(8));
        root.addView(smallButton("Biên dịch tối ưu game (làm 1 lần sau mỗi bản cập nhật)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                compileGame();
            }
        }), rowParams(8));
        root.addView(smallButton("Mở Gaming Hub / Game Booster", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPackage(GAMING_HUB_PKG, "Không tìm thấy Gaming Hub trên máy.");
            }
        }), rowParams(8));
        root.addView(smallButton("↺ Khôi phục cài đặt sau khi chơi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restore();
            }
        }), rowParams(8));

        root.addView(text("Nhật ký", 15, C_GOLD, true), rowParams(18));
        logView = text("Sẵn sàng.", 12, C_DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(C_CARD, 14));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setTextIsSelectable(true);
        root.addView(logView, rowParams(8));

        return scroll;
    }

    private void log(final String line) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                CharSequence old = logView.getText();
                String all = old + "\n" + line;
                // Giu nhat ky gon: toi da ~6000 ky tu cuoi
                if (all.length() > 6000) {
                    all = all.substring(all.length() - 6000);
                }
                logView.setText(all);
            }
        });
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setBusy(final boolean b) {
        busy = b;
        ui.post(new Runnable() {
            @Override
            public void run() {
                boostBtn.setEnabled(!b);
                boostBtn.setAlpha(b ? 0.5f : 1f);
            }
        });
    }

    // ------------------------------------------------------------------ thong so

    private long availMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private long totalMb() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / (1024 * 1024);
    }

    private void refreshStats() {
        if (statsView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        long avail = availMb();
        long total = totalMb();
        sb.append(String.format(Locale.US, "RAM trống: %.1f / %.1f GB", avail / 1024f, total / 1024f));

        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            sb.append(String.format(Locale.US, "\nPin: %d%%  ·  Nhiệt độ pin: %.1f°C", level, t / 10f));
        }

        String game = findGame();
        sb.append("\nGame: ").append(game == null ? "CHƯA CÀI Đấu Trường Chân Lý" : game);

        String sh;
        if (!shizukuAlive()) {
            sh = "chưa chạy (chỉ dùng được mức cơ bản)";
        } else if (!shizukuGranted()) {
            sh = "đang chạy, chưa cấp quyền";
        } else {
            sh = "sẵn sàng ✓";
        }
        sb.append("\nShizuku: ").append(sh);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        sb.append("\nQuyền Không làm phiền: ")
                .append(nm.isNotificationPolicyAccessGranted() ? "đã cấp ✓" : "chưa cấp");
        statsView.setText(sb.toString());
    }

    // ------------------------------------------------------------------ tien ich

    private String findGame() {
        try {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                if (ai.packageName.startsWith(GAME_PREFIX)) {
                    return ai.packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Set<String> keepSet(String game) {
        Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        keep.add(getPackageName());
        keep.add(SHIZUKU_PKG);
        if (game != null) {
            keep.add(game);
        }
        return keep;
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Throwable t) {
            toast("Không mở được màn hình cài đặt này.");
        }
    }

    private boolean launchPackage(String pkg, String failMsg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {
        }
        toast(failMsg);
        return false;
    }

    // ------------------------------------------------------------------ Shizuku

    private boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestShizuku() {
        if (!shizukuAlive()) {
            if (!launchPackage(SHIZUKU_PKG,
                    "Chưa cài hoặc chưa khởi động Shizuku. Cài Shizuku từ CH Play rồi bấm Start trong app đó.")) {
                log("✗ Shizuku chưa chạy. Cài Shizuku, bật Gỡ lỗi không dây, ghép nối rồi bấm Start.");
            }
            return;
        }
        if (shizukuGranted()) {
            toast("Shizuku đã sẵn sàng.");
            return;
        }
        try {
            Shizuku.requestPermission(SHIZUKU_REQ);
        } catch (Throwable t) {
            log("✗ Không xin được quyền Shizuku: " + t);
        }
    }

    /** Ket qua mot lenh shell chay qua Shizuku. */
    private static final class Result {
        int code = -1;
        String out = "";
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

    /**
     * Chay lenh voi quyen shell (uid 2000) thong qua Shizuku.
     * Shizuku.newProcess la ham private tu API 13 nen goi bang reflection.
     */
    private Result sh(String cmd) {
        Result res = new Result();
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            // "2>&1" de gop loi vao dau ra, tranh treo vi day bo dem stderr
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

    /** Chay 1 lenh va ghi ket qua vao nhat ky. */
    private boolean step(String label, String cmd) {
        Result r = sh(cmd);
        boolean ok = r.code == 0;
        String detail = r.out.isEmpty() ? "" : " — " + firstLine(r.out);
        log((ok ? "✓ " : "✗ ") + label + (ok && r.out.length() > 80 ? "" : detail));
        return ok;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String f = i < 0 ? s : s.substring(0, i);
        return f.length() > 140 ? f.substring(0, 140) + "…" : f;
    }

    // ------------------------------------------------------------------ TOI UU

    private void boost() {
        if (busy) {
            return;
        }
        setBusy(true);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doBoost();
                } catch (Throwable t) {
                    log("✗ Lỗi: " + t);
                } finally {
                    setBusy(false);
                }
            }
        });
    }

    private void doBoost() {
        final String game = findGame();
        log("──────── Bắt đầu tối ưu ────────");
        long before = availMb();
        Set<String> keep = keepSet(game);

        // 1) Khong lam phien
        if (prefs.getBoolean(K_DND, true)) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                log("✓ Đã bật Không làm phiền");
            } else {
                log("• Bỏ qua Không làm phiền (chưa cấp quyền)");
            }
        }

        // 2) Don tien trinh nen bang API thuong (khong can Shizuku)
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int killed = 0;
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if (!keep.contains(ai.packageName)) {
                    am.killBackgroundProcesses(ai.packageName);
                    killed++;
                }
            }
        } catch (Throwable ignored) {
        }
        log("✓ Đã gửi lệnh dọn nền cho " + killed + " gói");

        // 3) Cac lenh cap ADB qua Shizuku
        if (shizukuGranted()) {
            if (prefs.getBoolean(K_FORCE_STOP, true)) {
                Result list = sh("pm list packages -3");
                StringBuilder script = new StringBuilder();
                int n = 0;
                if (list.code == 0) {
                    for (String line : list.out.split("\n")) {
                        String pkg = line.replace("package:", "").trim();
                        if (pkg.isEmpty() || keep.contains(pkg) || !pkg.matches("[A-Za-z0-9._]+")) {
                            continue;
                        }
                        script.append("am force-stop ").append(pkg).append("; ");
                        n++;
                    }
                }
                if (n > 0) {
                    script.append("true");
                    step("Buộc dừng " + n + " app bên thứ ba", script.toString());
                } else {
                    log("• Không có app nào cần buộc dừng");
                }
            }
            step("Dọn toàn bộ tiến trình nền", "am kill-all");
            if (game != null) {
                step("Game Mode = performance cho game", "cmd game mode performance " + game);
            }
            if (prefs.getBoolean(K_FIXED_PERF, true)) {
                step("Chế độ hiệu năng cố định", "cmd power set-fixed-performance-mode-enabled true");
            }
            if (prefs.getBoolean(K_HZ, false)) {
                step("Ép tối thiểu 120Hz", "settings put system min_refresh_rate 120.0");
            }
            if (prefs.getBoolean(K_GOS, false)) {
                step("Tắt GOS", "pm disable-user --user 0 " + GOS_PKG);
            }
        } else {
            log("• Shizuku chưa sẵn sàng → chỉ chạy mức cơ bản");
        }

        // 4) Ket qua
        try {
            Thread.sleep(700);
        } catch (InterruptedException ignored) {
        }
        long after = availMb();
        log(String.format(Locale.US, "RAM trống: %d MB → %d MB (%+d MB)", before, after, after - before));

        // 5) Vao game
        if (game == null) {
            log("✗ Chưa cài Đấu Trường Chân Lý nên không mở game được.");
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        log("▶ Mở game…");
        ui.post(new Runnable() {
            @Override
            public void run() {
                launchPackage(game, "Không mở được game.");
            }
        });
    }

    // ------------------------------------------------------------------ KHOI PHUC

    private void restore() {
        if (busy) {
            return;
        }
        setBusy(true);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    log("──────── Khôi phục ────────");
                    NotificationManager nm =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm.isNotificationPolicyAccessGranted()) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                        log("✓ Đã tắt Không làm phiền");
                    }
                    if (shizukuGranted()) {
                        String game = findGame();
                        if (game != null) {
                            step("Game Mode = standard", "cmd game mode standard " + game);
                        }
                        step("Tắt hiệu năng cố định", "cmd power set-fixed-performance-mode-enabled false");
                        step("Bỏ ép 120Hz", "settings delete system min_refresh_rate");
                        step("Bật lại GOS", "pm enable " + GOS_PKG);
                    } else {
                        log("• Shizuku chưa sẵn sàng → chỉ khôi phục Không làm phiền");
                    }
                } catch (Throwable t) {
                    log("✗ Lỗi: " + t);
                } finally {
                    setBusy(false);
                }
            }
        });
    }

    // ------------------------------------------------------------------ BIEN DICH GAME

    private void compileGame() {
        final String game = findGame();
        if (game == null) {
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        if (!shizukuGranted()) {
            toast("Tính năng này cần Shizuku.");
            return;
        }
        if (busy) {
            return;
        }
        setBusy(true);
        log("Đang biên dịch tối ưu game, có thể mất 1–3 phút, anh đừng thoát app…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    step("Biên dịch tối ưu (speed) " + game, "cmd package compile -m speed -f " + game);
                } finally {
                    setBusy(false);
                }
            }
        });
    }

    // ------------------------------------------------------------------ DANH SACH GIU LAI

    private void pickKeepApps() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!system && !ai.packageName.equals(getPackageName())
                        && !ai.packageName.startsWith(GAME_PREFIX)
                        && !ai.packageName.equals(SHIZUKU_PKG)) {
                    apps.add(ai);
                }
            }
        } catch (Throwable ignored) {
        }
        final String[] labels = new String[apps.size()];
        final List<String> names = new ArrayList<String>();
        for (ApplicationInfo ai : apps) {
            names.add(String.valueOf(pm.getApplicationLabel(ai)));
        }
        // Sap xep theo ten hien thi
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < apps.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return names.get(a).compareToIgnoreCase(names.get(b));
            }
        });
        final String[] pkgs = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        final Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        for (int i = 0; i < order.size(); i++) {
            int src = order.get(i);
            labels[i] = names.get(src);
            pkgs[i] = apps.get(src).packageName;
            checked[i] = keep.contains(pkgs[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("App được giữ chạy nền")
                .setMultiChoiceItems(labels, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Lưu", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Set<String> out = new HashSet<String>();
                        for (int i = 0; i < pkgs.length; i++) {
                            if (checked[i]) {
                                out.add(pkgs[i]);
                            }
                        }
                        prefs.edit().putStringSet(K_KEEP, out).apply();
                        log("✓ Đã lưu " + out.size() + " app được giữ lại");
                    }
                })
                .setNegativeButton("Hủy", null)
                .show();
    }
}
