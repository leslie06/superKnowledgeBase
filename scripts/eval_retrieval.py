#!/usr/bin/env python3
"""
检索评测脚本：读取题集 CSV，逐题调用 /search 接口，统计 Hit@k、MRR 和问答可用率。

只依赖 Python 标准库。用法：

    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv
    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv --out report.csv

题集 CSV 列（第一行为表头）：
    question         必填，用户问题
    expected_file    预期命中的文件名（子串匹配即可）
    expected_section 预期章节路径（子串匹配，对应 headingPath）
    expected_text    预期片段中包含的原文（子串匹配，最精确）
    note             备注，不参与计算

匹配规则：非空的 expected_* 列必须同时满足；每列可用 | 分隔多个可接受的值；
比较前忽略大小写和所有空白字符。三个 expected_* 列都为空的题视为"无答案题"，
期望最高分低于 min-score（即问答时会被拒答）。
"""

import argparse
import csv
import json
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

APPLICATION_YAML = Path(__file__).resolve().parent.parent / "src/main/resources/application.yaml"


@dataclass
class Case:
    line: int
    question: str
    expected_file: list[str]
    expected_section: list[str]
    expected_text: list[str]
    note: str

    @property
    def answerable(self) -> bool:
        return bool(self.expected_file or self.expected_section or self.expected_text)


@dataclass
class Outcome:
    case: Case
    results: list[dict]
    rank: int | None = None  # 第一个正确片段的排名，从 1 开始
    score: float | None = None  # 第一个正确片段的分数
    error: str | None = None
    extra: dict = field(default_factory=dict)

    @property
    def top1_score(self) -> float | None:
        return self.results[0]["score"] if self.results else None


def normalize(text: str | None) -> str:
    return re.sub(r"\s+", "", text or "").lower()


def split_alternatives(value: str | None) -> list[str]:
    return [normalize(v) for v in (value or "").split("|") if normalize(v)]


def read_retrieval_defaults() -> tuple[int, float]:
    """从 application.yaml 读取 skb.retrieval.top-k 和 min-score，读不到时用默认值。"""
    top_k, min_score = 6, 0.5
    try:
        text = APPLICATION_YAML.read_text(encoding="utf-8")
        block = re.search(r"^\s+retrieval:\s*\n((?:\s+.*\n?)*)", text, re.MULTILINE)
        if block:
            if m := re.search(r"top-k:\s*(\d+)", block.group(1)):
                top_k = int(m.group(1))
            if m := re.search(r"min-score:\s*([\d.]+)", block.group(1)):
                min_score = float(m.group(1))
    except OSError:
        pass
    return top_k, min_score


def load_cases(path: Path) -> list[Case]:
    cases = []
    with path.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames or "question" not in reader.fieldnames:
            sys.exit(f"题集缺少 question 列：{path}")
        for i, row in enumerate(reader, start=2):
            question = (row.get("question") or "").strip()
            if not question or question.startswith("#"):
                continue
            cases.append(Case(
                line=i,
                question=question,
                expected_file=split_alternatives(row.get("expected_file")),
                expected_section=split_alternatives(row.get("expected_section")),
                expected_text=split_alternatives(row.get("expected_text")),
                note=(row.get("note") or "").strip(),
            ))
    return cases


def search(base_url: str, kb_id: int, query: str, top_k: int) -> list[dict]:
    url = f"{base_url.rstrip('/')}/api/knowledge-bases/{kb_id}/search"
    body = json.dumps({"query": query, "topK": top_k}).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(detail).get("detail", detail)
        except ValueError:
            pass
        raise RuntimeError(f"HTTP {e.code}：{detail}") from None


def matches(case: Case, chunk: dict) -> bool:
    checks = [
        (case.expected_file, chunk.get("filename")),
        (case.expected_section, chunk.get("headingPath")),
        (case.expected_text, chunk.get("content")),
    ]
    return all(any(alt in normalize(value) for alt in alts) for alts, value in checks if alts)


def evaluate(case: Case, base_url: str, kb_id: int, top_k: int) -> Outcome:
    try:
        results = search(base_url, kb_id, case.question, top_k)
    except (RuntimeError, urllib.error.URLError, TimeoutError) as e:
        return Outcome(case, [], error=str(e))
    outcome = Outcome(case, results)
    if case.answerable:
        for rank, chunk in enumerate(results, start=1):
            if matches(case, chunk):
                outcome.rank, outcome.score = rank, chunk["score"]
                break
    return outcome


def describe(chunk: dict) -> str:
    parts = [chunk.get("filename") or "", chunk.get("headingPath") or "", chunk.get("location") or ""]
    return " / ".join(p for p in parts if p)


def pct(n: int, d: int) -> str:
    return f"{n / d:6.1%}" if d else "   -  "


def print_report(outcomes: list[Outcome], top_k: int, retrieval_top_k: int, min_score: float, show: int):
    answerable = [o for o in outcomes if o.case.answerable and not o.error]
    no_answer = [o for o in outcomes if not o.case.answerable and not o.error]
    errors = [o for o in outcomes if o.error]

    print("\n" + "=" * 72)
    print(f"共 {len(outcomes)} 题：有答案 {len(answerable)}，无答案 {len(no_answer)}，请求失败 {len(errors)}")
    print(f"参数：search topK={top_k}，问答 top-k={retrieval_top_k}，min-score={min_score}")
    print("=" * 72)

    if answerable:
        n = len(answerable)
        hit = lambda k: sum(1 for o in answerable if o.rank and o.rank <= k)
        mrr = sum(1 / o.rank for o in answerable if o.rank) / n
        usable = sum(1 for o in answerable
                     if o.rank and o.rank <= retrieval_top_k and o.score >= min_score)
        print("\n【有答案题】")
        for k in sorted({1, 3, retrieval_top_k, top_k}):
            if k <= top_k:
                print(f"  Hit@{k:<3}  {pct(hit(k), n)}  ({hit(k)}/{n})")
        print(f"  MRR@{top_k:<3}  {mrr:.3f}")
        print(f"  问答可用率 {pct(usable, n)}  ({usable}/{n})  ← 排名 ≤ {retrieval_top_k} 且分数 ≥ {min_score}，问答时真正能用上")

    if no_answer:
        n = len(no_answer)
        rejected = sum(1 for o in no_answer if o.top1_score is None or o.top1_score < min_score)
        print("\n【无答案题】")
        print(f"  拒答率     {pct(rejected, n)}  ({rejected}/{n})  ← 最高分 < {min_score}，不会调用 LLM")

    correct_scores = sorted(o.score for o in answerable if o.score is not None)
    noise_scores = sorted(o.top1_score for o in no_answer if o.top1_score is not None)
    if correct_scores or noise_scores:
        print("\n【分数分布】用于调整 min-score")
        if correct_scores:
            print(f"  正确片段分数：最低 {correct_scores[0]:.3f}，中位 {correct_scores[len(correct_scores) // 2]:.3f}，"
                  f"最高 {correct_scores[-1]:.3f}")
        if noise_scores:
            print(f"  无答案题最高分：最低 {noise_scores[0]:.3f}，中位 {noise_scores[len(noise_scores) // 2]:.3f}，"
                  f"最高 {noise_scores[-1]:.3f}")
        if correct_scores and noise_scores:
            if correct_scores[0] > noise_scores[-1]:
                print(f"  两者没有重叠，min-score 设在 {noise_scores[-1]:.3f} ~ {correct_scores[0]:.3f} 之间可全部区分")
            else:
                print("  两者有重叠，单靠阈值无法完全区分")

    failures = [o for o in answerable if not o.rank or o.rank > retrieval_top_k or o.score < min_score]
    failures += [o for o in no_answer if o.top1_score is not None and o.top1_score >= min_score]
    if failures:
        print(f"\n【未通过的题】（{len(failures)} 道，每题列出前 {show} 条结果）")
        for o in failures:
            c = o.case
            if c.answerable:
                status = f"未召回（前 {top_k} 条中没有）" if not o.rank else f"排名 {o.rank}，分数 {o.score:.3f}"
            else:
                status = f"无答案题但最高分 {o.top1_score:.3f} ≥ {min_score}"
            print(f"\n  第 {c.line} 行：{c.question}")
            print(f"    结果：{status}" + (f"    备注：{c.note}" if c.note else ""))
            for i, chunk in enumerate(o.results[:show], start=1):
                mark = "✓" if c.answerable and matches(c, chunk) else " "
                print(f"    {mark}{i}. {chunk['score']:.3f}  {describe(chunk)}")

    for o in errors:
        print(f"\n  第 {o.case.line} 行请求失败：{o.error}")
    print()


def write_details(path: Path, outcomes: list[Outcome]):
    with path.open("w", encoding="utf-8-sig", newline="") as f:  # 带 BOM，Excel 直接打开不乱码
        writer = csv.writer(f)
        writer.writerow(["行号", "问题", "类型", "命中排名", "命中分数", "第1名分数", "第1名出处", "第1名内容", "错误", "备注"])
        for o in outcomes:
            top = o.results[0] if o.results else {}
            writer.writerow([
                o.case.line,
                o.case.question,
                "有答案" if o.case.answerable else "无答案",
                o.rank or "",
                f"{o.score:.4f}" if o.score is not None else "",
                f"{top['score']:.4f}" if top else "",
                describe(top) if top else "",
                (top.get("content") or "")[:200].replace("\n", " ") if top else "",
                o.error or "",
                o.case.note,
            ])


def main():
    default_top_k, default_min_score = read_retrieval_defaults()
    parser = argparse.ArgumentParser(description="知识库检索评测")
    parser.add_argument("--kb-id", type=int, required=True, help="知识库 ID")
    parser.add_argument("--csv", type=Path, required=True, help="题集 CSV 路径")
    parser.add_argument("--base-url", default="http://localhost:8080", help="后端地址，默认 %(default)s")
    parser.add_argument("--top-k", type=int, default=10, help="每题召回条数（1~50），默认 %(default)s")
    parser.add_argument("--retrieval-top-k", type=int, default=default_top_k,
                        help="问答时实际使用的条数，默认读取 application.yaml（%(default)s）")
    parser.add_argument("--min-score", type=float, default=default_min_score,
                        help="相似度阈值，默认读取 application.yaml（%(default)s）")
    parser.add_argument("--show", type=int, default=3, help="未通过的题展示前几条结果，默认 %(default)s")
    parser.add_argument("--out", type=Path, help="把逐题明细写入 CSV")
    args = parser.parse_args()

    cases = load_cases(args.csv)
    if not cases:
        sys.exit("题集为空")

    outcomes = []
    for i, case in enumerate(cases, start=1):
        outcome = evaluate(case, args.base_url, args.kb_id, args.top_k)
        outcomes.append(outcome)
        if outcome.error:
            flag = "错误"
        elif case.answerable:
            flag = f"第{outcome.rank}名" if outcome.rank else "未召回"
        else:
            flag = f"最高分 {outcome.top1_score:.3f}" if outcome.top1_score is not None else "无结果"
        print(f"[{i}/{len(cases)}] {flag:<12} {case.question}", flush=True)

    print_report(outcomes, args.top_k, args.retrieval_top_k, args.min_score, args.show)
    if args.out:
        write_details(args.out, outcomes)
        print(f"逐题明细已写入 {args.out}")


if __name__ == "__main__":
    main()
