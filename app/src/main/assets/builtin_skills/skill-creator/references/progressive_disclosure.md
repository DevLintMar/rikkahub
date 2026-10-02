# Progressive Disclosure Architecture for Agent Skills

In RikkaHub and modern agentic architectures, context window space and prompt caching efficiency are paramount. Agent Skills are designed around a **Three-Tier Progressive Disclosure Model** to deliver maximum capability with minimal continuous context overhead.

---

## 1. The Three Tiers

```text
┌────────────────────────────────────────────────────────────────────────┐
│ Tier 1: Frontmatter (Name + Description)                                │
│ Always in Context (via <available_skills>). Hard limit: 1024 characters.│
│ Sole purpose: Enable the model to determine WHEN to call use_skill.   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Model calls use_skill(name)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Tier 2: SKILL.md Body (Workflow Orchestration)                          │
│ Loaded into context ONLY when the skill is active.                     │
│ Core workflow instructions, decision trees, checklists, and safety.   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Model calls use_skill(name, path)
                                    │ or executes scripts via workspace_shell
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Tier 3: References, Scripts & Assets                                   │
│ Deep domain documentation, deterministic scripts, static templates.   │
│ Loaded or executed strictly on-demand.                                 │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Tier 1: Frontmatter Engineering

Only `name` and `description` are loaded into every conversation prompt when skills are enabled.

### Rules of Frontmatter
- **Character Budget**: RikkaHub strictly enforces `MAX_SKILL_DESCRIPTION_LENGTH = 1024`.
- **Third-Person Imperative**: Begin with "Use when the user wants to..." or "Use when asked to...".
- **Trigger Fidelity**:
  - Incorporate core domain nouns, verbs, error signatures, and user phrasing.
  - State both **inclusion** criteria ("Use when...") and **exclusion** criteria ("Do NOT use for general...").
  - Do not stuff instructions into description; description only explains *when* to load Tier 2.

---

## 3. Tier 2: SKILL.md Body Engineering

The body of `SKILL.md` is the operational playbook (SOP).

### What Belongs in Tier 2
1. **Executive Summary**: A 1-2 sentence overview of the methodology.
2. **Step-by-Step Workflow**: Numbered sequential phases (e.g., Phase 1: Inspect -> Phase 2: Execute -> Phase 3: Verify).
3. **Navigation Pointers**: Markdown links pointing to Tier 3 references for detailed sub-tasks.
4. **Safety & Fallbacks**: What to do if commands fail or permissions are missing.

### What Does NOT Belong in Tier 2
- Hundreds of lines of code or complex tables. Move them to `scripts/` or `references/`.
- Deep theoretical tutorials. Move them to `references/`.

---

## 4. Tier 3: Supporting Files

### 4.1 References (`references/*.md`)
- Deep reference manuals, API schema tables, or exhaustive format specifications.
- Referenced from `SKILL.md` via relative links: `[Architecture Guide](references/architecture.md)`.
- Loaded dynamically by the AI using:
  ```json
  {"name": "my-skill", "path": "references/architecture.md"}
  ```

### 4.2 Scripts (`scripts/*`)
- Any complex, repetitive, or deterministic operation should be a script rather than a 50-step natural language prompt.
- **Benefits**:
  - 100% deterministic execution.
  - 0 Token cost during computation.
  - Faster wall-clock execution inside the sandbox.
- **Requirements**:
  - Must include a shebang (`#!/usr/bin/env python3` or `#!/bin/sh`).
  - Must have executable permissions (`chmod +x scripts/my_script.py`).
  - Must rely strictly on standard libraries or clearly documented dependencies.

### 4.3 Assets (`assets/*`)
- Configuration templates, mock fixtures, boilerplates.
- Read by AI or copied directly into the workspace.
