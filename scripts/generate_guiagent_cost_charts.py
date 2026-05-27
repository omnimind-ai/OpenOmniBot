#!/usr/bin/env python3
"""Generate GUI Agent token/time cost charts as dependency-free SVG files."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "docs/omniflow/reports/guiagent-cost-data.json"
DEFAULT_OUT = ROOT / "docs/omniflow/reports/assets"


COLORS = {
    "online_no_recall": "#0b6bcb",
    "online_with_recall_guidance": "#99621b",
    "offline_replay": "#147a52",
    "recall_segment": "#5f6b7a",
    "image": "#7c3aed",
    "residual": "#94a3b8",
}


def fmt_int(value: int | float | None) -> str:
    if value is None:
        return "-"
    return f"{int(round(value)):,}"


def load_data(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def bar_chart(
    title: str,
    subtitle: str,
    rows: list[dict[str, Any]],
    value_key: str,
    unit: str,
    output: Path,
    width: int = 1120,
    row_height: int = 42,
) -> None:
    margin_left = 230
    margin_right = 150
    margin_top = 96
    margin_bottom = 34
    bar_height = 20
    height = margin_top + margin_bottom + row_height * len(rows)
    plot_width = width - margin_left - margin_right
    max_value = max((int(row.get(value_key) or 0) for row in rows), default=1)
    max_value = max(max_value, 1)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
        "<style>",
        "text{font-family:Inter,Arial,sans-serif;fill:#17202a} .muted{fill:#5f6b7a} .title{font-size:26px;font-weight:800} .subtitle{font-size:14px} .label{font-size:13px}.value{font-size:13px;font-weight:700}.axis{stroke:#dce3ea;stroke-width:1}.bar-bg{fill:#edf1f5}",
        "</style>",
        '<rect width="100%" height="100%" rx="8" fill="#ffffff"/>',
        f'<text x="24" y="36" class="title">{html.escape(title)}</text>',
        f'<text x="24" y="62" class="subtitle muted">{html.escape(subtitle)}</text>',
        f'<line x1="{margin_left}" y1="{margin_top - 18}" x2="{width - margin_right}" y2="{margin_top - 18}" class="axis"/>',
    ]

    for idx, row in enumerate(rows):
        y = margin_top + idx * row_height
        value = int(row.get(value_key) or 0)
        bar_width = 0 if value == 0 else max(2, value / max_value * plot_width)
        color = COLORS.get(str(row.get("group", "")), COLORS["online_no_recall"])
        label = str(row.get("name", ""))
        extra = str(row.get("extra", ""))
        parts += [
            f'<text x="24" y="{y + 15}" class="label">{html.escape(label)}</text>',
            f'<rect x="{margin_left}" y="{y}" width="{plot_width}" height="{bar_height}" rx="5" class="bar-bg"/>',
            f'<rect x="{margin_left}" y="{y}" width="{bar_width:.1f}" height="{bar_height}" rx="5" fill="{color}"/>',
            f'<text x="{margin_left + plot_width + 14}" y="{y + 15}" class="value">{fmt_int(value)} {html.escape(unit)}</text>',
        ]
        if extra:
            parts.append(f'<text x="24" y="{y + 32}" class="label muted">{html.escape(extra)}</text>')

    parts.append("</svg>")
    write_text(output, "\n".join(parts))


def stacked_step_chart(
    title: str,
    subtitle: str,
    rows: list[dict[str, Any]],
    total_key: str,
    step_value_key: str,
    unit: str,
    output: Path,
    width: int = 1120,
    row_height: int = 50,
) -> None:
    margin_left = 230
    margin_right = 155
    margin_top = 98
    margin_bottom = 44
    bar_height = 22
    height = margin_top + margin_bottom + row_height * len(rows)
    plot_width = width - margin_left - margin_right
    max_value = max((int(row.get(total_key) or 0) for row in rows), default=1)
    max_value = max(max_value, 1)
    palette = ["#0b6bcb", "#147a52", "#99621b", "#7c3aed", "#5f6b7a", "#94a3b8"]

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
        "<style>",
        "text{font-family:Inter,Arial,sans-serif;fill:#17202a}.muted{fill:#5f6b7a}.title{font-size:26px;font-weight:800}.subtitle{font-size:14px}.label{font-size:13px}.value{font-size:13px;font-weight:700}.bar-bg{fill:#edf1f5}",
        "</style>",
        '<rect width="100%" height="100%" rx="8" fill="#ffffff"/>',
        f'<text x="24" y="36" class="title">{html.escape(title)}</text>',
        f'<text x="24" y="62" class="subtitle muted">{html.escape(subtitle)}</text>',
    ]

    for idx, row in enumerate(rows):
        y = margin_top + idx * row_height
        total = int(row.get(total_key) or 0)
        label = str(row.get("name", ""))
        parts += [
            f'<text x="24" y="{y + 15}" class="label">{html.escape(label)}</text>',
            f'<rect x="{margin_left}" y="{y}" width="{plot_width}" height="{bar_height}" rx="5" class="bar-bg"/>',
        ]
        x = margin_left
        steps = row.get("steps") or []
        if steps:
            for step_index, step in enumerate(steps):
                step_value = int(step.get(step_value_key) or 0)
                width_px = step_value / max_value * plot_width
                parts.append(
                    f'<rect x="{x:.1f}" y="{y}" width="{max(width_px, 1):.1f}" height="{bar_height}" rx="3" fill="{palette[step_index % len(palette)]}"/>'
                )
                x += width_px
            step_label = " / ".join(f"{step.get('name')} {fmt_int(step.get(step_value_key))}" for step in steps)
            parts.append(f'<text x="24" y="{y + 34}" class="label muted">{html.escape(step_label)}</text>')
        else:
            color = COLORS.get(str(row.get("group", "")), COLORS["online_no_recall"])
            width_px = total / max_value * plot_width
            parts.append(f'<rect x="{margin_left}" y="{y}" width="{max(width_px, 1):.1f}" height="{bar_height}" rx="5" fill="{color}"/>')
            parts.append(f'<text x="24" y="{y + 34}" class="label muted">未记录步骤拆分</text>')
        parts.append(f'<text x="{margin_left + plot_width + 14}" y="{y + 15}" class="value">{fmt_int(total)} {html.escape(unit)}</text>')

    parts.append("</svg>")
    write_text(output, "\n".join(parts))


def combined_token_time_chart(
    title: str,
    subtitle: str,
    cases: list[dict[str, Any]],
    output: Path,
    width: int = 1120,
    row_height: int = 58,
) -> None:
    margin_left = 245
    margin_right = 175
    margin_top = 96
    margin_bottom = 40
    bar_height = 14
    gap = 6
    height = margin_top + margin_bottom + row_height * len(cases)
    plot_width = width - margin_left - margin_right
    max_tokens = max((int((case.get("online") or {}).get("total_tokens") or 0) for case in cases), default=1)
    max_ms = max((int((case.get("local") or {}).get("duration_ms") or 0) for case in cases), default=1)
    max_tokens = max(max_tokens, 1)
    max_ms = max(max_ms, 1)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(title)}">',
        "<style>",
        "text{font-family:Inter,Arial,sans-serif;fill:#17202a}.muted{fill:#5f6b7a}.title{font-size:26px;font-weight:800}.subtitle{font-size:14px}.label{font-size:13px}.value{font-size:13px;font-weight:700}.bar-bg{fill:#edf1f5}.token{fill:#0b6bcb}.time{fill:#147a52}",
        "</style>",
        '<rect width="100%" height="100%" rx="8" fill="#ffffff"/>',
        f'<text x="24" y="36" class="title">{html.escape(title)}</text>',
        f'<text x="24" y="62" class="subtitle muted">{html.escape(subtitle)}</text>',
        '<rect x="24" y="76" width="14" height="10" class="token"/><text x="44" y="85" class="label muted">在线 token</text>',
        '<rect x="162" y="76" width="14" height="10" class="time"/><text x="182" y="85" class="label muted">本地 / replay 用时</text>',
    ]

    for idx, case in enumerate(cases):
        y = margin_top + idx * row_height
        online = case.get("online") or {}
        local = case.get("local") or {}
        tokens = int(online.get("total_tokens") or 0)
        ms = int(local.get("duration_ms") or 0)
        token_width = 0 if tokens == 0 else max(2, tokens / max_tokens * plot_width)
        time_width = 0 if ms == 0 else max(2, ms / max_ms * plot_width)
        parts += [
            f'<text x="24" y="{y + 14}" class="label">{html.escape(str(case.get("name", "")))}</text>',
            f'<text x="24" y="{y + 32}" class="label muted">{html.escape(str(case.get("goal", ""))[:80])}</text>',
            f'<rect x="{margin_left}" y="{y}" width="{plot_width}" height="{bar_height}" rx="4" class="bar-bg"/>',
            f'<rect x="{margin_left}" y="{y}" width="{token_width:.1f}" height="{bar_height}" rx="4" class="token"/>',
            f'<rect x="{margin_left}" y="{y + bar_height + gap}" width="{plot_width}" height="{bar_height}" rx="4" class="bar-bg"/>',
            f'<rect x="{margin_left}" y="{y + bar_height + gap}" width="{time_width:.1f}" height="{bar_height}" rx="4" class="time"/>',
            f'<text x="{margin_left + plot_width + 14}" y="{y + 12}" class="value">{fmt_int(tokens)} tok</text>',
            f'<text x="{margin_left + plot_width + 14}" y="{y + 32}" class="value">{fmt_int(ms) if ms else "-"} ms</text>',
        ]

    parts.append("</svg>")
    write_text(output, "\n".join(parts))


def step_summary(steps: list[dict[str, Any]], missing_note: str = "") -> str:
    if not steps:
        return html.escape(missing_note or "步骤明细未记录")
    lines = []
    for step in steps:
        tokens = step.get("tokens")
        duration = step.get("duration_ms")
        token_text = f"{fmt_int(tokens)} tok" if tokens is not None else "token 未记录"
        duration_text = f"{fmt_int(duration)} ms" if duration is not None else "用时未记录"
        lines.append(
            f"{step.get('index', '')}. {html.escape(str(step.get('name', 'step')))} "
            f"<span class=\"muted\">({token_text}, {duration_text})</span>"
        )
    return "<br>".join(lines)


def detail_table_html(cases: list[dict[str, Any]], output: Path) -> None:
    rows = []
    for case in cases:
        online = case.get("online") or {}
        local = case.get("local") or {}
        online_steps = step_summary(online.get("steps") or [], online.get("note", "在线步骤明细未记录"))
        local_steps = step_summary(local.get("steps") or [], local.get("note", "本地步骤明细未记录"))
        rows.append(
            "<tr>"
            f"<td><strong>{html.escape(str(case.get('name', '')))}</strong><br><span class=\"muted\">{html.escape(str(case.get('goal', '')))}</span></td>"
            f"<td class=\"num\">{fmt_int(online.get('total_tokens'))}</td>"
            f"<td class=\"num\">{online.get('call_count') if online.get('call_count') is not None else '-'}</td>"
            f"<td>{online_steps}</td>"
            f"<td class=\"num\">{fmt_int(local.get('total_tokens'))}</td>"
            f"<td class=\"num\">{fmt_int(local.get('duration_ms')) if local.get('duration_ms') is not None else '-'}</td>"
            f"<td>{local_steps}</td>"
            "</tr>"
        )

    document = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <style>
    :root {{ color:#17202a; font-family:Inter,Arial,sans-serif; }}
    body {{ margin:0; background:#fff; }}
    table {{ width:100%; border-collapse:collapse; border:1px solid #dce3ea; }}
    th, td {{ padding:12px 13px; border-bottom:1px solid #edf1f5; text-align:left; vertical-align:top; font-size:13px; line-height:1.55; }}
    th {{ background:#f6f8fb; color:#5f6b7a; font-size:12px; font-weight:850; text-transform:uppercase; }}
    tr:last-child td {{ border-bottom:0; }}
    .num {{ text-align:right; white-space:nowrap; font-variant-numeric:tabular-nums; }}
    .muted {{ color:#5f6b7a; }}
  </style>
</head>
<body>
  <table>
    <thead>
      <tr>
        <th>案例</th>
        <th>在线 token</th>
        <th>VLM 调用</th>
        <th>在线步骤（token / 用时）</th>
        <th>本地 token</th>
        <th>本地用时</th>
        <th>Replay / 召回步骤（token / 用时）</th>
      </tr>
    </thead>
    <tbody>
      {''.join(rows)}
    </tbody>
  </table>
</body>
</html>
"""
    write_text(output, document)


def make_summary(data: dict[str, Any]) -> dict[str, Any]:
    token_runs = data.get("token_runs", [])
    online = [r for r in token_runs if r.get("group") == "online_no_recall"]
    recall = [r for r in token_runs if r.get("group") == "online_with_recall_guidance"]
    online_tokens = sum(int(r.get("total_tokens") or 0) for r in online)
    online_calls = sum(int(r.get("call_count") or 0) for r in online)
    recall_tokens = sum(int(r.get("total_tokens") or 0) for r in recall)
    recall_calls = sum(int(r.get("call_count") or 0) for r in recall)
    return {
        "online_no_recall_total_tokens": online_tokens,
        "online_no_recall_call_count": online_calls,
        "online_no_recall_avg_tokens_per_call": round(online_tokens / online_calls) if online_calls else None,
        "online_with_recall_total_tokens": recall_tokens,
        "online_with_recall_call_count": recall_calls,
        "online_with_recall_avg_tokens_per_call": round(recall_tokens / recall_calls) if recall_calls else None,
        "offline_replay_total_tokens": 0,
    }


def generate(data_path: Path, out_dir: Path) -> None:
    data = load_data(data_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    token_rows = data.get("token_runs", [])
    time_rows = data.get("time_runs", [])
    cases = data.get("cases", [])

    bar_chart(
        "各样本 token 消耗",
        "在线 VLM 消耗 token；离线 replay 和本地 recall / segment 为 0 model token。",
        [
            {**row, "extra": f"{row.get('call_count', 0)} calls" if row.get("call_count") else ""}
            for row in token_rows
        ],
        "total_tokens",
        "tokens",
        out_dir / "guiagent-token-cost.svg",
    )
    stacked_step_chart(
        "已记录的逐步 token 拆分",
        "只有 RunLog 记录到步骤 token 的样本会拆分；其余只展示总量。",
        [row for row in token_rows if row.get("group") == "online_no_recall"],
        "total_tokens",
        "tokens",
        "tokens",
        out_dir / "guiagent-token-step-split.svg",
    )
    bar_chart(
        "本地 replay / recall 用时",
        "本地执行有时间成本，但没有模型 token 成本。",
        time_rows,
        "duration_ms",
        "ms",
        out_dir / "guiagent-time-cost.svg",
    )
    stacked_step_chart(
        "已记录的本地步骤用时拆分",
        "已记录 phase 数据的 replay / recall 样本按步骤拆分。",
        time_rows,
        "duration_ms",
        "duration_ms",
        "ms",
        out_dir / "guiagent-time-step-split.svg",
    )
    combined_token_time_chart(
        "Token 和用时合并视图",
        "每个样本同时展示在线模型 token 和本地 / replay 用时。",
        cases,
        out_dir / "guiagent-token-time-combined.svg",
    )
    detail_table_html(cases, out_dir / "guiagent-step-detail.html")
    write_text(out_dir / "guiagent-cost-summary.json", json.dumps(make_summary(data), ensure_ascii=False, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="Input JSON data file")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT, help="Output directory for SVG files")
    args = parser.parse_args()
    generate(args.data, args.out_dir)
    print(f"Generated GUI Agent cost charts in {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
