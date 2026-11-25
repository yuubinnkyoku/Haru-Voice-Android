package com.yuubinnkyoku.haruvoice;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * speech worker プロトコル (speech.cpp 0.11.x) の薄いクライアント。
 *
 * - stdout は JSON Lines プロトコル専用。redirectErrorStream(true) は厳禁
 *   (stderr を混ぜると JSON のパースが壊れる)。stderr は別スレッドで拾う。
 * - 1 行 = 1 メッセージ。ready が最初に 1 回、以降は chunk / end / error / fatal。
 * - fatal または stdout の EOF で onClosed。destroy() はプロセスの強制終了。
 */
public class WorkerClient {

    public interface Listener {
        void onReady(int modelSampleRate, int frameSamples, String raw);
        void onChunk(String id, int seq, byte[] pcm, String text);
        void onEnd(String id, int frames, int samples);
        void onError(String id, String code, String message);
        void onFatal(String message);
        void onLog(String line);
        void onClosed();
    }

    final Listener L;
    Process proc = null;
    BufferedWriter out = null;
    volatile boolean started = false;
    volatile boolean closedFlag = false;

    public WorkerClient(Listener l) { this.L = l; }

    /** argv は実行バイナリから全て: (bin, "worker", model, "--language", "ja", ...) */
    public void start(List<String> argv, File workDir, String nativeLibDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workDir);
        pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("SPEECH_NO_UPDATE_CHECK", "1");
        proc = pb.start();
        out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
        started = true;
        Thread tOut = new Thread(new Runnable() { @Override public void run() { readLoop(); } }, "wk-out");
        Thread tErr = new Thread(new Runnable() { @Override public void run() { errLoop(); } }, "wk-err");
        tOut.setDaemon(true); tErr.setDaemon(true);
        tOut.start(); tErr.start();
    }

    void readLoop() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                handleLine(line);
            }
            L.onLog("[worker] stdout が終了しました");
        } catch (Exception e) {
            L.onLog("[worker] stdout 読み取り例外: " + e);
        } finally {
            closedFlag = true;
            L.onClosed();
        }
    }

    void errLoop() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) L.onLog("[worker] " + line);
        } catch (Exception e) { /* stdout 終了後は閉じられるだけ */ }
    }

    void handleLine(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String type = o.optString("type", "");
            if ("ready".equals(type)) {
                int rate = 24000, frame = 3200;
                JSONObject m = o.optJSONObject("model");
                if (m != null) {
                    rate = m.optInt("sample_rate", 24000);
                    frame = m.optInt("frame_samples", 3200);
                }
                L.onReady(rate, frame, line);
            } else if ("chunk".equals(type)) {
                String id = o.optString("id");
                int seq = o.optInt("seq", -1);
                byte[] pcm = null;
                String b64 = o.optString("pcm", "");
                if (!b64.isEmpty()) pcm = Base64.decode(b64, Base64.NO_WRAP);
                String text = (o.has("text") && !o.isNull("text")) ? o.optString("text") : null;
                L.onChunk(id, seq, pcm, text);
            } else if ("end".equals(type)) {
                L.onEnd(o.optString("id"), o.optInt("frames", -1), o.optInt("samples", -1));
            } else if ("error".equals(type)) {
                L.onError(o.optString("id"), o.optString("code"), o.optString("message", line));
            } else if ("fatal".equals(type)) {
                L.onFatal(o.optString("message", line));
            } else {
                L.onLog("[worker?] " + line);
            }
        } catch (Exception e) {
            L.onLog("[worker] JSON パース失敗: " + line);
        }
    }

    // ---- 送信 ----

    synchronized void send(JSONObject o) throws IOException {
        if (!started || closedFlag) throw new IOException("worker は終了しています");
        out.write(o.toString());
        out.write('\n');
        out.flush();
    }

    public void dialogue(String id, int mySampleRate) throws IOException, JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "dialogue");
        o.put("id", id);
        o.put("sample_rate", mySampleRate);
        send(o);
    }

    /** pcm = int16 LE モノラル、sample_rate は dialogue で宣言した自分側のレート */
    public void chunk(String id, int seq, byte[] pcm) throws IOException, JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "chunk");
        o.put("id", id);
        o.put("seq", seq);
        o.put("pcm", Base64.encodeToString(pcm, Base64.NO_WRAP));
        send(o);
    }

    public void hangUp(String id) throws IOException, JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "hang_up");
        o.put("id", id);
        send(o);
    }

    public synchronized void destroy() {
        closedFlag = true;
        try { if (out != null) out.close(); } catch (Exception e) { /* 無視 */ }
        if (proc != null) proc.destroy();
    }

    public boolean isClosed() { return closedFlag; }
}
