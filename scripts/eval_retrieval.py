#!/usr/bin/env python3
"""
检索评测脚本：读取题集 CSV，逐题调用 /search 接口，统计 Hit@k、MRR 和问答可用率。

只依赖 Python 标准库。用法：

    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv
    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv --out report.csv

    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv --mode vector   # 只看向量召回
    python3 scripts/eval_retrieval.py --kb-id 5 --csv scripts/eval/laws.csv --rerank off    # 对比不精排的效果

题集 CSV 列（第一行为表头）：
    question         必填，用户问题
    expected_file    预期命中的文件名（子串匹配即可）
    expected_section 预期章节路径（子串匹配，对应 headingPath）
    expected_text    预期片段中包含的原文（子串匹配，最精确）
    note             备注，不参与计算

匹配规则：非空的 expected_* 列必须同时满足；每列可用 | 分隔多个可接受的值；
比较前忽略大小写和所有空白字符。三个 expected_* 列都为空的题视为"无答案题"，
期望没有任何片段被判为相关（即问答时会被拒答）。同一个问题可以写多行、每行一条证据，
用来统计列举类问题每条证据的召回情况。

相关性直接使用后端返回的 relevant 字段，与 Retriever.retrieve() 一致：精排分数 ≥ rerank.min-score
（未精排时为余弦相似度 ≥ retrieval.min-score），或片段精确命中了问题中的编号（exactMatch，如条款号、Q5、商品编码）。
旧版后端没有 relevant 字段时按余弦阈值计算。"分数"在精排时指精排分数，否则指余弦相似度。
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
        """所有结果中的最高分，无答案题用它判断阈值能否拦住。"""
        return max(gate_score(c) for c in self.results) if self.results else None

    def usable_rank(self, min_score: float) -> int | None:
        """第一个正确片段在"相关片段"中的名次，即问答时它排在交给模型的第几位。"""
        relevant = [c for c in self.results if is_relevant(c, min_score)]
        for rank, chunk in enumerate(relevant, start=1):
            if matches(self.case, chunk):
                return rank
        return None

    def rejected(self, min_score: float) -> bool:
        return not any(is_relevant(c, min_score) for c in self.results)


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


def gate_score(chunk: dict) -> float:
    """后端做相关性过滤所用的分数：精排时为精排分数，否则为余弦相似度。"""
    return chunk["rerankScore"] if chunk.get("rerankScore") is not None else chunk["score"]


def is_relevant(chunk: dict, min_score: float) -> bool:
    if "relevant" in chunk:
        return chunk["relevant"]
    return chunk["score"] >= min_score or bool(chunk.get("exactMatch"))


def search(base_url: str, kb_id: int, query: str, top_k: int, mode: str | None, rerank: str | None) -> list[dict]:
    url = f"{base_url.rstrip('/')}/api/knowledge-bases/{kb_id}/search"
    payload = {"query": query, "topK": top_k}
    if mode:
        payload["mode"] = mode
    if rerank:
        payload["rerank"] = rerank == "on"
    body = json.dumps(payload).encode("utf-8")
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


def evaluate(case: Case, base_url: str, kb_id: int, top_k: int, mode: str | None, rerank: str | None) -> Outcome:
    try:
        results = search(base_url, kb_id, case.question, top_k, mode, rerank)
    except (RuntimeError, urllib.error.URLError, TimeoutError) as e:
        return Outcome(case, [], error=str(e))
    outcome = Outcome(case, results)
    if case.answerable:
        for rank, chunk in enumerate(results, start=1):
            if matches(case, chunk):
                outcome.rank, outcome.score = rank, gate_score(chunk)
                break
    return outcome


def describe(chunk: dict) -> str:
    parts = [chunk.get("filename") or "", chunk.get("headingPath") or "", chunk.get("location") or ""]
    return " / ".join(p for p in parts if p)


def sources(chunk: dict) -> str:
    """召回来源，如"向量#3 关键词#1 精排0.912 精确"；旧版后端没有这些字段时为空。"""
    parts = []
    if chunk.get("vectorRank"):
        parts.append(f"向量#{chunk['vectorRank']}")
    if chunk.get("keywordRank"):
        parts.append(f"关键词#{chunk['keywordRank']}")
    if chunk.get("rerankScore") is not None:
        parts.append(f"精排{chunk['rerankScore']:.3f}")
    if chunk.get("exactMatch"):
        parts.append("精确")
    return " ".join(parts)


def pct(n: int, d: int) -> str:
    return f"{n / d:6.1%}" if d else "   -  "


def print_report(outcomes: list[Outcome], top_k: int, retrieval_top_k: int, min_score: float, show: int,
                 mode: str | None, rerank: str | None):
    answerable = [o for o in outcomes if o.case.answerable and not o.error]
    no_answer = [o for o in outcomes if not o.case.answerable and not o.error]
    errors = [o for o in outcomes if o.error]

    print("\n" + "=" * 72)
    print(f"共 {len(outcomes)} 题：有答案 {len(answerable)}，无答案 {len(no_answer)}，请求失败 {len(errors)}")
    reranked = any(c.get("rerankScore") is not None for o in outcomes for c in o.results)
    print(f"参数：mode={mode or 'hybrid（后端默认）'}，rerank={rerank or '后端默认'}（{'已精排' if reranked else '未精排'}），"
          f"search topK={top_k}，问答 top-k={retrieval_top_k}")
    print("=" * 72)

    if answerable:
        n = len(answerable)
        hit = lambda k: sum(1 for o in answerable if o.rank and o.rank <= k)
        mrr = sum(1 / o.rank for o in answerable if o.rank) / n
        usable = sum(1 for o in answerable
                     if (r := o.usable_rank(min_score)) and r <= retrieval_top_k)
        print("\n【有答案题】")
        for k in sorted({1, 3, retrieval_top_k, top_k}):
            if k <= top_k:
                print(f"  Hit@{k:<3}  {pct(hit(k), n)}  ({hit(k)}/{n})")
        print(f"  MRR@{top_k:<3}  {mrr:.3f}")
        print(f"  问答可用率 {pct(usable, n)}  ({usable}/{n})  ← 过滤掉不相关片段后仍在前 {retrieval_top_k}，问答时真正能用上")

    if no_answer:
        n = len(no_answer)
        rejected = sum(1 for o in no_answer if o.rejected(min_score))
        print("\n【无答案题】")
        print(f"  拒答率     {pct(rejected, n)}  ({rejected}/{n})  ← 没有片段被判为相关，不会调用 LLM")

    correct_scores = sorted(o.score for o in answerable if o.score is not None)
    noise_scores = sorted(o.top1_score for o in no_answer if o.top1_score is not None)
    if correct_scores or noise_scores:
        print(f"\n【分数分布】{'精排分数，用于调整 rerank.min-score' if reranked else '余弦相似度，用于调整 retrieval.min-score'}")
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

    failures = [o for o in answerable if not (r := o.usable_rank(min_score)) or r > retrieval_top_k]
    failures += [o for o in no_answer if not o.rejected(min_score)]
    if failures:
        print(f"\n【未通过的题】（{len(failures)} 道，每题列出前 {show} 条结果）")
        for o in failures:
            c = o.case
            if c.answerable:
                status = f"未召回（前 {top_k} 条中没有）" if not o.rank else f"排名 {o.rank}，分数 {o.score:.3f}"
            else:
                status = f"无答案题但有片段被判为相关（最高分 {o.top1_score:.3f}）"
            print(f"\n  第 {c.line} 行：{c.question}")
            print(f"    结果：{status}" + (f"    备注：{c.note}" if c.note else ""))
            for i, chunk in enumerate(o.results[:show], start=1):
                mark = "✓" if c.answerable and matches(c, chunk) else " "
                flag = "" if is_relevant(chunk, min_score) else "（不相关）"
                print(f"    {mark}{i}. {chunk['score']:.3f}  {describe(chunk)}  {sources(chunk)}{flag}".rstrip())

    for o in errors:
        print(f"\n  第 {o.case.line} 行请求失败：{o.error}")
    print()


def write_details(path: Path, outcomes: list[Outcome]):
    with path.open("w", encoding="utf-8-sig", newline="") as f:  # 带 BOM，Excel 直接打开不乱码
        writer = csv.writer(f)
        writer.writerow(["行号", "问题", "类型", "命中排名", "命中分数", "命中来源", "第1名分数", "第1名出处", "第1名内容",
                         "错误", "备注"])
        for o in outcomes:
            top = o.results[0] if o.results else {}
            hit = o.results[o.rank - 1] if o.rank else {}
            writer.writerow([
                o.case.line,
                o.case.question,
                "有答案" if o.case.answerable else "无答案",
                o.rank or "",
                f"{o.score:.4f}" if o.score is not None else "",
                sources(hit) if hit else "",
                f"{gate_score(top):.4f}" if top else "",
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
                        help="余弦相似度阈值，仅在后端未返回 relevant 字段时使用，默认读取 application.yaml（%(default)s）")
    parser.add_argument("--mode", choices=["hybrid", "vector", "keyword"],
                        help="召回方式，默认用后端默认的 hybrid；对比单路效果时使用")
    parser.add_argument("--rerank", choices=["on", "off"], help="是否精排，默认按后端配置；只在 hybrid 模式下生效")
    parser.add_argument("--show", type=int, default=3, help="未通过的题展示前几条结果，默认 %(default)s")
    parser.add_argument("--out", type=Path, help="把逐题明细写入 CSV")
    args = parser.parse_args()

    cases = load_cases(args.csv)
    if not cases:
        sys.exit("题集为空")

    outcomes = []
    for i, case in enumerate(cases, start=1):
        outcome = evaluate(case, args.base_url, args.kb_id, args.top_k, args.mode, args.rerank)
        outcomes.append(outcome)
        if outcome.error:
            flag = "错误"
        elif case.answerable:
            flag = f"第{outcome.rank}名" if outcome.rank else "未召回"
        else:
            flag = f"最高分 {outcome.top1_score:.3f}" if outcome.top1_score is not None else "无结果"
        print(f"[{i}/{len(cases)}] {flag:<12} {case.question}", flush=True)

    print_report(outcomes, args.top_k, args.retrieval_top_k, args.min_score, args.show, args.mode, args.rerank)
    if args.out:
        write_details(args.out, outcomes)
        print(f"逐题明细已写入 {args.out}")


if __name__ == "__main__":
    main()
