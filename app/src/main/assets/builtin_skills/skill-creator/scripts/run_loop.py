#!/usr/bin/env python3
"""RikkaHub Skill Automated Convergence & Self-Healing Loop.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Features:
1. Orchestrates run_eval.py and improve_description.py in an automated feedback loop.
2. Multilingual Chinese & English intent tokenization and semantic evaluation.
3. Train / Holdout dataset splitting to prevent description overfitting.
4. Truthful execution mode reporting (Live LLM vs Offline Heuristic Simulation).
5. Generates comprehensive multi-turn convergence history (iteration_history.json).
6. HTML report generation and in-place SKILL.md update upon convergence.
"""

import argparse
import copy
import json
import os
import random
import sys
from pathlib import Path

# Add scripts directory to path to import brother modules directly
SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_eval
import improve_description
import generate_report


def split_train_holdout(
    eval_set: list[dict],
    holdout_ratio: float = 0.3,
    seed: int = 42
) -> tuple[list[dict], list[dict]]:
    """Split evaluation dataset into Train and Holdout sets stratified by should_trigger."""
    if len(eval_set) <= 3 or holdout_ratio <= 0.0:
        return eval_set, eval_set

    positives = [item for item in eval_set if item.get("should_trigger", True)]
    negatives = [item for item in eval_set if not item.get("should_trigger", True)]

    rng = random.Random(seed)
    rng.shuffle(positives)
    rng.shuffle(negatives)

    n_holdout_pos = max(1, int(len(positives) * holdout_ratio)) if len(positives) > 1 else 0
    n_holdout_neg = max(1, int(len(negatives) * holdout_ratio)) if len(negatives) > 1 else 0

    holdout_pos = positives[:n_holdout_pos]
    train_pos = positives[n_holdout_pos:]

    holdout_neg = negatives[:n_holdout_neg]
    train_neg = negatives[n_holdout_neg:]

    train_set = train_pos + train_neg
    holdout_set = holdout_pos + holdout_neg

    if not train_set:
        return eval_set, eval_set

    return train_set, holdout_set


def run_evaluation_on_set(
    skill_name: str,
    description: str,
    eval_items: list[dict],
    api_base: str,
    api_key: str,
    model: str,
    timeout: int,
    reasoning_effort: str | None = None,
) -> dict:
    """Run simulated or API-based evaluation on a specific query subset."""
    results = []
    passed_count = 0
    actually_used_llm = False
    api_error_count = 0

    for item in eval_items:
        query = item.get("query", "")
        should_trigger = bool(item.get("should_trigger", True))

        triggered, reason, mode = run_eval.evaluate_single_query(
            query=query,
            skill_name=skill_name,
            description=description,
            api_base=api_base,
            api_key=api_key,
            model=model,
            timeout=timeout,
            reasoning_effort=reasoning_effort,
        )

        if mode == "live-llm":
            actually_used_llm = True
        elif mode == "api-error":
            api_error_count += 1

        passed = (triggered == should_trigger)
        if passed:
            passed_count += 1

        results.append({
            "query": query,
            "should_trigger": should_trigger,
            "triggered": triggered,
            "pass": passed,
            "passed": passed,
            "triggers": 1 if triggered else 0,
            "runs": 1,
            "trigger_rate": 1.0 if triggered else 0.0,
            "reason": reason,
            "mode": mode
        })

    total = len(eval_items)
    failed_count = total - passed_count
    pass_rate = (passed_count / total * 100.0) if total > 0 else 0.0

    api_configured = bool(api_base and api_base != "offline")

    # Mirror run_eval's honesty rule: a configured-but-failing endpoint is reported as
    # degraded, never as a clean offline run.
    if actually_used_llm:
        effective_model = model
        eval_mode_desc = "Live LLM API"
        is_simulated = False
    elif api_configured and api_error_count > 0:
        effective_model = f"{model} (API unreachable)"
        eval_mode_desc = "DEGRADED - API Error, Fell Back To Offline Heuristics"
        is_simulated = True
    else:
        effective_model = "offline-simulated-heuristics"
        eval_mode_desc = "Offline Heuristic Simulation (Wiring Smoke Test)"
        is_simulated = True

    return {
        "model": effective_model,
        "evaluation_mode": eval_mode_desc,
        "is_simulated": is_simulated,
        "api_error_count": api_error_count,
        "results": results,
        "summary": {
            "total": total,
            "passed": passed_count,
            "failed": failed_count,
            "pass_rate": pass_rate,
            "is_simulated": is_simulated
        }
    }


def main():
    parser = argparse.ArgumentParser(description="RikkaHub Skill Automated Convergence Loop")
    parser.add_argument("--skill-path", required=True, help="Path to skill directory")
    parser.add_argument("--eval-set", default=None, help="Path to custom eval set JSON file")
    parser.add_argument("--max-iterations", type=int, default=3, help="Max optimization iterations (default: 3)")
    parser.add_argument("--target-pass-rate", type=float, default=100.0, help="Target pass rate %% to stop (default: 100.0)")
    parser.add_argument("--holdout-ratio", type=float, default=0.3, help="Holdout split ratio (default: 0.3)")
    parser.add_argument("--api-base", default=os.environ.get("LLM_API_BASE", ""), help="LLM API Base URL")
    parser.add_argument("--api-key", default=os.environ.get("LLM_API_KEY", ""), help="LLM API Key")
    parser.add_argument("--model", default=os.environ.get("LLM_MODEL", ""), help="Model name; when empty, resolved from GET /v1/models")
    parser.add_argument("--reasoning-effort", default=os.environ.get("LLM_REASONING_EFFORT", None), help="Reasoning effort (off/low/medium/high/xhigh/max); omit to use the assistant's own setting")
    parser.add_argument("--timeout", type=int, default=30, help="API timeout in seconds")
    parser.add_argument("--write", action="store_true", help="Write best converged description to SKILL.md")
    parser.add_argument("--output-json", default=None, help="Output path for iteration history JSON")
    parser.add_argument("--html", action="store_true", help="Generate HTML report")
    args = parser.parse_args()

    skill_path = Path(args.skill_path).resolve()
    frontmatter, _, _ = improve_description.parse_skill_md(skill_path)
    skill_name = frontmatter.get("name", skill_path.name)
    initial_desc = frontmatter.get("description", "")

    if args.api_base and not args.model:
        args.model = run_eval.probe_bridge_model(args.api_base, args.api_key) or "unknown-model"

    if args.eval_set:
        eval_file = Path(args.eval_set)
        if not eval_file.exists():
            print(f"[ERROR] Eval set file {eval_file} not found", file=sys.stderr)
            sys.exit(1)
        with open(eval_file, "r", encoding="utf-8") as f:
            raw_data = json.load(f)
            eval_items = raw_data.get("queries", raw_data) if isinstance(raw_data, dict) else raw_data
    else:
        print("[INFO] No custom eval set specified. Generating realistic Fast Eval template...")
        eval_items = run_eval.generate_fast_eval_template(skill_name, initial_desc)

    train_set, holdout_set = split_train_holdout(eval_items, holdout_ratio=args.holdout_ratio)
    print(f"=== Starting Skill Convergence Loop: {skill_name} ===")
    print(f"Total Queries: {len(eval_items)} (Train: {len(train_set)}, Holdout: {len(holdout_set)})")
    print(f"Target Pass Rate: {args.target_pass_rate}%, Max Iterations: {args.max_iterations}")

    if not args.api_base:
        print("\n" + "=" * 65)
        print(" [!] NOTICE: Running in OFFLINE SIMULATION MODE (Wiring Smoke Test)")
        print("     No LLM endpoint configured. Model will NOT be queried.")
        print("     Results evaluate local multilingual token/intent overlap.")
        print("     Pass --api-base to execute live model evaluation.")
        print("=" * 65 + "\n")
    else:
        print(f"Endpoint: {args.api_base}")
        print(f"Resolved Model: {args.model}")
        print(f"Reasoning Effort: {args.reasoning_effort or '(from active assistant)'}\n")

    print(f"Initial Description ({len(initial_desc)} chars):\n  \"{initial_desc}\"\n")

    history = []
    current_desc = initial_desc
    best_desc = initial_desc
    best_train_rate = -1.0

    # Iteration 0: Baseline Evaluation
    print("--- [Iteration 0: Baseline] Evaluating initial description ---")
    base_eval = run_evaluation_on_set(
        skill_name=skill_name,
        description=current_desc,
        eval_items=train_set,
        api_base=args.api_base,
        api_key=args.api_key,
        model=args.model,
        timeout=args.timeout,
        reasoning_effort=args.reasoning_effort,
    )
    base_rate = base_eval["summary"]["pass_rate"]
    print(f"Iteration 0 (Train): {base_eval['summary']['passed']}/{base_eval['summary']['total']} passed ({base_rate:.1f}%) [Mode: {base_eval['evaluation_mode']}]")

    history.append({
        "iteration": 0,
        "description": current_desc,
        "chars": len(current_desc),
        "train_pass_rate": base_rate,
        "summary": base_eval["summary"],
        "results": base_eval["results"],
        "analysis": "Initial baseline evaluation."
    })

    best_desc = current_desc
    best_train_rate = base_rate

    # Iteration Loop
    for it in range(1, args.max_iterations + 1):
        if best_train_rate >= args.target_pass_rate:
            print(f"\n[TARGET REACHED] Achieved {best_train_rate:.1f}% pass rate on Train set. Stopping loop early.")
            break

        print(f"\n--- [Iteration {it}] Refining Description ---")
        last_results = history[-1]["results"]
        fn_list = [r["query"] for r in last_results if not r["passed"] and r["should_trigger"]]
        fp_list = [r["query"] for r in last_results if not r["passed"] and not r["should_trigger"]]

        print(f"Detected {len(fn_list)} False Negative(s) and {len(fp_list)} False Positive(s)")

        if not fn_list and not fp_list:
            print("No failures on Train set! Ending loop.")
            break

        if args.api_base:
            improved_desc, analysis = improve_description.llm_optimize(
                current_description=current_desc,
                skill_name=skill_name,
                false_negatives=fn_list,
                false_positives=fp_list,
                api_base=args.api_base,
                api_key=args.api_key,
                model=args.model,
                timeout=args.timeout,
                reasoning_effort=args.reasoning_effort,
            )
        else:
            improved_desc, analysis = improve_description.heuristic_optimize(
                current_description=current_desc,
                skill_name=skill_name,
                false_negatives=fn_list,
                false_positives=fp_list
            )

        print(f"Candidate Description ({len(improved_desc)} chars):\n  \"{improved_desc}\"")

        train_eval = run_evaluation_on_set(
            skill_name=skill_name,
            description=improved_desc,
            eval_items=train_set,
            api_base=args.api_base,
            api_key=args.api_key,
            model=args.model,
            timeout=args.timeout,
            reasoning_effort=args.reasoning_effort,
        )
        train_rate = train_eval["summary"]["pass_rate"]
        print(f"Iteration {it} (Train): {train_eval['summary']['passed']}/{train_eval['summary']['total']} passed ({train_rate:.1f}%)")

        history.append({
            "iteration": it,
            "description": improved_desc,
            "chars": len(improved_desc),
            "train_pass_rate": train_rate,
            "summary": train_eval["summary"],
            "results": train_eval["results"],
            "analysis": analysis
        })

        if train_rate > best_train_rate:
            best_train_rate = train_rate
            best_desc = improved_desc
            current_desc = improved_desc
            print(f"[IMPROVED] New best candidate found (Pass Rate: {best_train_rate:.1f}%)")
        else:
            print(f"[NO IMPROVEMENT] Candidate pass rate {train_rate:.1f}% <= best {best_train_rate:.1f}%.")
            current_desc = improved_desc

    # Holdout Validation on Best Description
    print(f"\n=== Holdout Set Generalization Validation ===")
    holdout_eval = run_evaluation_on_set(
        skill_name=skill_name,
        description=best_desc,
        eval_items=holdout_set,
        api_base=args.api_base,
        api_key=args.api_key,
        model=args.model,
        timeout=args.timeout,
        reasoning_effort=args.reasoning_effort,
    )
    holdout_rate = holdout_eval["summary"]["pass_rate"]
    print(f"Holdout Results: {holdout_eval['summary']['passed']}/{holdout_eval['summary']['total']} passed ({holdout_rate:.1f}%) [Mode: {holdout_eval['evaluation_mode']}]")

    print(f"\n================ Convergence Summary ================")
    print(f"Skill: {skill_name}")
    print(f"Evaluation Mode: {holdout_eval['evaluation_mode']}")
    print(f"Effective Model: {holdout_eval['model']}")
    print(f"Initial Pass Rate (Train): {base_rate:.1f}%")
    print(f"Final Best Pass Rate (Train): {best_train_rate:.1f}%")
    print(f"Holdout Generalization Rate: {holdout_rate:.1f}%")
    print(f"Best Description ({len(best_desc)} chars):\n  \"{best_desc}\"")

    convergence_report = {
        "skill_name": skill_name,
        "initial_description": initial_desc,
        "best_description": best_desc,
        "model": holdout_eval["model"],
        "evaluation_mode": holdout_eval["evaluation_mode"],
        "is_simulated": holdout_eval["is_simulated"],
        "baseline_train_rate": base_rate,
        "final_train_rate": best_train_rate,
        "holdout_rate": holdout_rate,
        "total_iterations": len(history) - 1,
        "history": history,
        "holdout_evaluation": holdout_eval
    }

    out_json = Path(args.output_json) if args.output_json else skill_path / "iteration_history.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(convergence_report, f, indent=2, ensure_ascii=False)
    print(f"\nIteration history saved to: {out_json}")

    if args.html:
        combined_eval_data = {
            "skill_name": skill_name,
            "description": best_desc,
            "model": holdout_eval["model"],
            "evaluation_mode": holdout_eval["evaluation_mode"],
            "is_simulated": holdout_eval["is_simulated"],
            "summary": {
                "total": holdout_eval["summary"]["total"] + history[-1]["summary"]["total"],
                "passed": holdout_eval["summary"]["passed"] + history[-1]["summary"]["passed"],
                "failed": holdout_eval["summary"]["failed"] + history[-1]["summary"]["failed"],
                "pass_rate": ((holdout_eval["summary"]["passed"] + history[-1]["summary"]["passed"]) /
                              (holdout_eval["summary"]["total"] + history[-1]["summary"]["total"]) * 100.0)
            },
            "results": history[-1]["results"] + holdout_eval["results"]
        }
        html_content = generate_report.render_html_report(combined_eval_data)
        out_html = out_json.with_suffix(".html")
        with open(out_html, "w", encoding="utf-8") as f:
            f.write(html_content)
        print(f"HTML convergence report generated at: {out_html}")

    if args.write:
        if best_desc != initial_desc:
            improve_description.update_skill_description(skill_path, best_desc)
            print(f"[SUCCESS] Updated SKILL.md with best converged description.")
        else:
            print("[INFO] Best description identical to initial. No changes written.")

    sys.exit(0 if holdout_rate >= 80.0 else 1)


if __name__ == "__main__":
    main()
