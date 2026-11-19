# Haru-Voice-Android

[Haru-4B](https://huggingface.co/sakasegawa/Haru-4B-GGUF)（speech.cpp 0.11.0 ベースのフルデュプレックス音声対話モデル）を Snapdragon 端末で単体動作させる Android アプリ「HaruVoice」。

ビルドに使っているサンドボックス環境は毎回リセットされるため、ソース・ビルドレシピ・署名鍵をこのリポジトリに永続化し、いつでも同一バイナリを再現できるようにするのがこのリポジトリの目的。

## 構成

- `app/` … APK の全ソース（MainActivity.java / DownloadService.java / AndroidManifest.xml）
- `build/BUILD-RECIPE.md` … ゼロから APK を作る完全レシピ（ツールチェーン取得・bionic パッチ・OOM 回避・署名まで）
- `docs/USAGE.md` … インストール手順と使い方
- `signing/` … v0.2.2 以降の統一署名鍵（base64）

## 現行バージョン: HaruVoice 0.2.2（versionCode 4）

- minSdk 26 / targetSdk 28 / arm64-v8a 専用
- APK sha256: `d1eb9cd96957cd9e55721f8c8a876f56bc33e94a3755ed03aba7baf59c947661`（2026-11-20 再ビルド・新鍵で署名）
- 機能:
  - モデルDL（Q8_0/Q6_K 切替、中断とレジューム、sha256 検証、フォアグラウンド通知（バックグラウンド継続）、Wi-Fi/モバイルデータ警告）
  - speech talk による返答生成と MediaPlayer での再生
  - haru-timing による速度実測（3 runs）
- 0.2.1 実機ログで判明した Content-Length 不具合（6.6GB モデルで進捗が出ない。getContentLength() が int で 2GB 超は必ず -1）を 0.2.2 で修正。実機での DL 動作はまだ未検証。

## 署名鍵

署名は `signing/HaruVoice-signing-key.jks.b64` で統一する。

```sh
base64 -d signing/HaruVoice-signing-key.jks.b64 > HaruVoice-signing-key.jks
```

- store パスワード / key パスワード: `haruvoice2026`
- alias: `haru`
- 証明書 DN: `CN=HaruVoice, O=yuubinnkyoku, C=JP`
- 証明書 SHA-256: `545885c1e7d9de52c8162a80b79ecbd1514ebc41795d0ce0de82948c028ec681`
- b64 の sha256: `3dd0c3464320040109d0a1521c371d2d189dac814bdd5bb2913d7207acd62de2`（3504 文字 / DER 2628 bytes）

この鍵で署名し続ける限り `adb install -r` でのバージョン更新が可能。鍵を失うと署名が変わり、アンインストール→インストールが必要になる（0.2.0〜0.2.2 の間に 3 回連続で起きた事故をこれで根絶する）。

> **2026-11-20 に鍵を再生成した（Policy B）。**
> 旧鍵の base64 は転写時に破損しており（3496 文字、6 bytes 欠落）、復元を試みたが不可能だった。
> 詳細は `docs/SIGNING-KEY-HISTORY.md` を参照。0.2.2 は未配布だったため実害はない。
> **この鍵が以後の唯一の正となる。失うと再びアンインストール事故になるので、必ずバックアップを取ること。**

## 注意

- F16 量子化モデルは ARM CPU でオーバーフロー停止するため使用禁止。Q8_0（推奨）か Q6_K を使う。
- APK バイナリは当面 artifacts で配布。GitHub Releases への格納は追って実施する。
- 実機: nubia NX741J（Snapdragon 8 Elite Gen 5、Android 36、16GB）で動かす前提。
