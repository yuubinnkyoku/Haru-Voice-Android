# HaruVoice ビルド完全レシピ

ビルド環境（3GB RAM の Linux サンドボックス）が毎回消えるため、ゼロから再現できる手順。git と unzip は無い前提（python の zipfile と codeload tarball で代用）。Bash コマンドは 2 分で打ち切られるので、長時間処理は分割して再開する。

## 0. ツールチェーン

- NDK r27c: `https://dl.google.com/android/repository/android-ndk-r27c-linux.zip` を python zipfile で extractall した後、`external_attr>>16` が S_ISLNK のエントリ（約35本）は os.symlink で張り替え、実行ビットは chmod 755
- cmake 3.31.6 / ninja 1.12.1 を GitHub releases から展開し chmod 755
- JDK17 と Android SDK: cmdline-tools で `platforms;android-34` と `build-tools;34.0.0` をインストール

## 1. ソース

- speech.cpp: `https://codeload.github.com/nyosegawa/speech.cpp/tar.gz/refs/heads/main`（VERSION 0.11.0）
- ggml: `https://codeload.github.com/nyosegawa/ggml/tar.gz/79b50d239d0b88852814c873db0d85d43ed66d9b` を speech.cpp/ggml/ に展開

## 2. bionic パッチ（3ファイル）

`tools/cli/update.cpp`、`tools/server/page.cpp`、`tools/catalog/curl.cpp` の `posix_spawn_file_actions_addclosefrom_np(&actions, 3);` を `#ifdef __GLIBC__` ガード + `for (fd = 3; fd <= 31; fd++) posix_spawn_file_actions_addclose(&actions, fd);` に置換。bionic には addclosefrom_np が無いため。

## 3. ネイティブビルド

```sh
cmake -B build-android-cpu -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM=/opt/tools/ninja
ninja -C build-android-cpu haru-timing
```

- `CMAKE_MAKE_PROGRAM` の指定は必須（PATH を見ないため）
- `ninja speech` は -O3 でホスト OOM（3GB）する。`ninja -t commands speech-cli` から未生成の `CMakeFiles/speech-cli.dir/*.o` を抽出し、`sed 's/-O3/-O1/'` で 1 個ずつ手動コンパイルしてから `ninja -j 1 speech`
- 完成した speech / haru-timing を llvm-strip し、APK の `lib/arm64-v8a/` に `libspeech-cli.so` / `libharu-timing.so` として配置。`libomp.so` も同梱（NDK の toolchain から）

## 4. APK 化

```sh
javac --release 8 -encoding UTF-8 -classpath $SDK/platforms/android-34/android.jar -d obj src/com/yuubinnkyoku/haruvoice/*.java
$BT/d8 --release --min-api 26 --lib $SDK/platforms/android-34/android.jar --output dexout obj/com/yuubinnkyoku/haruvoice/*.class
$BT/aapt2 link -I $SDK/platforms/android-34/android.jar --manifest AndroidManifest.xml \
  -o base.apk --min-sdk-version 26 --target-sdk-version 28 --version-code 4 --version-name 0.2.2
```

python zipfile で base.apk に `classes.dex` と `lib/arm64-v8a/` の 3 so を追加（APK 内パスは `lib/arm64-v8a/`）。バイナリを .so 名で同梱するのは W^X 回避（nativeLibraryDir から exec する）。targetSdk 28 なのは愛称。

```sh
$BT/zipalign -f 4 base.apk aligned.apk
base64 -d signing/HaruVoice-signing-key.jks.b64 > HaruVoice-signing-key.jks
$BT/apksigner sign --ks HaruVoice-signing-key.jks --ks-key-alias haru \
  --ks-pass pass:haruvoice2026 --key-pass pass:haruvoice2026 --out HaruVoice-0.2.2.apk aligned.apk
$BT/apksigner verify --print-certs HaruVoice-0.2.2.apk
```

## v0.2.2 の sha256

- APK: `e9402618f34e2c0f52905b3d69ffd8020e8c985573ef4e7d049b9d148228790b`
- libspeech-cli.so: `8368c02ceddd29a839f89496159a76ef227d079eb9cdd326410715ce61d892aa`
- libharu-timing.so: `5bd1fe7d3fbc3e9407496a38c8deac5affe07b05348f2cfad39d5f7eec78f351`
- libomp.so: `8d9fb977184d7a4bffd85cdd6ebb07db06a451dac6e425b47ec8ecd8d35c3a7c`
- モデル Q8_0: `c372a527e63ca4251429a48863535b8bca03ebd6f414bbf31f28a9d175349934`（6.62GB）
- モデル Q6_K: `8db05e86acca046bc183d4e30fbda22ac775ba4767ae5a10bcd0d48f8d7db793`（5.61GB）
