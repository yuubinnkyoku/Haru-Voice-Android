# NPU / GPU 調査記録

HaruVoice の CPU 実行 (RTFx 0.35 前後) を高速化できないか、Snapdragon 8 Elite Gen 5
(SM8850, `canoe`) の Hexagon NPU と Adreno 840 GPU を実機 (nubia NX741J, Android 16, user ビルド,
root なし) で検証した記録。

**結論: NPU には到達でき、LM を HTP で走らせることまで成功した。ただし (1) コーデックの
`CONV_2D_DW` が無い、(2) HMX 経路が NaN を出す、(3) HVX のみでも数値が不安定、(4) 速度が
CPU より遅い、の 4 点で実用にならない。GPU も同様。現状は CPU 実行が正解。**

> **2026-11-21 追記: 初版の「NPU 不可」は誤診だった。** 初版は `dlopen("libcdsprpc.so")` の
> `not found` を「非 root では到達不能」と結論したが、実際の原因は
> **`AndroidManifest.xml` の `<uses-native-library>` 宣言の欠落**だった。宣言を足すと
> `dlopen=ok`、`HTP0 / Hexagon` 列挙、HTP セッション生成まで通る。§1 を書き直した。
>
> **2026-11-21 第2ラウンド追記: LM の HTP 実行まで到達した。** §1.8 に op インベントリ、
> §1.9 に LM の部分オフロードの実測を足した。結論は「動くが速くならず、数値も壊れる」。

---

## 1. Hexagon NPU — 到達はするがコーデックで止まる

### 1.1 ビルド

`GGML_HEXAGON=ON` で arm64-v8a 向け `speech` と HTP スケル `libggml-htp-v81.so` のビルドは通る。

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
- `libggml-htp-v81.so` 885,064 bytes, `elf32-hexagon` (e_machine `0xA4`)
- デバイスの HTP は **v81** (`/vendor/lib/rfsa/adsp/libQnnHtpV81Skel.so` が存在)

**注意: `libggml-htp-v81.so` に llvm-strip をかけてはいけない。** arm64 前提の stripper は
Hexagon ELF を壊す。`speech` 本体だけを strip する。

### 1.2 鍵は manifest の 1 行 — `<uses-native-library>`

Android 12+ (API 31+) では、`/vendor/etc/public.libraries.txt` 掲載の vendor public library でも、
アプリが `<uses-native-library>` で宣言しないと classloader namespace (`clns-*`) に露出しない。
libnativeloader が manifest を読んで namespace を構成するため。

```xml
<application ...>
    <uses-native-library android:name="libcdsprpc.so" android:required="false" />
    <uses-native-library android:name="libOpenCL.so"   android:required="false" />
    <uses-native-library android:name="libvndksupport.so" android:required="false" />
```

- 配置は **`<application>` の子**。`<manifest>` 直下だと AAPT が
  `unexpected element <uses-native-library> found in <manifest>` で失敗する。
- **`libcdsprpc.so` は APK に同梱しない。** 同梱すると vendor の正規クライアントを遮蔽し、
  unsigned PD が `0xffffffff` で拒否される。宣言で vendor のを借りる。
- `extractNativeLibs="true"` にして、スケルが `nativeLibraryDir` に実体として展開されるようにする。

### 1.3 実測: G1〜G3 は PASS

プローブ APK (`com.yuubinnkyoku.haruprobe`, minSdk 32 / targetSdk 36, 本物の HaruVoice 鍵で署名) を
実機に入れ、**アプリプロセス内**で JNI から確認した。

```
process      = /system/bin/app_process64
uid          = 11141
dlopen(libcdsprpc.so) = ok
  dlsym remote_session_control   = ok
  dlsym remote_handle64_open     = ok
  dlsym remote_handle64_invoke   = ok
  dlsym dspqueue_create          = ok
  dlsym fastrpc_mmap             = ok
  dlsym rpcmem_alloc             = ok
dlopen(libOpenCL.so)  = ok

/proc/self/maps, libcdsprpc:
  6e96a9c000-6e96aca000 r--p 00000000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
  6e96acc000-6e96b25000 r-xp 00030000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
  6e96b28000-6e96b29000 r--p 0008c000 fe:11 38332741  /vendor/lib64/libcdsprpc.so
  6e96b2c000-6e96b2d000 rw-p 00090000 fe:11 38332741  /vendor/lib64/libcdsprpc.so

ggml devices, in this process:
  count = 2
  [0] HTP0 / Hexagon / type=1 / 0.0 GiB free of 0.0 GiB
  [1] CPU / CPU / type=0 / 14.9 GiB free of 14.9 GiB
```

`HTP0 / Hexagon / type=1` は Zenz の実績と完全に一致する。

**HTP セッションも生成できた** (`speech_log_set` で取得):

```
ggml-hex: HTP0 allocating new session : domain 1000 phys-idx 0 virt-idx 0
ggml-hex: HTP0 hwinfo: threads 8, hvx 8, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 100000
          uri file:///libggml-htp-v81.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=nsp1000&_session=0
          handle 0xb4000070d06675a0
ggml-hex: HTP0 op batching: n-bufs 16 n-tensors 8192 n-ops 1280 vmem 3344539648
ggml-hex: HTP0 power mode: burst
```

unsigned PD は通過し、`remote_session_control` のエラーは出ていない。**G1 (dlopen) / G2 (列挙) /
G3 (セッション) はすべて PASS。**

### 1.4 落とし穴: 子プロセスでは宣言が効かない

最初のプローブは `speech` を子プロセスとして `exec` したが、これでは
`dlopen failed: library "libcdsprpc.so" not found` のままだった。

**`exec` で起動した子プロセスは `/linkerconfig/ld.config.txt` から新しい namespace を作るので、
アプリの classloader namespace (`clns-*`) を引き継がない。** manifest の宣言は
アプリプロセス内の `dlopen` にしか効かない。判定は必ず JNI などで**プロセス内**から行うこと。

### 1.5 もう 1 つの落とし穴: `ADSP_LIBRARY_PATH`

セッション生成が最初 `error 0x80000406` (`AEE_EUNABLETOLOAD`) で失敗した。
`ggml-hexagon` はスケルを `file:///libggml-htp-v81.so?...` として DSP 側ローダに渡すが、
**`ADSP_LIBRARY_PATH` を自分では設定しない** (呼び出し側の責任)。設定しないとローダが
スケルを見つけられない。

```c
setenv("ADSP_LIBRARY_PATH", nativeLibDir, 1);
setenv("DSP_LIBRARY_PATH",  nativeLibDir, 1);
```

これを足した瞬間にセッションが通った。

### 1.6 G4 は FAIL — `CONV_2D_DW` が無い

セッションが通ったので会話を開始したところ、`haru::Codec::encode` の中で abort した。
ggml の abort コールバック (`ggml_set_abort_callback`) でメッセージを取ると:

```
D:/build/speech.cpp-main/ggml/src/ggml-hexagon/ggml-hexagon.cpp:8421:
ggml-hex: graph-compute CONV_2D_DW is not supported
```

`ggml-hexagon` の `op_remap_to_htp()` に `GGML_OP_CONV_2D_DW` の case が無く、`default:` で
`GGML_ABORT` する。haru のコーデックは `ggml_conv_2d_dw_direct()` を 1 回使うので
(`src/families/haru/codec.cpp:153`)、**モデル全体を HTP に載せることはできない。**

`GGML_HEXAGON_OPFILTER=CONV_2D_DW` で `supports_op` から外しても回避できない。
speech.cpp はバックエンドスケジューラを使わず、グラフを 1 つのバックエンドに丸ごと渡すため
(`src/common/graph.cpp` の `compute_graph`)、`supports_op` を通らずに `graph_compute` に来る。

### 1.7 部分オフロードの見通し

LLM 部分だけ HTP に載せる案は、**speech.cpp 側にバックエンド分割の仕組みが要る**。
`ModelFile` は `place` コールバックでテンソルごとにバックエンドを選べるが
(`src/common/model-file.h`)、グラフの計算は `Graph::compute(backend, allocr)` で
単一バックエンドに固定されている。`ggml_backend_sched` を使う経路は
`qwen35-decoder.cpp` にしかない。

つまり現状の haru 実装では、コーデックと LLM が同じグラフ実行系に乗っているため、
片方だけ NPU に逃がすには `ggml_backend_sched` への載せ替えが要る。これは
「manifest 1 行」では済まない規模の変更になる。

### 1.8 第2ラウンド: op インベントリ（Phase A）

`Graph::compute()` に `SPEECH_OP_INVENTORY` のフックを足し、グラフを計算する前に
全ノードの op 名と shape を出すようにした（`src/common/graph.cpp` の `print_op_inventory()`）。
各 call site に stage 名を付けて、どのグラフか分かるようにした。

実機で `talk` を 1 回流すと 7 ステージが取れる:

| stage | nodes | hexagon 未対応 |
|---|---|---|
| `head.constants` | 12 | なし |
| `codec.encode` | 746 | **CONV_2D_DW × 1248** |
| `dialogue.embed` | 17 | なし |
| `dialogue.rows` | 1786 | なし |
| `dialogue.emit` | 96 / 1897 | なし |
| `codec.decode` | 779 | **CONV_2D_DW × 1248** |

**LM 側（`dialogue.*` と `head.*`）は全 op が `op_remap_to_htp()` にある。**
未対応は codec の `CONV_2D_DW` ただ 1 種で、Phase C の対象が確定した。

`CONV_2D_DW` ノードの実測値（Phase C の設計に必要）:

```
encode: out(51200,1,32,1) s0(7,1,1,32) s1(51206,1,32,1) stride(1,1) pad(0,0) dil(1,1)
        nb out(128,6553600,4,6553600) s0(128,896,128,4) s1(128,6554368,4,6554368)
decode: out(1,1,2048,1)   s0(7,1,1,2048) s1(7,1,2048,1)  stride(1,1) pad(0,0) dil(1,1)
        nb out(8192,8192,4,8192) s0(8192,57344,8192,4) s1(8192,57344,4,57344)
```

カーネルは幅 7 の depthwise、stride 1・pad 0・dilation 1、チャネル数 32/64/2048。
`nb[2] == 4` がチャネル最速（CWHN）であることを示している。

### 1.9 第2ラウンド: LM の部分オフロード（Phase B）

#### 実装

- `haru::tensor_on_cpu(name)` を足し、`codec.*` のテンソルだけ CPU に残す
  （`src/families/haru/layout.cpp`）。LM・音声射影・head は HTP。
- `HaruEngine` が `ModelFile` の `place` コールバックで振り分け、
  `Dialogue` に CPU バックエンドを渡す。
- `Dialogue::emit()` は `decoder_.scheduler()` があれば scheduler 経由で計算する
  （`Qwen35Decoder` は `cpu` を渡すと `ggml_backend_sched` を作る）。

#### 結果: 動くが 4 つの理由で実用にならない

**(1) モデルロードとセッション生成は成功する。** `Loaded haru-4b on HTP0 in 7.66 s`、
`HTP0 hwinfo: threads 8, hvx 8, hmx 1, vtcm 8 MB`、`power mode: burst`。

**(2) HMX 経路が NaN を出す。** 既定（`GGML_HEXAGON_MM_SELECT=2`、HMX 優先）だと

```
speech: device: the model's audio came out as numbers that are not finite; run it on another device
```

**(3) HVX のみでも NaN と非再現が残る。** `GGML_HEXAGON_MM_SELECT=1`（HVX のみ）にすると
NaN の頻度は下がるが消えない。同じ seed 1 で 4 回走らせると

```
run 1: ""            → NaN で停止
run 2: "い"          → NaN で停止
run 3: ""            → NaN で停止
run 4: ""            → NaN で停止
```

`GGML_HEXAGON_NDEV=1`（単一デバイス）でも 4 回中 4 回 NaN なので、**レースではない**。
`max_seconds` を 1〜4 に振ると NaN の出方が変わる（1 と 3 は完走、2 と 4 は NaN）ので、
フレーム数に単純比例する問題でもない。**HTP の計算結果は再現性がなく、そのままでは使えない。**

CPU は同じ seed で常に同じ結果を返す。

**(4) CPU より遅い。** `talk` の実測（6.40 s の音声、48 フレーム）:

| 実行 | 1 回目 | 2 回目 | 3 回目 | RTFx |
|---|---|---|---|---|
| CPU | 20.98 s | 20.05 s | 19.90 s | 0.31–0.32 |
| HTP0 (HVX のみ) | 25.47 s | 25.37 s | 25.11 s | 0.25 |

**NPU の方が 25% 遅い。** 1 フレームずつ（M=1）の行列積では HMX のタイルが埋まらず、
重み転送とカーネル起動の固定費が支配的になるため。llama.cpp で NGL を上げると速くなるのは
プロンプト（M が大きい）をまとめて処理するからで、haru の会話は 1 フレーム = 1 トークン
ずつの逐次処理なので同じ効果が得られない。

#### 副産物: `ggml-hexagon` の `supports_op` バグ

`ggml_hexagon_supported_mul_mat()` が **K が 32 の倍数でない行列積を「対応」と答えていた**。
DSP 側の HMX カーネル（`hmx_mm_2d_f32()` / `hmx_mm_f16_f32_batched()` / `hmx_mm_id_2d_f32()`）は
`if (k % 32 != 0) return HTP_STATUS_NO_SUPPORT;` で拒否するので、1 行ステップの attention（K=2）で

```
ggml-hex: HTP0 dspcall : dsp-rsp NO-SUPPORT
ggml-hex: HTP0 synchronize failed : dsp-error NO-SUPPORT
```

となりプロセスごと abort していた。

**修正は HMX 経路だけに絞る。** 最初 `src0->ne[0] % 32 != 0` で無条件に `false` を返すように
したが、これは広すぎた。`k % 32` を課すのは HMX カーネルだけで、**HVX カーネルは任意の K を
取る**（`HTP_MM_KERNEL_HVX_F32_F32_VTCM` に K の制約はない）。無条件のガードは、後述する
depthwise conv の分解（K = カーネル幅 = 7）まで CPU に落としてしまう。最終的に

```c
if (kparams.n_hmx && src0->ne[0] % 32 != 0) return false;
```

とした（`ggml-hexagon.cpp`）。これで該当ノードは scheduler が CPU に回し、HVX で走れる
ノードは HTP に残る。

#### 副産物: `haru-timing` は NPU で走らない

`haru-timing` は codec を直接呼ぶので `CONV_2D_DW` に当たり abort する。
Phase B の速度は `talk` の `computed in` で判断するしかない。

### 1.10 第3ラウンド: NaN の出所を特定

第2ラウンドで「HVX のみでも NaN が出る」と記録したが、その出所を op 単位で追った。

#### 計測方法

`Dialogue::emit()` に `SPEECH_NAN_TRACE` のフックを足し、グラフの 4 つの結果
（`hidden` / `logits` / `silent` / `latent`）それぞれが有限かを 1 フレームごとに出す。

#### 結果: NaN は `latent` だけに出る

```
nan-trace: hidden ok logits ok silent ok latent ok
nan-trace: hidden ok logits ok silent ok latent ok
nan-trace: hidden ok logits ok silent ok latent NAN   ← ここで API が停止
```

- `hidden`（LM の最終隠れ状態）は常に有限
- `logits` / `silent`（LM の出力射影）も常に有限
- **`latent`（head の出力）だけが NaN になる**

つまり **LM の計算は正しく、head のグラフだけが壊れている。** 5 回中 3 回の頻度で出る。

#### 切り分け: head を CPU に固定すると NaN が消える

`haru::tensor_on_cpu()` に `SPEECH_HEAD_CPU` のスイッチを足し、`head.*` のテンソルを
CPU に置いて 5 回走らせた:

```
run 1..5 : latent NAN = 0, 0, 0, 0, 0
```

**NaN は 5/5 で消えた。** 原因は head のグラフを HTP で走らせたことにある。

head が使う op は `MUL_MAT` / `RMS_NORM` / `MUL` / `ADD` / `SCALE` / `SILU` / `CONT` で、
いずれも `op_remap_to_htp()` にある。怪しいのは `part()` が作る
`ggml_view_2d` + `ggml_cont` の組み合わせ（`head.cpp`）で、`ada` の出力
`[3 x hidden, 1]` から 3 つの `[hidden, 1]` を切り出す。HTP の `CONT` がこの
view オフセットを正しく扱えていない可能性がある。

#### ただし head を CPU に移すと別のクラッシュが出る

```
signal 11 (SIGSEGV), fault addr 0x2538a80821601540
  #00 ggml_hexagon_session::clone_buffer()  ggml-hexagon.cpp:4715
  #01 enqueue_op                            ggml-hexagon.cpp:4422
  #02 ggml_backend_hexagon_graph_compute    ggml-hexagon.cpp:8706
```

`clone_buffer()` の `sbuf->mem`（`shared_ptr`）が解放済みの状態で参照されている。
**head を CPU に移すと HTP バッファの寿命管理が壊れる**という、hexagon バックエンド側の
別のバグ。`GGML_ASSERT(sbuf && sbuf->mem)` を通り抜けてから落ちているので、
`mem` の参照カウントがどこかで失われている。

#### 第3ラウンドの結論

| 項目 | 状態 |
|---|---|
| NaN の出所 | **特定済み**: head のグラフ（`latent` のみ） |
| LM の計算 | **正しい**（`hidden` / `logits` / `silent` は常に有限） |
| head を CPU に置く | NaN は消えるが `clone_buffer` でセグフォルト |
| 残る作業 | (a) head の `CONT`+view が HTP で正しく動くか、 (b) `clone_buffer` の寿命バグ |

**次の一手は (a) と (b) の 2 つに絞られた。** どちらも hexagon バックエンド側の修正で、
haru 側の変更ではない。

#### Phase C の前提が変わった点（重要）

指示書には「幅 7 の conv は分解しても K=7 が 32 非倍数で CPU 回帰になる」とあるが、
これは**上の無条件ガードの副作用**であって、分解式の本質的な制約ではない。

- `ggml_im2col()` の出力は `ne[0] = KH*KW*IC`。depthwise をチャネルごとに分けると
  `IC=1` なので **K = KH*KW = 7**。
- `ggml_hexagon_precompute_im2col_params()` に K の 32 倍数要求は無い。
- HVX の `MUL_MAT` カーネルにも K の制約は無い。

したがってガードを HMX 限定にした今、**K=7 の分解は HTP で走れる**。Phase C を見送る理由は
「K が 32 非倍数だから」ではなく、**LM 自体が遅く・NaN を出すので codec だけ速くしても
全体が良くならない**という点に変わった。

---

## 2. Adreno GPU — 動くが出力が壊れる

### 2.1 OpenCL バックエンド

`GGML_OPENCL=ON` でビルドでき、GPU を列挙する。

```
GPUOpenCL  gpu   QUALCOMM Adreno(TM) 840, 8.0 GB, 6.9 GB free
CPU        cpu   CPU, 16.0 GB, 16.0 GB free
```

OpenCL 3.0 (`QUALCOMM build: 0842.23`)、compute units 12、max work group 1024。
`libOpenCL.so` は `public.libraries.txt` 経由でアプリから `dlopen` できる
(こちらは manifest 宣言があれば名前空間の問題は起きない)。

**しかし `GGML_OP_CONV_2D_DW` を OpenCL バックエンドが実装していない。**
`ggml_opencl_supports_op` に case が無く、実行時に落ちる。

```
ggml_backend_opencl_graph_compute: error: op not supported node_40 (CONV_2D_DW)
GGML_ASSERT(ok) failed
```

### 2.2 Vulkan バックエンド

`GGML_VULKAN=ON` でビルドでき、`CONV_2D_DW` も実装されている。

```
Vulkan0    igpu  Adreno (TM) 840, 20.3 GB, 20.3 GB free
CPU        cpu   CPU, 16.0 GB, 16.0 GB free
```

素のままだと `vkGetDeviceFaultInfoEXT` でヌルポインタ参照して落ちる。
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

153,600 サンプル中 80,136 個が `+32767`、72,689 個が `-32767` で、実質的に矩形波。
`GGML_VK_DISABLE_COOPMAT`, `DISABLE_COOPMAT2`, `DISABLE_MMVQ`, `DISABLE_BFLOAT16`,
`DISABLE_GRAPH_OPTIMIZE`, `DISABLE_MULTI_ADD`, `DISABLE_ASYNC`, `SERIALIZE_SUBMISSIONS` の
いずれを足しても変わらない。`conv2d_dw.comp` の CWHN レイアウト解釈が Adreno 840 で
合っていないと見られる。

したがって **Vulkan は速度だけなら 1.26 倍だが、音声が使えないので採用できない。**

---

## 3. CPU 側で試したこと

`-march=armv8.6-a+dotprod+i8mm+bf16` を付けると `sdot` 970 個・`smmla` 260 個が入るが、
実測では**入れない方が速い** (3 回の p50)。

| ビルド | step p50 | rtfx |
|---|---|---|
| 素 (NDK 既定, `-march` なし) | **372 ms** | 0.35 |
| `armv8.6-a+dotprod+i8mm+bf16` | 380 ms | 0.34 |

`GGML_OPENMP=OFF` にすると `libomp.so` が不要になり APK が 961 KB 軽くなる。速度への影響は
測定誤差の範囲。**0.2.5 の同梱バイナリは `-march` なし・OpenMP ありのまま**で、これが現状最速。

---

## 4. Vulkan ビルドに必要な追加物 (NDK に無い)

NDK r27c には Vulkan の C++ バインディングと SPIR-V のヘッダが入っていない。

- `vulkan.hpp` ほか Vulkan-Hpp のヘッダ一式 — `VK_HEADER_VERSION 275` (NDK r27c と一致) の
  タグ `v1.3.275` から取得
- `spirv/unified1/spirv.hpp` ほか — SPIRV-Headers から取得
- `SPIRV-HeadersConfig.cmake` のスタブ — `find_package(SPIRV-Headers CONFIG REQUIRED)` を
  満たすためだけのもの
- `glslc.exe` — NDK の `shader-tools/windows-x86_64/` に同梱

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

---

## 5. 副産物として見つかった ggml-opencl のバグ

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
テンソルを分割せず丸ごと読むようにした。これでこのクラッシュは消える。

---

## 6. 結論と次の一手

| 経路 | 到達 | 実用 | 止まっている理由 |
|---|---|---|---|
| Hexagon NPU | **可** | 不可 | `CONV_2D_DW` が `ggml-hexagon` に無い |
| Adreno OpenCL | 可 | 不可 | `CONV_2D_DW` が `ggml-opencl` に無い |
| Adreno Vulkan | 可 | 不可 | 音声が飽和して壊れる |
| CPU | — | **可** | 現状最速 (RTFx 0.35) |

NPU 到達の壁は **manifest の `<uses-native-library>` 1 行**で、これは解消済み。
残る壁は `CONV_2D_DW` のカーネルが無いこと。

次の一手の候補:

1. **`CONV_2D_DW` を `IM2COL` + `MUL_MAT` に分解する** — これが最短。
   `ggml-hexagon` は `HTP_OP_IM2COL` と `HTP_OP_MUL_MAT` を両方実装済みで、
   `op_remap_to_htp()` に `GGML_OP_CONV_2D_DW` の case を足して、グラフ構築時に
   depthwise conv をこの 2 つに書き換えれば既存カーネルで走る可能性が高い。
   depthwise (group = channels) なので、`IM2COL` のあとの `MUL_MAT` は
   チャネルごとの独立した小さな積和になり、`MUL_MAT` のバッチ扱いで表現できるかが焦点。
2. **`ggml_backend_sched` へ載せ替える** — コーデックを CPU、LLM を HTP に振り分ける。
   `Graph::compute(ggml_backend_sched_t)` は既にあり (`src/common/graph.cpp:101`)、
   `qwen35-decoder.cpp` に前例がある。ただし haru の 3 か所
   (`codec.cpp:228,268`, `dialogue.cpp:148,199`, `head.cpp:76`) はすべて
   単一バックエンドの `compute(backend, allocr)` を呼んでいるので、そこの載せ替えが要る。
3. **Vulkan の `conv2d_dw` を直す** — 速度は出ているので、シェーダの CWHN 解釈を
   修正できれば一番近い。ただし Adreno 固有の問題かどうかの切り分けが要る。

いずれも「manifest 1 行」では済まない。**HaruVoice 0.2.5 は CPU のまま配るのが正しい。**

### 6.1 第2ラウンドの結果を踏まえた優先度の見直し

Phase B（LM だけ HTP）を実際に走らせた結果、**Phase C に進む価値は下がった**。

- LM を HTP に載せても **CPU より 25% 遅い**（25.4 s vs 20.0 s）。1 フレーム = 1 トークンの
  逐次処理では HMX のタイルが埋まらず、転送とカーネル起動の固定費が支配的になる。
- HMX 経路は NaN を出し、HVX のみでも NaN と非再現が残る。**数値の再現性が無い。**
- コーデックを Phase C で NPU 化できたとしても、その上に載る LM がこの状態では
  全体として速くも正しくもならない。

**Phase C を見送る理由は「K=7 が 32 非倍数だから」ではない**（§1.9 の副産物を参照。
ガードを HMX 限定にした今、K=7 の分解は HTP で走れる）。理由は「LM が遅く・壊れているので、
codec だけ速くしても全体が良くならない」という点。

優先するなら次の順序になる:

1. **HVX のみで NaN が出る原因の特定** — これが最優先。HMX を切っても 4 回中 4 回 NaN なので、
   HMX 固有の問題ではない。`GGML_HEXAGON_NDEV=1` でも再現するのでレースでもない。
   `max_seconds` を 1〜4 に振ると出方が変わる（1 と 3 は完走、2 と 4 は NaN）ので、
   特定の shape か状態遷移に依存していそう。
2. **出力の再現性の確認** — 同じ入力・同じ seed で同じ結果になるか。現状は毎回変わる。
3. **M=1 の逐次処理で NPU が勝てるかの見極め** — 現状のデータでは 25% 負けている。
   HMX のタイルを埋めるには M を大きくする必要があり、haru の会話は 1 フレーム = 1 トークン
   なので構造的に不利。プロンプト（`dialogue.rows`、M が大きい）だけでも速くなるかは要測定。

**現時点の結論: HaruVoice は CPU 実行のままが正しい。** NPU は「到達できる」が
「速くならない・正しくない」ので、配布物に載せる理由がない。
