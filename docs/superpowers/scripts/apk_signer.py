"""抽取任意 APK 的 v2/v3 签名者证书 SHA-256 指纹（只读）。

用途：核对 nightly 构建产物的签名是否与预期指纹一致 —— 签名漂移 =
所有已装用户必须卸载重装（memory: signing-key-drift）。

为什么不用 `keytool -printcert -jarfile`：它只读 v1 签名，而我们 minSdk 26 的包
默认只有 v2/v3 签名块，于是它会说"不是签名的 jar"。这里按 APK Signing Block 结构解析。

用法：python docs/superpowers/scripts/apk_signer.py <apk> [<apk> ...]
"""
import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
IDS = ((0x7109871A, "v2"), (0xF05368C0, "v3"), (0x1B93AD61, "v3.1"))


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def analyze(path):
    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        print(f"{path}: 没有 EOCD")
        return
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    magic_abs = cd_off - 16
    print(f"--- {path}  ({len(data)} bytes) ---")
    if data[magic_abs:cd_off] != MAGIC:
        print("  无 v2/v3 签名块")
        return
    size2 = struct.unpack_from("<Q", data, magic_abs - 8)[0]
    pairs_start = magic_abs - 8 - (size2 - 24)
    blocks = {}
    off = pairs_start
    while off < magic_abs - 8:
        ln = struct.unpack_from("<Q", data, off)[0]
        blocks[u32(data, off + 8)] = data[off + 12: off + 8 + ln]
        off += 8 + ln
    print("  签名块:", ", ".join(f"0x{k:08x}({len(v)}B)" for k, v in blocks.items()))

    for bid, name in IDS:
        if bid not in blocks:
            continue
        val = blocks[bid]
        signers_len = u32(val, 0)
        p, idx = 4, 0
        while p < signers_len + 4:
            signer_len = u32(val, p)
            signer = val[p + 4: p + 4 + signer_len]
            sd = signer[4: 4 + u32(signer, 0)]
            q = 4 + u32(sd, 0)
            certs = sd[q + 4: q + 4 + u32(sd, q)]
            r = 0
            while r < len(certs):
                cl = u32(certs, r)
                der = certs[r + 4: r + 4 + cl]
                fp = ":".join(f"{b:02X}" for b in hashlib.sha256(der).digest())
                txt = "".join(chr(c) if 32 <= c < 127 else " " for c in der)
                marks = [w for w in ("NightlyDebug", "NightlyPre", "Nightly", "OU=CI", "CI") if w in txt]
                print(f"  [{name}] signer{idx} 证书 {cl}B  SHA-256 {fp}  可读标记 {marks}")
                r += 4 + cl
            p += 4 + signer_len
            idx += 1


for a in sys.argv[1:]:
    analyze(a)
