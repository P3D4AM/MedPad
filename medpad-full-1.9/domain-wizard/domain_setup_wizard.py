#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MedPad Domain Setup Wizard v1.1 — Cloudflare Named Tunnel (auto domain setup)
=============================================================================
تأیید توکن → یافتن Zone → ساخت/بازیابی تونل نامی → ingress → رکورد DNS → config

رفع ۷ باگ نسخه ۱.۰:
  ۱) جایگاه {account_id} جایگزین نمی‌شد (درخواست به لفظ خام می‌رفت)
  ۲) AccountTag فایل credentials همیشه خالی بود (از API پر می‌شود)
  ۳) save_wizard_state به self.completed_steps ارجاع می‌داد (AttributeError)
  ۴) حالت non-interactive در create_tunnel گیر می‌کرد (input() حذف شد)
  ۵) config_src اشتباه بود — اکنون 'local' تولید می‌شود
  ۶) تداخل DNS (رکورد A/AAAA هم‌نام) — رکورد متعارض حذف می‌شود
  ۷) پروتکل پیش‌فرض QUIC (در ایران مسدود) → پیش‌فرض http2

Usage:
  export CLOUDFLARE_API_TOKEN='...'          # Tunnel:Edit + DNS:Edit + Zone:Read
  python3 domain_setup_wizard.py             # interactive
  python3 domain_setup_wizard.py --non-interactive \
      --domain example.com --subdomain panel \
      --service http://localhost:3000 --protocol http2
"""
import argparse
import json
import os
import sys
import urllib.request
import urllib.error
from typing import Any, Dict, Optional

WIZARD_VERSION = "1.1"
CF_API_DEFAULT = "https://api.cloudflare.com/client/v4"
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".wizard_state.json")


class CloudflareError(Exception):
    def __init__(self, message: str, status: int = 0, errors: Any = None):
        super().__init__(message)
        self.status = status
        self.errors = errors or []


class CloudflareClient:
    """Minimal Cloudflare API v4 client (urllib — no external deps)."""

    def __init__(self, token: str, api_base: Optional[str] = None):
        self.token = token
        self.api_base = (api_base or os.environ.get("CF_API_BASE") or CF_API_DEFAULT).rstrip("/")

    def _request(self, method: str, path: str, body: Optional[Dict] = None) -> Dict[str, Any]:
        url = f"{self.api_base}{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = json.loads(resp.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            try:
                payload = json.loads(e.read().decode() or "{}")
            except Exception:
                payload = {}
            errors = payload.get("errors", [])
            msg = " | ".join(f"{x.get('code')}: {x.get('message')}" for x in errors) or f"HTTP {e.code}"
            raise CloudflareError(msg, status=e.code, errors=errors)
        except urllib.error.URLError as e:
            raise CloudflareError(f"اتصال به API کلادفلر برقرار نشد: {e.reason}", status=0)
        if payload.get("success") is False:
            errors = payload.get("errors", [])
            msg = " | ".join(f"{x.get('code')}: {x.get('message')}" for x in errors) or "API error"
            raise CloudflareError(msg, status=0, errors=errors)
        return payload

    # ── token / account / zone ──────────────────────────────
    def verify_token(self) -> Dict[str, Any]:
        return self._request("GET", "/user/tokens/verify")

    def list_accounts(self) -> list:
        r = self._request("GET", "/accounts?per_page=5")
        return r.get("result") or []

    def get_zone(self, domain: str) -> Dict[str, Any]:
        # exact first, then suffix walk (sub.example.com → example.com)
        r = self._request("GET", f"/zones?per_page=5&name={urllib.parse.quote(domain)}")
        zone = (r.get("result") or [None])[0]
        if zone:
            return zone
        parts = domain.split(".")
        for i in range(1, len(parts) - 1):
            candidate = ".".join(parts[i:])
            r = self._request("GET", f"/zones?per_page=5&name={urllib.parse.quote(candidate)}")
            zone = (r.get("result") or [None])[0]
            if zone:
                return zone
        raise CloudflareError(f"دامنه «{domain}» در این اکانت کلادفلر پیدا نشد")

    # ── tunnels ─────────────────────────────────────────────
    def list_tunnels(self, account_id: str) -> list:
        r = self._request("GET", f"/accounts/{account_id}/cfd_tunnel?is_deleted=false&per_page=20")
        return r.get("result") or []

    def get_tunnel(self, account_id: str, tunnel_id: str) -> Optional[Dict[str, Any]]:
        try:
            r = self._request("GET", f"/accounts/{account_id}/cfd_tunnel/{tunnel_id}")
            return r.get("result")
        except CloudflareError:
            return None

    def create_tunnel(self, account_id: str, name: str) -> Dict[str, Any]:
        # bug fix #5: config_src=local (config file is produced locally)
        r = self._request(
            "POST",
            f"/accounts/{account_id}/cfd_tunnel",
            {"name": name, "config_src": "local"},
        )
        return r["result"]

    def tunnel_token(self, account_id: str, tunnel_id: str) -> str:
        r = self._request("GET", f"/accounts/{account_id}/cfd_tunnel/{tunnel_id}/token")
        return r["result"]

    def put_ingress(self, account_id: str, tunnel_id: str, hostname: str, service: str) -> Dict:
        config = {
            "ingress": [
                {"hostname": hostname, "service": service, "originRequest": {"connectTimeout": 30, "noTLSVerify": True}},
                {"service": "http_status:404"},
            ]
        }
        self._request(
            "PUT",
            f"/accounts/{account_id}/cfd_tunnel/{tunnel_id}/configurations",
            config,
        )
        return config

    # ── DNS ─────────────────────────────────────────────────
    def list_dns(self, zone_id: str, name: str) -> list:
        r = self._request("GET", f"/zones/{zone_id}/dns_records?per_page=100&name={urllib.parse.quote(name)}")
        return r.get("result") or []

    def delete_record(self, zone_id: str, record_id: str) -> None:
        self._request("DELETE", f"/zones/{zone_id}/dns_records/{record_id}")

    def upsert_cname(self, zone_id: str, name: str, target: str) -> bool:
        """Upsert proxied CNAME; deletes conflicting A/AAAA (bug fix #6). Returns created?"""
        records = self.list_dns(zone_id, name)
        has_cname = False
        for rec in records:
            if rec["type"] == "CNAME":
                has_cname = True
                if rec["content"] != target or not rec.get("proxied"):
                    self._request(
                        "PUT",
                        f"/zones/{zone_id}/dns_records/{rec['id']}",
                        {"type": "CNAME", "name": name, "content": target, "proxied": True, "ttl": 1},
                    )
            elif rec["type"] in ("A", "AAAA"):
                # conflicting apex/sub A record → delete (fix #6)
                self.delete_record(zone_id, rec["id"])
        if not has_cname:
            self._request(
                "POST",
                f"/zones/{zone_id}/dns_records",
                {"type": "CNAME", "name": name, "content": target, "proxied": True, "ttl": 1},
            )
            return True
        return False


class DomainWizard:
    def __init__(self, token: str, domain: str, subdomain: str, service: str,
                 protocol: str = "http2", tunnel_name: str = "medpad",
                 non_interactive: bool = False, api_base: Optional[str] = None):
        self.cf = CloudflareClient(token, api_base)
        self.domain = domain.strip().lower()
        self.subdomain = subdomain.strip().lower() or "panel"
        self.service = service or "http://localhost:3000"
        self.protocol = protocol or "http2"  # bug fix #7
        self.tunnel_name = tunnel_name
        self.non_interactive = non_interactive
        self.state: Dict[str, Any] = self._load_state()

    # ── state (bug fix #3: no phantom attributes) ───────────
    def _load_state(self) -> Dict[str, Any]:
        try:
            with open(STATE_FILE) as f:
                return json.load(f)
        except Exception:
            return {}

    def save_wizard_state(self, **updates) -> None:
        self.state.update(updates)
        self.state["wizard_version"] = WIZARD_VERSION
        with open(STATE_FILE, "w") as f:
            json.dump(self.state, f, indent=2, ensure_ascii=False)

    # ── steps ───────────────────────────────────────────────
    def run(self) -> Dict[str, Any]:
        print("━━━ MedPad Domain Wizard v" + WIZARD_VERSION + " ━━━")
        # 1) token verify
        v = self.cf.verify_token()
        status = (v.get("result") or {}).get("status")
        print(f"✓ توکن معتبر است (status={status})")
        if status != "active":
            raise CloudflareError(f"وضعیت توکن active نیست: {status}")

        # 2) zone + account id (bug fix #1: resolve real account id)
        zone = self.cf.get_zone(self.domain)
        zone_id = zone["id"]
        zone_name = zone["name"]
        account_id = zone.get("account", {}).get("id")
        if not account_id:
            accounts = self.cf.list_accounts()
            account_id = accounts[0]["id"] if accounts else None
        if not account_id:
            raise CloudflareError("شناسه اکانت (account id) یافت نشد")
        account_tag = zone.get("account", {}).get("id") or account_id  # bug fix #2
        print(f"✓ Zone: {zone_name} | account: {account_id[:8]}…")

        hostname = f"{self.subdomain}.{self.domain}"
        if not hostname.endswith(zone_name):
            raise CloudflareError(f"زیردامنه باید زیر «{zone_name}» باشد")

        # 3) create-or-reuse tunnel (no input() in non-interactive — fix #4)
        tunnel = None
        prev_id = self.state.get("tunnelId")
        if prev_id:
            tunnel = self.cf.get_tunnel(account_id, prev_id)
            if tunnel:
                print(f"♻ تونل موجود بازیابی شد: {tunnel['name']} ({tunnel['id'][:8]}…)")
        if tunnel is None:
            for t in self.cf.list_tunnels(account_id):
                if t.get("name") == self.tunnel_name:
                    tunnel = t
                    print(f"♻ تونل هم‌نام پیدا شد: {t['name']} ({t['id'][:8]}…)")
                    break
        if tunnel is None:
            tunnel = self.cf.create_tunnel(account_id, self.tunnel_name)
            print(f"✓ تونل جدید ساخته شد: {tunnel['id'][:8]}…")
        tunnel_id = tunnel["id"]

        # 4) ingress
        self.cf.put_ingress(account_id, tunnel_id, hostname, self.service)
        print(f"✓ مسیردهی: {hostname} → {self.service}")

        # 5) DNS CNAME (proxied, conflict-safe)
        target = f"{tunnel_id}.cfargotunnel.com"
        created = self.cf.upsert_cname(zone_id, hostname, target)
        print(f"✓ DNS CNAME {'ساخته شد' if created else 'به‌روز است'} → {target[:20]}…")

        # 6) tunnel token for cloudflared run
        tok = self.cf.tunnel_token(account_id, tunnel_id)

        # 7) credentials + local config file (config_src=local — fix #5)
        cred = {
            "AccountTag": account_tag,
            "TunnelSecret": tok.split(":").pop(),
            "TunnelID": tunnel_id,
        }
        cred_file = os.path.join(os.path.dirname(STATE_FILE), f"{tunnel_id}.json")
        with open(cred_file, "w") as f:
            json.dump(cred, f, indent=2)
        yml = os.path.join(os.path.dirname(STATE_FILE), "config.yml")
        with open(yml, "w") as f:
            f.write(
                f"tunnel: {tunnel_id}\n"
                f"credentials-file: {cred_file}\n"
                f"protocol: {self.protocol}\n\n"
                f"ingress:\n  - hostname: {hostname}\n    service: {self.service}\n"
                f"  - service: http_status:404\n"
            )

        self.save_wizard_state(
            tunnelId=tunnel_id,
            hostname=hostname,
            zoneName=zone_name,
            accountId=account_id,
            service=self.service,
            protocol=self.protocol,
        )
        public_url = f"https://{hostname}"
        print("\n🎉 تنظیم دامنه کامل شد")
        print(f"   آدرس ثابت:  {public_url}")
        print(f"   config:     {yml}")
        print(f"   اجرای تونل: cloudflared tunnel --config {yml} run")
        return {
            "tunnel_id": tunnel_id,
            "hostname": hostname,
            "public_url": public_url,
            "account_tag": account_tag,
            "config_yml": yml,
            "credentials_file": cred_file,
        }


def main() -> int:
    ap = argparse.ArgumentParser(description="MedPad Cloudflare Domain Wizard v" + WIZARD_VERSION)
    ap.add_argument("--domain", help="example.com")
    ap.add_argument("--subdomain", default="panel")
    ap.add_argument("--service", default="http://localhost:3000")
    ap.add_argument("--protocol", default="http2", choices=["http2", "quic"])
    ap.add_argument("--tunnel-name", default="medpad")
    ap.add_argument("--non-interactive", action="store_true")
    ap.add_argument("--api-base", default=None, help="(testing) override CF API base")
    args = ap.parse_args()

    token = os.environ.get("CLOUDFLARE_API_TOKEN") or ""
    if not token and not args.non_interactive:
        token = input("🔑 Cloudflare API Token: ").strip()
    if not token:
        print("✗ توکن API داده نشد (CLOUDFLARE_API_TOKEN)", file=sys.stderr)
        return 2
    if not args.domain:
        if args.non_interactive:
            print("✗ --domain الزامی است", file=sys.stderr)
            return 2
        args.domain = input("🌐 دامنه (مثلا example.com): ").strip()
    if not args.domain:
        print("✗ دامنه داده نشد", file=sys.stderr)
        return 2

    try:
        wiz = DomainWizard(
            token=token,
            domain=args.domain,
            subdomain=args.subdomain,
            service=args.service,
            protocol=args.protocol,
            tunnel_name=args.tunnel_name,
            non_interactive=args.non_interactive,
            api_base=args.api_base,
        )
        wiz.run()
        return 0
    except CloudflareError as e:
        print(f"✗ خطای کلادفلر: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
