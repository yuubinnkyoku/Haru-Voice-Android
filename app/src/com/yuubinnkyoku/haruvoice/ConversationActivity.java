package com.yuubinnkyoku.haruvoice;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * 会話モード (0.3.2)。
 *
 * 0.3.0 からの変更:
 *  - ready 後に捨て会話 (id=w0) で沈黙 3 秒を計算させてキャッシュを暖機し、
 *    初回ターンの冷え込み (~64s) を load 時に吸収する。終わったら hang_up して本番会話を開始
 *  - 応答待ち中に「押して話す」で割り込み (バージイン): 現会話を打ち切って
 *    新しい会話で即録音開始。古い会話の出力 chunk は id で破棄 (文脈はリセットされる)
 *  - 応答待ち中の進捗表示「N/M フレーム・残り約 Xs」
 *  - 沈黙秒の既定値を 8→6 に変更 (短いほど待ちが短い。長い応答は切れやすくなる)
 *  - 再生開始後に届いた取り残し chunk を破棄 (次ターンへの混入防止)
 */
public class ConversationActivity extends Activity implements WorkerClient.Listener {

    static final int REC_RATE = 16000;              // 録音レート (worker 側がリサンプルする)
    static final int REC_CHUNK_BYTES = 3200;       // 1 送信 = 0.1 秒 (1600 サンプル)
    static final int SILENCE_PIECE_SAMPLES = 8000;  // 沈黙は 0.5 秒ずつ送る
    static final int STALL_MS = 3000;               // 出力停滞でターン確定
    static final long TURN_TIMEOUT_MS = 180000;     // 1 ターンの計算待ち上限
    static final long LOAD_TIMEOUT_MS = 300000;     // モデル読み込み待ち上限
    static final int WARM_SECONDS = 3;              // 暖機に流す沈黙秒
    static final long WARM_TIMEOUT_MS = 150000;     // 暖機の上限

    File workDir;
    String nativeLibDir, modelPath, myName, persona, voiceRefPath;

    TextView statusView, counterView, logView;
    ScrollView logScroll;
    Button btnTalk, btnReset, btnEnd;
    EditText silenceEdit;
    StringBuilder logBuf = new StringBuilder();
    StringBuilder transcript = new StringBuilder();

    WorkerClient wk = null;
    String convId = "c1";
    String activeId = null;      // 今受け付けている会話 id (暖機・割り込みの破棄フィルタ)
    int convSeq = 0, convNum = 1, turnNo = 0, discardedOld = 0;
    volatile boolean warming = false;
    volatile long warmOut = 0;    // 暖機会話で受けた出力サンプル数

    int modelRate = 24000;      // ready の model.sample_rate で上書きされる
    volatile boolean gotReady = false;

    enum St { LOADING, READY, RECORDING, THINKING, PLAYING, ENDED }
    volatile St st = St.LOADING;

    AudioRecord rec = null;
    AudioTrack liveOut = null;   // 会話開始直後のグリーティング用 (READY 時に直接流す)
    Thread recThread, watchThread, loadThread;
    volatile boolean recOn = false;

    // 会話の累積
    long convInSamples = 0;      // 16kHz 側の累積入力サンプル
    long convOutSamples = 0;     // モデルレート側の累積出力サンプル
    long convOutStart = 0;       // 今ターン開始時点の convOutSamples
    long expectedConvOut = 0;    // 今ターン終了時点の会話累積出力の想定
    // 今ターン分
    final ArrayList<byte[]> turnPcm = new ArrayList<byte[]>();
    final StringBuilder turnText = new StringBuilder();
    volatile long lastChunkAt = 0, turnDeadline = 0;
    long pressAt = 0, releaseAt = 0, silenceUsedSec = 6;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        modelPath = getIntent().getStringExtra("model");
        myName = getIntent().getStringExtra("name");
        persona = getIntent().getStringExtra("persona");
        voiceRefPath = getIntent().getStringExtra("voice_ref");
        nativeLibDir = getApplicationInfo().nativeLibraryDir;
        workDir = getExternalFilesDir(null);
        if (workDir == null) workDir = getFilesDir();
        buildUi();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appendLog("会話モード (0.3.2) 開始");
        appendLog("モデル: " + modelPath);
        startWorker();
    }

    // ================= UI =================

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x10, 0x10, 0x12));
        int pad = (int) (getResources().getDisplayMetrics().density * 10);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Haru と会話");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(0x9E, 0xD8, 0xFF));
        statusView.setPadding(0, pad / 2, 0, pad / 4);
        root.addView(statusView);

        counterView = new TextView(this);
        counterView.setTextColor(Color.rgb(0x88, 0x99, 0xAA));
        counterView.setTextSize(11);
        root.addView(counterView);

        btnTalk = new Button(this);
        btnTalk.setText("押して話す");
        btnTalk.setAllCaps(false);
        btnTalk.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { onTalkDown(); return true; }
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { onTalkUp(); return true; }
                return false;
            }
        });
        root.addView(btnTalk);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView sl = new TextView(this);
        sl.setText("応答待ちの沈黙秒 (短いほど待ちが短い): ");
        sl.setTextColor(Color.LTGRAY);
        row.addView(sl);
        silenceEdit = new EditText(this);
        silenceEdit.setText("6");
        silenceEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        silenceEdit.setTextColor(Color.WHITE);
        silenceEdit.setBackgroundColor(Color.rgb(0x1E, 0x22, 0x28));
        silenceEdit.setPadding(12, 8, 12, 8);
        row.addView(silenceEdit);
        root.addView(row);

        btnReset = new Button(this); btnReset.setText("会話を最初から"); btnReset.setAllCaps(false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetConversation(); }
        });
        root.addView(btnReset);

        btnEnd = new Button(this); btnEnd.setText("会話を終了"); btnEnd.setAllCaps(false);
        btnEnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { endConversation(); }
        });
        root.addView(btnEnd);

        TextView lt = new TextView(this);
        lt.setText("ログ");
        lt.setTextColor(Color.LTGRAY);
        lt.setPadding(0, pad, 0, pad / 4);
        root.addView(lt);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(0xCC, 0xE0, 0xCC));
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.setBackgroundColor(Color.rgb(0x18, 0x1C, 0x18));
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(logScroll, lp);

        setContentView(root);
    }

    void setStatus(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { statusView.setText(s); } });
    }

    void setCounters(final String s) {
        runOnUiThread(new Runnable() { @Override public void run() { counterView.setText(s); } });
    }

    void appendLog(final String line) {
        final String tag = (turnNo > 0 ? "[turn " + turnNo + "] " : "");
        android.util.Log.i("HaruVoiceConv", tag + line);
        // logcat is empty on this device, so the log also goes to a file that adb can read back.
        try {
            File lf = new File(workDir, "conv-log.txt");
            java.io.FileWriter w = new java.io.FileWriter(lf, true);
            w.write(tag + line + "\n");
            w.close();
        } catch (Exception e) { /* a log that cannot be written must not stop the conversation */ }
        runOnUiThread(new Runnable() { @Override public void run() {
            logBuf.append(tag).append(line).append('\n');
            if (logBuf.length() > 500000) logBuf.delete(0, logBuf.length() - 400000);
            logView.setText(logBuf);
            logScroll.post(new Runnable() { @Override public void run() {
                logScroll.scrollTo(0, Math.max(0, logView.getHeight() - logScroll.getHeight()));
            }});
            if (st != St.THINKING) {
                counterView.setText("入力 " + (convInSamples / REC_RATE) + "s / 出力 "
                        + (convOutSamples / modelRate) + "s / 状態 " + st);
            }
        }});
    }

    void appendTranscript(final String line) {
        runOnUiThread(new Runnable() { @Override public void run() {
            transcript.append(line).append('\n');
            appendLog("会話欄: " + line);
        }});
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ================= worker =================

    void startWorker() {
        final String bin = new File(nativeLibDir, "libspeech-cli.so").getAbsolutePath();
        loadThread = new Thread(new Runnable() { @Override public void run() {
            try {
                ArrayList<String> argv = new ArrayList<String>();
                argv.add(bin);
                argv.add("worker");
                argv.add(modelPath);
                argv.add("--language"); argv.add("ja");
                if (myName != null && !myName.isEmpty()) { argv.add("--name"); argv.add(myName); }
                if (persona != null && !persona.isEmpty()) { argv.add("--persona"); argv.add(persona); }
                if (voiceRefPath != null) {
                    argv.add("--add-voice"); argv.add("me=" + voiceRefPath);
                    argv.add("--voice"); argv.add("me");
                }
                wk = new WorkerClient(ConversationActivity.this);
                wk.start(argv, workDir, nativeLibDir);
                appendLog("$ speech worker (language=ja" + (voiceRefPath != null ? ", voice=me" : "") + ")");
            } catch (Exception e) {
                appendLog("worker 起動失敗: " + e);
                st = St.ENDED;
            }
        }}, "conv-load");
        loadThread.setDaemon(true);
        loadThread.start();
        setStatus("モデル読み込み中… (初回は数十秒〜数分)");
        // 読み込み監視 (警告のみ。待ち自体は続ける)
        Thread g = new Thread(new Runnable() { @Override public void run() {
            SystemClock.sleep(LOAD_TIMEOUT_MS);
            if (!gotReady && st == St.LOADING) appendLog("警告: ready が " + (LOAD_TIMEOUT_MS / 1000 / 60) + "分来ません。ログを確認してください");
        }}, "conv-loadguard");
        g.setDaemon(true);
        g.start();
    }

    // ---- WorkerClient.Listener (stdout リーダースレッドから呼ばれる) ----

    @Override public void onReady(int rate, int frame, String raw) {
        gotReady = true;
        if (rate > 0) modelRate = rate;
        appendLog("[ready] " + raw);
        appendLog("モデルレート " + modelRate + "Hz を使用します");
        appendLog("暖機を開始します (捨て会話で沈黙 " + WARM_SECONDS + "秒を計算し、初回ターンの冷え込みを防ぎます)");
        setStatus("準備中（キャッシュ暖機中… 数十秒）");
        startWarmup();
    }

    /** 暖機: 捨て会話 w0 で沈黙 WARM_SECONDS 秒を計算させてから、本番会話 c1 を開始する */
    void startWarmup() {
        activeId = "w0";
        warming = true;
        warmOut = 0;
        final long t0 = SystemClock.elapsedRealtime();
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try {
                wk.dialogue("w0", REC_RATE);
                int wSeq = 0;
                byte[] zeros = new byte[SILENCE_PIECE_SAMPLES * 2];
                for (int i = 0; i < WARM_SECONDS * 2; i++) wk.chunk("w0", wSeq++, zeros);
                long expected = (long) WARM_SECONDS * modelRate + 3200;   // 入力相当 + 最初の 1 フレーム
                long deadline = SystemClock.elapsedRealtime() + WARM_TIMEOUT_MS;
                while (warming && warmOut < expected - 3200
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(200);
                }
                try { wk.hangUp("w0"); } catch (Exception e) { appendLog("暖機 hang_up 失敗: " + e); }
            } catch (Exception e) {
                appendLog("暖機の失敗: " + e);
            }
            if (!warming) return;   // 画面が閉じられた
            convNum = 1;
            convId = "c" + convNum;
            convSeq = 0;
            convInSamples = 0;
            convOutSamples = 0;
            convOutStart = 0;
            expectedConvOut = 0;
            turnNo = 0;
            discardedOld = 0;
            synchronized (turnPcm) { turnPcm.clear(); }
            synchronized (turnText) { turnText.setLength(0); }
            activeId = convId;
            warming = false;
            try {
                wk.dialogue(convId, REC_RATE);
            } catch (Exception e) {
                appendLog("dialogue 送信失敗: " + e);
                st = St.ENDED;
                setStatus("会話の開始に失敗しました");
                return;
            }
            appendLog("暖機完了 (" + (SystemClock.elapsedRealtime() - t0) / 1000
                    + "s)。会話 " + convId + " を開始 (入力 " + REC_RATE + "Hz)");
            st = St.READY;
            setStatus("準備完了。「押して話す」を押しながら話し、離すと応答待ちになります");
        }}, "conv-warm");
        t.setDaemon(true);
        t.start();
    }

    @Override public void onChunk(String id, int seq, byte[] pcm, String text) {
        if (activeId == null || !id.equals(activeId)) {
            // 暖機・割り込みで打ち切った古い会話の取り残し
            discardedOld++;
            if (discardedOld % 20 == 1) {
                appendLog("古い会話 " + id + " の出力を破棄 (計 " + discardedOld + " 個)");
            }
            return;
        }
        if (warming) {
            // 暖機会話: 音声も文字も捨てる
            if (pcm != null) warmOut += pcm.length / 2;
            return;
        }
        if (pcm != null && pcm.length > 0) {
            convOutSamples += pcm.length / 2;
            if (st == St.READY) playDirect(pcm);              // グリーティング等はそのまま流す
            else if (st == St.PLAYING) { /* 再生開始後の取り残しは捨てる */ }
            else synchronized (turnPcm) { turnPcm.add(pcm); }
        }
        if (text != null && !text.isEmpty()) synchronized (turnText) { turnText.append(text); }
        lastChunkAt = SystemClock.elapsedRealtime();
        if (st == St.THINKING && expectedConvOut > 0) {
            int m = (int) ((expectedConvOut - convOutStart + 3199) / 3200);
            int n = (int) ((convOutSamples - convOutStart) / 3200);
            long elapsed = SystemClock.elapsedRealtime() - releaseAt;
            String rem = "";
            if (n > 0 && elapsed > 1000 && m > n) {
                long remMs = (long) ((m - n) * (double) elapsed / n);
                rem = " 残り約 " + (remMs / 1000) + "s";
            }
            setCounters("Haru が考えています… 応答 " + n + "/" + m + " フレーム" + rem);
            if (convOutSamples + 3200 >= expectedConvOut) finishTurn("想定フレーム数に到達");
        }
    }

    @Override public void onEnd(String id, int frames, int samples) {
        appendLog("[end] " + id + " frames=" + frames + " samples=" + samples);
    }

    @Override public void onError(String id, String code, String message) {
        appendLog("[error] " + id + " code=" + code + " " + message);
        if ("out_of_range".equals(code)) {
            st = St.ENDED;
            setStatus("この会話は長さの上限に達しました。「会話を最初から」で続けられます");
        } else {
            setStatus("エラー: " + code);
        }
    }

    @Override public void onFatal(String message) {
        appendLog("[fatal] " + message);
        st = St.ENDED;
        setStatus("fatal が発生しました。ログを確認してください");
    }

    @Override public void onLog(String line) { appendLog(line); }

    @Override public void onClosed() {
        warming = false;
        if (st != St.ENDED) {
            st = St.ENDED;
            setStatus("worker が終了しました");
            appendLog("worker が終了しました");
        }
    }

    // ================= 録音とターン進行 =================

    boolean micGranted() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    void onTalkDown() {
        if (warming) { toast("準備中（暖機中）です"); return; }
        if (st == St.PLAYING) { toast("再生中です"); return; }
        if (st == St.THINKING) bargeIn();   // 割り込み: そのまま録音開始へ落ちる
        if (st != St.READY) { toast("まだ準備中です"); return; }
        if (!micGranted()) {
            requestPermissions(new String[] { android.Manifest.permission.RECORD_AUDIO }, 1);
            toast("マイクの許可を出してからもう一度押してください");
            return;
        }
        startRecording();
    }

    /** 応答待ちへの割り込み: 現会話を捨てて新しい会話を開始する (文脈はリセットされる) */
    void bargeIn() {
        appendLog("割り込み: cancel で応答計算を止め、新しい会話にします (文脈はリセット)");
        try { wk.cancel(convId); } catch (Exception e) { appendLog("cancel 失敗: " + e); }
        convNum++;
        convId = "c" + convNum;
        convSeq = 0;
        convInSamples = 0;
        convOutSamples = 0;
        convOutStart = 0;
        expectedConvOut = 0;
        turnNo = 0;
        discardedOld = 0;
        synchronized (turnPcm) { turnPcm.clear(); }
        synchronized (turnText) { turnText.setLength(0); }
        activeId = convId;
        try {
            wk.dialogue(convId, REC_RATE);
        } catch (Exception e) {
            appendLog("dialogue 送信失敗: " + e);
            st = St.ENDED;
            setStatus("割り込み後の会話開始に失敗しました");
            return;
        }
        st = St.READY;   // このあと startRecording が RECORDING へ
        appendLog("会話 " + convId + " を開始 (割り込み)");
    }

    void startRecording() {
        try {
            int min = AudioRecord.getMinBufferSize(REC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, REC_CHUNK_BYTES * 8);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    REC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                appendLog("AudioRecord の初期化に失敗しました");
                rec = null;
                return;
            }
            rec.startRecording();
        } catch (Exception e) { appendLog("録音開始失敗: " + e); return; }
        recOn = true;
        st = St.RECORDING;
        pressAt = SystemClock.elapsedRealtime();
        turnNo++;
        convOutStart = convOutSamples;
        setStatus("話しています… (離すと応答待ち)");
        recThread = new Thread(new Runnable() { @Override public void run() {
            byte[] buf = new byte[REC_CHUNK_BYTES];
            int refused = 0;
            while (recOn) {
                int n;
                try { n = rec.read(buf, 0, buf.length); }
                catch (Exception e) { appendLog("録音読み取り例外: " + e); break; }
                if (n <= 0) { refused++; if (refused > 50) break; continue; }
                refused = 0;
                byte[] piece = new byte[n];
                System.arraycopy(buf, 0, piece, 0, n);
                convInSamples += n / 2;
                try { wk.chunk(convId, convSeq++, piece); }
                catch (Exception e) { appendLog("chunk 送信失敗: " + e); break; }
            }
        }}, "conv-rec");
        recThread.setDaemon(true);
        recThread.start();
    }

    void onTalkUp() {
        if (st != St.RECORDING) return;
        releaseAt = SystemClock.elapsedRealtime();
        recOn = false;
        try { recThread.join(500); } catch (InterruptedException ie) { /* 無視 */ }
        try { rec.stop(); } catch (Exception e) { /* 無視 */ }
        try { rec.release(); } catch (Exception e) { /* 無視 */ }
        rec = null;
        if (wk == null || wk.isClosed()) { st = St.READY; setStatus("worker がいないため中断しました"); return; }

        // 沈黙を一括送信 (worker に時間ガードはないので即送りで良い)
        silenceUsedSec = 6;
        try {
            silenceUsedSec = Math.max(2, Math.min(12,
                    Integer.parseInt(silenceEdit.getText().toString().trim())));
        } catch (Exception e) { /* 規定値 6 のまま */ }
        try {
            byte[] zeros = new byte[SILENCE_PIECE_SAMPLES * 2];
            for (int i = 0; i < silenceUsedSec * 2; i++) {
                convInSamples += SILENCE_PIECE_SAMPLES;
                wk.chunk(convId, convSeq++, zeros);
            }
        } catch (Exception e) {
            appendLog("沈黙 chunk 送信失敗: " + e);
        }
        // 想定累積出力 = 入力相当 + 最初の 1 フレーム (誤差は +3200 の余裕で吸収)
        expectedConvOut = Math.round(convInSamples * (float) modelRate / REC_RATE) + 3200;
        st = St.THINKING;
        lastChunkAt = SystemClock.elapsedRealtime();
        turnDeadline = lastChunkAt + TURN_TIMEOUT_MS;
        float askSec = (releaseAt - pressAt) / 1000f;
        float inSec = askSec + silenceUsedSec;
        appendLog("質問 " + askSec + "s + 沈黙 " + silenceUsedSec + "s を送信 (想定応答 " + inSec
                + "s分、温まっていれば待ち " + (int) (inSec * 1.5) + "s 前後)");
        appendTranscript("あなた: (" + askSec + "秒の音声)");
        setStatus("応答を計算中…");
        setCounters("Haru が考えています… 応答 0/" + (int) ((expectedConvOut - convOutStart + 3199) / 3200) + " フレーム");
        startWatcher();
    }

    void startWatcher() {
        watchThread = new Thread(new Runnable() { @Override public void run() {
            while (st == St.THINKING) {
                SystemClock.sleep(500);
                long now = SystemClock.elapsedRealtime();
                if (wk == null || wk.isClosed()) { appendLog("計算中に worker が終了しました"); st = St.ENDED; setStatus("worker が終了しました"); return; }
                if (now - lastChunkAt > STALL_MS) { finishTurn("停滞 3 秒で確定"); return; }
                if (now > turnDeadline) { finishTurn("タイムアウト"); return; }
            }
        }}, "conv-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    synchronized void finishTurn(String reason) {
        if (st != St.THINKING) return;
        st = St.PLAYING;
        final long outSamples = convOutSamples - convOutStart;
        final long computeMs = SystemClock.elapsedRealtime() - releaseAt;
        final float askSec = (releaseAt - pressAt) / 1000f;
        final float outSec = outSamples / (float) modelRate;
        String rtfx = computeMs > 0 && outSec > 0
                ? String.format("実効 rtfx=%.2f (出力/計算)", outSec / (computeMs / 1000f)) : "";
        appendLog("応答確定 (" + reason + "): 計算待ち " + computeMs / 1000f + "s、出力 " + outSec + "s、" + rtfx);
        Thread t = new Thread(new Runnable() { @Override public void run() {
            byte[] all;
            synchronized (turnPcm) {
                int total = 0;
                for (byte[] p : turnPcm) total += p.length;
                all = new byte[total];
                int off = 0;
                for (byte[] p : turnPcm) { System.arraycopy(p, 0, all, off, p.length); off += p.length; }
                turnPcm.clear();
            }
            playPcm(all);
            final String txt;
            synchronized (turnText) {
                txt = turnText.toString().trim();
                turnText.setLength(0);
            }
            final float outSecF = outSec;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (!txt.isEmpty()) appendTranscript("Haru: " + txt);
                else appendTranscript("Haru: (音声 " + outSecF + "秒)");
                st = St.READY;
                setStatus("あなたの番。「押して話す」で続けてください");
            }});
            appendLog("再生終了 (" + outSecF + "s)");
        }}, "conv-play");
        t.setDaemon(true);
        t.start();
    }

    // ---- 再生 ----

    /** turn のバッファを一括再生 (終わるまで戻らない) */
    void playPcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        AudioTrack tr = newTrack();
        if (tr == null) return;
        tr.play();
        int off = 0;
        while (off < pcm.length && !isFinishing()) {
            int w = tr.write(pcm, off, Math.min(8192, pcm.length - off));
            if (w <= 0) break;
            off += w;
        }
        long totalSamples = pcm.length / 2;
        long guard = SystemClock.elapsedRealtime() + 300000;
        while (!isFinishing() && tr.getPlaybackHeadPosition() < totalSamples
                && SystemClock.elapsedRealtime() < guard) {
            SystemClock.sleep(100);
        }
        try { tr.stop(); } catch (Exception e) { /* 無視 */ }
        tr.release();
    }

    /** READY 中に届いた小さな出力 (グリーティング) はそのまま流す */
    synchronized void playDirect(byte[] pcm) {
        if (liveOut == null) liveOut = newTrack();
        if (liveOut == null) return;
        if (liveOut.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) liveOut.play();
        liveOut.write(pcm, 0, pcm.length);
    }

    AudioTrack newTrack() {
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(modelRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Exception e) {
            appendLog("AudioTrack 生成失敗: " + e);
            return null;
        }
    }

    // ================= 会話の制御 =================

    void resetConversation() {
        if (wk == null || wk.isClosed()) { toast("worker が動いていません"); return; }
        if (warming) { toast("暖機中はできません"); return; }
        if (st == St.RECORDING) { toast("録音中はできません"); return; }
        if (st == St.PLAYING) { toast("再生中はできません"); return; }
        try { wk.cancel(convId); } catch (Exception e) { appendLog("cancel 失敗: " + e); }
        convNum++;
        convId = "c" + convNum;
        convSeq = 0;
        convInSamples = 0;
        convOutSamples = 0;
        expectedConvOut = 0;
        convOutStart = 0;
        turnNo = 0;
        discardedOld = 0;
        synchronized (turnPcm) { turnPcm.clear(); }
        synchronized (turnText) { turnText.setLength(0); }
        activeId = convId;
        try { wk.dialogue(convId, REC_RATE); }
        catch (Exception e) { appendLog("dialogue 送信失敗: " + e); st = St.ENDED; return; }
        st = St.READY;
        appendLog("会話 " + convId + " を開始 (前の会話は破棄)");
        appendTranscript("--- 会話を最初から (" + convNum + " 回目) ---");
        setStatus("新しい会話を開始しました。「押して話す」からどうぞ");
    }

    void endConversation() {
        warming = false;
        if (wk != null) {
            try { wk.hangUp(convId); } catch (Exception e) { /* 無視 */ }
            wk.destroy();
            wk = null;
        }
        st = St.ENDED;
        finish();
    }

    @Override protected void onDestroy() {
        warming = false;
        recOn = false;
        if (rec != null) { try { rec.release(); } catch (Exception e) { /* 無視 */ } rec = null; }
        synchronized (this) {
            if (liveOut != null) { try { liveOut.release(); } catch (Exception e) { /* 無視 */ } liveOut = null; }
        }
        if (wk != null) { wk.destroy(); wk = null; }
        super.onDestroy();
    }
}
