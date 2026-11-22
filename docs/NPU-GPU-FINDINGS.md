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

### 1.11 第4ラウンド: バグ (b) を修正、仮説 (a) は否定

#### §1 skel の系譜 — 問題なし

`libggml-htp-v81.so` は `htp-v81-prefix` の ExternalProject が 2026-11-21 にソースから
ビルドしたもので、fork HEAD の内容が入っている。prebuilt の古い skel を使い回してはいない。

#### §2 仮説 (a) は否定 — CONT + view は bit-exact

`htp-cont-test` を書いて、head の `part()` と同じ形状系列を両バックエンドで走らせた:

```
cont of view, [3 x 2560, 1] -> 3 x [2560, 1]:
  part 0 (offset 0)            bit-exact (2560 values)
  part 1 (offset 2560)         bit-exact (2560 values)
  part 2 (offset 5120)         bit-exact (2560 values)
cont of strided view, [2624 x 3] rows of 2560:
  strided row 0..2             bit-exact (2560 values)
ALL BIT-EXACT
```

CPU も HTP も同じ結果。**`CONT` の view オフセット処理にバグは無い。**
head の各 op 単体（`scale_bias` / `rms_norm` / `silu` / `mul_mat`）も両バックエンドで一致した。
head のグラフ全体（F16 重み、5 run）も HTP で有限・bit 一致。

**つまり head 単体では再現しない。** 再現には LM と head を混ぜた構成が要る。

#### §3 バグ (b) の正体 — CPU テンソルを hexagon のバッファとして読んでいた

`clone_buffer` のクラッシュを追うと、根は **`mmap_tensor()` が `t->buffer->context` を
無条件に `ggml_hexagon_shared_buffer *` にキャストしていた**ことだった。CPU バッファの
テンソルが来ると、CPU バックエンドのコンテキストを hexagon の構造体として読み、
`mem` に garbage が入って `clone_buffer()` で落ちる。

同じ無条件キャストが 4 箇所にあった:

| 箇所 | 症状 |
|---|---|
| `mmap_tensor()` | `clone_buffer()` で SIGSEGV |
| `fit_op()` の `fit_tensor` | `sbuf->fd()` で SIGSEGV |
| `add_tensor()` | `sbuf->base()` で SIGSEGV |
| `fit_op` の別経路 | `sbuf->fd()` で SIGSEGV |

修正は 4 つ:

1. **`mmap_tensor()`**: `ggml_backend_buffer_is_hexagon(t->buffer)` でなければ `nullptr` を返す
2. **`fit_op()` / 別経路**: CPU テンソルはバッチの容量を食わないので `continue`
3. **`add_tensor()`**: CPU テンソルが来たら `GGML_ABORT` でテンソル名を出す
   （黙ってゴミを読むより、どのテンソルが問題か分かる方がよい）
4. **`supports_op()`**: 入力に CPU バッファのテンソルがあれば `false`
   （`view_src` も辿る）

さらに **`ggml-backend.cpp` の scheduler** に 1 箇所:

5. 重みのバッファでバックエンドを決める経路が `supports_op()` を見ずに `return` していた。
   `ggml_backend_supports_op()` を通してから返すようにした。これで「重みは HTP だが
   別の入力が CPU」というノードが HTP に置かれなくなる。

そして **`Head` に CPU バックエンドを渡す**ようにした。`head.constants`（4 つのスカラーの
埋め込み）は head の重みを読むので、重みが CPU にあるなら CPU で計算する必要がある。
`Head(m, backend, cpu)` とし、`allocr_` と `compute()` をそのバックエンドに向けた。

#### 結果: SIGSEGV は消えた

`SPEECH_HEAD_CPU=1`（head を CPU に置く構成）で 4 回走らせて、**SIGSEGV は 0 回**。
以前は 4/4 で落ちていた。バグ (b) は修正できた。

#### ただし NaN は残る（2/6）

head を HTP に戻した既定構成で 6 回走らせると **2/6 で `latent` が NaN**。
`SPEECH_HEAD_TRACE` で追うと、head の `x`（ブロックを回る潜在変数）が NaN になり、
`cond` と `ada` は正常。head を CPU に置いても同じ `x` が NaN になるので、
**head の計算そのものではなく、その入力（`noise` か `hidden`）が壊れている**可能性が高い。

`noise` は `g.input()` で作られ、scheduler がバックエンドを決める。head が CPU にあると
`noise` も CPU に置かれ、`latent_proj`（HTP の重み）と衝突する構図が疑わしい。

#### 第4ラウンドの判定

| 項目 | 状態 |
|---|---|
| skel 系譜 | 問題なし（ソースから再ビルド済み） |
| 仮説 (a) CONT+view | **否定**（bit-exact） |
| バグ (b) SIGSEGV | **修正済み**（4 箇所のガード + scheduler + Head の CPU バックエンド） |
| NaN | **残存**（2/6）。入力テンソルのバックエンド跨ぎが疑わしい |
| M=1 速度判定 | 未到達（NaN が消えてから） |

**次の一手**: `noise` と `hidden` のどちらが壊れるかを、head の入力時点で読んで確定させる。
`g.input()` で作ったテンソルが scheduler でどのバックエンドに置かれるかを追えばよい。

### 1.12 第5ラウンド: 測定の誤りを発見（重要）

#### 4点フックの実装

`SPEECH_HEAD_TRACE` を拡張し、フレームごとに `noise` / `hidden` / head の `x` / `ada` / `velocity` /
`out` の有限性と FNV-1a hash、および**最初の非有限 index** を出すようにした。

#### 最初の測定結果（誤り）

```
run1: noise hash 86bd444b2bf94938, hidden b9736ad0b6699ba0, x ok
run2: noise hash 86bd444b2bf94938, hidden 8bafd005a68d18e5, x ok
run3: noise hash 5a07a9cc850895cb, hidden 87d0c870eaf90a2d, x NAN first=0
```

CPU では 3/3 で `noise` も `hidden` も同一 hash（完全に決定的）。HTP では `noise` が 5 回中 2 回、
`hidden` が 5/5 で違う値になった。**「noise の読み出しが壊れている」と判断しかけた。**

#### しかしこの測定は無効だった

`GGML_HEXAGON_VERBOSE=1` のログを取って、テンソルの配置を確認した:

```
init-tensor HTP0#leaf_513#0 : base 0x772f000000 data 0x772f000000 nbytes 256   ← noise
init-tensor node_1895       : base 0x772f000000 data 0x772f000180 nbytes 256
init-tensor node_1896       : base 0x772f000000 data 0x772f000000 nbytes 256   ← head の出力
```

**`noise`（`leaf_513`）と head の出力（`node_1896`）が同じアドレス `0x772f000000` を共有している。**
ggml のアロケータは入力と出力の寿命が重ならないので同じメモリを再利用する。

私の測定は `Graph::read(noise_t)` を **compute の後**に呼んでいたので、
**head の出力を読んでいた**。`noise` の hash が run ごとに変わったのは、出力が変わったからで、
`noise` の読み出しが壊れていたわけではなかった。

#### 修正した測定

`noise` はホスト側の `std::vector<float>` を compute の前に hash するように変えた
（`noise_hash` を compute 前に計算し、トレースではそれを表示する）。

#### この誤りから得られた確かな知見

1. **`hidden` は HTP で 5/5 非決定的**（CPU は 3/3 決定的）。これは測定方法に依存しない事実で、
   LM 側に非決定性があることを示す。第2ラウンドで観測した「run 間でテキストが違う」と整合する。
2. **`x` の NaN は `first=0`**（先頭要素）。周期性のない先頭ヒットは、未初期化読み出しよりも
   「計算結果が壊れている」寄りだが、`first=0` だけでは決められない。
3. **入力と出力が同じメモリを共有する**という事実は、今後テンソルを読むときの一般則として重要。
   compute 後に「入力」を読むと出力が返る。

#### 第5ラウンドの現状

| 項目 | 状態 |
|---|---|
| 4点フック | 実装済み（`noise` はホスト側で hash） |
| `noise` の読み出し | **測定誤りだった**（出力を読んでいた）。修正済み |
| `hidden` の非決定性 | **HTP で 5/5 非決定的**（CPU は決定的）。実在する問題 |
| `x` の NaN | `first=0`。原因未確定 |
| 再測定 | デバイスが offline になり中断。次回再開 |

### 1.13 第5ラウンド（続き）: 根本原因は LM の非決定性と確定

#### `noise` は完全に決定的だった

フレーム番号を出力に足して再測すると、`noise` の hash は**フレーム番号だけで決まる**ことが分かった:

```
frame 0  -> 3e780339facc7807
frame 26 -> cc2d890fc27ff7bc
frame 27 -> 353d1440d133ee71
frame 28 -> 5a07a9cc850895cb
frame 45 -> fbb08d252f3d27b6
frame 46 -> 817e14d0c3802415
frame 47 -> 86bd444b2bf94938
```

run 3 と run 4 は同じフレーム 45/46/47 で**同一の hash** を出した。RNG は正常。
以前「run 間で違う」と見えたのは、NaN で早期終了した run の**最終フレームが違った**ためで、
別々のフレームを比べていた。

#### head のブロック単位トレースで overflow を特定

`Head::build()` の各ブロック出力を trace に載せて 6 run 回した。5 run は完走、1 run で NaN。

完走した run の hash（run 1〜5）:

```
proj     21cf9594b9cf1582  ← 5 run すべて同一
blk0     1bb5b082ca98000a / d7b12c0487b0d19c / 75c57cccbf3fd0e4 / ...  ← 毎回違う
blk1     7f7ea75c17d48f02 / 18c80ba8c76eafd1 / ...                     ← 毎回違う
```

**`proj`（= `latent_proj(noise)`）だけが 5/5 で同一。** `noise` が決定的なので正しい。
`blk0` 以降が毎回違うのは、入力の `hidden` が毎回違うから。

NaN になった run 6 の内訳:

```
proj     ok
blk0     ok
blk1     NAN  first=0  [0]=0xff800000 [1]=0x7f800000 [2]=0xff800000 [3]=0x7f800000
blk2     NAN  first=0  [0]=0xffffffff [1]=0xffffffff [2]=0x7fffffff ...
blk3     NAN
tail0    ok   (cond 経路、独立)
tail1    ok   (ada、独立)
tail2    NAN  (velocity)
tail3    NAN  (out = noise + velocity)
```

`blk1` の先頭 4 要素が **-inf, +inf, -inf, +inf の交互**。これは飽和ではなく**発散**で、
ブロックが値を無限に増幅して float32 の範囲を超えたことを示す。
`blk2` 以降は `inf - inf` などで NaN に変わる。

#### 因果関係

```
LM (dialogue.rows) が HTP で非決定的
   ↓ hidden が run ごとに違う
head の入力が毎回違う
   ↓ ある run では blk1 が発散する領域に入る
x が inf → NaN
   ↓
latent が NaN → codec のデコードが NaN → API が停止
```

**head の overflow は結果であって原因ではない。** 根本原因は LM の非決定性。

#### なぜ LM が非決定的なのか（未解決）

`proj` が決定的で `hidden` が非決定的なので、非決定性は LM の計算の中にある。
候補:

1. **KV キャッシュの `CPY`** — `KeysValues::attend()` が `g.copy()` でキャッシュに書き込む。
   HTP の `CPY` が非同期で、完了を待たずに次の op が読む可能性。
2. **`GATED_DELTA_NET` の状態** — 状態テンソルを `CPY` で更新する箇所があり、同じ構図。
3. **opbatch の同期** — 第4ラウンドで `supports_op` にバッファ検査を足したが、
   `CPY` の完了順序は別問題。

第2ラウンドで観測した「run 間でテキストが違う」もこれで説明できる。

#### 第5ラウンドの結論

| 項目 | 状態 |
|---|---|
| `noise` | **決定的**（フレーム番号で決まる）。RNG は正常 |
| `proj` | **決定的**（5/5 同一 hash） |
| `hidden` | **非決定的**（5/5 で違う）。LM 側の問題 |
| `x` の NaN | `blk1` の発散。**LM 非決定性の結果** |
| 根本原因 | **LM の HTP 非決定性**（KV キャッシュの CPY 同期が最有力） |

**次の一手**: LM の first-divergence。同じ seed の 2 run で `dialogue.rows` の
node 出力 hash を比べ、最初に分岐する node を特定する。`CPY` が候補なので、
`KeysValues::attend()` の `g.copy()` の前後を重点的に見る。

### 1.14 第6ラウンド: Step 0 で同期修正の欠落を発見（port 完了、実機検証は中断）

#### Step 0: 同期系修正は haru 側ツリーに入っていなかった

llama.cpp 側で入れた `8d96593b6`（同期 set_tensor 3 経路の opbatch sync）の signature を
`ggml-hexagon.cpp` で探すと、**0 件**だった。`7df29a557` / `e30c6b7cd` の signature も無い。

set_tensor の 3 経路を確認したところ、**非対称**が見つかった:

| 経路 | `flush_sync()` |
|---|---|
| `get_tensor_async` | **あり** |
| `get_tensor_2d_async` | **あり** |
| `set_tensor_async` | **なし** ← 欠落 |
| `set_tensor_2d_async` | **なし** ← 欠落 |
| `host_buffer_set_tensor` | 不要（ホストメモリへの memcpy のみ） |

`get` 側だけがキューを drain していて、`set` 側は drain していない。
これはまさに `8d96593b6` が直したものと同型で、**本ラウンドの候補①（KV キャッシュの CPY）と
同じ構図**である。

#### 何が起きていたか

`set_tensor_async` はホスト側のマップ済みバッファに `memcpy` する。DSP 側のバッチが
まだ走っている間に書くと、**走行中のバッチが新しいバイト列を読む**。会話の KV キャッシュや
Gated DeltaNet の状態は「前のバッチが読み、次のバッチが書く」テンソルなので、
ここで混ざると run ごとに違う値になる。第5ラウンドで観測した `hidden` の非決定性と整合する。

#### 修正（port）

`set_tensor_async` と `set_tensor_2d_async` の先頭に `sess->flush_sync()` と
エラーチェックを足した（`get` 側と同じ形）。これで書き込みは走行中のバッチが終わってから
行われる。

#### 実機検証は中断（デバイス側の障害）

修正をビルドして実機で 5 run 測ろうとしたところ、**`/sdcard` の FUSE が deadlock** した。

```
PID 470  STAT D  speech-b
wchan: d_alloc_parallel
syscall: 56 (openat) 0xffffff9c ...
```

`openat` で `D`（uninterruptible sleep）状態のまま固着し、`kill -9` も効かない。
`/data` は応答するが `/sdcard` への `ls` が返らない。モデルは `/sdcard` 上にあるので、
以降の測定が一切できない。**デバイスの再起動が必要**。

これは今回の修正が原因ではない（プロセスはモデルを開く前、`openat` の段階で固まっており、
`/proc/470/fd` にモデルの fd が無い）。FUSE デーモンとカーネルの dentry ロックの問題。

#### 第6ラウンドの現状

| 項目 | 状態 |
|---|---|
| Step 0（同期修正の有無） | **欠落を発見**。`set_tensor_async` / `set_tensor_2d_async` に `flush_sync()` が無い |
| port | **完了**（`get` 側と同じ形にした） |
| 実機検証 | **中断**。`/sdcard` の FUSE deadlock でデバイス再起動待ち |
| first-divergence | 未実施（Step 0 で原因候補が絞れたため優先度は下がった） |
| barrier 実験 3 種 | 未実施 |
| NaN ゲート 10 run | 未実施 |

**次の一手**: デバイスを再起動してから、修正版で `hidden` の 5 run 安定性を測る。
安定すれば Step 0 の port で決着。安定しなければ first-divergence に進む。

### 1.15 第6ラウンド（続き）: flush 修正は効かず、KV レース説も否定

#### デバイス復旧

`/sdcard` の FUSE deadlock は `reboot` で解消した（`/system/bin/reboot` は
`D` 状態のプロセスがいても通り、再起動後に `/sdcard` が応答するようになった）。

#### flush 修正の効果: 無し

`set_tensor_async` / `set_tensor_2d_async` に `flush_sync()` を足した版で
`hidden` の hash を 5 run 測ると、**5/5 で違うまま**だった:

```
93bc007469b48577, 7c91c6063d533fbc, ad6aa241914bcee9, c4e5de11e9d54504, 070de5698d9ccb72
```

（うち 2 run は `latent` が NaN）

修正自体は正しい（`get` 側と対称になった）が、**非決定性の原因ではなかった**。

#### 決定的な切り分け: frame 0 で既に非決定的

`--max-seconds 0.2`（1 フレームだけ）で回すと、**最初のフレームから** hash が違う:

```
proj   : 3ef7a79c191df284 x4   ← 4/4 同一
hidden : f1a1c338e29aa708, a6e92f8edeac3ca8, cb445f91f7c61c56, bd2c5c44a205369e
```

`proj`（= `latent_proj(noise)`）は決定的なのに、`hidden`（LM の出力）は frame 0 で既に違う。

**frame 0 には壊れるべき過去の KV キャッシュが無い。** 会話はプロンプト
（`dialogue.rows`）→ frame 0 の順に走るので、非決定性はプロンプトのパスか
最初のステップにある。**KV キャッシュの write/read レース説は否定された。**

#### マルチセッション説も否定

`GGML_HEXAGON_NDEV=1`（単一セッション）でも frame 0 の `hidden` は 4/4 で違った:

```
f9ec687d6e0f4019, 69bfbd037ff1c025, e15f023c1e5221d0, aa158d399599beac
```

複数セッションへの分割が原因ではない。

#### CPU 参照を確定（重要）

CPU で同じ条件（`--max-seconds 0.2`、seed 1）を 3 run 回すと、**3/3 で完全に同一**:

```
CPU hidden : da9f16819412530a x3
```

**HTP の 4 run はいずれも CPU の値と一致しない。** つまり HTP は
「非決定的である」だけでなく「CPU と違う値になる」。正しさのゲートとして
CPU 参照が使えるようになった。

#### 何が残っているか

| 説 | 判定 |
|---|---|
| KV キャッシュの CPY レース | **否定**（frame 0 で既に違う） |
| `set_tensor` の flush 欠落 | **修正したが効果なし**（別の原因） |
| マルチセッション分割 | **否定**（NDEV=1 でも違う） |
| LM グラフ自体の非決定性 | **残存**。プロンプト（`dialogue.rows`、1786 node）が最有力 |

#### 第6ラウンドの結論

| 項目 | 状態 |
|---|---|
| Step 0 | **完了**。`set_tensor` の flush 欠落を発見・port（正しい修正だが原因ではなかった） |
| KV レース説 | **否定** |
| マルチセッション説 | **否定** |
| CPU 参照 | **確定**（`da9f16819412530a`、3/3 同一） |
| 非決定性の所在 | **LM グラフ内**。プロンプトのパスが最有力 |
| NaN ゲート | 未達（5 run 中 2 run で NaN） |

**次の一手**: `dialogue.rows`（プロンプト）の first-divergence。1786 node の
出力 hash を 2 run で比べ、最初に分岐する node を特定する。frame 0 で既に違うので、
**プロンプトの 1 回の実行の中で**分岐が起きている。`GATED_DELTA_NET` の状態初期化か、
HMX/HVX カーネルのリダクション順序が候補。

### 1.16 第7ラウンド: first-divergence で KV キャッシュへの CPY に到達

#### Step 2: KV キャッシュの zero-fill は効かない

`KeysValues` のバッファは `ggml_backend_alloc_ctx_tensors()` で確保され、
**ゼロ初期化されていなかった**（`Qwen35Decoder::States` は `start()` で
`ggml_backend_buffer_clear()` していたが、KV キャッシュはしていなかった）。
そこで確保直後に `ggml_backend_buffer_clear(buffer_, 0)` を足して測ると、
frame 0 の `hidden` は **4/4 で違うまま**だった:

```
c3114196c44ddc41, 8372b5204c4f5cfb, eb638401f2f496c8, 64e07bc1f30f963f
```

**未初期化読み出しではない。** この zero-fill は正しい修正として残す
（読まれない領域が不定値であるより 0 である方が、不具合が出たときに再現しやすい）。

#### Step 1: first-divergence — ノード単位の hash 比較

`Graph::compute()` に `SPEECH_NODE_HASH` のフックを足し、グラフを計算した後に
**全ノードの出力を FNV-1a で hash** して出すようにした（`print_node_hashes()`）。
scheduler 経由の `compute(sched)` にも足した（LM はこちらを通るため）。

同 seed 2 run の比較:

| stage | nodes | 不一致 |
|---|---|---|
| `dialogue.embed` | 17 | **0** |
| `head.constants` | 12 | **0** |
| `codec.encode` | 582 | **0** |
| `sched`（= `dialogue.rows`、LM） | 1475 | **1475（全部）** |
| `codec.decode` | 581 | 581（全部） |

**`dialogue.embed` の出力は 2 run で完全に同一**（`4c69ad7e58460a31`）。
`codec.encode` も完全一致。**`sched` だけが最初のノードから違う。**

```
sched [0] MUL_MAT node_0  A=e138c7b17aa8c568 B=1a96d12af55727b5
sched [1] MUL_MAT node_1  A=db300134c6b59325 B=e28372287fd92d57
...
```

**入力（埋め込み）は同一なのに、LM の最初の演算から違う。**
`codec.decode` が全部違うのは、その入力の latent が LM の出力だから（下流）。

#### 原因候補: KV キャッシュへの CPY

`GGML_HEXAGON_VERBOSE=1` のログを取ると、`leaf_0` という名前のテンソルが
**2 種類**あることが分かった:

```
init-tensor HTP0#leaf_0#0 : base 0x7157a00000 nbytes 10240   ← グラフ入力（埋め込み）
init-tensor leaf_0 (copy of (view)) : base 0x71f3200000 data 0x71f3400000 nbytes 98304
```

後者は `CPY` の出力で、**48 回**同じアドレスに再初期化される:

```
execute-op CPY| (view) x leaf_0 -> leaf_0 (copy of (view))
  src: 3:8192  nb 4:16!   (nb[0]=4, nb[1]=16)
  dst: 3:8192  nb 4:12    (nb[0]=4, nb[1]=12)
```

これは `KeysValues::attend()` の `g.copy()` — **新しい鍵を KV キャッシュに書く**箇所で、
src は `ggml_permute()` の view（非連続）、dst はキャッシュの `ggml_view_3d()`（非連続）。

#### カーネル選択の追跡

`ggml_hexagon_precompute_cpy_params()` の判定を追うと:

1. `transposed` の判定で `src0->nb[1] (16) < ne[0]*4 (32768)` が真 → `sameshape = false`
2. `src_is_contiguous = false`、`dst_is_contiguous = false` → `1D_CONTIG` にならない
3. `GATHER_ROWS` は `ne[0] <= 32` が条件だが `ne[0]=8192` → 該当せず
4. `sameshape` が false なので `SAMESHAPE_SAMETYPE` を飛ばす
5. → **`HTP_COPY_KERNEL_RESHAPE` に落ちる**

#### ただし DSP 側の RESHAPE は stride を扱える

`cpy-ops.c` の `DEFINE_CPY_RESHAPE` を読むと、`nb00`/`nb0` を使って
`dma_cpy_push_2d_chunked()` に切り替える実装があり、**非連続でも動くはず**である:

```c
const bool rows_contig = (nb00 == ELEM_SIZE) && (nb0 == ELEM_SIZE);
if (rows_contig) dma_cpy_sametype_reshape_contig(...);
else             dma_cpy_push_2d_chunked(dma_q, dst_addr, src0_addr, nb0, nb00, ELEM_SIZE, run);
```

ホスト側も `h.nb[0..3] = t->nb[0..3]` で正しく渡しており、`same_shape()` も
`nb` を比較しているので dedup の衝突も無い。

**したがって「RESHAPE が選ばれていること」自体は誤りではない。**
バグは `dma_cpy_push_2d_chunked()` の側、または `run` の計算
（`MIN(MIN(ne00 - i00, ne0 - i10), th_end - e)`）にある可能性が高い。

#### 試した修正とその結果

「非連続なら `supports_op` で断る」修正を試したが、**CPY の出力が HTP 上に
事前確保された KV キャッシュなので scheduler が動かせず**、こうなった:

```
pre-allocated tensor ((view) (copy of (reshaped) (permuted))) in a buffer (HTP0)
that cannot run the operation (CPY)
```

KV キャッシュは HTP に置かれたままなので、この CPY は HTP で走るしかない。
**修正は DSP 側の `dma_cpy_push_2d_chunked()` に入れる必要がある。** この修正は撤回した。

#### 第7ラウンドの結論

| 項目 | 状態 |
|---|---|
| KV zero-fill | **効果なし**（未初期化読み出しではない）。修正は残す |
| first-divergence | **`sched` の node 0 から**。`dialogue.embed` は完全一致 |
| 分岐位置 | 2 run で node 0 から全部。**固定**（timing 依存の移動ではない） |
| 原因候補 | **KV キャッシュへの CPY**（`nb 4:16!` → `4:12`、48 回） |
| カーネル | `HTP_COPY_KERNEL_RESHAPE`。DSP 側は stride 対応の実装がある |
| 次の一手 | `dma_cpy_push_2d_chunked()` の実装を読む（`run` の計算と chunk 分割） |

**分岐位置が固定**（毎回 node 0 から）なので、timing 依存のレースではなく
**決定的な値の誤り**である。KV キャッシュに書かれる鍵の値が毎回違うのは、
`dma_cpy_push_2d_chunked()` が stride を誤って解釈し、
**バッファの不定な領域を読んでいる**ためと考えられる。

### 1.17 第8ラウンド: shape の向きを訂正し、CPY が入口と確定

#### 訂正1: shape の向きを読み違えていた

第7ラウンドで「`3:8192` は ne[0]=8192」と書いたが、これは**誤り**だった。
`htp_opformat::format_tensor_dims()` は `ne[0]:ne[1]` の順に出す:

```cpp
return snprintf(str, max_size, "%d:%d", (int) t->ne[0], (int) t->ne[1]);
```

したがって `3:8192` は **ne[0]=3, ne[1]=8192** である。この読み方なら全て整合する:

- `ne[0]=3, nb[0]=4` → 行は 12 バイト
- `dst nb[1]=12` → **ちょうど 12 = 3×4 なので dst は連続**
- `src nb[1]=16` → 16 > 12 なので **src だけが 4 バイトの padding を持つ**

第7ラウンドの「ne[0]=8192 なのに nb[1]=16」という矛盾は、単なる読み違いだった。

#### 訂正2: `3:8192` は KV キャッシュではなく GDN の conv state

`3 × 8192 × 4 = 98304` バイト。haru の次元と突き合わせると:

```
channels = 2 × linear_num_key_heads × linear_key_head_dim + linear_num_value_heads × linear_value_head_dim
         = 2 × 16 × 128 + 32 × 128 = 4096 + 4096 = 8192
conv state = [conv_width - 1, channels] = [3, 8192]
```

**GDN の conv state だった。** 該当コードは `qwen35-decoder.cpp`:

```cpp
ggml_tensor * rows = ggml_concat(c, conv_state, ggml_cont(c, ggml_transpose(c, qkv)), 0);
g.copy(ggml_view_2d(c, rows, conv_width_ - 1, channels, rows->nb[1], (size_t) n * rows->nb[0]), conv_state);
```

`rows` は `[4, 8192]`（conv_state 3 行 + 新しい 1 行）で連続、`nb[1]=16`。
その先頭 3 行を `conv_state`（`nb[1]=12`）へ写す。**src だけ padding を持つ**構図。

#### Step 0 §1.2: CPY 出力の初めての直接比較 — 不一致

`KeysValues::hash()` を足し、プロンプト実行直後にキャッシュ全体を host へ pull して
FNV-1a で hash した。同 seed 2 run:

```
run 1: kv-hash rows 0..1 cache 7679b3bfe935c4cc
run 2: kv-hash rows 0..1 cache b70fc8f0d5f59dff
```

**不一致。** これまで「CPY が入口」は状況証拠だったが、**CPY が書いた中身が
run ごとに違うことが直接確認できた**。`noise` は両 run で同一
（`3e780339facc7807`）なので、入力側は決定的である。

#### Step 0 §1.1: 4 つの CPY の完全 shape

`SPEECH_CPY_DUMP` を足して、`precompute_cpy_params()` が受け取る
src/dst の ne/nb/data を素の printf で出した。プロンプト 1 回で 4 種類:

| # | src ne / nb | dst ne / nb | 何か |
|---|---|---|---|
| A | `[3,8192]` nb `[4,16,131072,131072]` | `[3,8192]` nb `[4,12,98304,98304]` | GDN conv state |
| B | `[128,128,32]` nb `[4,512,65536,2097152]` | 同左（完全一致） | GDN delta state |
| C | `[256,1,4]` nb `[4,4096,1024,4096]` | `[256,1,4]` nb `[4,1024,2048,8192]` | KV キャッシュ k |
| D | `[1,256,4]` nb `[4096,4,1024,4096]` | `[1,256,4]` nb `[4,8,2048,8192]` | KV キャッシュ v |

（`src data=0x0` は `supports_op` の時点でまだ割り当てられていないため。異常ではない。）

**A と B が GDN、C と D が KV キャッシュ。** 48 回の CPY はこの 4 種類の繰り返し。

#### 静的解析: 4 つとも計算は正しく見える

- **A**: `dma_cpy_sametype_sameshape_range` が `contiguous_outer` 経路に入り、
  `dma_cpy_push_2d_chunked(dst, src, nb1=12, nb01=16, row_size=12, nrows=8192)`。
  12 バイトを stride 16 から stride 12 へ 8192 行。**正しい**
- **B**: src と dst の nb が完全一致なので `1D_CONTIG`。**正しい**
- **C**: `ne00=256, nb00=4, nb0=4` → `run=256`、stride 4 == elem 4 なので連続 1024 バイト。**正しい**
- **D**: `ne00=1` なので `run=1`、1 要素 4 バイトを stride 4096 から stride 4 へ。**正しい**

`DEFINE_CPY_RESHAPE` の `dma_cpy_push_2d_chunked(..., ELEM_SIZE, run)` は
`row_size` が `ELEM_SIZE` 固定だが、`run > 1` のときは stride == elem_size なので
結果的に連続コピーになり正しい。**この 4 形状では発火しない。**

#### 第8ラウンドの現状

| 項目 | 状態 |
|---|---|
| shape の向き | **訂正済み**（`ne[0]:ne[1]` の順） |
| `3:8192` の正体 | **GDN conv state**（KV キャッシュではない） |
| CPY 出力の直接比較 | **不一致**（2 run で違う）。CPY が入口と確定 |
| 4 つの CPY の shape | 取得済み（A/B が GDN、C/D が KV） |
| 静的解析 | 4 つとも計算は正しい。**実測 replay が必要** |
| 次の一手 | 実測スナップショットからの replay（§2）。特に A の `contiguous_outer` 経路 |

**静的解析でバグが見つからない**ので、次は §2 の replay で実測するしかない。
`dma_cpy_push_2d_chunked` の `dma_ring_push_single_2d` が
`src_stride`/`dst_stride` を **16bit フィールド**に書く一方、
`DMA_MAX_2D_STRIDE` は v81 で 24bit を許している点は残る穴
（今回の形状では stride 16/12 で収まるが、他の形状で切り捨てが起きうる）。

### 1.18 第9ラウンド: state 別 hash で入口を絞り、GDN は無罪と確定

#### Step 0: state 別の hash 分離

`KeysValues::layer_hashes()` と `Qwen35Decoder::state_hashes()` を足し、
プロンプト直後に **k[0..7] / v[0..7] / delta[0..23] / conv[0..18]** を層別に hash した。
同 seed 2 run の比較:

```
60 キー中 59 が不一致
一致したのは conv[0] だけ（48cb3b0c19948e6c）
```

内訳: `cache` 1、`k` 8、`v` 8、`delta` 24、`conv` 18 がすべて不一致。

#### conv[0] だけが一致する理由

`build_rows` は層 0 から順に走る。層 0 は `linear_attention` なので `delta_mixer(0)` が最初:

```cpp
qkv  = mul_mat(ssm_in_qkv, x)                      // x = 埋め込み（決定的）
rows = concat(conv_state[0]=zeros, qkv^T)          // 決定的
g.copy(view_2d(rows, 3, channels, ...), conv_state) // CPY A
```

**`conv[0]` は GDN を一度も通らずに書かれる唯一の state** なので決定的。
一方 `delta[0]` は `ggml_gated_delta_net()` の出力から書かれるので、
そこが違えば `delta[0]` も違う。以降は層ごとに前の層の出力を入力にするため、
**腐敗が全層へ伝播する**。

#### NDEV=1 でも直らない

`GGML_HEXAGON_NDEV=1`（単一セッション）で 2 run 測ると:

```
conv[0] : 48cb3b0c19948e6c  (4 run すべて同一)
delta[0]: 0a6349ee5bdc93d9 / b41cd7378976e58f  (ndev=1)
          6125167e93b42b54 / e3eadd2424d4b410  (ndev=2)
```

**マルチセッション分割は原因ではない。**

#### GDN 単体 replay: 決定的かつ CPU と一致 = 無罪

`htp-gdn-test` を書いて、haru と同じ形状（`S_v=128, H=32, n_tokens=1, n_seqs=1, K=1`）で
`ggml_gated_delta_net()` だけを 5 回ずつ実行した:

```
CPU : 6ab79791dc3ec0b2 x5   DETERMINISTIC
HTP : 46a0a77473289fe8 x5   DETERMINISTIC
```

両バックエンドとも**完全に決定的**。hash は違うが、要素ごとに比べると:

| 要素 | CPU | HTP | 相対差 |
|---|---|---|---|
| [0] | +0.0804120675 | +0.0804120898 | 2.8e-07 |
| [1] | -0.0594192371 | -0.0594192222 | 2.5e-07 |
| [3] | -0.0883070678 | -0.0883070678 | 0 |
| rms | 0.259030466 | 0.259030457 | 3.5e-08 |
| max | 0.956314981 | 0.956315041 | 6e-08 |

**差はすべて 1e-8 相対オーダー = f32 の累積順序差。**
GDN は HTP で**正しく**、しかも**決定的**である。**GDN は無罪。**

#### 第9ラウンドの結論

| 項目 | 状態 |
|---|---|
| state 別 hash | 実装済み。59/60 が不一致、`conv[0]` だけ一致 |
| `conv[0]` が一致する理由 | **GDN を通らない唯一の state** |
| マルチセッション説 | **否定**（NDEV=1 でも不一致） |
| GDN 単体 | **決定的かつ CPU と 1e-8 で一致 = 無罪** |
| 非決定性の所在 | **GDN の周辺**（opbatch の順序、CPY と GDN の競合） |

**GDN カーネル自体は正しく決定的**なので、残る容疑は
「`state` を読む GDN と、その直後に同じ `state` へ書く CPY が
同じ opbatch 内で競合する」構図である。`delta_mixer` は

```cpp
out = ggml_gated_delta_net(..., state, 1);   // state を読む
g.copy(view_3d(out, ...), state);            // 同じ state に書く
```

の順で、CPY が非同期に走ると次の層の GDN が書きかけの state を読みうる。
第6ラウンドで `set_tensor` に入れた `flush_sync()` はホスト側の書き込みだけを
守っており、**opbatch 内の CPY→GDN の依存は守っていない**。

#### 次の一手

`delta_mixer` の `g.copy` の直後に明示的な同期を入れる（`GGML_HEXAGON_CPY_SAFE=1`
のようなフラグ付きで）か、opbatch のバッチ境界を CPY の後で切る。
それで `delta[0]` が決定的になれば因果が確定する。

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
