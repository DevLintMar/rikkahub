#!/usr/bin/env python3
"""RikkaHub Skill Description Self-Healing & Optimization Engine.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Features:
1. Multilingual intent & token extraction (Chinese & English).
2. Analyzes evaluation failures (false negatives = missed triggers, false positives = over-triggers).
3. Dual-engine optimization:
   - LLM Mode: Structured API prompt for semantic refinement (OpenAI-compatible).
   - Heuristic Mode: Offline multilingual vocabulary expansion and boundary tightening.
4. Strict enforcement of RikkaHub's MAX_SKILL_DESCRIPTION_LENGTH (1024 chars).
5. Safe in-place or dry-run update of SKILL.md frontmatter.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Reuse the shared endpoint helpers from the sibling evaluation script so the
# loopback bridge contract (model discovery, error surfacing) stays in one place.
_SCRIPTS_DIR = Path(__file__).resolve().parent
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

from run_eval import _describe_api_error, probe_bridge_model  # noqa: E402

MAX_SKILL_DESCRIPTION_LENGTH = 1024

STOP_WORDS_EN = {
    "a", "an", "the", "in", "on", "at", "to", "for", "of", "with", "by", "from",
    "up", "about", "into", "over", "after", "is", "are", "was", "were", "be",
    "been", "being", "have", "has", "had", "do", "does", "did", "can", "could",
    "will", "would", "shall", "should", "may", "might", "must", "i", "you",
    "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "my",
    "your", "his", "their", "please", "help", "how", "what", "can", "could",
    "want", "need", "like", "use", "using", "skill", "this", "that", "these",
    "those", "there", "here", "just", "now", "also", "very", "much"
}

STOP_WORDS_ZH = {
    "的", "了", "在", "是", "我", "你", "他", "她", "它", "我们", "你们", "他们",
    "这", "那", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很",
    "到", "说", "要", "去", "能", "会", "着", "没有", "看", "好", "自己",
    "帮", "帮我", "请", "请问", "一下", "怎么", "如何", "做", "用", "使用", "写",
    "一个", "个", "些", "给", "处理", "进行", "完成", "实现", "技能", "工具",
    "看一下", "有什么", "能否", "可以", "什么", "关于", "为了"
}

ZH_STOP_PATTERN = r"[的了在是我你他她它这那有和就不人都一上也很到说要去除看好帮请问做用写个些给]|[帮请]我|看一下|有什么|怎么|如何|能否|可以|一下|什么|关于|为了|进行"


def tokenize_multilingual(text: str) -> list[str]:
    """Extract informative multilingual tokens (Chinese chunks + English words)."""
    tokens = []
    t_lower = text.lower()

    # 1. English words
    for w in re.findall(r"[a-z0-9_\-]+", t_lower):
        if len(w) >= 2 and w not in STOP_WORDS_EN:
            tokens.append(w)

    # 2. Chinese chunks and 2-grams
    zh_chars = re.findall(r"[一-鿿]+", text)
    for block in zh_chars:
        sub_chunks = [c.strip() for c in re.split(ZH_STOP_PATTERN, block) if len(c.strip()) >= 2]
        for chunk in sub_chunks:
            if chunk not in STOP_WORDS_ZH:
                tokens.append(chunk)
            if len(chunk) > 2:
                for i in range(len(chunk) - 1):
                    gram = chunk[i:i + 2]
                    if gram not in STOP_WORDS_ZH:
                        tokens.append(gram)

    return tokens


def is_chinese_text(text: str) -> bool:
    """Detect if text contains significant Chinese content."""
    zh_count = len(re.findall(r"[一-鿿]", text))
    return zh_count >= 4 or (len(text) > 0 and (zh_count / len(text)) > 0.15)


def parse_skill_md(skill_dir: Path) -> tuple[dict, str, str]:
    """Parse SKILL.md frontmatter, raw frontmatter text, and body."""
    skill_file = skill_dir / "SKILL.md"
    if not skill_file.exists():
        raise FileNotFoundError(f"SKILL.md not found in {skill_dir}")

    content = skill_file.read_text(encoding="utf-8")
    if not content.startswith("---"):
        raise ValueError("SKILL.md must start with '---' on the first line")

    parts = re.split(r"\r?\n---\r?\n", content, maxsplit=1)
    if len(parts) < 2:
        raise ValueError("SKILL.md frontmatter is not closed with '---'")

    fm_raw = parts[0][3:].strip()
    body = parts[1].strip()

    frontmatter = {}
    lines = fm_raw.splitlines()
    idx = 0
    while idx < len(lines):
        line = lines[idx]
        if ":" in line and not line.startswith(" "):
            key, val = line.split(":", 1)
            key = key.strip()
            val = val.strip()

            if val in (">-", ">", "|", "|-"):
                block_lines = []
                idx += 1
                while idx < len(lines) and (lines[idx].startswith("  ") or lines[idx].strip() == ""):
                    block_lines.append(lines[idx].strip())
                    idx += 1
                frontmatter[key] = " ".join(block_lines).strip()
                continue
            else:
                frontmatter[key] = val.strip("\"'")
        idx += 1

    return frontmatter, fm_raw, body


def update_skill_description(skill_dir: Path, new_description: str) -> None:
    """Update description in SKILL.md while preserving other frontmatter and body."""
    new_desc_clean = new_description.strip()
    if len(new_desc_clean) > MAX_SKILL_DESCRIPTION_LENGTH:
        new_desc_clean = new_desc_clean[:MAX_SKILL_DESCRIPTION_LENGTH].rstrip()

    skill_file = skill_dir / "SKILL.md"
    content = skill_file.read_text(encoding="utf-8")
    parts = re.split(r"\r?\n---\r?\n", content, maxsplit=1)
    if len(parts) < 2:
        raise ValueError("Invalid SKILL.md format")

    fm_raw = parts[0][3:]
    body = parts[1]

    # Format new description as folded block
    words = new_desc_clean.split()
    wrapped_lines = []
    curr_line = "  "
    for w in words:
        if len(curr_line) + len(w) + 1 > 80:
            wrapped_lines.append(curr_line)
            curr_line = "  " + w
        else:
            curr_line += (" " if curr_line.strip() else "") + w
    if curr_line.strip():
        wrapped_lines.append(curr_line)

    new_desc_block = "description: >-\n" + "\n".join(wrapped_lines)

    if re.search(r"^description:", fm_raw, flags=re.MULTILINE):
        new_fm = re.sub(
            r"^description:\s*(?:>[-]?|\|[-]?|[^\r\n]*)(?:\r?\n(?:[ \t]+[^\r\n]*|\s*))*",
            new_desc_block,
            fm_raw,
            flags=re.MULTILINE
        )
    else:
        new_fm = fm_raw.strip() + "\n" + new_desc_block

    new_content = f"---\n{new_fm.strip()}\n---\n\n{body}\n"
    skill_file.write_text(new_content, encoding="utf-8")


def heuristic_optimize(
    current_description: str,
    skill_name: str,
    false_negatives: list[str],
    false_positives: list[str]
) -> tuple[str, str]:
    """Multilingual offline heuristic description optimizer."""
    analysis_points = []
    desc = current_description.strip()
    is_zh = is_chinese_text(desc) or any(is_chinese_text(q) for q in false_negatives)

    # 1. Analyze False Negatives (Missed triggers)
    fn_tokens = []
    for q in false_negatives:
        fn_tokens.extend(tokenize_multilingual(q))

    fn_freq = {}
    for t in fn_tokens:
        fn_freq[t] = fn_freq.get(t, 0) + 1

    top_fn = [k for k, _ in sorted(fn_freq.items(), key=lambda x: x[1], reverse=True)[:6]]
    if top_fn:
        analysis_points.append(f"Missed keywords detected from {len(false_negatives)} false negative(s): {', '.join(top_fn)}")

    # 2. Analyze False Positives (Over-triggered)
    fp_tokens = []
    for q in false_positives:
        fp_tokens.extend(tokenize_multilingual(q))

    fp_freq = {}
    for t in fp_tokens:
        fp_freq[t] = fp_freq.get(t, 0) + 1

    top_fp = [k for k, _ in sorted(fp_freq.items(), key=lambda x: x[1], reverse=True)[:4]]
    if top_fp:
        analysis_points.append(f"Over-trigger risks detected from {len(false_positives)} false positive(s): {', '.join(top_fp)}")

    # 3. Construct optimized description
    new_additions = [t for t in top_fn if t.lower() not in desc.lower()]

    if is_zh:
        if not desc.startswith("用于") and not desc.startswith("当用户"):
            desc = f"用于当用户需要{desc}"
        if new_additions:
            addition_text = f"，包括处理{'、'.join(new_additions)}"
            if desc.endswith("。") or desc.endswith("."):
                desc = desc[:-1] + addition_text + "。"
            else:
                desc = desc + addition_text + "。"
        if top_fp:
            boundary_text = f" 不适用于常规的{'、'.join(top_fp)}等与本技能无关的请求。"
            desc += boundary_text
    else:
        if not desc.startswith("Use when"):
            desc = f"Use when the user wants to {desc[0].lower() + desc[1:] if desc else 'perform tasks'}"
        if new_additions:
            addition_text = f", including {', '.join(new_additions)}"
            if desc.endswith("."):
                desc = desc[:-1] + addition_text + "."
            else:
                desc = desc + addition_text + "."
        if top_fp:
            boundary_text = f" Do not trigger for general {', '.join(top_fp)} queries unrelated to {skill_name}."
            if not desc.endswith("."):
                desc += "."
            desc += boundary_text

    # Length guard
    if len(desc) > MAX_SKILL_DESCRIPTION_LENGTH:
        truncated = desc[:MAX_SKILL_DESCRIPTION_LENGTH]
        last_punct = max(truncated.rfind("."), truncated.rfind("。"))
        if last_punct > 200:
            desc = truncated[:last_punct + 1]
        else:
            desc = truncated.rstrip()

    analysis = "\n".join(analysis_points) if analysis_points else "No specific keyword drift detected."
    return desc, analysis


def strip_code_fence(text: str) -> str:
    """Remove a surrounding ```json ... ``` fence that models often add around JSON."""
    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    body = stripped.split("\n", 1)[1] if "\n" in stripped else ""
    if body.rstrip().endswith("```"):
        body = body.rstrip()[:-3]
    return body.strip()


def llm_optimize(
    current_description: str,
    skill_name: str,
    false_negatives: list[str],
    false_positives: list[str],
    api_base: str,
    api_key: str,
    model: str,
    timeout: int = 30,
    reasoning_effort: str | None = None,
) -> tuple[str, str]:
    """Semantic description optimization via OpenAI-compatible LLM endpoint."""
    system_prompt = (
        "You are an expert AI Agent Skill Prompt Engineer. Your task is to optimize the 'description' "
        "field of a skill frontmatter in RikkaHub (a native Android LLM client).\n\n"
        "Rules:\n"
        "1. The description is the ONLY context the AI model sees when deciding whether to call `use_skill`.\n"
        f"2. Strict character limit: MUST be <= {MAX_SKILL_DESCRIPTION_LENGTH} characters. Brevity with high semantic signal is essential.\n"
        "3. Match the language of the original description (Chinese if Chinese, English if English).\n"
        "4. Fix False Negatives: Explicitly incorporate missed keywords and synonymous phrasing from queries that should have triggered.\n"
        "5. Fix False Positives: Narrow the scope or add explicit exclusion boundaries to avoid triggering on queries that should not trigger.\n"
        "6. Output JSON only, with no surrounding prose or code fence, in this format:\n"
        '{"analysis": "<short explanation of changes>", "improved_description": "<the optimized description>"}'
    )

    user_prompt = {
        "skill_name": skill_name,
        "current_description": current_description,
        "false_negatives": false_negatives,
        "false_positives": false_positives
    }

    url = f"{api_base.rstrip('/')}/chat/completions"
    headers = {
        "Content-Type": "application/json"
    }
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_prompt, ensure_ascii=False, indent=2)}
        ],
        "temperature": 0.2,
    }
    # Only pin reasoning effort when explicitly requested; RikkaHub's loopback bridge
    # otherwise inherits the active assistant's own level (an arbitrary value here
    # would be forwarded upstream and rejected).
    if reasoning_effort:
        payload["reasoning_effort"] = reasoning_effort

    req = urllib.request.Request(
        url=url,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            content = data["choices"][0]["message"]["content"]
            result = json.loads(strip_code_fence(content))
            improved = result.get("improved_description", current_description).strip()
            analysis = result.get("analysis", "Refined via LLM analysis.")

            if len(improved) > MAX_SKILL_DESCRIPTION_LENGTH:
                improved = improved[:MAX_SKILL_DESCRIPTION_LENGTH].rstrip()

            return improved, analysis
    except Exception as e:
        print(f"[WARN] LLM optimization request failed ({_describe_api_error(e)}). Falling back to heuristic mode.", file=sys.stderr)
        return heuristic_optimize(current_description, skill_name, false_negatives, false_positives)


def main():
    parser = argparse.ArgumentParser(description="RikkaHub Skill Description Self-Healing & Optimization")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--eval-results", default=None, help="Path to eval_results.json from run_eval.py")
    parser.add_argument("--false-negatives", default=None, help="JSON file or list of false negative queries")
    parser.add_argument("--false-positives", default=None, help="JSON file or list of false positive queries")
    parser.add_argument("--api-base", default=os.environ.get("LLM_API_BASE", ""), help="LLM API Base URL (optional)")
    parser.add_argument("--api-key", default=os.environ.get("LLM_API_KEY", ""), help="LLM API Key (optional)")
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", ""), help="Model name; when empty, resolved from GET /v1/models")
    parser.add_argument("--reasoning-effort", default=os.environ.get("LLM_REASONING_EFFORT", None), help="Reasoning effort (off/low/medium/high/xhigh/max); omit to use the assistant's own setting")
    parser.add_argument("--write", action="store_true", help="Write optimized description directly back to SKILL.md")
    parser.add_argument("--output-json", default=None, help="Path to save optimization report JSON")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    frontmatter, _, _ = parse_skill_md(skill_path)
    skill_name = frontmatter.get("name", skill_path.name)
    current_desc = frontmatter.get("description", "")

    fn_list = []
    fp_list = []

    if args.eval_results:
        eval_path = Path(args.eval_results)
        if eval_path.exists():
            with open(eval_path, "r", encoding="utf-8") as f:
                res = json.load(f)
                for item in res.get("results", []):
                    should = item.get("should_trigger")
                    passed = item.get("passed")
                    query = item.get("query", "")
                    if not passed:
                        if should:
                            fn_list.append(query)
                        else:
                            fp_list.append(query)

    if args.false_negatives:
        fn_path = Path(args.false_negatives)
        if fn_path.exists():
            with open(fn_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                fn_list.extend(data if isinstance(data, list) else [data])
        else:
            fn_list.append(args.false_negatives)

    if args.false_positives:
        fp_path = Path(args.false_positives)
        if fp_path.exists():
            with open(fp_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                fp_list.extend(data if isinstance(data, list) else [data])
        else:
            fp_list.append(args.false_positives)

    print(f"=== Optimizing Description for Skill: {skill_name} ===")
    print(f"Current Description ({len(current_desc)} chars):\n  \"{current_desc}\"\n")
    print(f"Detected Failures: {len(fn_list)} False Negative(s), {len(fp_list)} False Positive(s)")

    if not fn_list and not fp_list:
        print("[INFO] No trigger failures found. Current description is already optimal!")
        sys.exit(0)

    if args.api_base:
        # Resolve the real model id from the endpoint (RikkaHub's loopback bridge reports
        # the active conversation model) instead of shipping a placeholder label.
        if not args.model:
            args.model = probe_bridge_model(args.api_base, args.api_key) or "unknown-model"
        print(f"Using LLM Semantic Optimizer ({args.model} via {args.api_base})...")
        improved_desc, analysis = llm_optimize(
            current_description=current_desc,
            skill_name=skill_name,
            false_negatives=fn_list,
            false_positives=fp_list,
            api_base=args.api_base,
            api_key=args.api_key,
            model=args.model,
            reasoning_effort=args.reasoning_effort,
        )
    else:
        print("Using Offline Multilingual Heuristic Rule-Based Optimizer...")
        improved_desc, analysis = heuristic_optimize(
            current_description=current_desc,
            skill_name=skill_name,
            false_negatives=fn_list,
            false_positives=fp_list
        )

    print(f"\n--- Analysis ---\n{analysis}\n")
    print(f"--- Improved Description ({len(improved_desc)} chars) ---")
    print(f"  \"{improved_desc}\"\n")

    report = {
        "skill_name": skill_name,
        "original_description": current_desc,
        "improved_description": improved_desc,
        "analysis": analysis,
        "chars_before": len(current_desc),
        "chars_after": len(improved_desc),
        "false_negatives": fn_list,
        "false_positives": fp_list
    }

    if args.output_json:
        out_path = Path(args.output_json)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"Optimization report saved to: {out_path}")

    if args.write:
        update_skill_description(skill_path, improved_desc)
        print(f"[SUCCESS] Updated SKILL.md in-place at {skill_path / 'SKILL.md'}")


if __name__ == "__main__":
    main()
