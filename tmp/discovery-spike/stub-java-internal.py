"""桩：模拟 Java internal 检索端点，给 runner 端到端冒烟用（不启动真实 Java 栈）。"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

CANDIDATES = [
    ("2403.08319", "Knowledge Conflicts for LLMs: A Survey", 2024, "cs.CL"),
    ("2402.14407", "The Power of Noise: Redefining Retrieval for RAG", 2024, "cs.CL"),
    ("2305.13379", "Survey of Hallucination in Natural Language Generation", 2023, "cs.CL"),
    ("2405.14726", "Adaptive Chameleon or Stubborn Sloth: LLM behavior in RAG", 2024, "cs.CL"),
    ("2506.08938", "FaithfulRAG: Fact-Level Conflict Modeling for RAG", 2025, "cs.CL"),
    ("2211.10073", "Knowledge Neurons in Pretrained Transformers", 2022, "cs.CL"),
    ("2311.11260", "Trusting Your Evidence: Hallucinate Less with RAG", 2023, "cs.CL"),
    ("2404.10198", "RAG and the Truth: Multi-hop conflict resolution", 2024, "cs.CL"),
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[stub]", fmt % args)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        print("[stub] query:", body.get("query"), "yearFrom:", body.get("yearFrom"), "yearTo:", body.get("yearTo"))
        year_from, year_to = body.get("yearFrom"), body.get("yearTo")
        items = []
        for arxiv_id, title, year, cat in CANDIDATES:
            if year_from and year and year < year_from: continue
            if year_to and year and year > year_to: continue
            items.append({
                "arxivId": arxiv_id, "title": title, "summary": (
                    "We study how large language models behave when retrieved context conflicts with "
                    f"parametric memory. {title} introduces a systematic view of knowledge conflicts."),
                "authors": ["A. Author", "B. Author"], "categories": [cat],
                "publishedYear": year, "absUrl": f"https://arxiv.org/abs/{arxiv_id}",
                "pdfUrl": f"https://arxiv.org/pdf/{arxiv_id}", "libraryStatus": "NONE",
            })
        payload = json.dumps({"total": len(items), "hasNext": False, "items": items}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 18099), Handler).serve_forever()
