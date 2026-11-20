package com.yuubinnkyoku.haruvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class DownloadService extends Service {

    public static volatile DownloadService instance;

    public static final String ACTION_LOG   = "com.yuubinnkyoku.haruvoice.DL_LOG";
    public static final String ACTION_STATE = "com.yuubinnkyoku.haruvoice.DL_STATE";
    public static final String EXTRA_LINE   = "line";
    public static final String EXTRA_STATE  = "state";
    public static final String EXTRA_PATH   = "path";

    public static final int STATE_RUNNING = 0;
    public static final int STATE_DONE    = 1;
    public static final int STATE_FAILED  = 2;
    public static final int STATE_ABORTED = 3;

    static final String[] NAMES = { "Haru-4B-Q8_0.gguf", "Haru-4B-Q6_K.gguf" };
    static final String[] URLS = {
        "https://huggingface.co/sakasegawa/Haru-4B-GGUF/resolve/main/Haru-4B-Q8_0.gguf",
        "https://huggingface.co/sakasegawa/Haru-4B-GGUF/resolve/main/Haru-4B-Q6_K.gguf"
    };
    static final String[] SHA256 = {
        "c372a527e63ca4251429a48863535b8bca03ebd6f414bbf31f28a9d175349934",
        "8db05e86acca046bc183d4e30fbda22ac775ba4767ae5a10bcd0d48f8d7db793"
    };
    // 0.2.5: 既存モデルの判定に使う想定サイズ（サーバーに問い合わせずに済ませる）
    static final long[] SIZES = { 6623102720L, 5605707520L };

    private static final int NOTIF_ID = 42;
    private static final int RESULT_ID = 43;
    private static final String CH_PROGRESS = "haru_dl";
    private static final String CH_RESULT   = "haru_dl_result";

    private volatile boolean cancelled = false;
    public volatile boolean running = false;
    private Thread worker = null;
    private PowerManager.WakeLock wakeLock;

    // 0.2.5: 画面が前面にない間（Activity の onPause 中）は受信側が居らず
    // ブロードキャストが捨てられ、ログに穴が空けていた。プロセス内に履歴を残して
    // onResume で復元する。seq は通し番号で、履歴が先頭から捨てられても差分が取れる。
    static final java.util.ArrayList<String> LOG_HISTORY = new java.util.ArrayList<String>();
    static final int LOG_HISTORY_MAX = 4000;
    static long logSeq = 0L;

    static void pushHistory(String line) {
        synchronized (LOG_HISTORY) {
            LOG_HISTORY.add(line);
            logSeq++;
            while (LOG_HISTORY.size() > LOG_HISTORY_MAX) LOG_HISTORY.remove(0);
        }
    }

    /** seq 以降の行を返す。戻り値[0] は「次に要求すべき seq」 */
    static Object[] historySince(long since) {
        synchronized (LOG_HISTORY) {
            long firstSeq = logSeq - LOG_HISTORY.size() + 1; // 履歴先頭の通し番号
            long from = Math.max(since, firstSeq);
            int idx = (int) (from - firstSeq);
            if (idx < 0) idx = 0;
            if (idx > LOG_HISTORY.size()) idx = LOG_HISTORY.size();
            String[] out = LOG_HISTORY.subList(idx, LOG_HISTORY.size()).toArray(new String[0]);
            return new Object[] { out, Long.valueOf(logSeq + 1) };
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_PROGRESS, "モデルDL進捗", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_RESULT, "モデルDL結果", NotificationManager.IMPORTANCE_DEFAULT));
        }
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haruvoice:dl");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int quant = 0;
        if (intent != null) quant = intent.getIntExtra("quant", 0);
        instance = this;
        startForeground(NOTIF_ID, buildProgressNotification("準備中", 0, 0));
        if (!running) {
            final int q = (quant == 1) ? 1 : 0;
            cancelled = false;
            worker = new Thread(new Runnable() { public void run() { runDownload(q); } });
            worker.start();
        }
        return START_NOT_STICKY;
    }

    public void cancel() { cancelled = true; }

    @Override
    public void onDestroy() {
        cancelled = true;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- core ----------

    private void runDownload(int q) {
        running = true;
        sendState(STATE_RUNNING, null);
        try {
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception e) { /* ignore */ }
        try {
            doDownload(q);
        } catch (Exception e) {
            logd("DL失敗: " + e);
            sendState(STATE_FAILED, null);
        } finally {
            running = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
        }
    }

    private void doDownload(int q) throws Exception {
        String name = NAMES[q];
        String url = URLS[q];
        String expect = SHA256[q].toLowerCase();
        File destDir = getExternalFilesDir(null);
        if (destDir == null) destDir = getFilesDir();
        destDir.mkdirs();
        File part = new File(destDir, name + ".part");
        File dest = new File(destDir, name);

        logd("対象: " + name + " (" + (q == 0 ? "6.6GB・推奨" : "5.6GB・メモリ節約") + ")");
        logd("URL: " + url);
        logd("保存先: " + destDir.getAbsolutePath());

        // 0.2.5 修正: 完成済みモデルがあるのに「モデルDL開始」を押すと .part が無いため
        // have=0 になり、6.6GB を最初から取り直していた（完成ファイルは最後まで残るので
        // 一見「100%から0%に戻った」ように見える）。サイズ一致なら DL をスキップする。
        if (dest.exists() && dest.length() == SIZES[q]) {
            logd("既にダウンロード済みです (" + (dest.length() / 1048576L) + "MiB)。DLをスキップします");
            logd("再取得したい場合はファイルを削除してから実行してください: " + dest.getAbsolutePath());
            sendState(STATE_DONE, dest.getAbsolutePath());
            notifyResult(android.R.drawable.stat_sys_download_done, "モデルDL済み", name + " は取得済みです");
            return;
        }

        long have = part.exists() ? part.length() : 0L;
        if (have > 0) logd("既存の .part から再開: " + (have / 1048576L) + "MiB");

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "HaruVoice/" + versionName() + " (android)");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            conn.connect();
            int code = conn.getResponseCode();
            boolean resumed = false;
            if (code == 206) {
                resumed = true;
                logd("サーバーはレジュームを許可 (HTTP 206)");
            } else if (code == 200) {
                if (have > 0) logd("サーバーが初めからの取得を要求 (HTTP 200)。.partを破棄してやり直します");
                have = 0L;
            } else {
                throw new Exception("HTTP " + code + " (" + url + ")");
            }

            // 0.2.2 修正: 6.6GB > Integer.MAX_VALUE のため getContentLength() は -1 になる。
            // getContentLengthLong() を使う。206 の場合は Content-Range から総サイズを取る。
            long total = -1;
            if (code == 206) {
                String cr = conn.getHeaderField("Content-Range"); // "bytes START-END/TOTAL"
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        try { total = Long.parseLong(cr.substring(slash + 1).trim()); } catch (Exception pe) { total = -1; }
                    }
                }
            }
            if (total <= 0) {
                long contentLen = conn.getContentLengthLong();
                if (contentLen > 0) total = have + contentLen;
            }
            if (total > 0) {
                logd("総サイズ: " + (total / 1048576L) + "MiB (" + total + " bytes)");
            } else {
                logd("サーバーが総サイズを返しませんでした。進捗はダウンロード済み容量のみ表示します");
            }

            in = new BufferedInputStream(conn.getInputStream());
            out = new FileOutputStream(part, resumed);
            byte[] buf = new byte[65536];
            long t0 = System.currentTimeMillis();
            long lastLog = 0L, lastNotif = 0L, baseBytes = have;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) {
                    out.flush(); out.close(); out = null; in.close(); in = null;
                    logd("中断しました。.partは残るので「モデルDL開始」で再開できます");
                    sendState(STATE_ABORTED, null);
                    notifyResult(android.R.drawable.stat_notify_error, "DL中断", name + " (.part保持、再開可)");
                    return;
                }
                out.write(buf, 0, n);
                have += n;
                long now = System.currentTimeMillis();
                // 0.2.2 修正: 以前は /1024.0 で KiB/s を MiB/s として表示していた（1024倍過大）。
                // 直近の伸びから算出し、レジューム直後でも実速度が出るようにする。
                double speed = (have - baseBytes) / 1048576.0 / Math.max((now - t0) / 1000.0, 0.001);
                if (now - lastNotif >= 1000L) {
                    lastNotif = now;
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    String text = (total > 0 ? pct(have, total) + "%  " : "") + String.format("%.1f", speed) + "MiB/s";
                    nm.notify(NOTIF_ID, buildProgressNotification(text, have, total));
                }
                if (now - lastLog >= 15000L) {
                    lastLog = now;
                    String line = (total > 0 ? pct(have, total) + "%  " + (have / 1048576L) + " / " + (total / 1048576L) + "MiB"
                                                : "取得済み " + (have / 1048576L) + "MiB")
                        + "  " + String.format("%.1f", speed) + "MiB/s";
                    if (total > 0) {
                        // speed は既に MiB/s。残り秒 = 残りMiB / speed(MiB/s)
                        long remainSec = (long) (((total - have) / 1048576.0) / Math.max(speed, 0.001));
                        line += "  残り約" + (remainSec / 60) + "分" + (remainSec % 60) + "秒";
                    }
                    logd(line);
                }
            }
            out.flush(); out.close(); out = null; in.close(); in = null;
            conn.disconnect(); conn = null;

            logd("DL完了。sha256を検証中…");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildProgressNotification("sha256検証中", 1, 1));
            String actual = sha256(part);
            if (!actual.equals(expect)) {
                part.delete();
                logd("sha256不一致! 期待=" + expect);
                logd("実際=" + actual + " (ファイルは破棄しました)");
                sendState(STATE_FAILED, null);
                notifyResult(android.R.drawable.stat_notify_error, "DL失敗", name + " sha256不一致のため破棄");
                return;
            }
            File dest2 = new File(destDir, name);
            if (dest2.exists()) dest2.delete();
            boolean ok = part.renameTo(dest2);
            if (!ok) {
                FileInputStream fi = new FileInputStream(part);
                FileOutputStream fo = new FileOutputStream(dest2);
                byte[] c = new byte[65536];
                int r;
                while ((r = fi.read(c)) > 0) fo.write(c, 0, r);
                fi.close(); fo.close();
                part.delete();
            }
            logd("sha256 OK。保存完了: " + dest2.getAbsolutePath());
            sendState(STATE_DONE, dest2.getAbsolutePath());
            notifyResult(android.R.drawable.stat_sys_download_done, "モデルDL完了", dest2.getName() + " (sha256検証済み)");
        } finally {
            try { if (out != null) out.close(); } catch (Exception e) { /* */ }
            try { if (in != null) in.close(); } catch (Exception e) { /* */ }
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- helpers ----------

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private int pct(long have, long total) { return (int) Math.min(100, have * 100 / total); }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[1048576];
        int r;
        while ((r = fi.read(b)) > 0) md.update(b, 0, r);
        fi.close();
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private Notification buildProgressNotification(String text, long have, long total) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_PROGRESS);
        else b = new Notification.Builder(this);
        b.setContentTitle("Haru音声対話 モデルDL")
         .setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_download)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (total > 0) b.setProgress(1000, (int) Math.min(1000, have * 1000 / total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void notifyResult(int icon, String title, String text) {
        Notification.Builder b;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_RESULT);
        else b = new Notification.Builder(this);
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentTitle(title).setContentText(text).setSmallIcon(icon)
         .setContentIntent(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
         .setAutoCancel(true);
        nm.notify(RESULT_ID, b.build());
    }

    private void logd(String line) {
        pushHistory(line);
        Intent i = new Intent(ACTION_LOG).setPackage(getPackageName());
        i.putExtra(EXTRA_LINE, line);
        sendBroadcast(i);
    }

    private void sendState(int state, String path) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        if (path != null) i.putExtra(EXTRA_PATH, path);
        sendBroadcast(i);
    }
}
