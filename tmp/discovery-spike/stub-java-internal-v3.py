"""桩：模拟 Java 内部数据面（v3），给 runner 聊天 e2e 用。
三个端点：/internal/v1/discovery/search、/import、/memory。
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

CANDIDATES = [
    ("2403.01896", "Mitigating the Learning Bias in KV Cache Quantization", 2024),
    ("2402.02750", "KIVI: A Tuning-Free Asymmetric 2bit Quantization for KV Cache", 2024),
    ("2401.18079", "KVQuant: Towards 10 Million Context Length LLM Inference", 2024),
]
SAVED_MEMORY = []
IMPORTS = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/search"):
            items = [
                {
                    "arxivId": cid, "title": title, "summary": (
                        f"{title}. We study KV cache compression for long-context LLM inference."),
                    "authors": ["A. Author", "B. Author"], "categories": ["cs.CL"],
                    "publishedYear": year,
                    "absUrl": f"https://arxiv.org/abs/{cid}",
                    "pdfUrl": f"https://arxiv.org/pdf/{cid}", "libraryStatus": "NONE",
                }
                for cid, title, year in CANDIDATES
            ]
            payload = {"total": len(items), "hasNext": False, "items": items}
        elif self.path.endswith("/import"):
            results = [
                {"arxivId": aid, "status": "IMPORTED", "paperId": f"paper-{i}", "message": "已入库并排队解析"}
                for i, aid in enumerate(body.get("arxivIds", []))
            ]
            IMPORTS.extend(body.get("arxivIds", []))
            payload = {"items": results}
        elif self.path.endswith("/memory"):
            saved = [
                {"id": i + 1, "kind": item.get("kind", "INSIGHT"), "content": item.get("content", "")}
                for i, item in enumerate(body.get("items", []))
            ]
            SAVED_MEMORY.extend((s["kind"], s["content"]) for s in saved)
            payload = {"items": saved}
        else:
            payload = {"error": "not_found"}
        data = json.dumps(payload).encode()
        print(f"[stub] {self.path} <= {json.dumps(body, ensure_ascii=False)[:120]} => {data[:160]}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print("[stub] listening on 18099", flush=True)
    HTTPServer(("127.0.0.1", 18099), Handler).serve_forever()
