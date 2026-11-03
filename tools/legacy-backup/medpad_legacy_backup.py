#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MedPad legacy backup tool — the "additional tool" of the owner mission
(Nov 3, 2026):

    «یک ابزار اضافی هم تولید کن تا بتونم اطلاعات ورژنهای قبلی رو که بکاپ
    نداشتن و توشون اطلاعات وارد کردم رو بکاپ بگیرم به صورتی که به این
    نرم افزار که ورژن جدیدتر هست بتونم وارد کنم»

Reads a medpad.db file from an OLD MedPad install (any schema version the
released apks ever shipped — v6..v14; v4/v5 pre-repo builds work too when
their pending_checkins already carries the localId column) and converts
ALL of its data into the SAME versioned backup-JSON the app's own
«پشتیبان‌گیری و بازگردانی» produces and accepts:

    { "manifest": { "app": "MedPad", "formatVersion": 1,
        "schemaVersion": 14, "exportedAt": "<ISO-8601 UTC>",
        "appVersion": "medpad-legacy-backup/1.0",
        "sourceSchemaVersion": <detected>, "sourceDb": "<file name>" },
      "entities": { "<table>": [ {row}, ... ] } }

Import the result in the app: حساب کاربری → پشتیبان‌گیری و بازگردانی →
«بازگردانی از فایل پشتیبان (JSON)». The import is a MERGE (nothing is
deleted) and takes an automatic safety snapshot of the current data first.

How the old schema maps to the current one: every schema step in MedPad's
Room history is ADDITIVE (new nullable columns with safe defaults, new
tables). So the mapping is simply "read the columns that exist, default
the ones that do not" — exactly what the migration chain does on a live
device. Field-by-field contract: tools/legacy-backup/README-fa.md.

Stdlib only (sqlite3 + json) — no dependencies, Python 3.8+.

Usage:
    python3 medpad_legacy_backup.py <legacy-medpad.db> [-o OUTPUT.json]
"""

import argparse
import datetime
import json
import os
import shutil
import sqlite3
import sys
import tempfile

TOOL_VERSION = "medpad-legacy-backup/1.0"
FORMAT_VERSION = 1
CURRENT_SCHEMA_VERSION = 14
MIN_SCHEMA_VERSION = 4  # below this there is no Room migration path at all

# ── the schema map: table → {column → (kind, default_if_missing)} ─────────
#
# kinds: "text" | "int" | "real" | "bool" (SQLite INTEGER 0/1 → JSON bool)
#
# Columns ABSENT from an old file are OMITTED from the output (the format's
# "missing key" contract) — the app's reader then applies exactly the same
# default listed here (mirroring the Room migrations). The default column
# is therefore DOCUMENTATION of the app-side default, kept in lockstep
# with the Kotlin reader in backup/BackupManager.kt.
#
# Mirrors Android/app/src/main/java/com/medpad/tracker/db/Entities.kt
# (schema v14).

TABLES = {
    "doctors": {
        "id": ("text", None), "name": ("text", None), "type": ("text", "doctor"),
        "address": ("text", None), "phone": ("text", None),
        "province": ("text", None), "city": ("text", None),
        "lat": ("real", None), "lng": ("real", None),
        "radius": ("real", 150.0), "updatedAt": ("int", 0),
        "assigned": ("bool", False),
        # Customer 360 (schema v14)
        "lastName": ("text", None), "title": ("text", None),
        "specialty": ("text", None), "managerName": ("text", None),
        "phone2": ("text", None), "district": ("text", None),
        "postalCode": ("text", None), "notes": ("text", None),
        "active": ("bool", True), "pendingLocalId": ("text", None),
    },
    "customer_links": {  # schema v14
        "id": ("text", None), "clinicId": ("text", None),
        "physicianId": ("text", None), "clinicName": ("text", None),
        "physicianName": ("text", None), "note": ("text", None),
        "active": ("bool", True),
    },
    "customer_profiles": {  # schema v14
        "customerId": ("text", None), "payload": ("text", None),
        "fetchedAt": ("int", 0),
    },
    "pending_customer_ops": {  # schema v14
        "localId": ("text", None), "op": ("text", None),
        "customerId": ("text", None), "payload": ("text", None),
        "createdAt": ("int", 0), "attempts": ("int", 0),
        "lastError": ("text", None), "status": ("text", "pending"),
    },
    "pending_checkins": {
        "localId": ("text", ""),  # v4-era rows: the 4→5 migration default
        "kind": ("text", None), "customerId": ("text", None),
        "note": ("text", ""), "voicePath": ("text", None),
        "voicePaths": ("text", None),  # schema v13+
        "lat": ("real", None), "lng": ("real", None),
        "accuracy": ("real", None),  # schema v7+
        "createdAt": ("int", 0), "attempts": ("int", 0),
        "lastError": ("text", None), "status": ("text", "pending"),  # schema v9+
    },
    "pending_locations": {
        "localId": ("text", ""),  # schema v11+ (blank on older rows)
        "lat": ("real", None), "lng": ("real", None),
        "accuracy": ("real", None),  # schema v11+
        "createdAt": ("int", 0),
    },
    "planned_visits": {  # schema v8+
        "id": ("text", None), "customerId": ("text", None),
        "dayKey": ("text", None), "timeLocal": ("text", None),
        "scheduledAtMs": ("int", 0), "durationMin": ("int", 0),
        "priority": ("text", "normal"), "status": ("text", "scheduled"),
        "note": ("text", None), "visitId": ("text", None),
        "visitStartLocal": ("text", None),
        "visitOpen": ("bool", False), "overdue": ("bool", False),
        "customerName": ("text", None), "customerType": ("text", None),
        "customerAddress": ("text", None), "customerPhone": ("text", None),
        "customerAssigned": ("bool", False),
    },
    "op_events": {  # schema v12+
        "localId": ("text", None), "type": ("text", None),
        "status": ("text", "success"), "createdAt": ("int", 0),
        "errorCode": ("text", None), "message": ("text", None),
        "relatedType": ("text", None), "relatedId": ("text", None),
        "attempt": ("int", None),
    },
}

# columns that MUST exist in the file for a table to be readable at all
REQUIRED = {
    "doctors": ["id", "name", "lat", "lng"],
    "customer_links": ["id", "clinicId", "physicianId"],
    "customer_profiles": ["customerId", "payload"],
    "pending_customer_ops": ["localId", "op", "payload"],
    "pending_checkins": ["kind"],
    "pending_locations": ["lat", "lng"],
    "planned_visits": ["id", "customerId"],
    "op_events": ["localId", "type"],
}


class LegacyBackupError(Exception):
    """a typed, user-facing failure (Persian message)"""


def detect_schema_version(conn):
    """PRAGMA user_version — Room's schema version."""
    try:
        return conn.execute("PRAGMA user_version").fetchone()[0]
    except sqlite3.Error as e:
        raise LegacyBackupError("خواندن نسخهٔ ساختار ناموفق بود: %s" % e)


def table_columns(conn, table):
    """the columns a table physically has in THIS file."""
    try:
        rows = conn.execute('PRAGMA table_info("%s")' % table).fetchall()
    except sqlite3.Error:
        return []
    return [r[1] for r in rows]


def convert_value(kind, value):
    """SQLite value → JSON value (None stays None → omitted by the writer)."""
    if value is None:
        return None
    if kind == "bool":
        return bool(value)
    return value  # text / int / real pass through


def export_table(conn, table):
    """one table → list of row dicts (only columns present in the file;
    missing ones default exactly like the app's migration chain)."""
    columns = table_columns(conn, table)
    if not columns:
        return None  # the table does not exist in this old install
    spec = TABLES[table]
    missing_required = [c for c in REQUIRED[table] if c not in columns]
    if missing_required:
        raise LegacyBackupError(
            "جدول %s ستون‌های الزامی را ندارد (%s) — فایل مربوط به MedPad نیست"
            % (table, ", ".join(missing_required))
        )
    unknown = [c for c in columns if c not in spec and c != "uid"]
    if unknown:
        # honest, non-fatal: unknown columns mean a NEWER schema than mapped
        print(
            "  ⚠ جدول %s ستون‌های ناشناخته دارد (%s) — در خروجی عیناً حفظ می‌شوند"
            % (table, ", ".join(unknown))
        )
    select_cols = ", ".join('"%s"' % c for c in columns)
    rows = conn.execute('SELECT %s FROM "%s"' % (select_cols, table)).fetchall()
    out = []
    for row in rows:
        obj = {}
        for col, value in zip(columns, row):
            if col == "uid":
                continue  # row-local autoincrement — never part of the format
            value = convert_value(spec.get(col, ("text", None))[0], value)
            if value is None:
                continue  # the format contract: nulls are OMITTED, the app
                           # reader defaults missing keys (never "null" strings)
            obj[col] = value
        out.append(obj)
    return out


def build_backup(db_path):
    """Opens a PRIVATE COPY of the database (main file + any sibling -wal)
    read-write, so SQLite recovers the newest writes from the WAL exactly
    like a live open would — the user's original file is never touched."""
    tmpdir = tempfile.mkdtemp(prefix="medpad-legacy-")
    try:
        private = os.path.join(tmpdir, "legacy.db")
        shutil.copy2(db_path, private)
        wal_src, wal_used = db_path + "-wal", False
        if os.path.isfile(wal_src) and os.path.getsize(wal_src) > 0:
            shutil.copy2(wal_src, private + "-wal")
            wal_used = True
        try:
            conn = sqlite3.connect(private)
        except sqlite3.Error as e:
            raise LegacyBackupError("باز کردن فایل ناموفق بود: %s" % e)

        try:
            version = detect_schema_version(conn)
            if version < MIN_SCHEMA_VERSION:
                raise LegacyBackupError(
                    "نسخهٔ ساختار این پایگاه‌داده %d است — قدیمی‌تر از کمینهٔ پشتیبانی‌شده (%d) است"
                    % (version, MIN_SCHEMA_VERSION)
                )
            if version > CURRENT_SCHEMA_VERSION:
                raise LegacyBackupError(
                    "نسخهٔ ساختار این پایگاه‌داده %d است — جدیدتر از نسخهٔ فعلی ابزار (%d)؛ "
                    "ابتدا ابزار/برنامه را به‌روزرسانی کنید" % (version, CURRENT_SCHEMA_VERSION)
                )

            entities = {}
            total = 0
            for table in TABLES:
                rows = export_table(conn, table)
                if rows is None:
                    print("  – %s: در این نسخه وجود نداشت (نادیده)" % table)
                    entities[table] = []
                    continue
                entities[table] = rows
                total += len(rows)
                print("  ✓ %s: %d رکورد" % (table, len(rows)))

            manifest = {
                "app": "MedPad",
                "formatVersion": FORMAT_VERSION,
                "schemaVersion": CURRENT_SCHEMA_VERSION,
                "exportedAt": datetime.datetime.now(datetime.timezone.utc)
                .isoformat(timespec="seconds")
                .replace("+00:00", "Z"),
                "appVersion": TOOL_VERSION,
                "sourceSchemaVersion": version,
                "sourceDb": os.path.basename(db_path),
            }
            return {"manifest": manifest, "entities": entities}, total, version, wal_used
        finally:
            conn.close()
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def find_voice_references(entities):
    """voice paths referenced by queued check-ins — the JSON cannot carry
    the audio files themselves; warn so nothing is silently lost."""
    paths = []
    for row in entities.get("pending_checkins", []):
        vp = row.get("voicePath")
        if vp:
            paths.append(vp)
        vps = row.get("voicePaths")
        if vps:
            try:
                paths.extend([p for p in json.loads(vps) if p])
            except (ValueError, TypeError):
                pass
    return paths


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="medpad_legacy_backup",
        description="تبدیل پایگاه‌دادهٔ قدیمی MedPad به فایل پشتیبان JSON "
        "قابل بازگردانی در نسخهٔ جدید برنامه",
    )
    parser.add_argument("database", help="مسیر فایل medpad.db نسخهٔ قدیمی")
    parser.add_argument("-o", "--output", help="مسیر فایل JSON خروجی (پیش‌فرض: کنار فایل ورودی)")
    args = parser.parse_args(argv)

    db_path = os.path.abspath(args.database)
    if not os.path.isfile(db_path):
        print("خطا: فایل پیدا نشد: %s" % db_path)
        return 2

    print("MedPad legacy backup — %s" % TOOL_VERSION)
    print("فایل ورودی: %s" % db_path)
    try:
        backup, total, version, wal_used = build_backup(db_path)
    except LegacyBackupError as e:
        print("خطا: %s" % e)
        return 1

    out_path = os.path.abspath(args.output) if args.output else db_path + "-backup.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(backup, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print()
    print("✓ نسخهٔ ساختار تشخیص‌داده‌شده: %d" % version)
    if wal_used:
        print("✓ فایل wal. کنار فایل پیدا شد و آخرین تغییرات نوشته‌شده در آن هم بازیابی شد")
    print("✓ مجموع رکوردها: %d" % total)
    print("✓ فایل پشتیبان ساخته شد: %s" % out_path)
    print()
    print("برای بازگردانی: در اپ جدید → «حساب کاربری» → «پشتیبان‌گیری و بازگردانی»")
    print("→ «بازگردانی از فایل پشتیبان (JSON)» و انتخاب همین فایل.")
    print("بازگردانی ادغامی است و پیش از آن به‌طور خودکار از داده‌های فعلی نسخهٔ ایمنی گرفته می‌شود.")

    voices = find_voice_references(backup["entities"])
    if voices:
        print()
        print("⚠ یادآوری صوت‌ها: %d رکورد به فایل صوتی محلی ارجاع می‌دهد (مثال: %s)." % (len(voices), voices[0]))
        print("  فایل JSON فقط مسیرها را حفظ می‌کند؛ خود فایل‌های صوتی باید جداگانه منتقل شوند")
        print("  (راهنمای کامل در README-fa.md بخش «انتقال صداهای ضبط‌شده»).")

    return 0


if __name__ == "__main__":
    sys.exit(main())
