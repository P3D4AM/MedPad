#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mock Cloudflare API server for testing domain_setup_wizard.py — NO real token needed.
Covers: verify, zones, accounts, tunnels (list/create/get/token/ingress), DNS records.
Run:  python3 test_wizard_mock.py   (starts mock on :3789, runs wizard twice, asserts)
"""
import json
import os
import re
import subprocess
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 3789
BASE = f"http://127.0.0.1:{PORT}/client/v4"
TOKEN = "test-token-0123456789abcdef0123456789abcdef0123"  # 43 chars
ACCOUNT_ID = "a" * 32
ZONE_ID = "z" * 32
TUNNEL_ID = "0e11a9c1" + "f" * 24  # hex-ish like real ids

state = {
    "tunnels": [],
    "dns": [],
    "ingress": {},
    "requests": [],
}


class MockCF(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode() or "{}") if n else {}

    def _auth(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self._json(403, {"success": False, "errors": [{"code": 9109, "message": "Invalid access token"}]})
            return False
        return True

    def do_GET(self):
        state["requests"].append(("GET", self.path.split("?")[0]))
        if not self._auth():
            return
        p = self.path
        if "/user/tokens/verify" in p:
            return self._json(200, {"success": True, "result": {"status": "active", "id": "tok1"}})
        if "/accounts?" in p or p.endswith("/accounts"):
            return self._json(200, {"success": True, "result": [{"id": ACCOUNT_ID, "name": "Test"}]})
        if "/zones?" in p or p.endswith("/zones"):
            return self._json(200, {"success": True, "result": [{
                "id": ZONE_ID, "name": "example.com",
                "account": {"id": ACCOUNT_ID, "name": "Test"},
            }]})
        if "/cfd_tunnel/" + TUNNEL_ID + "/token" in p:
            return self._json(200, {"success": True, "result": f"secret:{TUNNEL_ID}-secret"})
        if re.search(rf"/cfd_tunnel/{TUNNEL_ID}/configurations", p):
            return self._json(200, {"success": True, "result": state["ingress"].get(TUNNEL_ID, {})})
        if re.search(r"/cfd_tunnel/[a-f0-9]+$", p):
            tid = p.rstrip("/").split("/")[-1]
            t = next((x for x in state["tunnels"] if x["id"] == tid), None)
            if not t:
                return self._json(404, {"success": False, "errors": [{"code": 10000, "message": "not found"}]})
            return self._json(200, {"success": True, "result": t})
        if "/cfd_tunnel" in p:
            return self._json(200, {"success": True, "result": state["tunnels"]})
        if "/dns_records" in p:
            name = ""
            if "name=" in p:
                name = p.split("name=")[1].split("&")[0]
            recs = [r for r in state["dns"] if r["name"] == name] if name else state["dns"]
            return self._json(200, {"success": True, "result": recs})
        return self._json(404, {"success": False, "errors": [{"code": 7000, "message": "no route"}]})

    def do_POST(self):
        state["requests"].append(("POST", self.path.split("?")[0]))
        if not self._auth():
            return
        body = self._read()
        if "/cfd_tunnel" in self.path and "token" not in self.path:
            t = {"id": TUNNEL_ID, "name": body.get("name", "medpad"), "status": "inactive",
                 "config_src": body.get("config_src")}
            state["tunnels"].append(t)
            return self._json(200, {"success": True, "result": t})
        if "/dns_records" in self.path:
            rec = dict(body)
            rec["id"] = "rec" + str(len(state["dns"]) + 1)
            state["dns"].append(rec)
            return self._json(200, {"success": True, "result": rec})
        return self._json(404, {"success": False, "errors": []})

    def do_PUT(self):
        state["requests"].append(("PUT", self.path.split("?")[0]))
        if not self._auth():
            return
        body = self._read()
        if "/configurations" in self.path:
            state["ingress"][TUNNEL_ID] = body
            return self._json(200, {"success": True, "result": body})
        if "/dns_records/" in self.path:
            rid = self.path.rstrip("/").split("/")[-1]
            for i, r in enumerate(state["dns"]):
                if r["id"] == rid:
                    state["dns"][i] = {**r, **body}
            return self._json(200, {"success": True, "result": body})
        return self._json(404, {"success": False, "errors": []})

    def do_DELETE(self):
        state["requests"].append(("DELETE", self.path.split("?")[0]))
        if not self._auth():
            return
        if "/dns_records/" in self.path:
            rid = self.path.rstrip("/").split("/")[-1]
            state["dns"] = [r for r in state["dns"] if r["id"] != rid]
            return self._json(200, {"success": True, "result": {"id": rid}})
        return self._json(404, {"success": False, "errors": []})


passed = 0
failed = 0


def check(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✓ {name}")
    else:
        failed += 1
        print(f"  ✗ {name} {extra}")


def run_wizard(domain="example.com", sub="panel"):
    env = dict(os.environ)
    env["CLOUDFLARE_API_TOKEN"] = TOKEN
    env["CF_API_BASE"] = BASE
    args = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "domain_setup_wizard.py"),
            "--non-interactive", "--domain", domain, "--subdomain", sub,
            "--service", "http://localhost:3000", "--protocol", "http2"]
    r = subprocess.run(args, capture_output=True, text=True, env=env, timeout=60)
    return r


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), MockCF)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"mock CF API on :{PORT}")

    here = os.path.dirname(os.path.abspath(__file__))
    os.chdir(here)

    # seed a conflicting A record (bug fix #6 path)
    state["dns"].append({"id": "rec-conflict", "type": "A", "name": "panel.example.com",
                         "content": "1.2.3.4", "proxied": False})

    print("\n— RUN 1: fresh install —")
    r1 = run_wizard()
    check("exit 0", r1.returncode == 0, r1.stdout + r1.stderr)
    check("tunnel created", any(t["id"] == TUNNEL_ID for t in state["tunnels"]))
    check("config_src=local", any(m[0] == "POST" and "cfd_tunnel" in m[1] for m in state["requests"]))
    check("ingress set", state["ingress"].get(TUNNEL_ID, {}).get("ingress", [{}])[0].get("hostname") == "panel.example.com")
    check("conflicting A deleted", not any(r["type"] == "A" for r in state["dns"]))
    check("CNAME created", any(r["type"] == "CNAME" and r["content"] == f"{TUNNEL_ID}.cfargotunnel.com" for r in state["dns"]))
    check("state saved", os.path.exists(".wizard_state.json"))
    st = json.load(open(".wizard_state.json"))
    check("state has tunnelId", st.get("tunnelId") == TUNNEL_ID)
    cred = json.load(open(f"{TUNNEL_ID}.json"))
    check("AccountTag filled (fix #2)", cred.get("AccountTag") == ACCOUNT_ID)
    check("TunnelID filled", cred.get("TunnelID") == TUNNEL_ID)
    yml = open("config.yml").read()
    check("config.yml protocol http2 (fix #7)", "protocol: http2" in yml)
    check("config.yml hostname", "hostname: panel.example.com" in yml)
    check("no literal {account_id} in requests (fix #1)",
          all("{account_id}" not in p for _, p in state["requests"]))
    check("output has public url", "https://panel.example.com" in r1.stdout)

    print("\n— RUN 2: idempotent re-run —")
    state["requests"].clear()
    r2 = run_wizard()
    check("exit 0", r2.returncode == 0, r2.stdout + r2.stderr)
    creates_in_run2 = sum(1 for m in state["requests"] if m[0] == "POST" and m[1].endswith("/cfd_tunnel"))
    check("no duplicate tunnel created", creates_in_run2 == 0, f"creates={creates_in_run2}")
    check("reused existing tunnel message", "بازیابی" in r2.stdout or "♻" in r2.stdout)

    print("\n— RUN 3: dead token fails fast —")
    env = dict(os.environ)
    env["CLOUDFLARE_API_TOKEN"] = "bad-token"
    env["CF_API_BASE"] = BASE
    r3 = subprocess.run([sys.executable, "domain_setup_wizard.py", "--non-interactive", "--domain", "example.com"],
                        capture_output=True, text=True, env=env, timeout=60)
    check("exit non-zero", r3.returncode == 1, str(r3.returncode))

    server.shutdown()
    print(f"\n═══ RESULT: {passed} passed, {failed} failed ═══")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
