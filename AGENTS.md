# AGENTS.md — MedPad

> **EN TL;DR:** After every meaningful change: review diffs, run related tests (report real results), stage only task-related files, commit with a clear message, push to GitHub, and verify the commit landed on the remote. Never commit the user's uncommitted work, secrets, or junk. No `git reset --hard` / `git clean -fd` / history rewrite / force push without explicit permission. If push is impossible (auth/policy), say exactly why — never claim work is published when it is not. Full policy (Persian) below.

## قانون Commit و Push (دستور دائمی مالک ریپو — 2026-10-25)

این قاعده توسط مالک ریپو (P3D4AM) به‌عنوان دستور دائمی صادر شده و برای همهٔ وظایف آینده معتبر است:

1. **تکمیل هر وظیفهٔ معنادار = Commit + Push.** صرف پایان کدنویسی به‌معنای تکمیل وظیفه نیست.
2. قبل از Commit: تغییرات را بازبینی کن، تست‌های مرتبط را اجرا کن و نتیجهٔ واقعی را ثبت کن، و مطمئن شو فقط فایل‌های مرتبط با وظیفه تغییر کرده‌اند (از `git add .` بدون بررسی دقیق استفاده نکن).
3. **اسرار هرگز Commit نمی‌شوند:** رمزها، API Key، Token، کلید خصوصی، `.env`، Keystore اندروید و رمزهای امضا (`Android/keystore/`)، دیتابیس واقعی (`custom.db`)، و داده‌های شخصی. افزودن به `.gitignore` تاریخچه را پاک نمی‌کند — موارد منتشرشده باید جداگانه گزارش و تعویض شوند.
4. **تغییرات کاربر محفوظ است:** تغییرات Commit‌نشدهٔ کاربر هرگز حذف/بازنویسی/جذب نمی‌شوند. دستورهای مخرب (`reset --hard`، `clean -fd`، بازنویسی تاریخچه، Force Push) بدون اجازهٔ صریح ممنوع.
5. **ادغام امن:** اگر Push به‌ دلیل اختلاف با Remote ناموفق شد، وضعیت را بررسی کن و راه‌حل امن (merge با حفظ هر دو طرف) را اجرا کن؛ کورکورانه بازنویسی نکن.
6. **گزارش صادقانه:** هر گزارش پایانی شامل وضعیت، شاخه، شناسهٔ Commit، نتیجهٔ واقعی Push و تأیید Remote باشد. اگر Push ممکن نبود (مثلاً اعتبارنامه موجود نیست)، دقیقاً علت را اعلام کن.
7. فایل‌های ضروری (قفل وابستگی‌ها، منابع build آفلاین، `.next` باندل‌شده برای نصب آفلاین، `offline/`) را صرفاً به دلیل حجم حذف نکن — ابتدا نقششان را در نصب آفلاین بررسی کن.

## زمینهٔ پروژه (خلاصه)

- MedPad مجموعهٔ سرور آفلاین است: نصب‌کنندهٔ Node (`install.mjs` + `wizard.mjs`)، سرویس‌ها (`services.mjs`، `server-manager.mjs`، `tunnel.mjs`)، پنل Next.js باندل‌شده (`app/panel/.next/standalone`)، سرور realtime (`app/realtime`) و اپ اندروید (`Android/`).
- **نصب آفلاین اجباری است:** باندل Node و `node_modules` و انجین‌های Prisma بخشی از ریپو هستند و نباید حذف شوند.
- زبان کاربر: **فارسی** · توکن‌ها و رمزها همیشه از طریق متغیر محیطی یا فایل لوکال غیرردیابی‌شده تأمین شوند.
- حافظهٔ کامل پروژه: `WORKLOG-MedPad.md` (داخل ریپو) — قبل از هر تغییر خوانده شود.
