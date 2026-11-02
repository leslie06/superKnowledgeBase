#!/usr/bin/env python3
"""
答案级评测：逐题调用 /chat 拿到真实回答，再请 DeepSeek 当裁判，按标准答案要点检查回答质量。

检索评测（eval_retrieval.py）只回答"正确片段有没有交给 LLM"，本脚本回答"LLM 最终答得对不对"：
要点是否答全、有没有资料之外的内容（编造）、[n] 引用是否指向真正支持该句的资料、无答案题是否拒答。

只依赖 Python 标准库。需要后端运行，每道题走完整的检索 + 生成流程。裁判需要 DeepSeek Key：
优先读环境变量 DEEPSEEK_API_KEY，否则读 config/application.yaml 中的 api-key。用法：

    python3 scripts/eval_answer.py --kb-id 5 --csv scripts/eval/answers/laws.csv
    python3 scripts/eval_answer.py --kb-id 5 --csv scripts/eval/answers/laws.csv --out report.csv

题集 CSV 列（第一行为表头），问题与 scripts/eval/ 下同名的检索题集一一对应：
    question    必填，用户问题
    key_points  标准答案要点，多个要点用 | 分隔，全部覆盖才算答对；为空表示无答案题，期望拒答
    note        备注，会作为参考提供给裁判，不参与计算

每道题新建一个会话、只问一轮，问完删除会话（--keep 保留，便于在前端查看）。生成有随机性（temperature 0.3），
裁判也会误判，单次结果有几个百分点的波动，比较改动前后的效果要看多次结果的趋势，并抽查未通过的题。
"""

import argparse
import csv
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

PRIVATE_YAML = Path(__file__).resolve().parent.parent / "config/application.yaml"
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"

JUDGE_SYSTEM_PROMPT = """
你是知识库问答系统的评测裁判。给定【问题】【参考资料】【标准答案要点】和系统的【回答】，按以下规则判断，只输出 JSON。

1. 要点覆盖（points）：逐条判断每个标准答案要点是否被回答正确表达。意思一致即可，不要求原文，但数字、期限、条件、主体必须一致。
   回答给出与要点矛盾的结论，或只说"资料中没有"，记为未覆盖。一个要点列了多项内容（如"A、B、C"）时，全部答出才算覆盖。
2. 无依据内容（unsupported）：列出回答中参考资料无法支持的事实性内容，例如资料之外的法规、数字、标准、流程。
   以下不算：对资料的复述和归纳；基于资料的正确推理或计算（如根据年限判断落在哪个区间）；"资料中没有提到"之类的说明；
   礼貌用语，以及"建议咨询人力资源部/专业律师"这类不含具体事实的建议。推理或计算出错的算无依据内容。
3. 引用错误（citation_errors）：回答中标注了 [n] 的句子，如果编号不存在，或者第 n 条资料并不支持这句话，列出该句。只核对有标注的句子。
4. 缺少引用（missing_citations）：直接取自资料的关键事实（数字、条件、规定）没有标注任何 [n] 的句子。
   开头的结论句后面紧跟着带引用的展开说明时，不算缺少引用。
5. 拒答（refused）：回答是否明确表示知识库或参考资料中没有能回答这个问题的信息。即使同时介绍了资料里的相关内容，
   只要表达了"无法回答/没有找到相关规定"，就记为 true。

输出格式（列表为空时输出 []，每条内容不超过 60 字）：
{"points": [{"index": 1, "covered": true, "reason": "一句话理由"}], "unsupported": [], "citation_errors": [], "missing_citations": [], "refused": false}
""".strip()


@dataclass
class Case:
    line: int
    question: str
    key_points: list[str]
    note: str

    @property
    def answerable(self) -> bool:
        return bool(self.key_points)


@dataclass
class Outcome:
    case: Case
    answer: str = ""
    citations: list[dict] = field(default_factory=list)
    first_token: float | None = None  # 秒，从发出请求到收到第一段回答
    total: float | None = None
    covered: list[bool] = field(default_factory=list)
    reasons: list[str] = field(default_factory=list)
    unsupported: list[str] = field(default_factory=list)
    citation_errors: list[str] = field(default_factory=list)
    missing_citations: list[str] = field(default_factory=list)
    refused: bool = False
    error: str | None = None

    @property
    def short_circuit(self) -> bool:
        """检索阶段没有相关片段，后端直接返回固定回答，没有调用 LLM。"""
        return not self.error and not self.citations

    @property
    def verdict(self) -> str:
        if self.error:
            return "请求失败"
        if not self.case.answerable:
            if not self.refused:
                return "未拒答"
            return "拒答但含无依据内容" if self.unsupported else "正确拒答"
        hit = sum(self.covered)
        if hit == len(self.covered):
            return "答对但含无依据内容" if self.unsupported else "答对"
        if hit == 0:
            return "误拒" if self.refused else "答错"
        return "部分答对"


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
            points = [p.strip() for p in (row.get("key_points") or "").split("|") if p.strip()]
            cases.append(Case(line=i, question=question, key_points=points, note=(row.get("note") or "").strip()))
    return cases


def read_api_key() -> str:
    if key := os.environ.get("DEEPSEEK_API_KEY"):
        return key
    try:
        if m := re.search(r"^\s*api-key:\s*(\S+)", PRIVATE_YAML.read_text(encoding="utf-8"), re.MULTILINE):
            return m.group(1).strip("'\"")
    except OSError:
        pass
    sys.exit("没有找到 DeepSeek API Key：请设置环境变量 DEEPSEEK_API_KEY，或在 config/application.yaml 中配置")


def http_json(method: str, url: str, payload=None, headers=None, timeout=60):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(url, data=body, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8")
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}：{detail[:200]}") from None


def ask(base_url: str, kb_id: int, case: Case, keep: bool) -> Outcome:
    """新建会话问一轮，解析 SSE 事件 citations → delta* → done（或 error）。"""
    outcome = Outcome(case)
    api = base_url.rstrip("/") + "/api"
    try:
        conversation = http_json("POST", f"{api}/knowledge-bases/{kb_id}/conversations",
                                 {"title": f"[评测] {case.question}"[:30]})
    except (RuntimeError, urllib.error.URLError, TimeoutError) as e:
        outcome.error = f"创建会话失败：{e}"
        return outcome
    try:
        request = urllib.request.Request(f"{api}/conversations/{conversation['id']}/chat",
                                         data=json.dumps({"question": case.question}).encode("utf-8"),
                                         headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
        start = time.perf_counter()
        parts = []
        with urllib.request.urlopen(request, timeout=300) as response:
            event, data = None, []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:])
                elif not line and event:
                    payload = json.loads("\n".join(data)) if data else None
                    if event == "citations":
                        outcome.citations = payload or []
                    elif event == "delta":
                        if outcome.first_token is None:
                            outcome.first_token = time.perf_counter() - start
                        parts.append(payload["text"])
                    elif event == "error":
                        outcome.error = payload.get("message", "未知错误")
                    event, data = None, []
        outcome.total = time.perf_counter() - start
        outcome.answer = "".join(parts)
        if not outcome.error and not outcome.answer:
            outcome.error = "没有收到回答"
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        outcome.error = f"问答失败：{e}"
    finally:
        if not keep:
            try:
                http_json("DELETE", f"{api}/conversations/{conversation['id']}")
            except (RuntimeError, urllib.error.URLError, TimeoutError):
                pass
    return outcome


def judge_prompt(outcome: Outcome) -> str:
    case = outcome.case
    sb = [f"【问题】\n{case.question}\n", "【参考资料】"]
    for c in outcome.citations:
        source = " ｜ ".join(p for p in (c.get("filename"), c.get("headingPath"), c.get("location")) if p)
        sb.append(f"[{c['index']}] {source}\n{c.get('content', '')}\n")
    sb.append("【标准答案要点】")
    if case.answerable:
        sb += [f"{i}. {p}" for i, p in enumerate(case.key_points, start=1)]
    else:
        sb.append("（无答案题：知识库中没有这个问题的答案，正确做法是明确拒答，points 输出 []）")
    if case.note:
        sb.append(f"出题备注（仅供参考）：{case.note}")
    sb.append(f"\n【回答】\n{outcome.answer}")
    return "\n".join(sb)


def parse_json(text: str) -> dict:
    try:
        return json.loads(text)
    except ValueError:
        if m := re.search(r"\{.*\}", text, re.DOTALL):  # deepseek-reasoner 等不支持 JSON 模式的模型
            return json.loads(m.group(0))
        raise


def judge(outcome: Outcome, api_key: str, model: str):
    payload = {
        "model": model,
        "messages": [{"role": "system", "content": JUDGE_SYSTEM_PROMPT},
                     {"role": "user", "content": judge_prompt(outcome)}],
        "temperature": 0,
    }
    if model == "deepseek-chat":
        payload["response_format"] = {"type": "json_object"}
    last_error = None
    for _ in range(3):
        try:
            response = http_json("POST", DEEPSEEK_URL, payload, {"Authorization": f"Bearer {api_key}"}, timeout=180)
            result = parse_json(response["choices"][0]["message"]["content"])
            points = {int(p["index"]): p for p in result.get("points", [])}
            outcome.covered = [bool(points.get(i, {}).get("covered")) for i in range(1, len(outcome.case.key_points) + 1)]
            outcome.reasons = [str(points.get(i, {}).get("reason", "")) for i in range(1, len(outcome.case.key_points) + 1)]
            outcome.unsupported = [str(s) for s in result.get("unsupported") or []]
            outcome.citation_errors = [str(s) for s in result.get("citation_errors") or []]
            outcome.missing_citations = [str(s) for s in result.get("missing_citations") or []]
            outcome.refused = bool(result.get("refused"))
            return
        except (RuntimeError, urllib.error.URLError, TimeoutError, ValueError, KeyError, TypeError) as e:
            last_error = e
    outcome.error = f"裁判失败：{last_error}"


def evaluate(case: Case, base_url: str, kb_id: int, keep: bool, api_key: str, model: str) -> Outcome:
    outcome = ask(base_url, kb_id, case, keep)
    if outcome.error:
        return outcome
    if outcome.short_circuit:
        # 没有参考资料时后端返回固定的"没有找到"，无需裁判
        outcome.refused = True
        outcome.covered = [False] * len(case.key_points)
        return outcome
    judge(outcome, api_key, model)
    return outcome


def pct(n: int, d: int) -> str:
    return f"{n / d:6.1%}" if d else "   -  "


def percentile(values: list[float], q: float) -> float:
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * q))]


def print_report(outcomes: list[Outcome], model: str, concurrency: int):
    answerable = [o for o in outcomes if o.case.answerable and not o.error]
    no_answer = [o for o in outcomes if not o.case.answerable and not o.error]
    errors = [o for o in outcomes if o.error]
    generated = [o for o in outcomes if not o.error and not o.short_circuit]

    print("\n" + "=" * 72)
    print(f"共 {len(outcomes)} 题：有答案 {len(answerable)}，无答案 {len(no_answer)}，失败 {len(errors)}；裁判模型 {model}")
    print("=" * 72)

    if answerable:
        n = len(answerable)
        verdicts = [o.verdict for o in answerable]
        full = verdicts.count("答对")
        covered_all = full + verdicts.count("答对但含无依据内容")
        points = sum(len(o.covered) for o in answerable)
        hit = sum(sum(o.covered) for o in answerable)
        print("\n【有答案题】")
        print(f"  答对       {pct(full, n)}  ({full}/{n})  ← 要点全部答出，且没有资料之外的内容")
        print(f"  要点答全   {pct(covered_all, n)}  ({covered_all}/{n})")
        print(f"  要点覆盖率 {pct(hit, points)}  ({hit}/{points})")
        print(f"  部分答对 {verdicts.count('部分答对')}，答错 {verdicts.count('答错')}，误拒 {verdicts.count('误拒')}")

    if no_answer:
        n = len(no_answer)
        correct = sum(1 for o in no_answer if o.verdict == "正确拒答")
        direct = sum(1 for o in no_answer if o.short_circuit)
        print("\n【无答案题】")
        print(f"  正确拒答   {pct(correct, n)}  ({correct}/{n})  ← 其中检索阶段直接拒答 {direct}，"
              f"LLM 看过资料后拒答 {correct - direct}")

    if generated:
        n = len(generated)
        unsupported = sum(1 for o in generated if o.unsupported)
        wrong_cite = sum(1 for o in generated if o.citation_errors)
        missing_cite = sum(1 for o in generated if o.missing_citations)
        print(f"\n【可信度】（调用了 LLM 的 {n} 个回答）")
        print(f"  含无依据内容 {pct(unsupported, n)}  ({unsupported}/{n})  ← 资料之外的内容，即编造或常识补充")
        print(f"  引用错误     {pct(wrong_cite, n)}  ({wrong_cite}/{n})  ← [n] 指向的资料不支持该句")
        print(f"  缺少引用     {pct(missing_cite, n)}  ({missing_cite}/{n})")
        firsts = [o.first_token for o in generated if o.first_token is not None]
        totals = [o.total for o in generated if o.total is not None]
        if firsts and totals:
            print(f"\n【延迟】首字中位 {statistics.median(firsts):.1f}s、P90 {percentile(firsts, 0.9):.1f}s；"
                  f"完整回答中位 {statistics.median(totals):.1f}s、P90 {percentile(totals, 0.9):.1f}s")
            if concurrency > 1:
                print(f"  并发 {concurrency} 时精排请求会排队，延迟偏高；看单用户延迟用 --concurrency 1")

    failures = [o for o in outcomes if not o.error and o.verdict not in ("答对", "正确拒答")]
    if failures:
        print(f"\n【未通过的题】（{len(failures)} 道）")
        for o in failures:
            c = o.case
            print(f"\n  第 {c.line} 行：{c.question}")
            print(f"    结论：{o.verdict}" + (f"（{sum(o.covered)}/{len(o.covered)} 个要点）" if c.answerable else "")
                  + (f"    备注：{c.note}" if c.note else ""))
            for point, ok, reason in zip(c.key_points, o.covered, o.reasons):
                if not ok:
                    print(f"    × 要点：{point}" + (f"（{reason}）" if reason else ""))
            for s in o.unsupported:
                print(f"    ! 无依据：{s}")
            for s in o.citation_errors:
                print(f"    ! 引用错误：{s}")
            print(f"    回答：{shorten(o.answer, 160)}")

    for o in errors:
        print(f"\n  第 {o.case.line} 行失败：{o.error}")
    print()


def shorten(text: str, limit: int) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[:limit] + "…"


def write_details(path: Path, outcomes: list[Outcome]):
    with path.open("w", encoding="utf-8-sig", newline="") as f:  # 带 BOM，Excel 直接打开不乱码
        writer = csv.writer(f)
        writer.writerow(["行号", "问题", "类型", "结论", "要点覆盖", "未覆盖要点", "无依据内容", "引用错误", "缺少引用",
                         "拒答", "引用条数", "首字耗时", "总耗时", "回答", "错误", "备注"])
        for o in outcomes:
            missing = [p for p, ok in zip(o.case.key_points, o.covered) if not ok]
            writer.writerow([
                o.case.line,
                o.case.question,
                "有答案" if o.case.answerable else "无答案",
                o.verdict,
                f"{sum(o.covered)}/{len(o.covered)}" if o.case.answerable and not o.error else "",
                "\n".join(missing),
                "\n".join(o.unsupported),
                "\n".join(o.citation_errors),
                "\n".join(o.missing_citations),
                "是" if o.refused else "",
                len(o.citations),
                f"{o.first_token:.1f}" if o.first_token is not None else "",
                f"{o.total:.1f}" if o.total is not None else "",
                o.answer,
                o.error or "",
                o.case.note,
            ])


def main():
    parser = argparse.ArgumentParser(description="知识库答案级评测")
    parser.add_argument("--kb-id", type=int, required=True, help="知识库 ID")
    parser.add_argument("--csv", type=Path, required=True, help="答案题集 CSV 路径（scripts/eval/answers/）")
    parser.add_argument("--base-url", default="http://localhost:8080", help="后端地址，默认 %(default)s")
    parser.add_argument("--judge-model", default="deepseek-chat", help="裁判模型，默认 %(default)s")
    parser.add_argument("--concurrency", type=int, default=3, help="同时评测的题数，默认 %(default)s")
    parser.add_argument("--keep", action="store_true", help="保留评测创建的会话，便于在前端查看")
    parser.add_argument("--out", type=Path, help="把逐题明细（含完整回答）写入 CSV")
    args = parser.parse_args()

    cases = load_cases(args.csv)
    if not cases:
        sys.exit("题集为空")
    api_key = read_api_key()

    outcomes: list[Outcome | None] = [None] * len(cases)
    with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = {pool.submit(evaluate, case, args.base_url, args.kb_id, args.keep, api_key, args.judge_model): i
                   for i, case in enumerate(cases)}
        for done, future in enumerate(as_completed(futures), start=1):
            i = futures[future]
            outcomes[i] = future.result()
            print(f"[{done}/{len(cases)}] {outcomes[i].verdict:<10} {cases[i].question}", flush=True)

    print_report(outcomes, args.judge_model, args.concurrency)
    if args.out:
        write_details(args.out, outcomes)
        print(f"逐题明细已写入 {args.out}")


if __name__ == "__main__":
    main()
