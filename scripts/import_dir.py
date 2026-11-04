#!/usr/bin/env python3
"""
目录批量导入：递归扫描目录，把支持的文件逐个上传到知识库，等待处理完成后输出导入报告。

用于把几百份真实资料一次性接进来，暴露解析失败、扫描件、切块异常等问题。只依赖 Python 标准库，需要后端运行。

    python3 scripts/import_dir.py --kb-id 7 --dir ~/资料 --dry-run         # 只扫描，统计文件类型，不上传
    python3 scripts/import_dir.py --kb-id 7 --dir ~/资料 --out report.csv  # 上传、等待处理完成、输出报告

上传走 /api/knowledge-bases/{kbId}/documents，后端按 SHA-256 去重，所以重复运行是安全的：已导入的文件记为"已存在"，
不会重复处理，这时脚本相当于重新生成一次报告。中途按 Ctrl-C 退出不影响后端继续处理，之后重新运行同一命令即可看到结果。
--retry-failed 会对之前处理失败的文件重新处理（例如改进解析之后）。

报告内容：按文件类型统计成功 / 失败 / 片段数；失败原因归类；文字疑似过少的文件（扫描件、以图片为主的 PDF/PPT）；
处理吞吐（文档/分钟、片段/分钟）。--out 写出逐个文件的明细 CSV。
"""

import argparse
import csv
import json
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

SUPPORTED = {
    "doc": "Word", "docx": "Word", "xls": "Excel", "xlsx": "Excel", "ppt": "PPT", "pptx": "PPT",
    "pdf": "PDF", "txt": "文本", "md": "文本",
}
# 与 application.yaml 的 spring.servlet.multipart.max-file-size 一致
MAX_FILE_BYTES = 200 * 1024 * 1024
# 每 MB 文件大小至少应产出 1 个片段；低于这个密度的多半是扫描件或以图片为主，文字没提取出来
MIN_CHUNKS_PER_MB = 1
DONE_STATUSES = {"READY", "FAILED"}


@dataclass
class Item:
    path: Path
    rel: str
    kind: str
    size: int
    result: str = ""        # 新增 / 已存在 / 已跳过 / 上传失败
    message: str = ""
    doc_id: int | None = None
    status: str = ""
    chunks: int = 0
    error: str = ""
    flag: str = ""


def scan(root: Path) -> tuple[list[Item], list[Item]]:
    """返回 (待上传, 跳过)。跳过隐藏文件、Office 临时锁文件（~$开头）、空文件、超限文件和不支持的类型。"""
    todo, skipped = [], []
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root)
        if any(part.startswith(".") for part in rel.parts) or not path.is_file():
            continue
        ext = path.suffix.lower().lstrip(".")
        item = Item(path, str(rel), SUPPORTED.get(ext, "." + ext if ext else "无扩展名"), path.stat().st_size)
        if path.name.startswith("~$"):
            item.message = "Office 临时文件"
        elif ext not in SUPPORTED:
            item.message = "不支持的类型"
        elif item.size == 0:
            item.message = "空文件"
        elif item.size > MAX_FILE_BYTES:
            item.message = "超过上传上限 200MB"
        if item.message:
            item.result = "已跳过"
            skipped.append(item)
        else:
            todo.append(item)
    return todo, skipped


def http_json(method: str, url: str, body: bytes | None = None, content_type="application/json", timeout=120):
    request = urllib.request.Request(url, data=body, method=method, headers={"Content-Type": content_type})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8")
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}：{detail[:200]}") from None


def upload(api: str, kb_id: int, item: Item):
    boundary = uuid.uuid4().hex
    filename = item.path.name.replace('"', "'")
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{filename}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
    body = head + item.path.read_bytes() + f"\r\n--{boundary}--\r\n".encode("utf-8")
    try:
        result = http_json("POST", f"{api}/knowledge-bases/{kb_id}/documents", body,
                           f"multipart/form-data; boundary={boundary}")[0]
    except (RuntimeError, OSError) as e:
        item.result, item.message = "上传失败", str(e)
        return
    item.doc_id, item.message = result.get("documentId"), result.get("message") or ""
    item.result = {"ACCEPTED": "新增", "DUPLICATE": "已存在"}.get(result["status"], "上传失败")


def fetch_documents(api: str, kb_id: int) -> dict[int, dict]:
    return {d["id"]: d for d in http_json("GET", f"{api}/knowledge-bases/{kb_id}/documents")}


def wait(api: str, kb_id: int, ids: set[int], interval: float) -> tuple[dict[int, dict], float, int]:
    """轮询到全部处理完成，返回 (文档信息, 等待耗时, 期间完成的片段数)。"""
    start = time.time()
    baseline = None
    while True:
        docs = fetch_documents(api, kb_id)
        mine = [docs[i] for i in ids if i in docs]
        done = [d for d in mine if d["status"] in DONE_STATUSES]
        chunks = sum(d["chunkCount"] for d in done)
        baseline = chunks if baseline is None else baseline
        statuses = Counter(d["status"] for d in mine)
        elapsed = time.time() - start
        print(f"\r  [{elapsed / 60:5.1f} 分钟] 完成 {len(done)}/{len(mine)}  "
              + "  ".join(f"{s} {n}" for s, n in sorted(statuses.items())) + " " * 10, end="", flush=True)
        if len(done) == len(mine):
            print()
            return docs, elapsed, chunks - baseline
        time.sleep(interval)


def fill_status(items: list[Item], docs: dict[int, dict]):
    for item in items:
        doc = docs.get(item.doc_id) if item.doc_id else None
        if not doc:
            continue
        item.status, item.chunks, item.error = doc["status"], doc["chunkCount"], doc.get("errorMsg") or ""
        if item.status == "READY" and item.chunks < item.size / 1024 / 1024 * MIN_CHUNKS_PER_MB:
            item.flag = "文字过少，疑似扫描件或以图片为主"


def size_text(n: float) -> str:
    for unit in ("B", "KB", "MB"):
        if n < 1024:
            return f"{n:.0f}{unit}"
        n /= 1024
    return f"{n:.1f}GB"


def print_scan(todo: list[Item], skipped: list[Item]):
    print("=" * 72)
    print(f"扫描结果：待导入 {len(todo)} 个（{size_text(sum(i.size for i in todo))}），跳过 {len(skipped)} 个")
    print("=" * 72)
    by_kind = defaultdict(list)
    for item in todo:
        by_kind[item.kind].append(item)
    for kind, items in sorted(by_kind.items(), key=lambda kv: -len(kv[1])):
        exts = Counter(i.path.suffix.lower() for i in items)
        print(f"  {kind:<6} {len(items):>5} 个  {size_text(sum(i.size for i in items)):>8}  "
              + "、".join(f"{e} {n}" for e, n in exts.most_common()))
    if skipped:
        print("\n【跳过】")
        reasons = Counter(i.message if i.message != "不支持的类型" else f"不支持的类型 {i.kind}" for i in skipped)
        for reason, n in reasons.most_common():
            print(f"  {n:>5} 个  {reason}")


def print_report(items: list[Item], elapsed: float, new_chunks: int):
    # 内容相同的文件对应同一个文档，统计按文档去重，否则片段数会重复计算
    unique: dict[int, Item] = {}
    for item in items:
        if item.status and item.doc_id not in unique:
            unique[item.doc_id] = item
    imported = list(unique.values())
    print()
    print("=" * 72)
    print(f"导入报告：{len(items)} 个文件对应 {len(imported)} 个文档；新增 {sum(i.result == '新增' for i in items)}，"
          f"已存在 {sum(i.result == '已存在' for i in items)}，上传失败 {sum(i.result == '上传失败' for i in items)}")
    print("=" * 72)
    print(f"  {'类型':<6} {'文件':>5} {'成功':>5} {'失败':>5} {'片段':>7} {'平均片段':>8}")
    by_kind = defaultdict(list)
    for item in imported:
        by_kind[item.kind].append(item)
    for kind, group in sorted(by_kind.items(), key=lambda kv: -len(kv[1])):
        ready = [i for i in group if i.status == "READY"]
        chunks = sum(i.chunks for i in ready)
        avg = f"{chunks / len(ready):.1f}" if ready else "-"
        print(f"  {kind:<6} {len(group):>5} {len(ready):>5} {len(group) - len(ready):>5} {chunks:>7} {avg:>8}")

    failed = [i for i in imported if i.status == "FAILED"]
    if failed:
        print(f"\n【处理失败】（{len(failed)} 个，按原因归类）")
        groups = defaultdict(list)
        for item in failed:
            groups[item.error[:60]].append(item)
        for reason, group in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            print(f"  {len(group):>4} 个  {reason}")
            for item in group[:3]:
                print(f"           - {item.rel}")
            if len(group) > 3:
                print(f"           …… 另有 {len(group) - 3} 个，见 --out 明细")

    flagged = [i for i in imported if i.flag]
    if flagged:
        print(f"\n【文字疑似过少】（{len(flagged)} 个，每 MB 不足 {MIN_CHUNKS_PER_MB} 个片段，可能有内容没提取出来）")
        for item in sorted(flagged, key=lambda i: -i.size)[:10]:
            print(f"  {size_text(item.size):>8}  {item.chunks:>3} 个片段  {item.rel}")
        if len(flagged) > 10:
            print(f"  …… 另有 {len(flagged) - 10} 个，见 --out 明细")

    duplicates = defaultdict(list)
    for item in items:
        if item.doc_id:
            duplicates[item.doc_id].append(item)
    duplicates = {k: v for k, v in duplicates.items() if len(v) > 1}
    if duplicates:
        print(f"\n【目录内内容重复】（{len(duplicates)} 组，只导入一份）")
        for group in list(duplicates.values())[:5]:
            print("  " + "  =  ".join(i.rel for i in group))
        if len(duplicates) > 5:
            print(f"  …… 另有 {len(duplicates) - 5} 组，见 --out 明细")

    upload_failed = [i for i in items if i.result == "上传失败"]
    if upload_failed:
        print(f"\n【上传失败】（{len(upload_failed)} 个）")
        for item in upload_failed[:10]:
            print(f"  {item.rel}：{item.message}")

    if elapsed > 0 and new_chunks > 0:
        done = sum(1 for i in imported if i.status in DONE_STATUSES)
        print(f"\n【吞吐】等待处理 {elapsed / 60:.1f} 分钟，完成 {done} 个文档、{new_chunks} 个片段；"
              f"约 {new_chunks / elapsed * 60:.0f} 片段/分钟")


def write_details(path: Path, items: list[Item]):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["相对路径", "类型", "大小", "上传结果", "文档ID", "状态", "片段数", "失败原因", "提示", "说明"])
        for i in items:
            writer.writerow([i.rel, i.kind, i.size, i.result, i.doc_id or "", i.status, i.chunks if i.status else "",
                             i.error, i.flag, i.message])
    print(f"\n逐个文件的明细已写入 {path}")


def main():
    parser = argparse.ArgumentParser(description="目录批量导入知识库")
    parser.add_argument("--kb-id", type=int, required=True, help="知识库 ID")
    parser.add_argument("--dir", type=Path, required=True, help="要导入的目录，递归扫描")
    parser.add_argument("--base-url", default="http://localhost:8080", help="后端地址，默认 %(default)s")
    parser.add_argument("--dry-run", action="store_true", help="只扫描统计，不上传")
    parser.add_argument("--no-wait", action="store_true", help="上传后不等待处理完成")
    parser.add_argument("--retry-failed", action="store_true", help="对之前处理失败的已存在文件重新处理")
    parser.add_argument("--interval", type=float, default=5, help="轮询处理状态的间隔秒数，默认 %(default)s")
    parser.add_argument("--out", type=Path, help="把逐个文件的明细写入 CSV")
    args = parser.parse_args()

    root = args.dir.expanduser().resolve()
    if not root.is_dir():
        parser.error(f"目录不存在：{root}")
    todo, skipped = scan(root)
    print_scan(todo, skipped)
    if args.dry_run or not todo:
        return

    api = args.base_url.rstrip("/") + "/api"
    http_json("GET", f"{api}/knowledge-bases/{args.kb_id}")  # 知识库不存在时尽早报错
    print(f"\n上传到知识库 {args.kb_id}：")
    for n, item in enumerate(todo, 1):
        upload(api, args.kb_id, item)
        print(f"\r  [{n}/{len(todo)}] {item.result}  {item.rel[:50]}" + " " * 20, end="", flush=True)
    print()

    if args.retry_failed:
        docs = fetch_documents(api, args.kb_id)
        retry = [i for i in todo if i.result == "已存在" and docs.get(i.doc_id, {}).get("status") == "FAILED"]
        for item in retry:
            http_json("POST", f"{api}/documents/{item.doc_id}/reindex")
        print(f"  重新处理之前失败的文件 {len(retry)} 个")

    ids = {i.doc_id for i in todo if i.doc_id}
    elapsed, new_chunks = 0.0, 0
    if args.no_wait:
        docs = fetch_documents(api, args.kb_id)
    else:
        print("等待处理完成（Ctrl-C 退出不影响后端继续处理）：")
        try:
            docs, elapsed, new_chunks = wait(api, args.kb_id, ids, args.interval)
        except KeyboardInterrupt:
            print("\n已退出等待，后端仍在处理；之后重新运行同一命令即可查看结果。")
            docs = fetch_documents(api, args.kb_id)
    fill_status(todo, docs)
    print_report(todo, elapsed, new_chunks)
    if args.out:
        write_details(args.out, todo + skipped)


if __name__ == "__main__":
    main()
