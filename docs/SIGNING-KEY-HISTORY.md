# 署名鍵の履歴と復元失敗の記録

## 結論

**2026-11-20 に署名鍵を再生成した（Policy B）。** 旧鍵は復元不可能と判定した。

- 新鍵: `signing/HaruVoice-signing-key.jks.b64`（3504 文字 / DER 2628 bytes）
- 証明書 SHA-256: `545885c1e7d9de52c8162a80b79ecbd1514ebc41795d0ce0de82948c028ec681`
- b64 の sha256: `3dd0c3464320040109d0a1521c371d2d189dac814bdd5bb2913d7207acd62de2`
- alias `haru` / store・key パスワード `haruvoice2026` / `CN=HaruVoice, O=yuubinnkyoku, C=JP`

0.2.2 は一度も配布していなかったため、実害はない。

## 旧鍵の破損内容（実測）

ハンドオフ文書には「欠落は 4 DER bytes、暗号文 912 bytes、salt/IV/iterations は無傷」と書かれていたが、
実際に DER を解析した結果は**まったく違っていた**。以下は実測値。

| 項目 | ハンドオフの記述 | 実測 |
|---|---|---|
| base64 長 | 3504 文字（正） / 3496 文字（破損） | 3496 文字 |
| DER 長 | 2628 bytes（正） / 2622 bytes（破損） | 2622 bytes |
| 欠落バイト数 | 4 bytes | **6 bytes** |
| 破損位置 | 暗号文領域の中 | **暗号文の後ろ（証明書バッグ領域）** |
| 鍵バッグ暗号文 | 912 bytes | **1232 bytes**（無傷） |
| 証明書バッグ暗号文 | 言及なし | **912 bytes**（こちらが本来の 912） |

### 何が生きていたか

1. **鍵バッグ（暗号文 1232 bytes）は完全に無傷だった。**
   - PBKDF2-HMAC-SHA256（salt `217e02a2616b99fa22c6eebd346b066de54d6eb7`, iter 10000, keyLen 32）で復号成功
   - 平文 = 有効な PKCS8 `PrivateKeyInfo`（1232 bytes）→ `RSAPrivateKey`（1192 bytes）
   - **p, q, e, d は数学的に整合**（p・q とも素数、e·d ≡ 1 mod λ、RSA ラウンドトリップ成功）

2. **証明書バッグも復号できた。**
   - base64 に位相ずれがあり、index 1983 で再同期させると `ContentInfo`（OID 1.2.840.113549.1.7.6）として正しく解釈できた
   - PBES2 で復号すると 912 bytes の X.509 証明書が得られた

### なぜ復元不可能と判定したか

- 保存された `n` ≠ `p × q`（17 バイト相違）
- `dp` ≠ d mod (p−1)、`dq` ≠ d mod (q−1)、`qinv` ≠ q⁻¹ mod p
- `dq` の長さが 125 bytes（本来 129）で DER 構造自体がずれている
- 証明書の modulus は `p × q` と 238 バイト相違 → **鍵と証明書が対応していない**
- 証明書は SPKI 以降が破損しており、X.509 として parse できない

Android の署名同一性を決めるのは**証明書の公開鍵**であり、秘密鍵そのものではない。
秘密鍵の p, q, e, d は揃っていたので数学的には鍵を再構成できたが、
**元の証明書が復元できない以上、同じ署名 identity は再現できない。**
新しい証明書を作ればそれは別 identity になる。よって復元は不可能と判断した。

## 再発防止

- base64 をコピーしたら**必ず sha256 を検証する**（`sha256sum` と `wc -c`）
- 破損検出は `keytool -list -keystore ... -storetype PKCS12` で即座に分かる
- 鍵はリポジトリだけでなく artifacts とローカルにもバックアップする
- 署名後は `apksigner verify --print-certs` で証明書 SHA-256 を確認し、README の値と一致するか見る
