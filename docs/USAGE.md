# HaruVoice の使い方（v0.2.5）

Haru-4B を端末単体で動かすアプリ。ADB 経由でインストールする。実機（nubia NX741J）で 6.6GB の Q8_0 を完走・sha256 検証済み、talk による返答生成も動作確認済み。

## 1. インストール

v0.2.0 / v0.2.1 を入れたことがあるなら先にアンインストール（署名が変わっているためアップデート不可）。0.2.2 以降は署名を統一しているので `adb install -r` で更新できる。

```sh
adb uninstall com.yuubinnkyoku.haruvoice   # 0.2.1 以前がある場合のみ
adb install -r HaruVoice-0.2.5.apk
```

## 2. モデルDL（アプリ内）

1. 「DL対象」ボタンで Q8_0（6.6GB・推奨）か Q6_K（5.6GB・メモリ節約）を切替
2. 「モデルDL開始」をタップ。フォアグラウンド通知が出て、アプリを閉じてもバックグラウンドで継続（WakeLock 付き）
3. 通知とログに進捗（％・速度・残り時間）が出る。中断しても `.part` が残るので再タップでレジューム（HTTP 206）
4. 完了後 sha256 検証を通ったら自動でモデル欄にセット

モバイルデータ回線では 1 回目のタップで警告して止まり、2 回目のタップで開始する。

> **0.2.5 以降**: 既に完成済みのモデルがある状態で「モデルDL開始」を押しても、再ダウンロードはせずスキップする。取り直したいときはファイルを削除してから実行する。

## 3. 質問音声を用意する

talk には**質問音声の wav** が必要。アプリは起動時に `question.wav` を自動検出する。

- 形式: **16kHz または 24kHz / モノラル / 16bit PCM**
- 置き場所: `Android/data/com.yuubinnkyoku.haruvoice/files/`（推奨）または `Download/HaruVoice/`
- ファイル名は任意。絶対パスを直接入力してもよい

### 音声が手元に無い場合（Windows の TTS で作る）

```powershell
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice("Microsoft Haruka Desktop")   # 日本語音声
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.SetOutputToWaveFile("question.wav", $fmt)
$s.Speak("こんにちは。今日はいい天気ですね。何をして過ごすのが好きですか。")
$s.Dispose()
```

```sh
adb push question.wav /sdcard/Android/data/com.yuubinnkyoku.haruvoice/files/question.wav
```

端末で録音する場合は 16kHz モノラルで保存できるアプリを使う。

## 4. 返答を作る

1. 名前・persona を適宜編集（任意の参照ボイス wav も指定可）
2. 「返答を作る」をタップ。モデルのロードに約 8 秒、生成は音声 6.4 秒分で約 22 秒（RTFx 0.30）
3. 完了したら「返答を再生」。`reply.wav`（24kHz モノラル）が作業ディレクトリに出力される

## 5. 速度実測

「速度を実測」で haru-timing 3 runs。step 時間の p50 がログに出るので、今後の高速化判定の資料に使う。

## トラブルシュート

- **「エラー: 質問音声が見つかりません」** … `question.wav` が所定の場所に無い、または拡張子・形式が違う。16kHz/24kHz モノラルの wav を置く。
- DL が始まらない: ログを確認。v0.2.1 は Content-Length の int 不具合で総サイズが取れない（6.6GB が 2GB を超えるため）。0.2.2 で修正済み。
- talk が終わらない: speech.cpp の talk は入力の無音が長いと 1 秒以上の遅延で stop 扱いになる設計。「停止」で止めて短めの wav で試す。
- モデル検出されない: ファイルサイズが 1GB 未満だと検出対象外。
