#!/usr/bin/env python3
"""Classify DB contents with SAFE flags only — no values printed."""
import sqlite3

KNOWN_DEMO_USERS = {"admin", "test", "boss", "user", "demo", "medpad", "ali", "reza"}

def classify(db):
    print("=" * 60)
    print("DB:", db.split("MedPad/")[1])
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    cur = con.cursor()
    users = [r[0] for r in cur.execute("SELECT username FROM Employee")]
    pw = [r[0] for r in cur.execute("SELECT password FROM Employee")]
    demo = sum(1 for u in users if u and u.lower() in KNOWN_DEMO_USERS)
    print(f"  Employees: {len(users)} | usernames matching known demo names: {demo}/{len(users)}")
    nonempty_pw = sum(1 for p in pw if p)
    # hash format detection (format only)
    fmts = set()
    for p in pw:
        if not p: continue
        if p.startswith("$2"): fmts.add("bcrypt")
        elif p.startswith("$scrypt"): fmts.add("scrypt")
        elif len(p) == 64 and all(c in "0123456789abcdef" for c in p): fmts.add("sha256-hex")
        elif p.startswith("pbkdf2"): fmts.add("pbkdf2")
        else: fmts.add("other/unknown")
    print(f"  Non-empty password entries: {nonempty_pw} | hash formats: {fmts or '-'}")
    keys = [r[0] for r in cur.execute("SELECT key FROM Config")]
    print(f"  Config keys (names only): {keys}")
    con.close()

for db in ["/home/z/my-project/MedPad/app/panel/db/custom.db",
           "/home/z/my-project/MedPad/medpad-full-1.9/medpad-offline-1.9.12/app/panel/db/custom.db"]:
    classify(db)
