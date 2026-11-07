#!/usr/bin/env python3
"""
本地 Rerank 服务：用交叉编码器（默认 BAAI/bge-reranker-v2-m3）给"问题 + 片段"逐对打分，供后端 Retriever 精排。

接口与 HuggingFace text-embeddings-inference 的 /rerank 相同，以后可以直接换成 TEI：

    POST /rerank   {"query": "年假有几天", "texts": ["片段1", "片段2"]}
    →              [{"index": 1, "score": 0.93}, {"index": 0, "score": 0.02}]   按分数从高到低
    GET  /health   → {"model": "...", "device": "mps"}

score 经过 sigmoid，范围 0~1，可以直接当相关性阈值使用。

依赖 sentence-transformers（会一并装上 torch）：

    pip install sentence-transformers
    python3 scripts/rerank_server.py                    # 默认端口 8081，首次运行会下载模型（约 2.2GB）
    python3 scripts/rerank_server.py --model BAAI/bge-reranker-base --port 8081

国内网络下载模型慢时可设置 HF_ENDPOINT=https://hf-mirror.com。
"""

import argparse
import json
import logging
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
from sentence_transformers import CrossEncoder

log = logging.getLogger("rerank")
# 序列长度补齐到它的倍数，见 Reranker.rerank
LENGTH_BUCKET = 128


def pick_device() -> str:
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


class Reranker:
    def __init__(self, model_name: str, max_length: int, batch_size: int):
        self.device = pick_device()
        self.model_name = model_name
        self.batch_size = batch_size
        self.max_length = max_length
        # GPU / MPS 上用 fp16，速度快约 15%，分数与 fp32 相差不到 0.01
        dtype = torch.float32 if self.device == "cpu" else torch.float16
        self.model = CrossEncoder(model_name, max_length=max_length, device=self.device,
                                  model_kwargs={"torch_dtype": dtype})
        # GPU / MPS 上并发推理没有收益，还可能出错，串行处理
        self.lock = threading.Lock()

    def release_cache(self):
        """
        归还推理时缓存的显存。PyTorch 用过的显存不还给系统，留着下次复用；每批片段长度不同，缓存越积越大，
        连续运行一天后 MPS 缓存涨到 14GB，16GB 内存的 Mac 上只能换出到磁盘，闲置后第一次精排要 10~25 秒。
        释放后每次推理多花几毫秒重新分配。
        """
        if self.device == "mps":
            torch.mps.empty_cache()
        elif self.device == "cuda":
            torch.cuda.empty_cache()

    def rerank(self, query: str, texts: list[str]) -> list[dict]:
        """
        MPS 为每一种输入形状（批大小 × 序列长度）编译一张计算图并一直缓存，每批片段长度都不一样时几乎每次都是新形状：
        实测每次请求涨约 60MB，连续运行一天涨到 17GB。所以序列长度补齐到 LENGTH_BUCKET 的倍数、批大小补齐到 2 的幂，
        形状最多 8 × 5 种，缓存满了就不再增长。补齐的部分有 attention mask，不影响分数。
        """
        if not texts:
            return []
        scores = []
        with self.lock:
            for start in range(0, len(texts), self.batch_size):
                batch = texts[start:start + self.batch_size]
                size = min(self.batch_size, 1 << (len(batch) - 1).bit_length())
                pairs = [(query, t) for t in batch] + [(query, "")] * (size - len(batch))
                features = self.model.tokenizer(pairs, padding=True, truncation=True, max_length=self.max_length,
                                                pad_to_multiple_of=LENGTH_BUCKET, return_tensors="pt").to(self.device)
                with torch.inference_mode():
                    logits = self.model.model(**features).logits.view(-1).float()
                scores.extend(torch.sigmoid(logits)[:len(batch)].cpu().tolist())
            self.release_cache()
        ranked = sorted(range(len(texts)), key=lambda i: scores[i], reverse=True)
        return [{"index": i, "score": float(scores[i])} for i in ranked]


def make_handler(reranker: Reranker):
    class Handler(BaseHTTPRequestHandler):
        # 默认的 HTTP/1.0 每次响应后关闭连接，而 Java HttpClient 会复用连接，导致下一次请求读不到响应
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path != "/health":
                return self.reply(404, {"error": "not found"})
            self.reply(200, {"model": reranker.model_name, "device": reranker.device})

        def do_POST(self):
            if self.path != "/rerank":
                return self.reply(404, {"error": "not found"})
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                query, texts = body["query"], body["texts"]
                if not isinstance(query, str) or not isinstance(texts, list):
                    raise ValueError("query 必须是字符串，texts 必须是数组")
            except (ValueError, KeyError) as e:
                return self.reply(400, {"error": f"请求格式错误：{e}"})
            start = time.perf_counter()
            result = reranker.rerank(query, texts)
            log.info("%d 个片段，耗时 %.0f ms：%s", len(texts), (time.perf_counter() - start) * 1000, query[:40])
            self.reply(200, result)

        def reply(self, status: int, payload):
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):
            pass  # 请求日志在 do_POST 里按需输出

    return Handler


def main():
    parser = argparse.ArgumentParser(description="本地 Rerank 服务")
    parser.add_argument("--model", default="BAAI/bge-reranker-v2-m3", help="交叉编码器模型，默认 %(default)s")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--max-length", type=int, default=1024,
                        help="问题 + 片段的最大 token 数，超出部分截断；片段上限 1000 字，默认 %(default)s")
    parser.add_argument("--batch-size", type=int, default=16)
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    log.info("加载模型 %s ...", args.model)
    reranker = Reranker(args.model, args.max_length, args.batch_size)
    reranker.rerank("预热", ["预热"])
    log.info("模型已加载（%s），监听 http://%s:%d", reranker.device, args.host, args.port)
    ThreadingHTTPServer((args.host, args.port), make_handler(reranker)).serve_forever()


if __name__ == "__main__":
    main()
