#!/usr/bin/env python3
"""Interactive HTML Evaluation Report Generator for RikkaHub Skills.

Zero third-party dependencies. Compatible with pure Python 3 standard library.
Generates single-file, responsive, self-contained HTML reports with:
- MD3 Adaptive Theme (Dark / Light)
- Truthful Execution Mode & Warning Banners (Live LLM vs Offline Heuristic Simulation)
- Summary metrics (Pass Rate, Positive Sensitivity, Negative Specificity)
- Granular query-level trigger results & semantic reasons
"""

import argparse
import html
import json
import sys
from pathlib import Path


def render_html_report(data: dict) -> str:
    """Render self-contained interactive HTML report from evaluation results JSON."""
    skill_name = html.escape(str(data.get("skill_name", "Unknown Skill")))
    description = html.escape(str(data.get("description", "")))
    model = html.escape(str(data.get("model", "Unknown Model")))
    eval_mode = html.escape(str(data.get("evaluation_mode", "Heuristic Simulation")))
    is_simulated = bool(data.get("is_simulated", True))
    warning = data.get("warning")

    summary = data.get("summary", {})
    total = summary.get("total", 0)
    passed = summary.get("passed", 0)
    failed = summary.get("failed", 0)
    pass_rate = (passed / total * 100) if total > 0 else 0.0

    results = data.get("results", [])

    # Calculate detailed metrics
    pos_total = sum(1 for r in results if r.get("should_trigger"))
    pos_passed = sum(1 for r in results if r.get("should_trigger") and r.get("pass"))
    neg_total = sum(1 for r in results if not r.get("should_trigger"))
    neg_passed = sum(1 for r in results if not r.get("should_trigger") and r.get("pass"))

    pos_rate = (pos_passed / pos_total * 100) if pos_total > 0 else 100.0
    neg_rate = (neg_passed / neg_total * 100) if neg_total > 0 else 100.0

    # Build rows
    rows_html = []
    for idx, item in enumerate(results, start=1):
        query = html.escape(str(item.get("query", "")))
        should_trigger = bool(item.get("should_trigger", False))
        is_pass = bool(item.get("pass", False))
        triggers = item.get("triggers", 1 if item.get("triggered") else 0)
        runs = item.get("runs", 1)
        rate = item.get("trigger_rate", (triggers / runs) if runs > 0 else 0.0)
        reason = html.escape(str(item.get("reason", "N/A")))

        status_class = "pass" if is_pass else "fail"
        status_label = "PASS" if is_pass else "FAIL"

        expected_badge = '<span class="badge expected-true">SHOULD TRIGGER</span>' if should_trigger else '<span class="badge expected-false">DO NOT TRIGGER</span>'
        actual_desc = f"{int(rate * 100)}% ({triggers}/{runs})"

        rows_html.append(f"""
        <tr class="{status_class}">
          <td class="col-num">{idx}</td>
          <td class="col-status"><span class="status-badge {status_class}">{status_label}</span></td>
          <td class="col-query">{query}</td>
          <td class="col-expected">{expected_badge}</td>
          <td class="col-actual">{actual_desc}</td>
          <td class="col-reason">{reason}</td>
        </tr>
        """)

    table_body = "\n".join(rows_html)
    json_dump = html.escape(json.dumps(data, indent=2))

    c_pass = "pass" if pass_rate >= 80 else "fail"
    c_pos = "pass" if pos_rate >= 80 else "fail"
    c_neg = "pass" if neg_rate >= 80 else "fail"

    warning_banner_html = ""
    if is_simulated:
        warning_banner_html = """
    <div class="banner warning-banner">
      <div class="banner-title">⚠️ 注意：当前运行在【离线启发式模拟（Wiring Smoke Test）】模式</div>
      <div class="banner-desc">
        本次评测未连接真实大模型（<code>model: offline-simulated-heuristics</code>），仅对本地关键词/意图规则与连通性进行了冒烟检验。<br>
        <strong>该分数不代表大模型的真实触发质量</strong>。如需进行真实模型端点评测，请在执行时传入 <code>--api-base</code> 或配置环境变量 <code>LLM_API_BASE</code>。
      </div>
    </div>
        """

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Skill Eval Report: {skill_name}</title>
  <style>
    :root {{
      --bg-color: #f8fafc;
      --card-bg: #ffffff;
      --text-main: #0f172a;
      --text-muted: #64748b;
      --border-color: #e2e8f0;
      --primary: #2563eb;
      --primary-light: #eff6ff;
      --pass: #16a34a;
      --pass-bg: #f0fdf4;
      --fail: #dc2626;
      --fail-bg: #fef2f2;
      --warn-border: #f59e0b;
      --warn-bg: #fffbeb;
      --warn-text: #b45309;
      --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }}
    @media (prefers-color-scheme: dark) {{
      :root {{
        --bg-color: #0f172a;
        --card-bg: #1e293b;
        --text-main: #f8fafc;
        --text-muted: #94a3b8;
        --border-color: #334155;
        --primary: #3b82f6;
        --primary-light: #1e3a8a;
        --pass: #22c55e;
        --pass-bg: #14532d;
        --fail: #ef4444;
        --fail-bg: #7f1d1d;
        --warn-border: #d97706;
        --warn-bg: #451a03;
        --warn-text: #fde68a;
      }}
    }}
    body {{
      margin: 0;
      padding: 1.25rem;
      background-color: var(--bg-color);
      color: var(--text-main);
      font-family: var(--font-family);
      line-height: 1.5;
    }}
    .container {{
      max-width: 1000px;
      margin: 0 auto;
    }}
    .header {{
      margin-bottom: 1.25rem;
    }}
    .title {{
      font-size: 1.5rem;
      font-weight: 700;
      margin: 0 0 0.25rem 0;
    }}
    .desc {{
      color: var(--text-muted);
      font-size: 0.9rem;
      margin: 0 0 0.5rem 0;
      line-height: 1.4;
    }}
    .meta-badges {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }}
    .meta-badge {{
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      padding: 0.2rem 0.6rem;
      font-size: 0.8rem;
      color: var(--text-muted);
    }}
    .meta-badge strong {{
      color: var(--text-main);
    }}
    .banner {{
      border-radius: 8px;
      padding: 0.75rem 1rem;
      margin-bottom: 1.25rem;
      font-size: 0.85rem;
    }}
    .warning-banner {{
      background-color: var(--warn-bg);
      border: 1px solid var(--warn-border);
      color: var(--warn-text);
    }}
    .banner-title {{
      font-weight: 700;
      margin-bottom: 0.25rem;
    }}
    .banner-desc {{
      line-height: 1.4;
      opacity: 0.95;
    }}
    .metrics-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 0.75rem;
      margin-bottom: 1.25rem;
    }}
    .metric-card {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0.85rem;
      text-align: center;
    }}
    .metric-val {{
      font-size: 1.6rem;
      font-weight: 700;
      color: var(--primary);
    }}
    .metric-val.pass {{ color: var(--pass); }}
    .metric-val.fail {{ color: var(--fail); }}
    .metric-label {{
      font-size: 0.75rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
      margin-top: 0.2rem;
    }}
    .table-card {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      overflow-x: auto;
      margin-bottom: 1.25rem;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      font-size: 0.85rem;
      text-align: left;
      min-width: 650px;
    }}
    th {{
      background-color: var(--bg-color);
      border-bottom: 1px solid var(--border-color);
      padding: 0.6rem 0.75rem;
      color: var(--text-muted);
      font-weight: 600;
      font-size: 0.75rem;
      text-transform: uppercase;
    }}
    td {{
      padding: 0.6rem 0.75rem;
      border-bottom: 1px solid var(--border-color);
      vertical-align: top;
    }}
    tr:last-child td {{
      border-bottom: none;
    }}
    .col-num {{ width: 35px; color: var(--text-muted); text-align: center; }}
    .col-status {{ width: 65px; text-align: center; }}
    .status-badge {{
      display: inline-block;
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      font-weight: 700;
      font-size: 0.7rem;
    }}
    .status-badge.pass {{ background: var(--pass-bg); color: var(--pass); }}
    .status-badge.fail {{ background: var(--fail-bg); color: var(--fail); }}
    .badge {{
      display: inline-block;
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      font-size: 0.7rem;
      font-weight: 600;
    }}
    .expected-true {{ background: var(--primary-light); color: var(--primary); }}
    .expected-false {{ background: var(--bg-color); color: var(--text-muted); border: 1px solid var(--border-color); }}
    .col-query {{ max-width: 250px; word-break: break-word; }}
    .col-reason {{ color: var(--text-muted); font-size: 0.8rem; word-break: break-word; }}
    details {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0.6rem 0.85rem;
      font-size: 0.8rem;
    }}
    summary {{
      cursor: pointer;
      font-weight: 600;
      color: var(--text-muted);
    }}
    pre {{
      margin-top: 0.5rem;
      padding: 0.6rem;
      background: var(--bg-color);
      border-radius: 4px;
      overflow-x: auto;
      font-size: 0.75rem;
    }}
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <h1 class="title">{skill_name}</h1>
      <p class="desc">{description}</p>
      <div class="meta-badges">
        <span class="meta-badge">Mode: <strong>{eval_mode}</strong></span>
        <span class="meta-badge">Model: <strong>{model}</strong></span>
        <span class="meta-badge">Total Tests: <strong>{total}</strong></span>
      </div>
    </div>

    {warning_banner_html}

    <div class="metrics-grid">
      <div class="metric-card">
        <div class="metric-val {c_pass}">{pass_rate:.1f}%</div>
        <div class="metric-label">Overall Pass Rate ({passed}/{total})</div>
      </div>
      <div class="metric-card">
        <div class="metric-val {c_pos}">{pos_rate:.1f}%</div>
        <div class="metric-label">Sensitivity ({pos_passed}/{pos_total})</div>
      </div>
      <div class="metric-card">
        <div class="metric-val {c_neg}">{neg_rate:.1f}%</div>
        <div class="metric-label">Specificity ({neg_passed}/{neg_total})</div>
      </div>
    </div>

    <div class="table-card">
      <table>
        <thead>
          <tr>
            <th class="col-num">#</th>
            <th class="col-status">Status</th>
            <th>Query</th>
            <th>Target Expectation</th>
            <th>Actual Result</th>
            <th>Trigger Attribution / Reason</th>
          </tr>
        </thead>
        <tbody>
          {table_body}
        </tbody>
      </table>
    </div>

    <details>
      <summary>View Raw JSON Data</summary>
      <pre>{json_dump}</pre>
    </details>
  </div>
</body>
</html>
"""


def main():
    parser = argparse.ArgumentParser(description="Generate Interactive HTML Evaluation Report")
    parser.add_argument("--input-json", required=True, help="Path to eval_results.json")
    parser.add_argument("--output-html", default=None, help="Path to save HTML report")
    args = parser.parse_args()

    in_path = Path(args.input_json)
    if not in_path.exists():
        print(f"Error: {in_path} does not exist", file=sys.stderr)
        sys.exit(1)

    with open(in_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    html_content = render_html_report(data)
    out_path = Path(args.output_html) if args.output_html else in_path.with_suffix(".html")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"HTML Report generated successfully: {out_path}")


if __name__ == "__main__":
    main()
