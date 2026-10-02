# Skill Evaluation, Self-Healing & Convergence Guide

This guide details how to rigorously test, evaluate, optimize, and converge Agent Skills in RikkaHub using the zero-dependency Python toolchain.

---

## 1. The Evaluation Architecture

Evaluating a skill measures two core dimensions:
1. **Sensitivity (Positive Recall)**: Does the model trigger `use_skill` when the user request clearly warrants it?
2. **Specificity (Negative Precision)**: Does the model refrain from triggering when the user asks unrelated or general questions?

```text
┌────────────────────────────────────────────────────────┐
│                   Evaluation Set                       │
│  ├── Positive Queries (should_trigger: true)           │
│  └── Negative Queries (should_trigger: false)          │
└───────────────────────────┬────────────────────────────┘
                            │ Stratified Split (e.g. 70/30)
              ┌─────────────┴─────────────┐
              ▼                           ▼
┌───────────────────────────┐ ┌───────────────────────────┐
│     Train Subset (70%)    │ │    Holdout Subset (30%)   │
│ Used for failure analysis │ │ Isolated for final        │
│ & description refinement  │ │ generalization validation │
└─────────────┬─────────────┘ └─────────────┬─────────────┘
              │                             │
              ▼                             │
┌───────────────────────────┐               │
│ Self-Healing Loop         │               │
│ (improve_description.py)  │               │
│ Extracts missed keywords, │               │
│ tightens negative bounds  │               │
└─────────────┬─────────────┘               │
              │                             │
              ▼                             ▼
       Converged Model ─────────────► Final Benchmark
```

---

## 2. Fast Evaluation (3+2 Suite)

For quick turnaround during skill drafting, run:

```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode fast \
  --html
```

### Default Fast Suite
- **3 Positive Queries**:
  1. Direct invocation with skill name.
  2. Paraphrased request mentioning the task domain.
  3. Implicit request needing the skill's instructions.
- **2 Negative Queries**:
  1. General knowledge query (e.g., world facts).
  2. Unrelated code generation task.

---

## 3. Automated Self-Healing Loop (`run_loop.py`)

When trigger accuracy is suboptimal, use `run_loop.py` to automatically refine the description until it reaches target pass rate:

```bash
python3 /builtin_skills/skill-creator/scripts/run_loop.py \
  --skill-path /workspace/skills-draft/<name> \
  --eval-set /workspace/skills-draft/<name>/tests/eval_set.json \
  --max-iterations 3 \
  --target-pass-rate 100.0 \
  --holdout-ratio 0.3 \
  --html \
  --write
```

### Parameters
- `--max-iterations`: Maximum refinement cycles (default: 3).
- `--target-pass-rate`: Pass rate threshold to stop early (default: 100.0%).
- `--holdout-ratio`: Fraction of queries withheld as unseen test set (default: 0.3). Prevents description overfitting.
- `--write`: Automatically updates `SKILL.md` frontmatter with the best converged description.
- `--html`: Generates an interactive HTML dashboard viewable inside RikkaHub.

---

## 4. Viewing Reports in RikkaHub

When `--html` is specified, an interactive single-file report is generated:

```text
file:///workspace/skills-draft/<name>/iteration_history.html
```

Opening this link in chat or tapping it inside RikkaHub launches the built-in full-screen viewer. It displays:
- Overall pass rate, positive sensitivity, and negative specificity badges.
- Query-by-query breakdown with trigger confidence.
- Multi-iteration improvement trajectory and frontmatter character counts.
