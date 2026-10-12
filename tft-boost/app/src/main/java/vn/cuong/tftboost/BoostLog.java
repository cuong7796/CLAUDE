package vn.cuong.tftboost;

import android.os.Handler;
import android.os.Looper;

/** Nhat ky dung chung giua man hinh chinh va dich vu Tro nang. */
final class BoostLog {

    interface Listener {
        void onChanged(String all);
    }

    private static final StringBuilder BUF = new StringBuilder("Sẵn sàng.");
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Listener listener;

    private BoostLog() {
    }

    static synchronized void add(String line) {
        BUF.append('\n').append(line);
        if (BUF.length() > 6000) {
            BUF.delete(0, BUF.length() - 6000);
        }
        final Listener l = listener;
        final String all = BUF.toString();
        if (l != null) {
            UI.post(() -> l.onChanged(all));
        }
    }

    static synchronized String text() {
        return BUF.toString();
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }
}
