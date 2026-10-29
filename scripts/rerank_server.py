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
        # GPU / MPS 上用 fp16，速度快约 15%，分数与 fp32 相差不到 0.01
        dtype = torch.float32 if self.device == "cpu" else torch.float16
        self.model = CrossEncoder(model_name, max_length=max_length, device=self.device,
                                  model_kwargs={"torch_dtype": dtype})
        # GPU / MPS 上并发推理没有收益，还可能出错，串行处理
        self.lock = threading.Lock()

    def rerank(self, query: str, texts: list[str]) -> list[dict]:
        if not texts:
            return []
        with self.lock:
            scores = self.model.predict([(query, t) for t in texts], batch_size=self.batch_size,
                                        activation_fn=torch.nn.Sigmoid(), show_progress_bar=False)
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
