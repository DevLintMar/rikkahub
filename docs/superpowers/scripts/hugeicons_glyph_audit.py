#!/usr/bin/env python3
"""huge-icons 字形完整性门禁 —— 防「名字存在但画不全」。

**背景**：`gradle/libs.versions.toml` 里 `huge-icons` 刻意钉在 `1.3`（上游已是 1.4，
1.4 改了整套字形观感，见 memory `huge-icons-pinned-1-3`）。但 `1.3` 这个 JitPack 产物
**是坏的**：它的生成器把**用 SVG 弧线（A 命令 / circle / ellipse）画出来的那段 path 整段丢掉**了。
实测：1.3 的 4688 个图标源码里含 `arcTo(` 的 0 个，1.4 是 499 个；1.4 用弧的图标里有 347 个
在 1.3 里少一段 path。

**症状**：图标在屏幕上只画出「剩下的一半」，而且**不会报错、CI 也全绿**。典型：`Globe02`
少外圆（偏好设置「网络」项只剩一条横线 + 一个竖梭形）、`StopCircle` 少外圈（停止朗读只剩
一个方块）、`AlertCircle` 少外圈。

**判据**（`--refresh` 时重算）：参考版本里有 `arcTo(` 的图标，若钉住版本里 **path 段数更少**，
就判为「少画了一段」。段数变少也可能是参考版本的重设计（如 `FolderClock` 在 1.3 里用 4 段
curveTo 画圆、1.4 拆成独立 path），这类留在 APPROVED 里当白名单。

**清单是提交进仓库的**（`docs/superpowers/data/hugeicons-<pinned>-suspect.json`），所以 CI
不需要联网、结果可复现；只有 `--refresh` 才去 JitPack 拉两个 sources jar。

用法：

    python3 docs/superpowers/scripts/hugeicons_glyph_audit.py            # 门禁（离线）
    python3 docs/superpowers/scripts/hugeicons_glyph_audit.py --refresh  # 重算清单（联网）
"""

from __future__ import annotations

import json
import re
import struct
import sys
import tempfile
import urllib.request
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
LIB_TOML = REPO / "gradle/libs.versions.toml"
DATA_DIR = REPO / "docs/superpowers/data"
JITPACK = "https://jitpack.io/com/github/rikkahub/hugeicons-compose/{v}/hugeicons-compose-{v}-sources.jar"
STROKE_DIR = "main/me/rerere/hugeicons/stroke"

# 参考版本：同一套图标、生成器正常的那一版（1.4 是上游在用的版本）。
REFERENCE_VERSION = "1.4"

# 假阳性白名单：清单里其实没缺段的图标。每加一条都要写明理由。
APPROVED = {
    "FolderClock": "1.3 用 4 段 curveTo 画了 Clock 的圆，没缺段；是 1.4 把圆拆成了 arcTo 的独立 path",
}

# 本地用 ForkIcons.* 补过的图标（app/.../ui/components/icons/ForkIcons.kt），别名 → 原名。
FORK_ALIASES = {
    "AlertCircle": "AlertCircle",
    "Database02": "Database02",
    "DatabaseRestore": "DatabaseRestore",
    "Globe02": "Globe02",
    "Image03": "Image03",
    "LanguageCircle": "LanguageCircle",
    "MusicNote03": "MusicNote03",
    "PaintBoard": "PaintBoard",
    "StopCircle": "StopCircle",
    "Video01": "Video01",
}


def pinned_version() -> str:
    text = LIB_TOML.read_text(encoding="utf-8")
    m = re.search(r'^huge-icons\s*=\s*"([^"]+)"', text, re.M)
    if not m:
        raise SystemExit("[!] 在 gradle/libs.versions.toml 里找不到 huge-icons 版本号")
    return m.group(1)


def manifest_path(pinned: str) -> Path:
    return DATA_DIR / f"hugeicons-{pinned}-suspect.json"


def read_zip(path: Path) -> dict:
    """自己走 local file header 解压。

    1.3 的 sources jar 里每个条目都重复了一次（9385 条 → 4696 个唯一文件），Python 的
    `zipfile` 会以 `BadZipFile: Overlapped entries` 拒读，所以不能用它。
    """
    data = path.read_bytes()
    entries = {}
    i = 0
    while True:
        i = data.find(b"PK\x03\x04", i)
        if i < 0:
            break
        method, = struct.unpack_from("<H", data, i + 8)
        csize, = struct.unpack_from("<I", data, i + 18)
        namelen, = struct.unpack_from("<H", data, i + 26)
        extralen, = struct.unpack_from("<H", data, i + 28)
        name = data[i + 30: i + 30 + namelen].decode("utf-8", "replace")
        start = i + 30 + namelen + extralen
        blob = data[start: start + csize]
        i = start + csize
        if method == 0:
            entries[name] = blob
        elif method == 8:
            try:
                entries[name] = zlib.decompress(blob, -15)
            except zlib.error:
                pass
    return entries


def fetch_jar(version: str) -> Path:
    dest = Path(tempfile.gettempdir()) / ("hugeicons-compose-%s-sources.jar" % version)
    if dest.is_file() and dest.stat().st_size > 1000000:
        print("  [cache] %s" % dest)
        return dest
    url = JITPACK.format(v=version)
    print("  [下载] %s" % url)
    with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as fp:
        fp.write(resp.read())
    return dest


def icon_paths(entries: dict, name: str):
    blob = entries.get("%s/%s.kt" % (STROKE_DIR, name))
    if blob is None:
        return None
    text = blob.decode("utf-8", "replace")
    out = []
    for block in text.split("path(")[1:]:
        body = block.split("}.build()")[0]
        if "{" in body:
            body = body.split("{", 1)[1]
        out.append(re.sub(r"\s+", "", body))
    return out


def suspect_icons(pinned: str) -> dict:
    ent_pinned = read_zip(fetch_jar(pinned))
    ent_ref = read_zip(fetch_jar(REFERENCE_VERSION))
    names = sorted(
        n.rsplit("/", 1)[-1][:-3]
        for n in ent_pinned
        if n.startswith(STROKE_DIR) and n.endswith(".kt")
    )
    suspect = []
    arc_total = 0
    for name in names:
        ref = ent_ref.get("%s/%s.kt" % (STROKE_DIR, name))
        if ref is None or b"arcTo(" not in ref:
            continue
        arc_total += 1
        mine, theirs = icon_paths(ent_pinned, name), icon_paths(ent_ref, name)
        if mine is not None and theirs is not None and len(mine) < len(theirs):
            suspect.append(name)
    return {
        "pinned": pinned,
        "reference": REFERENCE_VERSION,
        "rule": "参考版本含 arcTo( 、且钉住版本的 path 段数更少 → 钉住版本少画了一段",
        "icons": len(names),
        "reference_arc_icons": arc_total,
        "suspect": suspect,
    }


def used_icons():
    huge, fork = set(), set()
    for path in REPO.rglob("*.kt"):
        if "/build/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        huge.update(re.findall(r"HugeIcons\.([A-Za-z0-9_]+)", text))
        fork.update(re.findall(r"\bForkIcons\.([A-Za-z0-9_]+)", text))
    return huge, fork


def main() -> int:
    pinned = pinned_version()
    path = manifest_path(pinned)

    if "--refresh" in sys.argv[1:]:
        print("== 重算清单（pinned=%s，reference=%s）==" % (pinned, REFERENCE_VERSION))
        data = suspect_icons(pinned)
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        old = json.loads(path.read_text(encoding="utf-8"))["suspect"] if path.is_file() else []
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8", newline="\n")
        added = sorted(set(data["suspect"]) - set(old))
        removed = sorted(set(old) - set(data["suspect"]))
        print("  pinned 图标 %d 个；reference 里用弧的 %d 个" % (data["icons"], data["reference_arc_icons"]))
        print("  判为少画一段：%d 个 → %s" % (len(data["suspect"]), path.relative_to(REPO)))
        if old:
            print("  新增 %d：%s" % (len(added), added[:20]))
            print("  消失 %d：%s" % (len(removed), removed[:20]))
        return 0

    if not path.is_file():
        print("[!] 没有清单 %s；先跑一次 --refresh" % path.relative_to(REPO))
        return 1
    data = json.loads(path.read_text(encoding="utf-8"))
    if data["pinned"] != pinned:
        print(
            "[!] 清单是按 huge-icons=%s 生成的，而 libs.versions.toml 现在是 %s。\n"
            "    若是同步上游把版本带回了 %s：按 fork 决策改回 %s（见 memory huge-icons-pinned-1-3）；\n"
            "    若确实要换版本：先跑 --refresh 重算清单，并清理 app 里的 ForkIcons 补丁。"
            % (data["pinned"], pinned, pinned, data["pinned"])
        )
        return 1

    suspect = set(data["suspect"])
    huge, fork = used_icons()
    print("== huge-icons 字形完整性（pinned=%s，reference=%s）==" % (pinned, data["reference"]))
    print("  清单：%d 个图标在 %s 里少画了一段" % (len(suspect), pinned))
    print("  仓库在用：HugeIcons.* %d 个 / ForkIcons.* %d 个" % (len(huge), len(fork)))

    bad = sorted((huge & suspect) - set(APPROVED))
    if bad:
        print("\n[!] %d 个在用的图标在 %s 里画不全（屏幕上缺一半，且不报错、CI 全绿）：" % (len(bad), pinned))
        for name in bad:
            print("      %s" % name)
        print(
            "\n修法：在 app/src/main/java/me/rerere/rikkahub/ui/components/icons/ForkIcons.kt 里用\n"
            "      「参考版本的几何 + pinned 的描边风格」补一个同名图标，调用点改用 ForkIcons.*，\n"
            "      并把它加进本脚本的 FORK_ALIASES。"
        )
        return 1

    print("\n[ok] 在用的 HugeIcons.* 没有画不全的（%d 个假阳性已白名单：%s）"
          % (len(APPROVED), ", ".join(APPROVED)))
    stale = sorted({FORK_ALIASES.get(n, n) for n in fork} - suspect)
    if stale:
        print("      （注意：这些 ForkIcons 补丁在清单里已经不缺段了，可能是白补：%s）" % ", ".join(stale))
    return 0


if __name__ == "__main__":
    sys.exit(main())
