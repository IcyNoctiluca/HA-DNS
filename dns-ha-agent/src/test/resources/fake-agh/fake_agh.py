#!/usr/bin/env python3
"""
Minimal, dependency-free fake AGH for dns-ha-agent integration tests.

Answers A-record queries over UDP:53. Any name other than BLOCK_NAME always
resolves to RESOLVE_IP (stands in for the agent's probe.resolve_names
check). BLOCK_NAME resolves according to the current filter_mode:
  - "blocked"  (default): NXDOMAIN — filtering is working.
  - "bypassed": BYPASS_IP — simulates a broken filter (real answer leaked).

filter_mode is controlled at runtime via a tiny HTTP control API on :8080,
per BLUEPRINT.md §10 "FakeAghContainer ... responds to DNS queries with
canned answers controllable via HTTP API":

  POST /control/filter?mode=blocked
  POST /control/filter?mode=bypassed
  GET  /            -> 200 "ok" (liveness for Testcontainers wait strategy)

Deliberately stdlib-only (no dnspython/twisted/etc.) so the test image has
no extra build step or dependency-resolution surface.
"""
import os
import socket
import struct
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

BLOCK_NAME = os.environ.get("BLOCK_NAME", "dns-filter-sentinel.example.com").rstrip(".").lower()
RESOLVE_IP = os.environ.get("RESOLVE_IP", "93.184.216.34")
BYPASS_IP = os.environ.get("BYPASS_IP", "203.0.113.99")

state_lock = threading.Lock()
state = {"filter_mode": "blocked"}


def encode_name(name):
    out = b""
    for part in name.rstrip(".").split("."):
        out += bytes([len(part)]) + part.encode("ascii")
    return out + b"\x00"


def parse_qname(data, offset):
    labels = []
    while True:
        length = data[offset]
        if length == 0:
            offset += 1
            break
        offset += 1
        labels.append(data[offset:offset + length].decode("ascii", errors="replace"))
        offset += length
    return ".".join(labels), offset


def build_response(txid, qname, qtype, rcode, answer_ip=None):
    flags = struct.pack(">H", 0x8180 | (rcode & 0x0F))
    qdcount = struct.pack(">H", 1)
    ancount = struct.pack(">H", 1 if answer_ip else 0)
    header = txid + flags + qdcount + ancount + struct.pack(">HH", 0, 0)
    question = encode_name(qname) + struct.pack(">HH", qtype, 1)
    answer = b""
    if answer_ip:
        name_ptr = b"\xc0\x0c"  # pointer back to the question's name
        answer = (
            name_ptr
            + struct.pack(">H", 1)  # TYPE A
            + struct.pack(">H", 1)  # CLASS IN
            + struct.pack(">I", 60)  # TTL
            + struct.pack(">H", 4)  # RDLENGTH
            + socket.inet_aton(answer_ip)
        )
    return header + question + answer


def handle_query(data):
    txid = data[0:2]
    qname, offset = parse_qname(data, 12)
    qtype, _qclass = struct.unpack(">HH", data[offset:offset + 4])
    qname_lower = qname.rstrip(".").lower()

    if qname_lower == BLOCK_NAME:
        with state_lock:
            mode = state["filter_mode"]
        if mode == "blocked":
            return build_response(txid, qname, qtype, rcode=3)  # NXDOMAIN
        return build_response(txid, qname, qtype, rcode=0, answer_ip=BYPASS_IP)

    return build_response(txid, qname, qtype, rcode=0, answer_ip=RESOLVE_IP)


def dns_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 53))
    print("fake_agh dns_server listening on :53/udp", flush=True)
    while True:
        data, addr = sock.recvfrom(512)
        try:
            sock.sendto(handle_query(data), addr)
        except Exception as exc:  # noqa: BLE001 - best-effort test double
            print(f"fake_agh dns_server error: {exc}", flush=True)


class ControlHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path.startswith("/control/filter"):
            mode = "bypassed" if "mode=bypassed" in self.path else "blocked"
            with state_lock:
                state["filter_mode"] = mode
            self.send_response(200)
            self.end_headers()
            self.wfile.write(mode.encode("ascii"))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format_str, *args):  # keep container logs quiet
        pass


def control_server():
    HTTPServer(("0.0.0.0", 8080), ControlHandler).serve_forever()


if __name__ == "__main__":
    threading.Thread(target=control_server, daemon=True).start()
    print("fake_agh control_server listening on :8080/tcp", flush=True)
    dns_server()
