# HaruVoice 0.2.2 — ビルド・署名・配布の記録 (2026-11-20)

## 成果物

| ファイル | sha256 |
|---|---|
| `HaruVoice-0.2.2.apk` (3.43MB) | `d1eb9cd96957cd9e55721f8c8a876f56bc33e94a3755ed03aba7baf59c947661` |
| `HaruVoice-signing-key.jks` (2628B) | `0128e97d208cf1503d56ca312d5c16127d2ae50d48b9c9f2278431ecea7bf062` |
| `HaruVoice-signing-key.jks.b64` (3504文字) | `3dd0c3464320040109d0a1521c371d2d189dac814bdd5bb2913d7207acd62de2` |

- リポジトリ: https://github.com/yuubinnkyoku/Haru-Voice-Android
- 署名証明書 SHA-256: `545885c1e7d9de52c8162a80b79ecbd1514ebc41795d0ce0de82948c028ec681`
- alias `haru` / store・key パスワード `haruvoice2026` / `CN=HaruVoice, O=yuubinnkyoku, C=JP`

## 署名鍵: Policy B を採用（復元は不可能と判定）

旧鍵の base64 は転写破損していた。ハンドオフの記述（欠落4 bytes、暗号文912B）は**実測と違っていた**。

実測:
- 欠落は **6 bytes**、位置は**暗号文の後ろ（証明書バッグ領域）**
- 鍵バッグ暗号文は **1232 bytes で無傷** → PBKDF2 で復号成功、PKCS8 → RSAPrivateKey が得られた
- p, q, e, d は数学的に整合（両素数、e·d ≡ 1 mod λ、RSA ラウンドトリップ成功）
- しかし `n` ≠ `p×q`（17バイト相違）、dp/dq/qinv も不一致、dq の長さが 125B（本来129）
- 証明書バッグも復号できた（base64 の位相ずれを index 1983 で再同期）が、証明書は SPKI 以降が破損
- 証明書の modulus は p×q と 238 バイト相違 → 鍵と証明書が対応していない

**判定理由**: Android の署名 identity を決めるのは証明書の公開鍵。秘密鍵の p,q,e,d は揃っていたが、
元の証明書が復元できない以上、同じ identity は再現できない。よって復元不可能。
0.2.2 は未配布だったため実害なし。

## ビルドで判明した重要な知見

1. **`-DANDROID_PLATFORM=android-28` が必須**
   android-26 だと `spawn.h` の `posix_spawn` 系が `#if __ANDROID_API__ >= 28` で隠され、
   `update.cpp` / `page.cpp` / `curl.cpp` が「use of undeclared identifier 'posix_spawn'」で落ちる。
   minSdk 26 は APK 側の設定なので、ネイティブは 28 でビルドして問題ない。

2. **d8 は JDK 17 で動かす**
   JDK 25 の javac が出す class ファイルだと
   `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`
   で d8 が内部エラーになる。`JAVA_HOME` を JDK 17 に向けて javac と d8 の両方を実行する。

3. **メモリ 16GB 以上なら OOM 回避策は不要**
   3GB 環境では `ninja speech` が -O3 で OOM するが、39GB 環境では
   `ninja speech-cli haru-timing` がそのまま通った（229 ステップ、約 4 分）。

4. **libomp.so の sha256 がツールチェーンの検証になる**
   `8d9fb977184d7a4bffd85cdd6ebb07db06a451dac6e425b47ec8ecd8d35c3a7c` と一致すれば正しい NDK。

## 再現性の確認

GitHub に push した鍵をダウンロードし、それで `aligned.apk` を再署名したところ
**APK の sha256 が完全に一致**した（`d1eb9cd9...`）。鍵が正しく固定されたことの証明。

## 未検証事項

- **実機でのモデル DL は 0.2.2 で未検証。** 0.2.1 で判明した Content-Length 不具合
  （`getContentLength()` が int で 2GB 超は必ず -1 になる）は 0.2.2 で修正済みだが、
  実機で 6.6GB のモデル DL が完走するかは未確認。
- 「速度を実測」の step p50 の実機報告はまだ得られていない。
