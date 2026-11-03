#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for medpad_legacy_backup.py — fixture legacy databases (the
physical shapes of old installs: v6 / v11 / v13 / v14, plus a corrupt
file) converted to the backup-JSON the app's import accepts.

Run:  python3 -m unittest test_legacy_backup -v
(stdlib only; builds its fixtures in a temp dir)
"""

import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import medpad_legacy_backup as tool  # noqa: E402


def make_v6(path):
    """the first committed app's schema (earliest released apk)."""
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE doctors (id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
            address TEXT, phone TEXT, lat REAL NOT NULL, lng REAL NOT NULL,
            radius REAL NOT NULL, updatedAt INTEGER NOT NULL, assigned INTEGER NOT NULL,
            PRIMARY KEY(id));
        CREATE TABLE pending_checkins (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            localId TEXT NOT NULL, kind TEXT NOT NULL, customerId TEXT, note TEXT NOT NULL,
            voicePath TEXT, lat REAL, lng REAL, createdAt INTEGER NOT NULL,
            attempts INTEGER NOT NULL, lastError TEXT);
        CREATE TABLE pending_locations (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            lat REAL NOT NULL, lng REAL NOT NULL, createdAt INTEGER NOT NULL);
        INSERT INTO doctors VALUES ('d1','دکتر سلطانی','doctor','شیراز','07132220000',
            29.6,52.5,180.0,1700000111000,1);
        INSERT INTO pending_checkins (localId,kind,customerId,note,voicePath,lat,lng,createdAt,attempts)
            VALUES ('chk1','checkin','d1','گزارش','/voice/a.m4a',29.6,52.5,1700000222000,0);
        INSERT INTO pending_locations (lat,lng,createdAt) VALUES (29.6,52.5,1700000333000);
        """
    )
    conn.execute("PRAGMA user_version = 6")
    conn.commit()
    conn.close()


def make_v11(path):
    """dev.4/dev.6 shape: no voicePaths, no op_events, no Customer-360."""
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE doctors (id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
            address TEXT, phone TEXT, province TEXT, city TEXT,
            lat REAL NOT NULL, lng REAL NOT NULL, radius REAL NOT NULL,
            updatedAt INTEGER NOT NULL, assigned INTEGER NOT NULL, PRIMARY KEY(id));
        CREATE TABLE pending_checkins (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            localId TEXT NOT NULL, kind TEXT NOT NULL, customerId TEXT, note TEXT NOT NULL,
            voicePath TEXT, lat REAL, lng REAL, accuracy REAL,
            createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT,
            status TEXT NOT NULL DEFAULT 'pending');
        CREATE TABLE pending_locations (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            localId TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, accuracy REAL,
            createdAt INTEGER NOT NULL);
        CREATE TABLE planned_visits (id TEXT NOT NULL, customerId TEXT NOT NULL,
            dayKey TEXT NOT NULL, timeLocal TEXT NOT NULL, scheduledAtMs INTEGER NOT NULL,
            durationMin INTEGER NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL,
            note TEXT, visitId TEXT, visitStartLocal TEXT, visitOpen INTEGER NOT NULL,
            overdue INTEGER NOT NULL, customerName TEXT, customerType TEXT,
            customerAddress TEXT, customerPhone TEXT, customerAssigned INTEGER NOT NULL,
            PRIMARY KEY(id));
        INSERT INTO doctors VALUES ('d1','دکتر رضایی','doctor',NULL,'09150000000','فارس','شیراز',
            29.6,52.5,150.0,1700000444000,1);
        INSERT INTO pending_checkins (localId,kind,customerId,note,lat,lng,createdAt,attempts,status)
            VALUES ('chk2','checkout',NULL,'پایان ویزیت',29.6,52.5,1700000555000,2,'failed');
        INSERT INTO pending_locations (localId,lat,lng,accuracy,createdAt)
            VALUES ('loc2',29.6,52.5,7.5,1700000666000);
        INSERT INTO planned_visits VALUES ('p1','d1','2026-11-05','08:00',1700000777000,30,
            'normal','scheduled','یادآور',NULL,NULL,0,0,'دکتر رضایی','doctor',NULL,NULL,1);
        """
    )
    conn.execute("PRAGMA user_version = 11")
    conn.commit()
    conn.close()


def make_v13(path):
    """dev.7 shape: v11 + voicePaths + op_events, still no Customer-360."""
    make_v11(path)
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE op_events (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            localId TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL,
            createdAt INTEGER NOT NULL, errorCode TEXT, message TEXT,
            relatedType TEXT, relatedId TEXT, attempt INTEGER);
        ALTER TABLE pending_checkins ADD COLUMN voicePaths TEXT;
        INSERT INTO op_events (localId,type,status,createdAt,message)
            VALUES ('ev1','queue.flush','success',1700000888000,'خالی شد');
        """
    )
    conn.execute("PRAGMA user_version = 13")
    conn.commit()
    conn.close()


def make_v14(path):
    """current shape — full eight tables, incl. Customer-360."""
    make_v13(path)
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        ALTER TABLE doctors ADD COLUMN lastName TEXT;
        ALTER TABLE doctors ADD COLUMN title TEXT;
        ALTER TABLE doctors ADD COLUMN specialty TEXT;
        ALTER TABLE doctors ADD COLUMN managerName TEXT;
        ALTER TABLE doctors ADD COLUMN phone2 TEXT;
        ALTER TABLE doctors ADD COLUMN district TEXT;
        ALTER TABLE doctors ADD COLUMN postalCode TEXT;
        ALTER TABLE doctors ADD COLUMN notes TEXT;
        ALTER TABLE doctors ADD COLUMN active INTEGER NOT NULL DEFAULT 1;
        ALTER TABLE doctors ADD COLUMN pendingLocalId TEXT;
        CREATE TABLE customer_links (id TEXT NOT NULL, clinicId TEXT NOT NULL,
            physicianId TEXT NOT NULL, clinicName TEXT, physicianName TEXT, note TEXT,
            active INTEGER NOT NULL, PRIMARY KEY(id));
        CREATE TABLE customer_profiles (customerId TEXT NOT NULL, payload TEXT NOT NULL,
            fetchedAt INTEGER NOT NULL, PRIMARY KEY(customerId));
        CREATE TABLE pending_customer_ops (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            localId TEXT NOT NULL, op TEXT NOT NULL, customerId TEXT, payload TEXT NOT NULL,
            createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT,
            status TEXT NOT NULL);
        INSERT INTO doctors (id,name,type,phone,lat,lng,radius,updatedAt,assigned,specialty,active)
            VALUES ('d2','مریم محمدی','doctor','09120000000',35.1,51.1,150.0,1700000999000,1,'قلب',1);
        INSERT INTO customer_links VALUES ('l1','d2','d2','کلینیک','دکتر',NULL,1);
        INSERT INTO customer_profiles VALUES ('d2','{}',1700001010000);
        INSERT INTO pending_customer_ops (localId,op,customerId,payload,createdAt,attempts,status)
            VALUES ('op1','create',NULL,'{}',1700001020000,0,'pending');
        """
    )
    conn.execute("PRAGMA user_version = 14")
    conn.commit()
    conn.close()


class LegacyBackupToolTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="medpad-legacy-test-")
        self.addCleanup(lambda: shutil.rmtree(self.dir, ignore_errors=True))

    def path(self, name):
        return os.path.join(self.dir, name)

    def run_tool(self, db, out=None):
        out = out or self.path("out.json")
        code = tool.main([db, "-o", out])
        self.assertEqual(0, code, "tool must succeed")
        with open(out, encoding="utf-8") as f:
            return json.load(f)

    # ── v6: the earliest released schema ─────────────────────────────────

    def test_v6_converts_with_migration_defaults(self):
        db = self.path("v6.db")
        make_v6(db)
        data = self.run_tool(db)

        m = data["manifest"]
        self.assertEqual("MedPad", m["app"])
        self.assertEqual(1, m["formatVersion"])
        self.assertEqual(14, m["schemaVersion"])       # output = CURRENT schema
        self.assertEqual(6, m["sourceSchemaVersion"])  # provenance kept
        self.assertTrue(m["exportedAt"].endswith("Z"))
        self.assertEqual("medpad.db" if os.path.basename(db) == "medpad.db" else "v6.db", m["sourceDb"])

        d = data["entities"]["doctors"][0]
        self.assertEqual("دکتر سلطانی", d["name"])
        self.assertEqual(180.0, d["radius"])
        self.assertTrue(d["assigned"])
        # columns that did not exist on v6 are OMITTED — the app's reader
        # applies the same defaults the Room migrations would (province→
        # null, active→true, …). Omitting keeps the file forward-compatible.
        self.assertNotIn("province", d)
        self.assertNotIn("specialty", d)
        self.assertNotIn("active", d)

        chk = data["entities"]["pending_checkins"][0]
        self.assertEqual("chk1", chk["localId"])
        self.assertNotIn("status", chk)                 # 8→9 default 'pending' → omitted
        self.assertNotIn("accuracy", chk)              # 6→7 NULL
        self.assertNotIn("voicePaths", chk)            # 12→13 NULL

        loc = data["entities"]["pending_locations"][0]
        self.assertNotIn("localId", loc)               # 10→11 default '' → omitted
        self.assertNotIn("accuracy", loc)

        # tables that did not exist on v6 come out EMPTY, not missing
        self.assertEqual([], data["entities"]["planned_visits"])
        self.assertEqual([], data["entities"]["op_events"])
        self.assertEqual([], data["entities"]["customer_links"])

        self.assertEqual(3, sum(len(v) for v in data["entities"].values()))

    # ── v11 ───────────────────────────────────────────────────────────────

    def test_v11_converts_status_and_locations(self):
        db = self.path("v11.db")
        make_v11(db)
        data = self.run_tool(db)

        self.assertEqual(11, data["manifest"]["sourceSchemaVersion"])
        chk = data["entities"]["pending_checkins"][0]
        self.assertEqual("failed", chk["status"])      # a REAL v11 value survives
        self.assertNotIn("voicePaths", chk)
        loc = data["entities"]["pending_locations"][0]
        self.assertEqual("loc2", loc["localId"])
        self.assertEqual(7.5, loc["accuracy"])
        pv = data["entities"]["planned_visits"][0]
        self.assertEqual("p1", pv["id"])
        self.assertTrue(pv["customerAssigned"])        # 1 → true
        self.assertFalse(pv["visitOpen"])              # 0 → false
        self.assertEqual([], data["entities"]["op_events"])

    # ── v13 ───────────────────────────────────────────────────────────────

    def test_v13_keeps_op_events_and_voice_paths(self):
        db = self.path("v13.db")
        make_v13(db)
        data = self.run_tool(db)

        self.assertEqual(13, data["manifest"]["sourceSchemaVersion"])
        ev = data["entities"]["op_events"][0]
        self.assertEqual("ev1", ev["localId"])
        self.assertEqual("خالی شد", ev["message"])
        # 360 tables empty on v13
        self.assertEqual([], data["entities"]["customer_links"])
        self.assertEqual([], data["entities"]["pending_customer_ops"])

    # ── v14: identity + the full field set ───────────────────────────────

    def test_v14_full_shape(self):
        db = self.path("v14.db")
        make_v14(db)
        data = self.run_tool(db)

        self.assertEqual(14, data["manifest"]["sourceSchemaVersion"])
        d2 = [d for d in data["entities"]["doctors"] if d["id"] == "d2"][0]
        self.assertEqual("قلب", d2["specialty"])
        self.assertTrue(d2["active"])
        self.assertEqual("l1", data["entities"]["customer_links"][0]["id"])
        self.assertEqual("op1", data["entities"]["pending_customer_ops"][0]["localId"])
        self.assertEqual(1, len(data["entities"]["customer_profiles"]))

        # uid must NEVER appear anywhere in the output
        for rows in data["entities"].values():
            for row in rows:
                self.assertNotIn("uid", row)

    # ── wal recovery ──────────────────────────────────────────────────────

    def test_sibling_wal_is_recovered(self):
        # build a fully-checkpointed v6 file, then add one doctor on a WAL
        # connection and snapshot BOTH files while it is still open — the
        # newest row lives ONLY in the -wal, exactly like an old install
        # whose app was closed abruptly
        src = self.path("walsrc.db")
        make_v6(src)
        conn = sqlite3.connect(src)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute(
            "INSERT INTO doctors VALUES ('d-wal','کلینیک وال','clinic',NULL,NULL,1.0,1.0,150.0,1,1)"
        )
        conn.commit()
        db = self.path("wal.db")
        shutil.copy2(src, db)
        shutil.copy2(src + "-wal", db + "-wal")
        conn.close()

        self.assertTrue(os.path.getsize(db + "-wal") > 0, "fixture must produce a non-empty wal")
        data = self.run_tool(db)
        ids = {d["id"] for d in data["entities"]["doctors"]}
        self.assertIn("d1", ids)
        self.assertIn("d-wal", ids, "the wal-only row must be recovered")

    # ── error paths ───────────────────────────────────────────────────────

    def test_corrupt_file_fails_honestly(self):
        bad = self.path("corrupt.db")
        with open(bad, "w", encoding="utf-8") as f:
            f.write("این فایل پایگاه‌داده نیست")
        out = self.path("nope.json")
        code = tool.main([bad, "-o", out])
        self.assertEqual(1, code)
        self.assertFalse(os.path.exists(out))

    def test_too_old_schema_fails_honestly(self):
        db = self.path("old.db")
        conn = sqlite3.connect(db)
        conn.executescript("CREATE TABLE doctors (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        conn.execute("PRAGMA user_version = 3")
        conn.commit()
        conn.close()
        code = tool.main([db, "-o", self.path("nope.json")])
        self.assertEqual(1, code)

    def test_future_schema_fails_honestly(self):
        db = self.path("future.db")
        make_v14(db)
        conn = sqlite3.connect(db)
        conn.execute("PRAGMA user_version = 15")
        conn.commit()
        conn.close()
        code = tool.main([db, "-o", self.path("nope.json")])
        self.assertEqual(1, code)

    def test_missing_file_fails_honestly(self):
        self.assertEqual(2, tool.main([self.path("ghost.db"), "-o", self.path("nope.json")]))

    # ── the null contract ─────────────────────────────────────────────────

    def test_null_values_are_omitted_not_written(self):
        db = self.path("nulls.db")
        make_v6(db)
        data = self.run_tool(db)
        raw = json.dumps(data, ensure_ascii=False)
        self.assertNotIn(": null", raw, "the format omits nulls — never writes JSON null")


if __name__ == "__main__":
    unittest.main()
