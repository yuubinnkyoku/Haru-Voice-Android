package com.yuubinnkyoku.haruvoice;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String[] MODEL_NAMES = { "Haru-4B-Q8_0.gguf", "Haru-4B-Q6_K.gguf" };

    File nativeLibDir;
    File workDir; // getExternalFilesDir
    File speechBin, timingBin;

    EditText modelEdit, questionEdit, refEdit, nameEdit, personaEdit;
    Button btnQuant, btnDl, btnTalk, btnPlay, btnTime, btnStop, btnConv;
    TextView statusView, logView;
    ScrollView logScroll;
    StringBuilder logBuf = new StringBuilder();

    int quant = 0;           // 0=Q8_0, 1=Q6_K
    boolean meteredWarned = false;
    boolean busy = false;     // talk/timing 実行中
    Process proc = null;
    MediaPlayer player = null;
    File replyFile;
    long logSeqShown = 1L;    // DLログ履歴のどこまで表示したか

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DownloadService.ACTION_LOG.equals(action)) {
                // 0.2.5: 行を直接追記せず履歴経由で取り込む。ブロードキャストと
                // onResume の復元が二重に走っても重複しない。
                restoreLogHistory();
            } else if (DownloadService.ACTION_STATE.equals(action)) {
                int st = intent.getIntExtra(DownloadService.EXTRA_STATE, -1);
                String path = intent.getStringExtra(DownloadService.EXTRA_PATH);
                if (st == DownloadService.STATE_RUNNING) {
                    btnDl.setText("DLを中断");
                    status("DL実行中。アプリを閉じても通知とともに継続します");
                } else {
                    btnDl.setText("モデルDL開始");
                    if (st == DownloadService.STATE_DONE) {
                        if (path != null) modelEdit.setText(path);
                        status("DL完了 (sha256検証済み)");
                    } else if (st == DownloadService.STATE_ABORTED) {
                        status("DL中断 (.part保持)");
                    } else {
                        status("DL失敗。ログを確認してください");
                    }
                }
            }
        }
    };

    // ================= lifecycle =================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ApplicationInfo ai = getApplicationInfo();
        nativeLibDir = new File(ai.nativeLibraryDir);
        speechBin = new File(nativeLibDir, "libspeech-cli.so");
        timingBin = new File(nativeLibDir, "libharu-timing.so");
        workDir = getExternalFilesDir(null);
        if (workDir == null) workDir = getFilesDir();
        workDir.mkdirs();
        replyFile = new File(workDir, "reply.wav");

        buildUi();

        appendLog("HaruVoice " + appVersion() + " (speech.cpp 0.11.0 / Haru-4B)");
        appendLog("native: " + nativeLibDir.getAbsolutePath());
        appendLog("work:   " + workDir.getAbsolutePath());
        appendLog("speech: " + (speechBin.exists() ? "OK" : "見つからない!") + "  timing: " + (timingBin.exists() ? "OK" : "見つからない!"));
        File m = findExistingModel();
        if (m != null) {
            modelEdit.setText(m.getAbsolutePath());
            appendLog("モデルを検出: " + m.getAbsolutePath());
        }
        File q = resolveInput("question.wav");
        if (q != null) questionEdit.setText(q.getAbsolutePath());
        status("準備完了");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(DownloadService.ACTION_LOG);
        f.addAction(DownloadService.ACTION_STATE);
        registerReceiver(receiver, f);
        // 0.2.2 修正: 画面が前面にない間に開始した場合 STATE_RUNNING を取りこぼし、
        // 「準備完了」のまま残っていた。実際の稼働状態から復元する。
        if (DownloadService.instance != null && DownloadService.instance.running) {
            btnDl.setText("DLを中断");
            status("DL実行中。アプリを閉じても通知とともに継続します");
        } else {
            btnDl.setText("モデルDL開始");
        }
        // 0.2.5 修正: onPause 中は受信側が居らずブロードキャストが捨てられるため、
        // 戻ってきたときにログの穴を埋める。
        restoreLogHistory();
    }

    /** プロセス内に残した DL ログ履歴を、まだ表示していない分だけ追記する */
    void restoreLogHistory() {
        Object[] r = DownloadService.historySince(logSeqShown);
        String[] lines = (String[]) r[0];
        logSeqShown = ((Long) r[1]).longValue();
        for (String s : lines) appendLog(s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception e) { /* */ }
    }

    // ================= UI =================

    /** バージョン表記をハードコードせず PackageManager から取る（更新時にズレない） */
    String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x10, 0x10, 0x12));
        int pad = (int) (getResources().getDisplayMetrics().density * 10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Haru音声対話 " + appVersion());
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(0x9E, 0xD8, 0xFF));
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(statusView);

        btnQuant = mkButton("DL対象: Q8_0 (6.6GB・推奨)");
        btnQuant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                quant = 1 - quant;
                btnQuant.setText(quant == 0 ? "DL対象: Q8_0 (6.6GB・推奨)" : "DL対象: Q6_K (5.6GB・メモリ節約)");
            }
        });
        root.addView(btnQuant);

        btnDl = mkButton("モデルDL開始");
        btnDl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDlClicked(); }
        });
        root.addView(btnDl);

        modelEdit = mkEdit("モデル (.gguf)");
        root.addView(modelEdit);

        questionEdit = mkEdit("質問音声 (wav、16k/24kモノラル)");
        root.addView(questionEdit);

        refEdit = mkEdit("参照ボイス (任意のwav、例: myvoice.wav)");
        root.addView(refEdit);

        nameEdit = mkEdit("名前 (例: ハル)");
        nameEdit.setText("ハル");
        root.addView(nameEdit);

        personaEdit = mkEdit("persona (例: 落ち着いた話し方をする大学生。)");
        personaEdit.setText("落ち着いた話し方をする大学生。");
        personaEdit.setMinLines(2);
        root.addView(personaEdit);

        btnTalk = mkButton("返答を作る (speech talk)");
        btnTalk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTalk(); }
        });
        root.addView(btnTalk);

        btnPlay = mkButton("返答を再生");
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playReply(); }
        });
        root.addView(btnPlay);

        btnTime = mkButton("速度を実測 (haru-timing 3 runs)");
        btnTime.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startTiming(); }
        });
        root.addView(btnTime);

        btnStop = mkButton("停止");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopAll(); }
        });
        root.addView(btnStop);

        // 0.3.0: 会話モード (worker プロトコルによるターン制対話)
        btnConv = mkButton("会話モードを開く (0.3.1)");
        btnConv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConvClicked(); }
        });
        root.addView(btnConv);

        TextView logTitle = new TextView(this);
        logTitle.setText("ログ");
        logTitle.setTextColor(Color.LTGRAY);
        logTitle.setPadding(0, pad, 0, pad / 4);
        root.addView(logTitle);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(0xCC, 0xE0, 0xCC));
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(pad / 3, pad / 3, pad / 3, pad / 3);
        logScroll.setBackgroundColor(Color.rgb(0x18, 0x1C, 0x18));
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(logScroll, lp);

        setContentView(root);
    }

    Button mkButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    EditText mkEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setTextSize(13);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setBackgroundColor(Color.rgb(0x1E, 0x22, 0x28));
        e.setPadding(12, 10, 12, 10);
        return e;
    }

    // ================= ログ (スマートスクロール) =================

    void appendLog(final String line) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                logBuf.append(line).append('\n');
                // 上限: 40万文字で頭を削る
                if (logBuf.length() > 500000) logBuf.delete(0, logBuf.length() - 400000);

                ScrollView sv = logScroll;
                TextView tv = logView;
                int oldContent = tv.getHeight();
                int visH = sv.getHeight();
                int maxScroll = Math.max(0, oldContent - visH);
                // ユーザーが最下部付近にいるときだけ自動追従。上を読んでいる時は現在位置を維持
                boolean atBottom = sv.getScrollY() >= maxScroll - 16;
                final int oldY = sv.getScrollY();
                final int keptOldContent = oldContent;
                final boolean stick = atBottom;

                tv.setText(logBuf);

                sv.post(new Runnable() {
                    @Override public void run() {
                        int newContent = tv.getHeight();
                        if (stick) {
                            sv.scrollTo(0, Math.max(0, newContent - sv.getHeight()));
                        } else if (newContent != keptOldContent) {
                            double r = keptOldContent > 0 ? (double) oldY / keptOldContent : 0;
                            sv.scrollTo(0, (int) (r * newContent));
                        }
                    }
                });
            }
        });
    }

    void status(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { statusView.setText(s); }
        });
    }

    // ================= DL =================

    void onDlClicked() {
        if (DownloadService.instance != null && DownloadService.instance.running) {
            DownloadService.instance.cancel();
            status("DL中断要求…");
            return;
        }
        boolean metered = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            metered = cm.isActiveNetworkMetered();
        } catch (Exception e) { /* */ }
        if (metered && !meteredWarned) {
            meteredWarned = true;
            appendLog("⚠ モバイルデータ回線のようです。モデルは " + (quant == 0 ? "6.6GB" : "5.6GB") + " です。");
            appendLog("  それでも良ければもう一度「モデルDL開始」を押してください。");
            status("モバイルデータ警告。もう一度タップで開始");
            return;
        }
        Intent i = new Intent(this, DownloadService.class);
        i.putExtra("quant", quant);
        startForegroundService(i);
        appendLog("DLサービスを開始しました (フォアグラウンド通知付き)");
    }

    // ================= 実行 (talk / timing) =================

    void setBusy(final boolean b) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                busy = b;
                btnTalk.setEnabled(!b);
                btnTime.setEnabled(!b);
                btnPlay.setEnabled(!b && replyFile.exists());
            }
        });
    }

    // ================= 会話モード (0.3.0) =================

    void onConvClicked() {
        File model = new File(modelEdit.getText().toString().trim());
        if (!model.exists() || model.length() < 100000000L) {
            appendLog("エラー: モデルファイルが見つかりません: " + model.getAbsolutePath());
            return;
        }
        String name = nameEdit.getText().toString().trim();
        if (name.isEmpty()) { appendLog("エラー: 名前を入れてください"); return; }
        String ref = refEdit.getText().toString().trim();
        Intent i = new Intent(this, ConversationActivity.class);
        i.putExtra("model", model.getAbsolutePath());
        i.putExtra("name", name);
        i.putExtra("persona", personaEdit.getText().toString().trim());
        File rf = ref.isEmpty() ? null : resolveInput(ref);
        if (rf != null) i.putExtra("voice_ref", rf.getAbsolutePath());
        else if (!ref.isEmpty()) appendLog("参照ボイスが見つからないためデフォルトボイスで開きます: " + ref);
        startActivity(i);
    }

    File resolveInput(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        File f = new File(s.trim());
        if (f.isAbsolute()) return f.exists() ? f : null;
        File[] dirs = { workDir, new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HaruVoice"),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) };
        for (File d : dirs) {
            if (d == null) continue;
            File c = new File(d, s.trim());
            if (c.exists()) return c;
        }
        return null;
    }

    File findExistingModel() {
        File[] dirs = { workDir,
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HaruVoice"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) };
        for (String n : MODEL_NAMES) {
            for (File d : dirs) {
                if (d == null) continue;
                File c = new File(d, n);
                if (c.exists() && c.length() > 1000000000L) return c;
            }
        }
        return null;
    }

    int runCli(List<String> argv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        pb.directory(workDir);
        pb.environment().put("LD_LIBRARY_PATH", nativeLibDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("SPEECH_NO_UPDATE_CHECK", "1");
        proc = pb.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        String l;
        while ((l = br.readLine()) != null) appendLog(l);
        int rc = proc.waitFor();
        proc = null;
        return rc;
    }

    void startTalk() {
        if (busy) return;
        // ここはUIスレッドから呼ばれるので直読みでOK
        final File model = new File(modelEdit.getText().toString().trim());
        final File question = resolveInput(questionEdit.getText().toString());
        final String name = nameEdit.getText().toString().trim();
        final String persona = personaEdit.getText().toString().trim();
        final String ref = refEdit.getText().toString().trim();
        if (!model.exists() || model.length() < 100000000L) {
            appendLog("エラー: モデルファイルが見つかりません: " + model.getAbsolutePath());
            return;
        }
        if (question == null) {
            appendLog("エラー: 質問音声が見つかりません: " + questionEdit.getText().toString());
            return;
        }
        if (name.isEmpty()) { appendLog("エラー: 名前を入れてください"); return; }
        setBusy(true);
        status("talk 実行中… (600秒上限)");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> argv = new ArrayList<String>();
                    argv.add(speechBin.getAbsolutePath());
                    argv.add("talk");
                    argv.add(model.getAbsolutePath());
                    argv.add("--language"); argv.add("ja");
                    argv.add("--name"); argv.add(name);
                    if (!persona.isEmpty()) { argv.add("--persona"); argv.add(persona); }
                    argv.add("--max-seconds"); argv.add("600");
                    if (!ref.isEmpty()) {
                        File rf = resolveInput(ref);
                        if (rf != null) { argv.add("--add-voice"); argv.add("me=" + rf.getAbsolutePath()); argv.add("--voice"); argv.add("me"); }
                        else appendLog("参照ボイスが見つかりません。無しで続けます: " + ref);
                    }
                    argv.add("-o"); argv.add(replyFile.getAbsolutePath());
                    argv.add(question.getAbsolutePath());
                    appendLog("$ speech talk (モデル: " + model.getName() + ")");
                    int rc = runCli(argv);
                    if (rc == 0 && replyFile.exists()) {
                        appendLog("完了: " + replyFile.getAbsolutePath() + " (" + (replyFile.length() / 1024) + "KiB)");
                        status("返答生成完了。「返答を再生」で聞けます");
                    } else {
                        appendLog("talk 終了コード=" + rc);
                        status("talk 失敗 (rc=" + rc + ")");
                    }
                } catch (Exception e) {
                    appendLog("talk 例外: " + e);
                    status("talk 失敗");
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    void startTiming() {
        if (busy) return;
        final File model = new File(modelEdit.getText().toString().trim());
        final File question = resolveInput(questionEdit.getText().toString());
        if (!model.exists() || model.length() < 100000000L) {
            appendLog("エラー: モデルファイルが見つかりません: " + model.getAbsolutePath());
            return;
        }
        if (question == null) {
            appendLog("エラー: 質問音声が見つかりません");
            return;
        }
        setBusy(true);
        status("haru-timing 実測中… (3 runs / 各600秒)");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> argv = new ArrayList<String>();
                    argv.add(timingBin.getAbsolutePath());
                    argv.add(model.getAbsolutePath());
                    argv.add("cpu"); argv.add("none");
                    argv.add(question.getAbsolutePath());
                    argv.add("3"); argv.add("600");
                    appendLog("$ haru-timing " + model.getName() + " cpu none 3 600");
                    int rc = runCli(argv);
                    appendLog("haru-timing 終了コード=" + rc);
                    status(rc == 0 ? "実測終了。出力を報告に貼ってください" : "実測失敗 (rc=" + rc + ")");
                } catch (Exception e) {
                    appendLog("timing 例外: " + e);
                    status("実測失敗");
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    void playReply() {
        if (!replyFile.exists()) { appendLog("reply.wav がありません"); return; }
        try {
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(replyFile.getAbsolutePath());
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.prepare();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { mp.release(); player = null; }
            });
            player.start();
            status("再生中…");
        } catch (Exception e) {
            appendLog("再生エラー: " + e);
        }
    }

    void stopAll() {
        try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception e) { /* */ }
        if (DownloadService.instance != null && DownloadService.instance.running) {
            DownloadService.instance.cancel();
            appendLog("DL中断要求…");
        }
        if (proc != null) {
            proc.destroy();
            appendLog("実行中プロセスを停止しました");
        }
        status("停止しました");
        Toast.makeText(this, "停止", Toast.LENGTH_SHORT).show();
    }
}
