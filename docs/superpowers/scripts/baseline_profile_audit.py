#!/usr/bin/env python3
"""baselineProfiles 过期检测（B 类 B19 的守卫）。

`app/src/release/generated/baselineProfiles/{baseline,startup}-prof.txt` 是构建期由
Baseline Profile 生成器产出的 ART 规则，**不会随代码重构自动更新**：类被删掉之后规则
仍然留在文件里，只是被 ART 静默忽略 —— 于是「启动优化」在不知不觉中失效。本次上游同步
后的状态就是如此（`GenerationHandler`、`jlatexmath` 的规则指向的类已不存在，
而新流式链路最热的 `StreamChunk` 一条规则都没有）。

脚本做两件事：

1. 报告两份文件是否**完全相同**（baseline 与 startup 职责不同，逐字节一致通常意味着
   生成流程有问题，本仓当前就是如此）；
2. 把规则里出现的**自有类**与源码树对账，列出已经不存在于源码中的那些。

它只报告，不做修复：重新生成需要一台连着设备/模拟器的机器
（`./gradlew :app:generateReleaseBaselineProfile`；`app/baselineprofile/build.gradle.kts`
里写的是 `useConnectedDevices = true`），本机没有 Android 编译器。
**CI 上的做法**：`Android Instrumented` 工作流的 `baseline-profile` 任务
（`.github/workflows/android-instrumented.yml`，x86_64 模拟器 + `-PwithX86_64`）。

已知的过期项列在 [KNOWN_STALE] 里：`--strict` 只对**不在**该表中的过期项返回 1，
这样 nightly debug 的门禁不会因为一条已知欠账而整体失效，同时又拦得住新增的。

用法：

    python3 docs/superpowers/scripts/baseline_profile_audit.py            # 只报告
    python3 docs/superpowers/scripts/baseline_profile_audit.py --strict   # 有**新增**过期项时退出码 1
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
PROFILE_DIR = REPO / "app/src/release/generated/baselineProfiles"
PROFILES = ("baseline-prof.txt", "startup-prof.txt")
SOURCE_SUFFIXES = (".kt", ".java")

# 描述符里的类名：`Lcom/example/Foo;`
CLASS_RE = re.compile(r"L([A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)+);")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)

# 顶层类型声明。缩进的（嵌套类）匹配不到 —— 它们统一归并到最外层类再对账。
DECL_RE = re.compile(
    r"""^(?:@[\w.]+(?:\([^)]*\))?\s*)*
        (?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|annotation|
             value|expect|actual|inline|suspend|external|operator|infix|tailrec|vararg|
             noinline|crossinline|reified|fun)\s+)*
        (?:class|interface|object|typealias)\s+([A-Za-z_]\w*)""",
    re.VERBOSE | re.M,
)

# 生成期合成出来、源码里永远没有对应文件的名字：
#   ComposableSingletons —— Compose 编译器生成的 lambda 单例
#   R                    —— AGP 生成的资源类
#   *_Impl                —— Room / KSP / Hilt 生成的实现类
SYNTHETIC_NAMES = ("ComposableSingletons", "R")
SYNTHETIC_SUFFIXES = ("_Impl",)

# 已知过期项：这些规则指向的类在上游合并后搬了包或已被删除，ART 会静默忽略它们 ——
# 真正的修法是**重新生成**，而那需要一台 API 33+ 的设备/模拟器（见下方 USAGE）。
# 在重新生成之前列在这里，好让 `--strict` 仍然对**新增**的过期项生效（门禁不能因为
# 一条已知欠账就整体失效）。修完任何一条都要从这张表里删掉。
KNOWN_STALE = {
    "me/rerere/ai/provider/providers/ClaudeProvider": "provider 已拆进 providers/claude/ 子包",
    "me/rerere/ai/provider/providers/GoogleProvider": "provider 已拆进 providers/google/ 子包",
    "me/rerere/ai/provider/providers/OpenAIProvider": "provider 已拆进 providers/openai/ 子包",
    "me/rerere/rikkahub/data/ai/GenerationHandler": "已被上游 GenerationLoop + ChatToolFactory 取代",
    "me/rerere/rikkahub/data/ai/tools/LocalToolOption": "已搬进 tools/local/ 子包",
    "me/rerere/rikkahub/data/ai/tools/LocalTools": "已搬进 tools/local/ 子包",
    "me/rerere/rikkahub/data/api/SponsorAPI": "fork 已移除赞助商功能",
}

REGENERATE_HINT = (
    "重新生成：Actions → Android Instrumented → Run workflow → task = baseline-profile\n"
    "（本机没有 Android 编译器；该任务在 x86_64 模拟器上跑 :app:generateReleaseBaselineProfile，\n"
    " 产物作为 baseline-profiles artifact 上传，人工确认后再提交。）"
)


def scan_sources() -> tuple[set[str], set[str]]:
    """返回 (源码里声明过的类全名集合, 源码里存在过的包路径集合)。"""
    declared: set[str] = set()
    packages: set[str] = set()

    for module in REPO.iterdir():
        if not module.is_dir() or not (module / "src").is_dir():
            continue
        for src_root in (module / "src").glob("*/java"):
            for file in src_root.rglob("*"):
                if src_root not in file.parents or file.suffix not in SOURCE_SUFFIXES:
                    continue
                try:
                    text = file.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue

                pkg_match = PACKAGE_RE.search(text)
                pkg = pkg_match.group(1).replace(".", "/") if pkg_match else ""
                if pkg:
                    packages.add(pkg)

                prefix = f"{pkg}/" if pkg else ""
                for name in DECL_RE.findall(text):
                    declared.add(f"{prefix}{name}")
                if file.suffix == ".kt":
                    # 顶层函数所在的文件门面类，例如 Message.kt → me/rerere/ai/ui/MessageKt
                    declared.add(f"{prefix}{file.stem}Kt")

    return declared, packages


def profile_classes(path: Path) -> set[str]:
    """规则里出现的**外部类**名（`Foo$Companion` 这类合成名归并到外层类）。"""
    names: set[str] = set()
    for raw in CLASS_RE.findall(path.read_text(encoding="utf-8", errors="replace")):
        outer = raw.split("$", 1)[0]
        if not outer:
            continue
        simple = outer.rsplit("/", 1)[-1]
        if simple in SYNTHETIC_NAMES or simple.endswith(SYNTHETIC_SUFFIXES):
            continue
        names.add(outer)
    return names


def main() -> int:
    strict = "--strict" in sys.argv[1:]

    missing = [name for name in PROFILES if not (PROFILE_DIR / name).is_file()]
    if missing:
        print(f"[!] 找不到 profile 文件：{', '.join(missing)}")
        return 1

    digests = {
        name: hashlib.sha256((PROFILE_DIR / name).read_bytes()).hexdigest()
        for name in PROFILES
    }
    print("== 文件指纹 ==")
    for name, digest in digests.items():
        print(f"  {name:20s} sha256={digest[:16]}…")
    identical = len(set(digests.values())) == 1
    print(
        f"  两份逐字节相同：{'是（正常：AGP 由同一次收集同时产出两者，2026-09-13 重新生成后仍然如此）' if identical else '否'}"
    )

    declared, packages = scan_sources()
    print(f"\n== 与源码树对账（声明类 {len(declared)} 个 / 包 {len(packages)} 个）==")

    stale_total: set[str] = set()
    for name in PROFILES:
        classes = profile_classes(PROFILE_DIR / name)
        # 只检查「所在包确实存在于本仓源码」的类；androidx / huge-icons 这类外部依赖的包
        # 不在 packages 里，天然被排除，不会产生假阳性。
        owned = {c for c in classes if c.rsplit("/", 1)[0] in packages}
        stale = sorted(c for c in owned if c not in declared)
        stale_total.update(stale)
        print(
            f"  {name:20s} 规则里外部类 {len(classes):5d} 个；落在本仓包内的 {len(owned):4d} 个，"
            f"其中源码里已不存在 {len(stale):3d} 个"
        )

    if not stale_total:
        print("\n[ok] 没有发现指向已删类的规则。")
        return 0

    known = sorted(stale_total & set(KNOWN_STALE))
    new = sorted(stale_total - set(KNOWN_STALE))

    if known:
        print(f"\n[!] {len(known)} 条**已知**过期规则（ART 静默忽略，启动优化在这些路径上失效）：")
        for name in known[:30]:
            print(f"      {name}  —— {KNOWN_STALE[name]}")
        print(f"\n  {REGENERATE_HINT}")

    if not new:
        print("\n[ok] 没有**新增**的过期项。")
        return 0

    print(f"\n[!] {len(new)} 条**新增**过期项（不在 KNOWN_STALE 里，最多列出 30 条）：")
    for name in new[:30]:
        print(f"      {name}")
    if len(new) > 30:
        print(f"      … 另有 {len(new) - 30} 条")
    print(
        "\n这些规则会被 ART 静默忽略（不会报错，只是优化失效）。修法有两种：\n"
        f"  A. {REGENERATE_HINT}\n"
        "  B. 如果是本次刚引入的、能确认无害，就加进 KNOWN_STALE 并写明理由。"
    )
    return 1 if strict else 0


if __name__ == "__main__":
    sys.exit(main())
