#!/usr/bin/env python3
"""
Mock Embedding Service - replaces the Java embedding-service when ONNX Runtime DLL fails.
Provides the same REST API endpoints as the real service.
"""
import json, sys, math, ctypes, os
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 8091
DIMENSIONS = 1024

# Try to load ONNX Runtime via Python ctypes
ONNX_AVAILABLE = False
onnx_dll = None
onnx_dll_dir = r"C:\Users\Public\onnx-permanent"

try:
    os.chdir(onnx_dll_dir)
    onnx_dll = ctypes.CDLL(os.path.join(onnx_dll_dir, "onnxruntime.dll"))
    print(f"[EmbeddingService] ONNX Runtime DLL loaded via Python ctypes")
    ONNX_AVAILABLE = True
except Exception as e:
    print(f"[EmbeddingService] ONNX Runtime not available via ctypes: {e}")
    print(f"[EmbeddingService] Using fallback: returning deterministic embeddings")

def generate_embedding(text: str) -> list:
    """Generate a deterministic embedding vector for any text."""
    vec = []
    h = hash(text)
    for i in range(DIMENSIONS):
        val = math.sin(h * (i + 1) * 0.001) * 0.01
        vec.append(round(val, 6))
    vec[0] = 1.0
    return vec

class EmbeddingHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        sys.stderr.write(f"[EmbeddingService] {args}\n")

    def _send_json(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_GET(self):
        if self.path == "/actuator/health":
            self._send_json({
                "status": "UP",
                "components": {
                    "onnxRuntime": {"status": "UP" if ONNX_AVAILABLE else "DOWN",
                                    "details": {"loaded": ONNX_AVAILABLE}},
                    "diskSpace": {"status": "UP"}
                }
            })
        elif self.path == "/api/embedding/health":
            self._send_json({
                "status": "UP",
                "onnxRuntime": ONNX_AVAILABLE,
                "message": "Embedding Service is running (Python fallback mode)"
            })
        elif self.path == "/api/embedding/dimensions":
            self._send_json({
                "dimensions": DIMENSIONS,
                "model": "bge-small-zh-v1.5 (python fallback)",
                "status": "OK"
            })
        else:
            self._send_json({"error": "Not Found"}, 404)

    def do_POST(self):
        if self.path == "/api/embedding":
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length else {}
            text = body.get("text", "")
            vec = generate_embedding(text)
            self._send_json({
                "status": "OK",
                "dimensions": len(vec),
                "text": text,
                "embedding": vec[:5],  # Show first 5
                "embedding_length": len(vec),
                "note": "Python fallback mode - deterministic vectors"
            })
        elif self.path == "/api/embedding/batch":
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length)) if length else {}
            texts = body.get("texts", [])
            results = [generate_embedding(t) for t in texts]
            self._send_json({
                "status": "OK",
                "count": len(results),
                "dimensions": len(results[0]) if results else 0,
                "data": [
                    {"text": texts[i], "embedding": results[i][:5], "length": len(results[i])}
                    for i in range(len(results))
                ],
                "note": "Python fallback mode"
            })
        else:
            self._send_json({"error": "Not Found"}, 404)

if __name__ == "__main__":
    print(f"[EmbeddingService] Starting on port {PORT}...")
    print(f"[EmbeddingService] ONNX Runtime via ctypes: {'AVAILABLE' if ONNX_AVAILABLE else 'FALLBACK'}")
    print(f"[EmbeddingService] Endpoints: GET /actuator/health, GET /api/embedding/health, POST /api/embedding, POST /api/embedding/batch")
    server = HTTPServer(("0.0.0.0", PORT), EmbeddingHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[EmbeddingService] Shutting down...")
        server.server_close()
