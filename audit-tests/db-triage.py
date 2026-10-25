#!/usr/bin/env python3
"""Phase 2 DB triage — print ONLY schema, row counts, and safe flags.
NEVER prints passwords/hashes/tokens/usernames."""
import sqlite3, sys, hashlib

DBS = [
    "/home/z/my-project/MedPad/app/panel/db/custom.db",
    "/home/z/my-project/MedPad/medpad-full-1.9/inst-stage/panel-src/db/custom.db",
    "/home/z/my-project/MedPad/medpad-full-1.9/medpad-offline-1.9.12/app/panel/db/custom.db",
]

for db in DBS:
    print("=" * 70)
    print("DB:", db.split("MedPad/")[1])
    with open(db, "rb") as f:
        data = f.read()
    print(f"  size={len(data)}  sha256={hashlib.sha256(data).hexdigest()[:16]}")
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    cur = con.cursor()
    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    print("  tables:", ", ".join(tables))
    for t in tables:
        cols = [c[1] for c in cur.execute(f"PRAGMA table_info('{t}')")]
        n = cur.execute(f"SELECT COUNT(*) FROM '{t}'").fetchone()[0]
        print(f"    - {t}: rows={n}  cols=[{', '.join(cols)}]")
        # safe flags only: presence of data, NOT values
        if n > 0 and t.lower() in ("user", "users", "admin", "admins", "session", "sessions", "token", "tokens"):
            print(f"      ⚠️  CONTAINS {n} ROW(S) — potential real credentials data")
    con.close()
print("=" * 70)
print("Triage complete — no sensitive values printed.")
