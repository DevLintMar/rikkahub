#!/usr/bin/env python3
"""RikkaHub Skill Evaluation Report Generator.

Generates a standalone, dependency-free HTML report (viewer.html) from evaluation JSON results.
Compatible with pure Python 3 standard library.
"""

import argparse
import html
import json
import sys
from pathlib import Path


def render_html_report(data: dict) -> str:
    skill_name = html.escape(str(data.get("skill_name", "Unknown Skill")))
    description = html.escape(str(data.get("description", "No description provided.")))
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
        </tr>
        """)

    table_body = "\n".join(rows_html)
    json_dump = html.escape(json.dumps(data, indent=2))

    c_pass = "pass" if pass_rate >= 80 else "fail"
    c_pos = "pass" if pos_rate >= 80 else "fail"
    c_neg = "pass" if neg_rate >= 80 else "fail"

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
      }}
    }}
    body {{
      margin: 0;
      padding: 1.5rem;
      background-color: var(--bg-color);
      color: var(--text-main);
      font-family: var(--font-family);
      line-height: 1.5;
    }}
    .container {{
      max-width: 900px;
      margin: 0 auto;
    }}
    .header-card {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      padding: 1.5rem;
      margin-bottom: 1.5rem;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
    }}
    .title-row {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.5rem;
    }}
    h1 {{
      margin: 0;
      font-size: 1.5rem;
      font-weight: 700;
    }}
    .desc {{
      color: var(--text-muted);
      font-size: 0.95rem;
      margin-top: 0.25rem;
      margin-bottom: 1rem;
    }}
    .metrics-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 1rem;
      margin-top: 1rem;
    }}
    .metric-card {{
      background: var(--bg-color);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 1rem;
      text-align: center;
    }}
    .metric-val {{
      font-size: 1.75rem;
      font-weight: 700;
      color: var(--primary);
    }}
    .metric-val.pass {{ color: var(--pass); }}
    .metric-val.fail {{ color: var(--fail); }}
    .metric-label {{
      font-size: 0.8rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
      margin-top: 0.25rem;
    }}
    .table-card {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      overflow: hidden;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
      margin-bottom: 1.5rem;
    }}
    table {{
      width: 100%;
      border-collapse: collapse;
      font-size: 0.9rem;
      text-align: left;
    }}
    th {{
      background-color: var(--bg-color);
      border-bottom: 1px solid var(--border-color);
      padding: 0.75rem 1rem;
      color: var(--text-muted);
      font-weight: 600;
      font-size: 0.8rem;
      text-transform: uppercase;
    }}
    td {{
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-color);
    }}
    tr:last-child td {{
      border-bottom: none;
    }}
    .col-num {{ width: 40px; color: var(--text-muted); text-align: center; }}
    .col-status {{ width: 80px; text-align: center; }}
    .status-badge {{
      display: inline-block;
      padding: 0.2rem 0.5rem;
      border-radius: 4px;
      font-weight: 700;
      font-size: 0.75rem;
    }}
    .status-badge.pass {{ background: var(--pass-bg); color: var(--pass); }}
    .status-badge.fail {{ background: var(--fail-bg); color: var(--fail); }}
    .badge {{
      display: inline-block;
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      font-size: 0.75rem;
      font-weight: 600;
    }}
    .expected-true {{ background: var(--primary-light); color: var(--primary); }}
    .expected-false {{ background: var(--bg-color); color: var(--text-muted); border: 1px solid var(--border-color); }}
    details {{
      background-color: var(--card-bg);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      padding: 0.75rem 1rem;
      font-size: 0.85rem;
    }}
    summary {{
      cursor: pointer;
      font-weight: 600;
      color: var(--text-muted);
    }}
    pre {{
      background: var(--bg-color);
      padding: 0.75rem;
      border-radius: 6px;
      overflow-x: auto;
      font-size: 0.8rem;
    }}
  </style>
</head>
<body>
  <div class="container">
    <div class="header-card">
      <div class="title-row">
        <h1>Skill Eval Report: {skill_name}</h1>
      </div>
      <div class="desc"><strong>Description:</strong> {description}</div>
      <div class="metrics-grid">
        <div class="metric-card">
          <div class="metric-val {c_pass}">{pass_rate:.1f}%</div>
          <div class="metric-label">Pass Rate ({passed}/{total})</div>
        </div>
        <div class="metric-card">
          <div class="metric-val {c_pos}">{pos_rate:.1f}%</div>
          <div class="metric-label">Trigger Sensitivity ({pos_passed}/{pos_total})</div>
        </div>
        <div class="metric-card">
          <div class="metric-val {c_neg}">{neg_rate:.1f}%</div>
          <div class="metric-label">Negative Specificity ({neg_passed}/{neg_total})</div>
        </div>
      </div>
    </div>

    <div class="table-card">
      <table>
        <thead>
          <tr>
            <th class="col-num">#</th>
            <th class="col-status">Result</th>
            <th>Test Query</th>
            <th>Expected</th>
            <th>Trigger Rate</th>
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
</html>"""


def main():
    parser = argparse.ArgumentParser(description="Generate HTML viewer report from skill eval JSON")
    parser.add_argument("--input", required=True, help="Path to input eval JSON file")
    parser.add_argument("--output", default=None, help="Path to output HTML file (default: alongside JSON)")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Error: input file {input_path} not found.", file=sys.stderr)
        sys.exit(1)

    with open(input_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    html_content = render_html_report(data)

    output_path = Path(args.output) if args.output else input_path.with_suffix(".html")
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"Report successfully generated at: {output_path}")


if __name__ == "__main__":
    main()
