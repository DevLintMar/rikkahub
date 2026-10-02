---
name: skill-creator
description: >-
  Create new Agent Skills or update existing ones, with static linting, trigger fidelity evaluation, and interactive HTML reports. Use when the user wants to make a skill, turn a workflow, prompt, or set of instructions into a reusable skill, or improve and test skills in /skills.
---

# Skill Creator

A skill is a directory containing a `SKILL.md` file (YAML frontmatter + Markdown instructions) and optional supporting files. Only the `name` and `description` of enabled skills are always in context; the body is loaded with the `use_skill` tool when a user request matches, and supporting files are loaded on demand.

## Where Skills Live

- **User skills**: `/skills/<name>/` in the workspace. Writable.
- **Built-in skills**: `/builtin_skills/<name>/`, read-only. System meta-skills (like skill-creator and environment-setup) are protected system infrastructure. If a custom skill has the same name, it will be loaded as a custom variant (e.g. `<name>-custom`) without overriding the official meta-skill.
- **Staging draft area**: `/workspace/skills-draft/<name>/`. Recommended during authoring to isolate work-in-progress drafts from active context.
- A new skill in `/skills/<name>/` appears in the app's skill list automatically, but is disabled by default. Always remind the user to enable it for their assistant.

## Recommended Workflow

1. **Understand requirements**: Clarify the scope, exact trigger queries, and expected outputs.
2. **Draft in Staging**: Create `/workspace/skills-draft/<name>/` and assemble files.
3. **Static Linting**: Run `/builtin_skills/skill-creator/scripts/run_eval.py --mode lint` to ensure frontmatter, description length, and file links are valid.
4. **Evaluate Trigger Accuracy**: Run trigger evaluations (Fast 3+2 query suite) to verify the model triggers `use_skill` appropriately. Refer to [Evaluation Guide](references/evaluation_guide.md) for details.
5. **Publish**: Move the validated directory from `/workspace/skills-draft/<name>/` to `/skills/<name>/`.
6. **Report**: Inform the user, provide the interactive HTML report link, remind them to enable the skill, and suggest a trial prompt.

## Directory Layout

```text
/skills/<name>/
├── SKILL.md
├── references/   # detailed reference docs, loaded on demand
├── scripts/      # executable helpers (must have #! shebang and chmod +x)
└── assets/       # templates, mock data, and static assets
```

## SKILL.md Specification

```markdown
---
name: my-skill
description: >-
  What the skill does and when to use it, including trigger keywords.
compatibility: Requires python3 (optional)
---

# My Skill

Detailed imperative instructions...
```

### Frontmatter Rules
- Must begin with `---` on line 1, and end with `---`.
- `name` (required): MUST match the directory name. Lowercase letters, digits, and hyphens only (max 64 chars).
- `description` (required): What the skill does and when to use it.
  - **Hard limit**: Maximum 1024 characters. Longer descriptions will be truncated.
  - Write in third person, including natural user phrasing and keywords.
- `compatibility` (optional): Environment prerequisites (e.g. `Requires python3`).

### Body & Supporting Files
- **References**: Document deep instructions in `references/`. Link them using relative Markdown links, e.g. `[Documentation](references/guide.md)`. The `use_skill` tool can read these sub-paths via its `path` argument. Keep links one level deep.
- **Scripts**: Place deterministic or complex logic into `scripts/`.
  - Always add a shebang (e.g. `#!/bin/sh` or `#!/usr/bin/env python3`).
  - Set executable permissions: `chmod +x scripts/<script_name>`.
  - Reference scripts using absolute paths (e.g. `/skills/<name>/scripts/<script_name>`).
  - Rely on standard libraries or declare external dependencies in `compatibility`.
- **Assets**: Text templates or configuration examples in `assets/`.

## Validation & Evaluation Tools

Helper scripts are available in `/builtin_skills/skill-creator/scripts/`:

### 1. Static Linting (0 Token Cost)
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode lint
```
Validates:
- Directory name equals `name`.
- Description is <= 1024 characters.
- All Markdown links resolve to real files.
- Scripts have shebangs and executable flags.

### 2. Fast Evaluation (3 Positive + 2 Negative Queries)
```bash
python3 /builtin_skills/skill-creator/scripts/run_eval.py \
  --skill-path /workspace/skills-draft/<name> \
  --mode fast \
  --html
```
Tests sensitivity (triggering on relevant requests) and specificity (not triggering on general or irrelevant queries).

### 3. Interactive HTML Viewer
The `--html` option outputs `eval_results.html`. You can share the clickable file link with the user:
```text
file:///workspace/skills-draft/<name>/eval_results.html
```
Clicking this link opens the full-screen interactive dashboard inside RikkaHub.

## Without a Workspace

If workspace tools (`workspace_shell`, `workspace_write_file`) are unavailable:
1. Output the complete `SKILL.md` content in a single code block.
2. Instruct the user to tap **+** on the Agent Skills page, select **Add manually**, and paste the content.
3. Multi-file skills can be packed into a `.zip` and imported via **Import from file**.

## Checklist Before Publishing

- [ ] Directory name matches frontmatter `name`.
- [ ] `description` is concise, keyword-rich, and under 1024 characters.
- [ ] All relative file links exist.
- [ ] Every script has a shebang and `chmod +x`.
- [ ] Static linting passed with 0 errors.
- [ ] Moved from staging to `/skills/<name>/`.
