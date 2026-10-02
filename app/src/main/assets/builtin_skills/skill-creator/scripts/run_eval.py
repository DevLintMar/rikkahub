#!/usr/bin/env python3
"""RikkaHub Skill Evaluation & Linting Suite.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Supports:
1. Static linting (YAML frontmatter, description <= 1024 chars, relative link integrity, script shebangs)
2. Fidelity evaluation against RikkaHub use_skill tool definitions
3. Fast eval mode (3 positive queries + 2 negative queries)
4. HTML viewer generation
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# RikkaHub MAX_SKILL_DESCRIPTION_LENGTH limitation
MAX_SKILL_DESCRIPTION_LENGTH = 1024


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

    # Parse simple YAML frontmatter fields
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
                # Folded or literal block
                block_lines = []
                idx += 1
                while idx < len(lines) and (lines[idx].startswith("  ") or lines[idx].strip() == ""):
                    block_lines.append(lines[idx].strip())
                    idx += 1
                frontmatter[key] = " ".join(block_lines).strip()
                continue
            else:
                frontmatter[key] = val.strip("'\"")
        idx += 1

    # Extract Markdown relative links (ignore code blocks and inline code examples)
    clean_body = re.sub(r"```[\s\S]*?```", "", body)
    clean_body = re.sub(r"`[^`\n]+`", "", clean_body)

    links = re.findall(r"\[([^\]]+)\]\(([^)]+)\)", clean_body)
    relative_links = []
    for _, target in links:
        target_path = target.split("#")[0].strip()
        if target_path and not target_path.startswith(("http://", "https://", "mailto:")):
            relative_links.append(target_path)

    return frontmatter, body, relative_links


def lint_skill(skill_dir: Path) -> tuple[bool, list[str], list[str]]:
    """Perform static rules and integrity check on a skill directory."""
    errors = []
    warnings = []

    try:
        frontmatter, body, relative_links = parse_skill_md(skill_dir)
    except Exception as e:
        errors.append(f"Failed to parse SKILL.md: {e}")
        return False, errors, warnings

    name = frontmatter.get("name", "")
    description = frontmatter.get("description", "")

    # Rule 1: Name must equal directory name
    if not name:
        errors.append("Missing required frontmatter field 'name'")
    elif name != skill_dir.name:
        errors.append(f"Frontmatter name '{name}' does not match directory name '{skill_dir.name}'")

    if not re.match(r"^[a-z0-9][a-z0-9-]{0,63}$", name):
        errors.append(f"Invalid skill name '{name}'. Must be 1-64 lowercase letters, digits, and hyphens.")

    # Rule 2: Description length check
    if not description:
        errors.append("Missing required frontmatter field 'description'")
    else:
        desc_len = len(description)
        if desc_len > MAX_SKILL_DESCRIPTION_LENGTH:
            errors.append(
                f"Description length ({desc_len} chars) exceeds RikkaHub limit of {MAX_SKILL_DESCRIPTION_LENGTH} chars. "
                "Text beyond this limit will be truncated in available_skills."
            )
        elif desc_len < 20:
            warnings.append(f"Description is very short ({desc_len} chars). It may not trigger reliably.")

    # Rule 3: Relative Markdown links must resolve to existing files
    for rel_link in relative_links:
        target_file = (skill_dir / rel_link).resolve()
        if not target_file.exists():
            errors.append(f"Linked file does not exist: {rel_link}")
        else:
            try:
                target_file.relative_to(skill_dir.resolve())
            except ValueError:
                errors.append(f"Linked file '{rel_link}' points outside skill directory")

    # Rule 4: Check executable scripts in scripts/
    scripts_dir = skill_dir / "scripts"
    if scripts_dir.is_dir():
        for script_file in scripts_dir.iterdir():
            if script_file.is_file():
                # Check shebang
                try:
                    with open(script_file, "rb") as f:
                        header = f.read(2)
                        if header != b"#!":
                            warnings.append(f"Script '{script_file.name}' is missing a '#!' shebang header")
                except Exception as e:
                    warnings.append(f"Cannot read script '{script_file.name}': {e}")

                # Check executable bit on POSIX systems
                if os.name == "posix" and not os.access(script_file, os.X_OK):
                    warnings.append(f"Script '{script_file.name}' does not have executable permission (chmod +x)")

    return len(errors) == 0, errors, warnings


def build_simulation_system_prompt(skill_name: str, description: str) -> str:
    """Build exact system prompt structure identical to RikkaHub SkillsTools.kt."""
    return f"""**Skills**
You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.
<available_skills>
  <skill>
    <name>{skill_name}</name>
    <description>{description}</description>
  </skill>
</available_skills>
"""


def get_use_skill_tool_spec() -> dict:
    """Return OpenAI-compatible tool definition for use_skill."""
    return {
        "type": "function",
        "function": {
            "name": "use_skill",
            "description": "Load and apply a skill to get specialized instructions or capabilities. Call this tool when the user's request matches one of the available skills.",
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


def call_chat_completion(
    api_base: str,
    api_key: str,
    model: str,
    messages: list[dict],
    tools: list[dict],
    timeout: int = 30
) -> dict:
    """Send Chat Completions request via pure urllib.request."""
    url = f"{api_base.rstrip('/')}/chat/completions"
    payload = {
        "model": model,
        "messages": messages,
        "tools": tools,
        "tool_choice": "auto",
        "temperature": 0.0,
    }
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


def evaluate_single_query(
    query: str,
    skill_name: str,
    description: str,
    api_base: str,
    api_key: str,
    model: str,
    timeout: int
) -> bool:
    """Run single query test and return whether use_skill(name=skill_name) was invoked."""
    sys_prompt = build_simulation_system_prompt(skill_name, description)
    messages = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": query}
    ]
    tools = [get_use_skill_tool_spec()]

    try:
        resp = call_chat_completion(api_base, api_key, model, messages, tools, timeout=timeout)
        choices = resp.get("choices", [])
        if not choices:
            return False

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
                        return True
                except json.JSONDecodeError:
                    if skill_name in str(args_raw):
                        return True
        return False
    except Exception as e:
        print(f"Warning: API call failed for query '{query}': {e}", file=sys.stderr)
        return False


def run_evaluation(
    eval_set: list[dict],
    skill_name: str,
    description: str,
    api_base: str,
    api_key: str,
    model: str,
    runs_per_query: int = 1,
    trigger_threshold: float = 0.5,
    timeout: int = 30
) -> dict:
    """Run full evaluation suite over eval_set."""
    results = []
    for item in eval_set:
        query = item["query"]
        should_trigger = bool(item.get("should_trigger", True))

        triggers = 0
        for _ in range(runs_per_query):
            if evaluate_single_query(query, skill_name, description, api_base, api_key, model, timeout):
                triggers += 1

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
            "pass": did_pass
        })

    passed = sum(1 for r in results if r["pass"])
    total = len(results)

    return {
        "skill_name": skill_name,
        "description": description,
        "model": model,
        "summary": {
            "total": total,
            "passed": passed,
            "failed": total - passed,
            "pass_rate": (passed / total * 100) if total > 0 else 0.0
        },
        "results": results
    }


def generate_fast_eval_template(skill_name: str, description: str) -> list[dict]:
    """Generate default Fast Eval set (3 positive queries + 2 negative queries)."""
    return [
        {
            "query": f"Please use {skill_name} to help me with my task.",
            "should_trigger": True
        },
        {
            "query": f"How do I accomplish this? Can you apply the {skill_name} skill?",
            "should_trigger": True
        },
        {
            "query": f"I need instructions for {skill_name}.",
            "should_trigger": True
        },
        {
            "query": "What is the capital of France?",
            "should_trigger": False
        },
        {
            "query": "Write a quick Python script to calculate fibonacci numbers.",
            "should_trigger": False
        }
    ]


def main():
    parser = argparse.ArgumentParser(description="RikkaHub Skill Evaluation & Linting Suite")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--mode", choices=["lint", "eval", "fast"], default="lint", help="Execution mode")
    parser.add_argument("--eval-set", default=None, help="Path to eval set JSON file")
    parser.add_argument("--output-json", default=None, help="Path to output results JSON")
    parser.add_argument("--html", action="store_true", help="Generate HTML report alongside JSON")
    parser.add_argument("--api-base", default=os.environ.get("LLM_API_BASE", "http://127.0.0.1:8080/v1"), help="LLM API Base URL")
    parser.add_argument("--api-key", default=os.environ.get("LLM_API_KEY", ""), help="LLM API Key")
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", "gpt-4o-mini"), help="Model to evaluate with")
    parser.add_argument("--runs-per-query", type=int, default=1, help="Runs per query")
    parser.add_argument("--threshold", type=float, default=0.5, help="Pass threshold")
    parser.add_argument("--timeout", type=int, default=30, help="Timeout in seconds")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    if not skill_path.is_dir():
        print(f"Error: {skill_path} is not a valid directory", file=sys.stderr)
        sys.exit(1)

    # 1. Run static lint
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

    # 2. Run Evaluation
    frontmatter, _, _ = parse_skill_md(skill_path)
    skill_name = frontmatter.get("name", skill_path.name)
    description = frontmatter.get("description", "")

    if args.mode == "fast" or not args.eval_set:
        print(f"Running Fast Eval (3 positive + 2 negative queries)...")
        eval_set = generate_fast_eval_template(skill_name, description)
    else:
        eval_set_path = Path(args.eval_set)
        if not eval_set_path.exists():
            print(f"Error: Eval set file {eval_set_path} not found", file=sys.stderr)
            sys.exit(1)
        with open(eval_set_path, "r", encoding="utf-8") as f:
            eval_set = json.load(f)

    eval_result = run_evaluation(
        eval_set=eval_set,
        skill_name=skill_name,
        description=description,
        api_base=args.api_base,
        api_key=args.api_key,
        model=args.model,
        runs_per_query=args.runs_per_query,
        trigger_threshold=args.threshold,
        timeout=args.timeout
    )

    out_json_path = Path(args.output_json) if args.output_json else skill_path / "eval_results.json"
    with open(out_json_path, "w", encoding="utf-8") as f:
        json.dump(eval_result, f, indent=2, ensure_ascii=False)
    print(f"Evaluation results saved to: {out_json_path}")

    # 3. Generate HTML report if requested
    if args.html:
        from generate_report import render_html_report
        html_content = render_html_report(eval_result)
        out_html_path = out_json_path.with_suffix(".html")
        with open(out_html_path, "w", encoding="utf-8") as f:
            f.write(html_content)
        print(f"HTML Viewer report generated at: {out_html_path}")

    summary = eval_result["summary"]
    print(f"Summary: {summary['passed']}/{summary['total']} passed ({summary['pass_rate']:.1f}%)")
    sys.exit(0 if summary["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
