# NPU / GPU 調査記録 (2026-11-21)

HaruVoice 0.2.5 の CPU 実行 (RTFx 0.35 前後) を高速化できないか、Snapdragon 8 Elite Gen 5
(SM8850, `canoe`) の Hexagon NPU と Adreno 840 GPU を実機 (nubia NX741J, Android 16, user ビルド,
root なし) で検証した記録。**結論: どちらも HaruVoice の APK からは実用にならない。**

## 1. Hexagon NPU (ggml-hexagon) — 不可

### ビルドは成功する

`GGML_HEXAGON=ON` で arm64-v8a 向け `speech` と HTP スケル `libggml-htp-v81.so` のビルドは通った。

```
cmake -S D:\build\speech.cpp-main -B D:\build\android-hexagon -G Ninja
  -DCMAKE_TOOLCHAIN_FILE=.../android-ndk-r27c/build/cmake/android.toolchain.cmake
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release
  -DGGML_HEXAGON=ON -DGGML_OPENMP=OFF
  -DHEXAGON_SDK_ROOT=C:/Qualcomm/Hexagon_SDK/6.6.0.0
  -DHEXAGON_TOOLS_ROOT=C:/Qualcomm/Hexagon_SDK/6.6.0.0/tools/HEXAGON_Tools/19.0.07
  -DPREBUILT_LIB_DIR=toolv19_v81
```

- `speech.stripped` 5,910,888 bytes
- `libggml-htp-v81.so` 885,064 bytes, ELF `e_machine = 0xA4` (Hexagon)
- デバイスの HTP アーキテクチャは **v81** (`/vendor/lib/rfsa/adsp/libQnnHtpV81Skel.so` が存在)

### しかし実行時に NPU へ到達できない

最小プローブ APK (`com.yuubinnkyoku.nputest`, 本物の HaruVoice 鍵で署名) を実機に入れて確認した。

| 試行 | 結果 |
|---|---|
| `open("/dev/fastrpc-cdsp")` | `EACCES (Permission denied)` |
| `open("/dev/fastrpc-cdsp-secure")` | `EACCES` |
| `open("/dev/fastrpc-adsp-secure")` | `EACCES` |
| `dlopen("libcdsprpc.so")` | `library "libcdsprpc.so" not found` |
| `/vendor/lib64/libcdsprpc.so` をアプリ領域へコピーして `dlopen` | `library "vendor.qti.hardware.dsp@1.0.so" not found` |

理由は 2 つで、どちらも通常のアプリでは回避できない。

1. **DAC + SELinux**: `/dev/fastrpc-cdsp` は `crw-rw-r-- system system`、ラベル
   `u:object_r:vendor_qdsp_device:s0`。アプリのコンテキストは
   `u:r:untrusted_app_27:s0:c115,c260,c512,c768` で、`vendor_qdsp_device` への `rw` が無い。
   `getenforce` は `Enforcing`。**shell (uid 2000, `u:r:shell:s0`) でも同じく `Permission denied`**
   になるので、`adb shell` からも開けない。
2. **リンカ名前空間**: `/linkerconfig/ld.config.txt` の `namespace.default.search.paths` は
   `/system/${LIB}` と `/system_ext/${LIB}` のみ。`/vendor/lib64` は `permitted.paths` にも無い。
   `libcdsprpc.so` は `/vendor/etc/public.libraries.txt` に載っているが、この端末では
   アプリの名前空間から見えない。コピーしても `vendor.qti.hardware.dsp@1.0.so` など
   vendor 専用の依存が芋づる式に足りなくなる。

`ggml-hexagon` は HTP スケルを DSP 側のローダに `file:///libggml-htp-v81.so?...` として読ませる
設計なので、そもそもホスト側から `libcdsprpc.so` を `dlopen` できない時点で動かない。

**回避するには** system 署名 / priv-app としてのインストール、root シェル、または Qualcomm QNN を
vendor 側の HTP スケル経由で使う構成のいずれかが要る。いずれも配布 APK では取れない。

## 2. Adreno GPU — 動くが出力が壊れる

### OpenCL バックエンド

`GGML_OPENCL=ON` でビルドでき、`speech devices` が GPU を列挙する。

```
GPUOpenCL  gpu   QUALCOMM Adreno(TM) 840, 8.0 GB, 6.9 GB free
CPU        cpu   CPU, 16.0 GB, 16.0 GB free
```

OpenCL 3.0 (`QUALCOMM build: 0842.23`)、compute units 12、max work group 1024。
`libOpenCL.so` は `/vendor/lib64` にあり、`public.libraries.txt` 経由でアプリからも `dlopen` できる
(こちらは名前空間の問題が起きない)。

**しかし haru のコーデックが使う `GGML_OP_CONV_2D_DW` を OpenCL バックエンドが実装していない。**
`ggml_opencl_supports_op` に `CONV_2D_DW` の case が無く、実行時に落ちる。

```
ggml_backend_opencl_graph_compute: error: op not supported node_40 (CONV_2D_DW)
GGML_ASSERT(ok) failed
```

### Vulkan バックエンド

`GGML_VULKAN=ON` でビルドでき、`CONV_2D_DW` も実装されている。

```
Vulkan0    igpu  Adreno (TM) 840, 20.3 GB, 20.3 GB free
CPU        cpu   CPU, 16.0 GB, 16.0 GB free
```

ビルドには NDK に無いものが要る (詳細は §4)。

**素のままだと `vkGetDeviceFaultInfoEXT` でヌルポインタ参照して落ちる。**
`GGML_VK_DISABLE_FUSION=1` を付けると最後まで走る。

```
GGML_VK_DISABLE_FUSION=1 ./speech-vk talk MODEL --device gpu -o out.wav question.wav
Loaded haru-4b on Vulkan0 in 4.74 s
out.wav: 6.40 s of audio in 48 frames, computed in 14.120 s, RTFx 0.45
```

`haru-timing` で測ると GPU の方が速い (3 回、安定)。

| | step p50 | encode | model | decode | rtfx |
|---|---|---|---|---|---|
| Vulkan0 | **251 ms** | 60 ms | 131 ms | 58 ms | **0.53** |
| CPU | 316 ms | 98 ms | 119 ms | 99 ms | 0.42 |

**ただし生成される音声が壊れている。** 同じ入力・同じ seed で CPU と比べると:

| | peak | RMS | 内容 |
|---|---|---|---|
| CPU | 19,494 | 849 | 正常な音声 |
| Vulkan | 32,767 | 32,712 | ±32767 に張り付いた飽和波形 |

サンプル値の分布を見ると 153,600 サンプル中 80,136 個が `+32767`、72,689 個が `-32767` で、
実質的に矩形波。`GGML_VK_DISABLE_COOPMAT`, `DISABLE_COOPMAT2`, `DISABLE_MMVQ`,
`DISABLE_BFLOAT16`, `DISABLE_GRAPH_OPTIMIZE`, `DISABLE_MULTI_ADD`, `DISABLE_ASYNC`,
`SERIALIZE_SUBMISSIONS` のいずれを足しても変わらない。`CONV_2D_DW` のシェーダ
(`conv2d_dw.comp`) か、その入力の CWHN レイアウト解釈が Adreno 840 で合っていないと見られる。

したがって **Vulkan は速度だけなら 1.26 倍だが、音声が使えないので採用できない。**

## 3. CPU 側で試したこと

`-march=armv8.6-a+dotprod+i8mm+bf16` を付けると `sdot` 970 個・`smmla` 260 個が入るが、
実測では**入れない方が速い** (3 回の p50)。

| ビルド | step p50 | rtfx |
|---|---|---|
| 素 (NDK 既定, `-march` なし) | **372 ms** | 0.35 |
| `armv8.6-a+dotprod+i8mm+bf16` | 380 ms | 0.34 |

`GGML_OPENMP=OFF` にすると `libomp.so` が不要になり APK が 961 KB 軽くなる。速度への影響は
測定誤差の範囲。**0.2.5 の同梱バイナリは `-march` なし・OpenMP ありのまま**で、これが現状最速。

## 4. Vulkan ビルドに必要な追加物 (NDK に無い)

NDK r27c には Vulkan の C++ バインディングと SPIR-V のヘッダが入っていない。以下を用意した。

- `vulkan.hpp` ほか Vulkan-Hpp のヘッダ一式 — `VK_HEADER_VERSION 275` (NDK r27c と一致) の
  タグ `v1.3.275` から取得
- `spirv/unified1/spirv.hpp` ほか — SPIRV-Headers から取得
- `SPIRV-HeadersConfig.cmake` のスタブ — `find_package(SPIRV-Headers CONFIG REQUIRED)` を
  満たすためだけのもの (実際には使われない)
- `glslc.exe` — NDK の `shader-tools/windows-x86_64/` に同梱されている

configure はこうなる。

```
cmake -S D:\build\speech.cpp-main -B D:\build\android-vulkan -G Ninja
  -DCMAKE_TOOLCHAIN_FILE=.../android-ndk-r27c/build/cmake/android.toolchain.cmake
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release
  -DGGML_OPENMP=OFF -DGGML_VULKAN=ON
  "-DVulkan_GLSLC_EXECUTABLE=.../shader-tools/windows-x86_64/glslc.exe"
  -DSPIRV-Headers_DIR=D:/build/spirv-headers-stub
  "-DVulkan_INCLUDE_DIR=D:/build/vulkan-headers"
```

`speech.stripped` は 25,607,320 bytes (CPU 版の 4.5 倍)。`libvulkan.so` に依存する。

## 5. 副産物として見つかった ggml-opencl のバグ

OpenCL を試す過程で、`ggml-opencl` の Q8_0 レイアウト変換パスに実バグを見つけた。

`ggml_backend_opencl_buffer_set_tensor()` の Q8_0 分岐は、渡された `offset` と `size` を無視して
**`ggml_nbytes(tensor)` バイトを `data` から読む**。一方 `ModelFile::load()` は
`kReadPiece` (16 MB) ずつ `ggml_backend_tensor_set()` を呼ぶ。Haru-4B の `token_embd` は
644 MB あるので、16 MB のバッファから 644 MB 読もうとして `clEnqueueWriteBuffer` の中で
セグメンテーション違反になる。

```
#00 __memmove_aarch64_nt
#01 libgsl.so (ioctl_kgsl_sharedmem_write)
#02 libCB.so (cb_enqueue_write_buffer)
#03 libOpenCL_adreno.so (qCLDrvAPI_clEnqueueWriteBuffer)
#04 ggml_cl_create_temp_upload_buffer  ggml-opencl.cpp:10044
#05 ggml_backend_opencl_buffer_set_tensor  ggml-opencl.cpp:10917
```

`src/common/model-file.cpp` に `read_piece()` を足し、バッファ名が `OpenCL` のときだけ
テンソルを分割せず丸ごと読むようにした。これでこのクラッシュは消える (その先で
`CONV_2D_DW` 非対応に当たるので、OpenCL 自体は使えないまま)。

## 6. 結論

- **NPU は無理**。非 root の user ビルドでは `/dev/fastrpc-cdsp` を開けず、`libcdsprpc.so` も
  ロードできない。ビルドは通るが実行できない。
- **GPU も今は無理**。Vulkan は 1.26 倍速いが音声が飽和して壊れる。OpenCL は
  `CONV_2D_DW` 未実装で動かない。
- **CPU のままが正解**。0.2.5 の同梱バイナリ (素の `-march`、OpenMP あり) が現状最速で、
  `-march` を盛っても速くならない。

将来 Vulkan の `CONV_2D_DW` が Adreno で正しく動くようになれば、`GGML_VK_DISABLE_FUSION=1`
を既定にして GPU に載せる道はある。それまでは CPU 版を配る。
