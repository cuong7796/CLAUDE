package vn.cuong.tftboost;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TFT Boost 2.0 - toi uu 1 cham cho Dau Truong Chan Ly Mobile, KHONG can Shizuku.
 *
 * Chuoi toi uu: bat Khong lam phien -> don tien trinh nen -> dich vu Tro nang tu bam
 * "Buoc dung" cho cac app vua dung -> "Dong tat ca" app gan day -> mo game.
 */
public class MainActivity extends Activity {

    private static final String GAME_PREFIX = "com.riotgames.league.teamfighttactics";
    private static final String GAMING_HUB_PKG = "com.samsung.android.game.gamehome";
    private static final String DEVICE_CARE_PKG = "com.samsung.android.lool";
    private static final long RECENT_WINDOW_MS = 12L * 60 * 60 * 1000; // app dung trong 12 gio qua
    private static final int MAX_STOP = 30;

    /** App he thong nang, buoc dung an toan (trinh duyet, video, mang xa hoi cai san...). */
    private static final Set<String> HEAVY_SYSTEM = new HashSet<String>(Arrays.asList(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.sec.android.app.sbrowser",
            "com.google.android.apps.maps",
            "com.google.android.apps.photos",
            "com.google.android.gm",
            "com.google.android.apps.docs",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.instagram.android",
            "com.microsoft.office.officehubrow",
            "com.netflix.mediaclient",
            "com.samsung.android.app.tips",
            "com.sec.android.app.shealth",
            "com.samsung.android.voc",
            "com.sec.android.app.samsungapps",
            "com.android.vending"));

    /** Khong bao gio buoc dung (bao thuc, goi dien, tin nhan, bo phan he thong). */
    private static final Set<String> NEVER = new HashSet<String>(Arrays.asList(
            "com.android.settings",
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.sec.android.app.clockpackage",
            "com.samsung.android.dialer",
            "com.samsung.android.messaging",
            "com.samsung.android.incallui",
            "com.google.android.gms",
            "com.samsung.android.game.gamehome",
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gos",
            "com.samsung.android.honeyboard",
            "com.samsung.android.lool"));

    private static final String K_AUTO = "auto_run";
    private static final String K_DND = "dnd";
    private static final String K_FORCE_STOP = "a11y_force_stop";
    private static final String K_CLOSE_RECENTS = "a11y_close_recents";
    private static final String K_KEEP = "keep";

    private static final int C_BG = Color.parseColor("#0E1220");
    private static final int C_CARD = Color.parseColor("#1B2340");
    private static final int C_GOLD = Color.parseColor("#F0B232");
    private static final int C_TEXT = Color.parseColor("#E8ECF8");
    private static final int C_DIM = Color.parseColor("#9AA3BF");

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statsView;
    private TextView logView;
    private Button boostBtn;

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
        if (savedInstanceState == null && prefs.getBoolean(K_AUTO, false)) {
            ui.postDelayed(this::boost, 500);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        logView.setText(BoostLog.text());
        BoostLog.setListener(all -> logView.setText(all));
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        BoostLog.setListener(null);
        ui.removeCallbacks(ticker);
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
        s.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(key, isChecked).apply());
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

        root.addView(text("TFT Boost 2.0", 26, C_GOLD, true));
        root.addView(text("Tối ưu 1 chạm · Không cần Shizuku · Galaxy S25+", 13, C_DIM, false));

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
        boostBtn.setOnClickListener(v -> boost());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76));
        bp.topMargin = dp(16);
        root.addView(boostBtn, bp);

        root.addView(text("Tùy chọn khi tối ưu", 15, C_GOLD, true), rowParams(22));
        root.addView(toggle("Tự tối ưu ngay khi mở app (đúng 1 chạm)", K_AUTO, false));
        root.addView(toggle("Bật Không làm phiền khi vào game", K_DND, true));
        root.addView(toggle("Buộc dừng các app vừa dùng (Trợ năng)", K_FORCE_STOP, true));
        root.addView(toggle("Đóng tất cả app gần đây (Trợ năng)", K_CLOSE_RECENTS, true));

        root.addView(text("Thiết lập lần đầu", 15, C_GOLD, true), rowParams(18));
        root.addView(text("Làm lần lượt 1 → 4. Dòng trạng thái phía trên hiện ✓ là xong.", 12, C_DIM, false));
        root.addView(smallButton("1. Cho phép cài đặt bị hạn chế (⋮ góc phải)", v -> openAppInfo()), rowParams(8));
        root.addView(smallButton("2. Bật Trợ năng → Ứng dụng đã cài → TFT Boost", v ->
                openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)), rowParams(8));
        root.addView(smallButton("3. Cấp quyền Truy cập dữ liệu sử dụng", v ->
                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)), rowParams(8));
        root.addView(smallButton("4. Cấp quyền Không làm phiền", v ->
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), rowParams(8));

        root.addView(text("Công cụ", 15, C_GOLD, true), rowParams(18));
        root.addView(smallButton("Chọn app KHÔNG bị dừng (Zalo, Messenger…)", v -> pickKeepApps()), rowParams(8));
        root.addView(smallButton("Xem trước danh sách sẽ bị buộc dừng", v -> previewTargets()), rowParams(8));
        root.addView(smallButton("Mở Gaming Hub / Game Booster", v ->
                launchPackage(GAMING_HUB_PKG, "Không tìm thấy Gaming Hub trên máy.")), rowParams(8));
        root.addView(smallButton("Mở Chăm sóc thiết bị (Device care)", v ->
                launchPackage(DEVICE_CARE_PKG, "Không mở được Chăm sóc thiết bị.")), rowParams(8));
        root.addView(smallButton("↺ Tắt Không làm phiền sau khi chơi", v -> restore()), rowParams(8));

        root.addView(text("Nhật ký", 15, C_GOLD, true), rowParams(18));
        logView = text(BoostLog.text(), 12, C_DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(round(C_CARD, 14));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setTextIsSelectable(true);
        root.addView(logView, rowParams(8));

        return scroll;
    }

    private void log(String line) {
        BoostLog.add(line);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ trang thai

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

    private boolean a11yOn() {
        return BoostService.instance != null;
    }

    private boolean usageGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean dndGranted() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.isNotificationPolicyAccessGranted();
    }

    private void refreshStats() {
        if (statsView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "RAM trống: %.1f / %.1f GB", availMb() / 1024f, totalMb() / 1024f));
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            sb.append(String.format(Locale.US, "\nPin: %d%%  ·  Nhiệt độ pin: %.1f°C", level, t / 10f));
        }
        String game = findGame();
        sb.append("\nGame: ").append(game == null ? "CHƯA CÀI Đấu Trường Chân Lý" : game);
        sb.append("\nTrợ năng TFT Boost: ").append(a11yOn() ? "đang bật ✓" : "CHƯA BẬT");
        sb.append("\nDữ liệu sử dụng: ").append(usageGranted() ? "đã cấp ✓" : "chưa cấp");
        sb.append("\nKhông làm phiền: ").append(dndGranted() ? "đã cấp ✓" : "chưa cấp");
        statsView.setText(sb.toString());
    }

    // ------------------------------------------------------------------ tien ich

    private String findGame() {
        try {
            for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                if (ai.packageName.startsWith(GAME_PREFIX)) {
                    return ai.packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Throwable t) {
            toast("Không mở được màn hình cài đặt này.");
        }
    }

    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("Bấm ⋮ góc trên bên phải → Cho phép cài đặt bị hạn chế");
        } catch (Throwable t) {
            toast("Không mở được Thông tin ứng dụng.");
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

    /** Cac goi tuyet doi khong dung: app nay, game, danh sach giu lai, launcher, ban phim, SMS, dien thoai. */
    private Set<String> protectedSet(String game) {
        Set<String> keep = new HashSet<String>(prefs.getStringSet(K_KEEP, new HashSet<String>()));
        keep.addAll(NEVER);
        keep.add(getPackageName());
        if (game != null) {
            keep.add(game);
        }
        PackageManager pm = getPackageManager();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) {
                keep.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        try {
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) {
                keep.add(ime.substring(0, ime.indexOf('/')));
            }
        } catch (Throwable ignored) {
        }
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(this);
            if (sms != null) {
                keep.add(sms);
            }
        } catch (Throwable ignored) {
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.getDefaultDialerPackage() != null) {
                keep.add(tm.getDefaultDialerPackage());
            }
        } catch (Throwable ignored) {
        }
        return keep;
    }

    /** App can buoc dung: co mo tu man hinh chinh, da dung trong 12 gio qua, khong thuoc danh sach bao ve. */
    private List<String> collectTargets(String game) {
        final List<String> out = new ArrayList<String>();
        if (!usageGranted()) {
            return out;
        }
        Set<String> keep = protectedSet(game);
        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - RECENT_WINDOW_MS, now);
        final List<UsageStats> recent = new ArrayList<UsageStats>();
        for (UsageStats us : stats.values()) {
            if (us.getLastTimeUsed() >= now - RECENT_WINDOW_MS) {
                recent.add(us);
            }
        }
        Collections.sort(recent, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        for (UsageStats us : recent) {
            String pkg = us.getPackageName();
            if (keep.contains(pkg) || out.contains(pkg)) {
                continue;
            }
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !HEAVY_SYSTEM.contains(pkg)) {
                    continue;
                }
                if (pm.getLaunchIntentForPackage(pkg) == null) {
                    continue;
                }
                out.add(pkg);
            } catch (Throwable ignored) {
            }
            if (out.size() >= MAX_STOP) {
                break;
            }
        }
        return out;
    }

    private List<String> labelsOf(List<String> pkgs) {
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<String>();
        for (String p : pkgs) {
            try {
                names.add(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))));
            } catch (Throwable t) {
                names.add(p);
            }
        }
        return names;
    }

    private void previewTargets() {
        if (!usageGranted()) {
            toast("Cần cấp quyền Truy cập dữ liệu sử dụng trước (bước 3).");
            return;
        }
        List<String> names = labelsOf(collectTargets(findGame()));
        String msg = names.isEmpty()
                ? "Không có app nào cần buộc dừng lúc này."
                : "Sẽ buộc dừng " + names.size() + " app:\n\n• " + android.text.TextUtils.join("\n• ", names);
        new AlertDialog.Builder(this)
                .setTitle("Danh sách buộc dừng")
                .setMessage(msg)
                .setPositiveButton("Đóng", null)
                .show();
    }

    // ------------------------------------------------------------------ TOI UU

    private void boost() {
        BoostService svc = BoostService.instance;
        if (svc != null && svc.isRunning()) {
            return;
        }
        final String game = findGame();
        log("──────── Bắt đầu tối ưu ────────");
        long before = availMb();

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

        // 2) Don tien trinh nen bang API thuong
        Set<String> keep = protectedSet(game);
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
        log("✓ Đã dọn tiến trình nền của " + killed + " gói");
        long after = availMb();
        log(String.format(Locale.US, "RAM trống: %d MB → %d MB (%+d MB)", before, after, after - before));

        // 3) Buoc dung + dong app gan day qua dich vu Tro nang
        boolean wantStop = prefs.getBoolean(K_FORCE_STOP, true);
        boolean wantClose = prefs.getBoolean(K_CLOSE_RECENTS, true);
        if (svc != null && (wantStop || wantClose)) {
            List<String> targets = new ArrayList<String>();
            if (wantStop) {
                if (usageGranted()) {
                    targets = collectTargets(game);
                    log("• Sẽ buộc dừng " + targets.size() + " app vừa dùng");
                } else {
                    log("• Bỏ qua buộc dừng (chưa cấp quyền Dữ liệu sử dụng)");
                }
            }
            if (game == null) {
                log("✗ Chưa cài Đấu Trường Chân Lý – tối ưu xong sẽ không mở game.");
            }
            svc.start(targets, labelsOf(targets), game, wantClose);
            return;
        }

        if (svc == null) {
            log("• Trợ năng chưa bật → chỉ chạy mức cơ bản");
        }
        if (game == null) {
            log("✗ Chưa cài Đấu Trường Chân Lý nên không mở game được.");
            toast("Chưa tìm thấy Đấu Trường Chân Lý trên máy.");
            return;
        }
        log("▶ Mở game…");
        launchPackage(game, "Không mở được game.");
    }

    private void restore() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            log("✓ Đã tắt Không làm phiền");
        } else {
            log("• Chưa cấp quyền Không làm phiền nên không có gì để khôi phục");
        }
    }

    // ------------------------------------------------------------------ DANH SACH GIU LAI

    private void pickKeepApps() {
        final PackageManager pm = getPackageManager();
        final List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean eligible = !system || HEAVY_SYSTEM.contains(ai.packageName);
                if (eligible && !ai.packageName.equals(getPackageName())
                        && !ai.packageName.startsWith(GAME_PREFIX)
                        && pm.getLaunchIntentForPackage(ai.packageName) != null) {
                    apps.add(ai);
                }
            }
        } catch (Throwable ignored) {
        }
        final List<String> names = new ArrayList<String>();
        for (ApplicationInfo ai : apps) {
            names.add(String.valueOf(pm.getApplicationLabel(ai)));
        }
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < apps.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));

        final String[] labels = new String[apps.size()];
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
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Lưu", (dialog, which) -> {
                    Set<String> out = new HashSet<String>();
                    for (int i = 0; i < pkgs.length; i++) {
                        if (checked[i]) {
                            out.add(pkgs[i]);
                        }
                    }
                    prefs.edit().putStringSet(K_KEEP, out).apply();
                    log("✓ Đã lưu " + out.size() + " app được giữ lại");
                })
                .setNegativeButton("Hủy", null)
                .show();
    }
}
