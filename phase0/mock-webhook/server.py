#!/usr/bin/env python3
"""Minimal webhook receiver for Phase 0 (Teams substitute)."""
from http.server import BaseHTTPRequestHandler, HTTPServer


class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        print("=== WEBHOOK POST RECEIVED ===", flush=True)
        print(f"path={self.path} bytes={length}", flush=True)
        print(body.decode("utf-8", errors="replace")[:4000], flush=True)
        print("=== END WEBHOOK ===", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, format: str, *args) -> None:
        return


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), WebhookHandler).serve_forever()
