#!/usr/bin/env python3
"""Recording reverse proxy for MCP traffic, used to capture what real clients send.

    record-mcp-traffic.py <listen-port> <daemon-port> <out.jsonl>

Point a client at http://127.0.0.1:<listen-port>/mcp; every request is forwarded to the daemon's
http://127.0.0.1:<daemon-port> and appended to <out.jsonl> as one JSON object: method, path,
headers, body, and the response's status, content type and (up to 64 KiB of) body. Responses are
streamed through as they arrive, so an SSE GET stream stays usable. The captured requests become
replay fixtures in daemon/src/binaryTest/resources/client-fixtures/.
"""
import http.client
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN, TARGET, OUT = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
LOCK = threading.Lock()
HOP_BY_HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "host", "content-length"}


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _forward(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        conn = http.client.HTTPConnection("127.0.0.1", TARGET, timeout=300)
        conn.request(self.command, self.path, body=body or None, headers=headers)
        resp = conn.getresponse()
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in HOP_BY_HOP:
                self.send_header(k, v)
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        captured = b""
        try:
            while True:
                chunk = resp.read1(8192)
                if not chunk:
                    break
                if len(captured) < 65536:
                    captured += chunk
                self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            conn.close()
            record = {
                "method": self.command,
                "path": self.path,
                "headers": dict(self.headers.items()),
                "body": body.decode("utf-8", "replace"),
                "response": {
                    "status": resp.status,
                    "contentType": resp.getheader("Content-Type"),
                    "body": captured.decode("utf-8", "replace"),
                },
            }
            with LOCK, open(OUT, "a") as f:
                f.write(json.dumps(record) + "\n")

    do_GET = do_POST = do_DELETE = _forward

    def log_message(self, fmt, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", LISTEN), Proxy).serve_forever()
