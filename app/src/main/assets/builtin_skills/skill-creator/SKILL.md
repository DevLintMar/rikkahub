---
name: skill-creator
description: >-
  Create new Agent Skills or upgrade existing ones with industrial-grade tooling:
  progressive disclosure architecture, static linting, trigger fidelity evaluation,
  automated self-healing description optimization loop, interactive HTML dashboards,
  and zip distribution packaging. Use when the user wants to create a skill, convert
  instructions/workflows into a reusable skill, evaluate or optimize trigger accuracy
  in /skills, or package skills for distribution.
---

# Skill Creator: Industrial-Grade Skill Engineering Workshop

A skill is a self-contained directory containing a `SKILL.md` file (YAML frontmatter + Markdown SOP) and optional supporting files (references, scripts, and assets). Only the `name` and `description` of enabled skills are permanently loaded into the assistant's system context; the body and supporting files are disclosed on demand via the `use_skill` tool.

---

## 1. Where Skills Live

- **User Skills (`/skills/<name>/`)**: Workspace directory for user-created and imported skills. Editable and active.
- **Built-in System Meta-Skills (`/builtin_skills/<name>/`)**: Read-only core infrastructure (such as `skill-creator` and `environment-setup`). Protected system skills cannot be overridden; if a user creates a skill with the same name, it safely coexists as a `<name>-custom` variant.
- **Staging Draft Area (`/workspace/skills-draft/<name>/`)**: Isolated workspace directory for drafting and evaluating skills before publishing. Always draft here first to avoid polluting active conversation context.
- **Exported Packages (`/workspace/exports/<name>.zip`)**: Distribution packages ready for sharing or importing on other devices.

*Note: Newly published skills in `/skills/<name>/` appear in RikkaHub automatically, but are disabled by default. Always instruct the user to enable the skill in their Assistant configuration.*

---

## 2. Intent Capture Framework

Before generating files, clarify the skill's purpose across three dimensions:

1. **Trigger Boundary (Sensitivity vs Specificity)**:
   - What exact intents, verbs, and keywords MUST trigger this skill?
   - What similar but unrelated user tasks must NEVER trigger this skill?
2. **Deterministic vs Generative**:
   - Which operations require mathematical, exact, or high-speed execution? (Put them in `scripts/`).
   - Which operations require LLM reasoning, code synthesis, or natural language guidance? (Put them in `SKILL.md` or `references/`).
3. **Environment & Dependencies**:
   - Does the skill depend on Python 3, Shell utilities, or network access?
   - If Python is needed, recommend relying on standard libraries to avoid external dependency issues.

---

## 3. Progressive Disclosure Architecture

To conserve context window budget, organize skills into three distinct layers. Refer to [Progressive Disclosure Guide](references/progressive_disclosure.md) for full architectural patterns.

```text
/skills/<name>/
├── SKILL.md          # Tier 1 (Frontmatter) & Tier 2 (Workflow Playbook)
├── references/       # Tier 3: Deep domain references (read on-demand)
│   └── guide.md
├── scripts/          # Tier 3: Executable helpers (executed via workspace_shell)
│   └── helper.py
└── assets/           # Tier 3: Templates, mock fixtures, and static resources
    └── template.json
```

### Tier 1: Frontmatter Engineering (`SKILL.md`)
- `name` (required): Must match the directory name. Lowercase alphanumeric, hyphens, and underscores only (max 64 chars).
- `description` (required): High semantic density explaining **WHAT** the skill does and **EXACTLY WHEN** to use it.
  - **Hard Limit**: Maximum 1024 characters.
  - Written in third person (e.g. `Use when the user wants to...`).
  - Contains explicit trigger keywords and exclusion boundaries.

### Tier 2: Workflow SOP (`SKILL.md` Body)
- Loaded into context only when `use_skill(name="<name>")` is invoked.
- Contains sequential execution steps, checklists, branch logic, and navigation pointers to Tier 3 files.
- Keep the body concise and actionable. Move detailed tables and code to `references/` or `scripts/`.

### Tier 3: Supporting Files
- **`references/`**: Detailed documentation. Link via relative Markdown links (`[Deep Guide](references/guide.md)`). The model reads them with `use_skill(name="<name>", path="references/guide.md")`.
- **`scripts/`**: Deterministic automation scripts.
  - Must have shebangs (`#!/usr/bin/env python3` or `#!/bin/sh`).
  - Must be executable (`chmod +x scripts/<script_name>`).
  - Must be referenced using absolute paths (`/skills/<name>/scripts/<script_name>`).
- **`assets/`**: Static templates and boilerplate configurations.

---

## 4. End-to-End Development Workflow

### Step 1: Draft in Staging
Create the skill directory inside `/workspace/skills-draft/<name>/` and populate `SKILL.md`, `scripts/`, and `references/`.

### Step 2: Static Linting (0 Token Cost)
Run static linting to ensure frontmatter syntax, description length, relative link integrity, and script permissions are valid:
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode lint
```

### Step 3: Fast Trigger Evaluation
Run the standard 3+2 evaluation suite to verify trigger sensitivity and specificity:
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode fast \
  --html
```

### Step 4: Automated Self-Healing & Convergence Loop
If triggers fail or over-trigger, run the automated convergence loop. It splits evaluation queries into Train and Holdout sets, extracts missed keywords, tightens boundaries, and refines the description until it hits the target pass rate:
```bash
python3 /builtin_skills/skill-creator/scripts/run_loop.py \
  --skill-path /workspace/skills-draft/<name> \
  --max-iterations 3 \
  --target-pass-rate 100.0 \
  --holdout-ratio 0.3 \
  --html \
  --write
```
Refer to [Evaluation & Loop Guide](references/evaluation_and_loop_guide.md) for parameter details.

### Step 5: Publish to Active Workspace
Once validated, move the draft from staging to `/skills/<name>/`:
```bash
mv /workspace/skills-draft/<name> /skills/<name>
```

### Step 6: Package for Distribution (Optional)
Create an exportable zip package compatible with RikkaHub's "Import from file":
```bash
python3 /builtin_skills/skill-creator/scripts/package_skill.py \
  --skill-path /skills/<name> \
  --output /workspace/exports/<name>.zip
```

### Step 7: Inform and Remind the User
Provide the user with:
1. Interactive HTML Report Link: `file:///skills/<name>/eval_results.html` (opens full-screen viewer in RikkaHub).
2. Direct reminder to enable the skill in **Settings -> Agent Skills** for their assistant.
3. A sample trial prompt to test the newly installed skill.

---

## 5. Tooling Reference Table

| Script | Purpose | Key Flags |
|---|---|---|
| `scripts/run_eval.py` | Static linting & trigger evaluation | `--mode [lint\|fast\|eval]`, `--html`, `--eval-set` |
| `scripts/improve_description.py` | Semantic/heuristic description self-healing | `--false-negatives`, `--false-positives`, `--write` |
| `scripts/run_loop.py` | Automated Train/Holdout convergence loop | `--max-iterations`, `--target-pass-rate`, `--write`, `--html` |
| `scripts/package_skill.py` | Distribution packaging (.zip) & pre-lint | `--output`, `--skip-lint`, `--flat` |
| `scripts/generate_report.py` | Standalone interactive HTML report builder | `--input-json`, `--output-html` |

---

## 6. Without a Workspace

If workspace execution tools are unavailable:
1. Provide the complete `SKILL.md` content in a single code block.
2. Instruct the user to tap **+** in **Settings -> Agent Skills**, select **Add manually**, and paste the content.
3. For multi-file skills, provide the packaged `.zip` file for import via **Import from file**.

---

## 7. Pre-Publishing Checklist

- [ ] Directory name strictly matches `name` in frontmatter.
- [ ] `description` is under 1024 characters and starts with third-person imperative.
- [ ] All relative links in Markdown point to existing files.
- [ ] All scripts have shebangs (`#!`) and executable permissions (`chmod +x`).
- [ ] Static linting passes with 0 errors.
- [ ] Evaluated sensitivity (positive queries trigger `use_skill`) and specificity (negative queries do not trigger).
- [ ] Moved from staging to `/skills/<name>/`.
