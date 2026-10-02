#!/usr/bin/env python3
"""RikkaHub Skill Evaluation & Linting Suite.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Supports:
1. Static linting (YAML frontmatter, description <= 1024 chars, relative link integrity, script shebangs)
2. Fidelity evaluation against RikkaHub use_skill tool definitions
3. Real LLM API inference mode (when --api-base is provided)
4. Offline multilingual heuristic simulation fallback (truthfully labeled as smoke test)
5. Chinese & English intent tokenization and semantic overlap scoring
6. Interactive HTML report generation
"""

import argparse
import html
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# RikkaHub MAX_SKILL_DESCRIPTION_LENGTH limitation
MAX_SKILL_DESCRIPTION_LENGTH = 1024

STOP_WORDS_EN = {
    "a", "an", "the", "in", "on", "at", "to", "for", "of", "with", "by", "from",
    "up", "about", "into", "over", "after", "is", "are", "was", "were", "be",
    "been", "being", "have", "has", "had", "do", "does", "did", "can", "could",
    "will", "would", "shall", "should", "may", "might", "must", "i", "you",
    "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "my",
    "your", "his", "their", "please", "help", "how", "what", "can", "could",
    "want", "need", "like", "use", "using", "skill", "this", "that", "these",
    "those", "there", "here", "when", "user", "wants", "asks"
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


def tokenize_multilingual(text: str) -> set[str]:
    """Tokenize English words and Chinese phrases/n-grams without external dependencies."""
    tokens = set()
    t_lower = text.lower()

    # 1. English / Latin words
    for w in re.findall(r"[a-z0-9_\-]+", t_lower):
        if len(w) >= 2 and w not in STOP_WORDS_EN:
            tokens.add(w)

    # 2. Chinese semantic chunks and n-grams
    zh_chars = re.findall(r"[一-鿿]+", text)
    for block in zh_chars:
        sub_chunks = [c.strip() for c in re.split(ZH_STOP_PATTERN, block) if len(c.strip()) >= 2]
        for chunk in sub_chunks:
            if chunk not in STOP_WORDS_ZH:
                tokens.add(chunk)
            if len(chunk) > 2:
                for i in range(len(chunk) - 1):
                    gram = chunk[i:i + 2]
                    if gram not in STOP_WORDS_ZH:
                        tokens.add(gram)

    return tokens


def is_chinese_text(text: str) -> bool:
    """Detect if text contains significant Chinese content."""
    zh_count = len(re.findall(r"[一-鿿]", text))
    return zh_count >= 4 or (len(text) > 0 and (zh_count / len(text)) > 0.15)


def parse_skill_md(skill_dir: Path) -> tuple[dict, str, list[str]]:
    """Parse SKILL.md frontmatter and body using standard library regex."""
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

    clean_body = re.sub(r"```[\s\S]*?```", "", body)
    clean_body = re.sub(r"`[^`\n]*`", "", clean_body)
    links = re.findall(r"\[.*?\]\((?!https?://)(?!mailto:)(.*?)\)", clean_body)

    return frontmatter, body, links


def lint_skill(skill_dir: Path) -> tuple[bool, list[str], list[str]]:
    """Run static lint checks on a skill directory."""
    errors = []
    warnings = []

    skill_file = skill_dir / "SKILL.md"
    if not skill_file.exists():
        errors.append(f"Missing SKILL.md in {skill_dir}")
        return False, errors, warnings

    try:
        frontmatter, body, links = parse_skill_md(skill_dir)
    except Exception as e:
        errors.append(f"Failed to parse SKILL.md: {e}")
        return False, errors, warnings

    name = frontmatter.get("name")
    if not name:
        errors.append("Missing 'name' in frontmatter")
    else:
        if name != skill_dir.name:
            errors.append(f"Skill name '{name}' does not match directory name '{skill_dir.name}'")
        if not re.match(r"^[a-z0-9_-]+$", name):
            errors.append(f"Skill name '{name}' contains invalid characters (must be lowercase alphanumeric, hyphens, underscores)")
        if len(name) > 64:
            errors.append(f"Skill name '{name}' exceeds 64 characters")

    desc = frontmatter.get("description")
    if not desc:
        errors.append("Missing 'description' in frontmatter")
    else:
        if len(desc) > MAX_SKILL_DESCRIPTION_LENGTH:
            errors.append(f"Description length ({len(desc)}) exceeds maximum allowed ({MAX_SKILL_DESCRIPTION_LENGTH} chars)")
        if len(desc) < 15:
            warnings.append("Description is very short (< 15 chars), might lead to trigger under-sensitivity")

    for link in links:
        target_path = link.split("#")[0].strip()
        if not target_path:
            continue
        resolved = (skill_dir / target_path).resolve()
        if not resolved.exists():
            errors.append(f"Linked file does not exist: {target_path}")

    scripts_dir = skill_dir / "scripts"
    if scripts_dir.exists() and scripts_dir.is_dir():
        for script in scripts_dir.iterdir():
            if script.is_file():
                try:
                    with open(script, "rb") as f:
                        header = f.read(2)
                        if header != b"#!":
                            warnings.append(f"Script '{script.name}' missing shebang (#!)")
                except Exception:
                    pass

                if os.name == "posix" and not os.access(script, os.X_OK):
                    warnings.append(f"Script '{script.name}' is not marked executable (chmod +x recommended)")

    return (len(errors) == 0), errors, warnings


def build_simulation_system_prompt(skill_name: str, description: str) -> str:
    """Simulate RikkaHub's WorkspaceReminderTransformer system prompt."""
    return f"""You are a helpful AI assistant in RikkaHub with access to tools.

<available_skills>
  <skill>
    <name>{skill_name}</name>
    <description>{description}</description>
  </skill>
</available_skills>

If the user request is relevant to the skill above, you should invoke the `use_skill` tool with name="{skill_name}".
Otherwise, do not call `use_skill`.
"""


def get_use_skill_tool_spec() -> dict:
    """Return RikkaHub's use_skill OpenAI-compatible tool definition."""
    return {
        "type": "function",
        "function": {
            "name": "use_skill",
            "description": "Execute or load instructions from an available agent skill.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "The name of the skill to use"
                    },
                    "path": {
                        "type": "string",
                        "description": "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions."
                    }
                },
                "required": ["name"]
            }
        }
    }


def _describe_api_error(e: Exception) -> str:
    """Render an exception into a short, actionable message including the upstream body."""
    if isinstance(e, urllib.error.HTTPError):
        body = ""
        try:
            body = e.read().decode("utf-8", errors="replace").strip()
        except Exception:
            pass
        # Surface the upstream 'message' field when the body is OpenAI-shaped JSON.
        detail = ""
        if body:
            try:
                parsed = json.loads(body)
                err = parsed.get("error")
                if isinstance(err, dict) and err.get("message"):
                    detail = str(err["message"])
                elif isinstance(err, str):
                    detail = err
            except (json.JSONDecodeError, AttributeError):
                detail = body[:400]
        return f"HTTP {e.code} {e.reason}" + (f": {detail[:400]}" if detail else "")
    return f"{type(e).__name__}: {e}"


def call_chat_completion(
    api_base: str,
    api_key: str,
    model: str,
    messages: list[dict],
    tools: list[dict],
    timeout: int = 30,
    reasoning_effort: str | None = None,
) -> dict:
    """Send Chat Completions request via urllib.request."""
    url = f"{api_base.rstrip('/')}/chat/completions"
    payload = {
        "model": model,
        "messages": messages,
        "tools": tools,
        "tool_choice": "auto",
        "temperature": 0.0,
    }
    # Only send reasoning_effort when the caller explicitly asked for it. RikkaHub's
    # loopback bridge resolves the parameter from the active assistant otherwise, and
    # arbitrary values ("none"/"minimal") are rejected by most upstream gateways.
    if reasoning_effort:
        payload["reasoning_effort"] = reasoning_effort

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}" if api_key else "",
        },
        method="POST"
    )

    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def probe_bridge_model(api_base: str, api_key: str, timeout: int = 3) -> str | None:
    """Query GET /v1/models on an OpenAI-compatible endpoint and return the first model id.

    Against RikkaHub's loopback bridge this resolves to the model of the currently
    active conversation, so reports never fall back to a fabricated placeholder name.
    """
    url = f"{api_base.rstrip('/')}/models"
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        entries = data.get("data") or []
        if entries and isinstance(entries[0], dict):
            return entries[0].get("id")
    except Exception:
        return None
    return None


def evaluate_query_simulated(query: str, skill_name: str, description: str) -> tuple[bool, str]:
    """Offline multilingual heuristic simulation based on semantic keyword & intent overlap.

    NO CHEATING: Does NOT automatically trigger simply because skill_name appears in query.
    """
    q_tokens = tokenize_multilingual(query)
    desc_tokens = tokenize_multilingual(description)

    if not q_tokens or not desc_tokens:
        return False, "Insufficient extractable semantics from query or description"

    # 1. Check negative exclusion boundaries
    desc_lower = description.lower()
    for marker in ("do not trigger for", "not intended for", "不适用于", "不要触发于", "不要用于", "排除"):
        if marker in desc_lower:
            neg_part = desc_lower.split(marker, 1)[1].split(".")[0].split("。")[0]
            neg_tokens = tokenize_multilingual(neg_part)
            neg_overlap = q_tokens & neg_tokens
            if neg_overlap:
                return False, f"Matches exclusion boundary: {', '.join(neg_overlap)}"

    # 2. Check Chinese direct semantic intent match
    zh_query_phrases = [t for t in q_tokens if any('一' <= c <= '鿿' for c in t)]
    zh_desc_text = "".join(re.findall(r"[一-鿿]+", description))
    direct_zh_containment = [p for p in zh_query_phrases if len(p) >= 2 and p in zh_desc_text]

    if direct_zh_containment:
        return True, f"Direct Chinese semantic intent match: {', '.join(direct_zh_containment[:3])}"

    # 3. Multilingual keyword overlap
    overlap = q_tokens & desc_tokens
    if len(overlap) >= 2 or (len(overlap) >= 1 and len(q_tokens) <= 2):
        return True, f"Semantic keyword overlap: {', '.join(list(overlap)[:4])}"

    return False, f"Insufficient keyword overlap (matched {len(overlap)}: {list(overlap)})"


def evaluate_single_query(
    query: str,
    skill_name: str,
    description: str,
    api_base: str,
    api_key: str,
    model: str,
    timeout: int,
    reasoning_effort: str | None = None,
) -> tuple[bool, str, str]:
    """Run single query test and return (triggered, reason, execution_mode)."""
    if not api_base or api_base == "offline":
        triggered, reason = evaluate_query_simulated(query, skill_name, description)
        return triggered, reason, "offline-simulated"

    sys_prompt = build_simulation_system_prompt(skill_name, description)
    messages = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": query}
    ]
    tools = [get_use_skill_tool_spec()]

    try:
        resp = call_chat_completion(
            api_base, api_key, model, messages, tools,
            timeout=timeout, reasoning_effort=reasoning_effort,
        )
        choices = resp.get("choices", [])
        if not choices:
            return False, "Empty choices returned from model", "live-llm"

        message = choices[0].get("message", {})
        tool_calls = message.get("tool_calls", [])

        for call in tool_calls:
            func = call.get("function", {})
            if func.get("name") == "use_skill":
                args_raw = func.get("arguments", "{}")
                try:
                    args = json.loads(args_raw) if isinstance(args_raw, str) else args_raw
                    target_name = args.get("name", "")
                    if target_name == skill_name:
                        return True, f"use_skill invoked for {skill_name}", "live-llm"
                    else:
                        return False, f"Model invoked a different skill: '{target_name}'", "live-llm"
                except json.JSONDecodeError:
                    if skill_name in str(args_raw):
                        return True, f"use_skill raw matched {skill_name}", "live-llm"
        return False, "Model responded without invoking use_skill", "live-llm"
    except Exception as e:
        detail = _describe_api_error(e)
        print(f"[WARN] API call failed for query '{query}': {detail}. Falling back to offline simulation.", file=sys.stderr)
        triggered, reason = evaluate_query_simulated(query, skill_name, description)
        return triggered, f"API Error ({detail}); Fallback simulation: {reason}", "api-error"


def run_evaluation(
    eval_set: list[dict],
    skill_name: str,
    description: str,
    api_base: str,
    api_key: str,
    model: str,
    runs_per_query: int = 1,
    trigger_threshold: float = 0.5,
    timeout: int = 30,
    reasoning_effort: str | None = None,
) -> dict:
    """Run full evaluation suite over eval_set."""
    results = []
    actually_used_llm = False
    api_error_count = 0

    for item in eval_set:
        query = item["query"]
        should_trigger = bool(item.get("should_trigger", True))

        triggers = 0
        last_reason = ""
        last_mode = "offline-simulated"

        for _ in range(runs_per_query):
            triggered, reason, mode = evaluate_single_query(
                query, skill_name, description, api_base, api_key, model, timeout,
                reasoning_effort=reasoning_effort,
            )
            if triggered:
                triggers += 1
            last_reason = reason
            last_mode = mode
            if mode == "live-llm":
                actually_used_llm = True
            elif mode == "api-error":
                api_error_count += 1

        trigger_rate = triggers / runs_per_query
        if should_trigger:
            did_pass = trigger_rate >= trigger_threshold
        else:
            did_pass = trigger_rate < trigger_threshold

        results.append({
            "query": query,
            "should_trigger": should_trigger,
            "trigger_rate": trigger_rate,
            "triggers": triggers,
            "runs": runs_per_query,
            "pass": did_pass,
            "passed": did_pass,
            "reason": last_reason,
            "mode": last_mode
        })

    passed = sum(1 for r in results if r["pass"])
    total = len(results)

    api_configured = bool(api_base and api_base != "offline")

    if actually_used_llm:
        effective_model = model
        eval_mode_desc = "Live LLM API"
        warning_notice = None
        is_simulated = False
    elif api_configured and api_error_count > 0:
        # Endpoint was configured but every call raised — this is NOT a clean offline
        # run, and reporting it as one would hide a broken gateway behind a green score.
        effective_model = f"{model} (API unreachable)"
        eval_mode_desc = "DEGRADED - API Error, Fell Back To Offline Heuristics"
        warning_notice = (
            f"⚠️ The configured endpoint ({api_base}) answered with errors for all "
            f"{api_error_count} query attempt(s); results below are offline heuristic "
            "fallbacks and do NOT reflect real model behaviour. Inspect the per-query "
            "'reason' field for the upstream error text."
        )
        is_simulated = True
    else:
        effective_model = "offline-simulated-heuristics"
        eval_mode_desc = "Offline Heuristic Simulation (Wiring Smoke Test)"
        warning_notice = (
            "⚠️ Notice: Ran in OFFLINE SIMULATION mode without calling an actual LLM. "
            "Scores evaluate local keyword/intent overlap only. "
            "To evaluate real model fidelity, pass --api-base (or set LLM_API_BASE)."
        )
        is_simulated = True

    return {
        "skill_name": skill_name,
        "description": description,
        "model": effective_model,
        "evaluation_mode": eval_mode_desc,
        "is_simulated": is_simulated,
        "api_error_count": api_error_count,
        "warning": warning_notice,
        "summary": {
            "total": total,
            "passed": passed,
            "failed": total - passed,
            "pass_rate": (passed / total * 100) if total > 0 else 0.0,
            "is_simulated": is_simulated
        },
        "results": results
    }


def extract_positive_intent_chunks(description: str) -> list[str]:
    """Cleanly extract positive functional intent chunks, strictly stripping negative exclusion sections."""
    desc_clean = description

    # 1. Strip negative exclusion sections
    for marker in ("不适用于", "不要触发于", "不要用于", "排除", "do not trigger for", "not intended for", "except for"):
        idx = desc_clean.lower().find(marker.lower())
        if idx != -1:
            desc_clean = desc_clean[:idx]

    # 2. Strip leading boilerplate
    desc_clean = re.sub(
        r"^(?:用于当用户需要|用于|当用户需要|帮助用户|use when the user wants to|use when|a skill to|a skill for)\s*",
        "",
        desc_clean,
        flags=re.IGNORECASE
    ).strip()

    # 3. Split by Chinese / English punctuation into natural clause phrases
    clauses = re.split(r"[、，,；;。.\n\r\t]+", desc_clean)

    valid_chunks = []
    for c in clauses:
        c = c.strip()
        c = re.sub(r"^(?:管理|处理|实现|完成|记录|查看|生成|创建|配置)\s*", "", c).strip()
        if 2 <= len(c) <= 15:
            valid_chunks.append(c)

    return valid_chunks


def generate_fast_eval_template(skill_name: str, description: str) -> list[dict]:
    """Generate realistic Fast Eval set without cheating, grammar errors, or negative section contamination."""
    is_zh = is_chinese_text(description)
    positive_chunks = extract_positive_intent_chunks(description)

    if is_zh:
        p1 = positive_chunks[0] if len(positive_chunks) > 0 else "相关任务规划"
        p2 = positive_chunks[1] if len(positive_chunks) > 1 else (positive_chunks[0] if len(positive_chunks) > 0 else "相关操作")
        p3 = positive_chunks[2] if len(positive_chunks) > 2 else (positive_chunks[0] if len(positive_chunks) > 0 else "具体事项")

        return [
            {
                "query": f"帮我处理一下{p1}，具体怎么操作？",
                "should_trigger": True
            },
            {
                "query": f"我需要查看并更新{p2}，请协助我完成。",
                "should_trigger": True
            },
            {
                "query": f"今天关于{p3}需要理清头绪，能帮我规划并执行吗？",
                "should_trigger": True
            },
            {
                "query": "法国的首都是哪里？请简要介绍其人口与面积。",
                "should_trigger": False
            },
            {
                "query": "写一段 Python 脚本计算斐波那契数列的前20项，并分析时间复杂度。",
                "should_trigger": False
            }
        ]
    else:
        p1 = positive_chunks[0] if len(positive_chunks) > 0 else "the primary workflow"
        p2 = positive_chunks[1] if len(positive_chunks) > 1 else p1
        p3 = positive_chunks[2] if len(positive_chunks) > 2 else p1

        return [
            {
                "query": f"Can you help me handle {p1}? Please guide me through it.",
                "should_trigger": True
            },
            {
                "query": f"I need to work on {p2}. What is the recommended procedure?",
                "should_trigger": True
            },
            {
                "query": f"Please execute the workflow for {p3} according to standard rules.",
                "should_trigger": True
            },
            {
                "query": "What is the capital of France? Give a brief geographic summary.",
                "should_trigger": False
            },
            {
                "query": "Write a quick Python script to calculate Fibonacci numbers up to 100.",
                "should_trigger": False
            }
        ]


def render_html_report(eval_data: dict) -> str:
    """Render interactive HTML report from evaluation results."""
    try:
        from generate_report import render_html_report as gen_report
        return gen_report(eval_data)
    except ImportError:
        script_dir = Path(__file__).resolve().parent
        sys.path.insert(0, str(script_dir))
        from generate_report import render_html_report as gen_report
        return gen_report(eval_data)


def main():
    parser = argparse.ArgumentParser(description="RikkaHub Skill Evaluation & Linting Suite")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--mode", choices=["lint", "eval", "fast"], default="lint", help="Execution mode")
    parser.add_argument("--eval-set", default=None, help="Path to eval set JSON file")
    parser.add_argument("--output-json", default=None, help="Path to output results JSON")
    parser.add_argument("--html", action="store_true", help="Generate HTML report alongside JSON")
    parser.add_argument("--api-base", default=os.environ.get("LLM_API_BASE", ""), help="LLM API Base URL (empty for offline)")
    parser.add_argument("--api-key", default=os.environ.get("LLM_API_KEY", ""), help="LLM API Key")
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", ""), help="Model name; when empty, resolved from GET /v1/models")
    parser.add_argument("--reasoning-effort", default=os.environ.get("LLM_REASONING_EFFORT", None), help="Reasoning effort (off/low/medium/high/xhigh/max); omit to use the assistant's own setting")
    parser.add_argument("--runs-per-query", type=int, default=1, help="Runs per query")
    parser.add_argument("--threshold", type=float, default=0.5, help="Pass threshold")
    parser.add_argument("--timeout", type=int, default=30, help="Timeout in seconds")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    if not skill_path.is_dir():
        print(f"Error: {skill_path} is not a valid directory", file=sys.stderr)
        sys.exit(1)

    lint_ok, errors, warnings = lint_skill(skill_path)

    print(f"=== Linting Skill: {skill_path.name} ===")
    if errors:
        print(f"[FAIL] Found {len(errors)} error(s):")
        for err in errors:
            print(f"  - {err}")
    else:
        print("[PASS] Static validation passed with 0 errors.")

    if warnings:
        print(f"[WARN] {len(warnings)} warning(s):")
        for warn in warnings:
            print(f"  - {warn}")

    if args.mode == "lint":
        sys.exit(0 if lint_ok else 1)

    if not lint_ok:
        print("Linting failed. Aborting evaluation.", file=sys.stderr)
        sys.exit(1)

    frontmatter, _, _ = parse_skill_md(skill_path)
    skill_name = frontmatter.get("name", skill_path.name)
    description = frontmatter.get("description", "")

    if args.mode == "fast" or not args.eval_set:
        print("Running Fast Eval (3 realistic positive + 2 negative queries)...")
        eval_set = generate_fast_eval_template(skill_name, description)
    else:
        eval_set_path = Path(args.eval_set)
        if not eval_set_path.exists():
            print(f"Error: Eval set file {eval_set_path} does not exist", file=sys.stderr)
            sys.exit(1)
        with open(eval_set_path, "r", encoding="utf-8") as f:
            eval_set = json.load(f)

    if not args.api_base:
        print("\n" + "=" * 65)
        print(" [!] NOTICE: Running in OFFLINE SIMULATION MODE (Wiring Smoke Test)")
        print("     No LLM endpoint configured. Model will NOT be queried.")
        print("     Results evaluate local multilingual token/intent overlap.")
        print("     Pass --api-base to execute live model evaluation.")
        print("=" * 65 + "\n")
    else:
        # Resolve the effective model from the endpoint itself (RikkaHub's loopback
        # bridge reports the currently active conversation model here), so we never
        # label a report with a hard-coded placeholder the request never used.
        if not args.model:
            args.model = probe_bridge_model(args.api_base, args.api_key) or "unknown-model"
        print(f"Endpoint: {args.api_base}")
        print(f"Resolved Model: {args.model}")
        print(f"Reasoning Effort: {args.reasoning_effort or '(from active assistant)'}\n")

    print(f"Evaluating {len(eval_set)} queries for skill '{skill_name}'...")
    eval_result = run_evaluation(
        eval_set=eval_set,
        skill_name=skill_name,
        description=description,
        api_base=args.api_base,
        api_key=args.api_key,
        model=args.model,
        runs_per_query=args.runs_per_query,
        trigger_threshold=args.threshold,
        timeout=args.timeout,
        reasoning_effort=args.reasoning_effort,
    )

    out_json_path = Path(args.output_json) if args.output_json else skill_path / "eval_results.json"
    with open(out_json_path, "w", encoding="utf-8") as f:
        json.dump(eval_result, f, indent=2, ensure_ascii=False)
    print(f"Evaluation results saved to: {out_json_path}")

    if args.html:
        html_content = render_html_report(eval_result)
        out_html_path = out_json_path.with_suffix(".html")
        with open(out_html_path, "w", encoding="utf-8") as f:
            f.write(html_content)
        print(f"HTML Viewer report generated at: {out_html_path}")

    summary = eval_result["summary"]
    print(f"\nExecution Mode: {eval_result['evaluation_mode']}")
    print(f"Effective Model: {eval_result['model']}")
    print(f"Summary: {summary['passed']}/{summary['total']} passed ({summary['pass_rate']:.1f}%)")

    sys.exit(0 if summary["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
