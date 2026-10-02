# Skill Evaluation & Testing Guide

This guide describes how to test and evaluate Agent Skills inside RikkaHub workspaces using the built-in evaluation scripts.

## The Problem with Guessing Descriptions

A skill's `description` is the ONLY information the model sees before deciding whether to call `use_skill`.
- If the description is too narrow, the skill will fail to trigger on valid requests (Under-triggering).
- If the description is too broad, the skill will trigger unnecessarily on unrelated tasks (Over-triggering).
- RikkaHub enforces a maximum description length of **1024 characters** (`MAX_SKILL_DESCRIPTION_LENGTH`). Longer descriptions will be silently truncated, potentially losing critical trigger keywords.

## Evaluation Workflow

### 1. Staging Workflow (Drafting in Isolation)

Never create or edit skills directly inside `/skills/<name>/` while iterating.
Instead, use a staging workspace directory:
```
/workspace/skills-draft/<name>/
├── SKILL.md
├── references/
├── scripts/
└── assets/
```
Once validation and evals pass, copy or move the finished skill to `/skills/<name>/`.

### 2. Static Linting (Zero API Cost)

Before running model evaluations, always run the static linter:
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode lint
```
This checks:
- The `name` in frontmatter matches the directory name exactly.
- `description` does not exceed 1024 characters.
- All relative Markdown links in `SKILL.md` (e.g. `[guide](references/doc.md)`) point to real existing files.
- All files in `scripts/` have a `#!` shebang and executable permissions (`chmod +x`).

### 3. Fast Eval Set (3 Positive + 2 Negative)

A good evaluation set balances sensitivity (triggering when it should) and specificity (not triggering when it shouldn't).

Example `eval_set.json`:
```json
[
  {
    "query": "Can you format this commit message according to conventional commits?",
    "should_trigger": true
  },
  {
    "query": "Review my git commit log and make sure it follows the standard rules.",
    "should_trigger": true
  },
  {
    "query": "I need help with git commit standards.",
    "should_trigger": true
  },
  {
    "query": "What is the difference between git rebase and git merge?",
    "should_trigger": false
  },
  {
    "query": "Write a Python script to calculate prime numbers.",
    "should_trigger": false
  }
]
```

### 4. Running Model Evaluations

Run evaluations against a local loopback server or compatible endpoint:
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode eval \
  --eval-set /workspace/skills-draft/<name>/eval_set.json \
  --html
```
If you don't provide an `--eval-set`, you can use `--mode fast` to run the default 3+2 query template automatically:
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode fast \
  --html
```

### 5. Viewing Interactive Reports in RikkaHub

The `--html` flag outputs `eval_results.html` in the skill directory.
You can view this interactive Material Design 3 report directly inside RikkaHub by opening its file URL:
```text
file:///workspace/skills-draft/<name>/eval_results.html
```
The App's built-in web viewer will render the interactive dashboard with dark/light mode support, pass rates, and trigger metrics.
