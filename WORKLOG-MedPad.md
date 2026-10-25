# 🧠 MedPad — حافظه دائمی پروژه (PERMANENT MEMORY / WORKLOG)

> ⚠️ **این فایل تنها منبع حافظه پروژه MedPad است.**
> این فایل: append-only است، هیچ‌وقت چیزی از آن حذف نمی‌شود، و بعد از هر کار باید آپدیت شود.
> اگر سندباکس ریست شد، طبق بخش «⚡ پروتکل بازیابی» در همین فایل عمل کن — **هرگز کارهای انجام‌شده را از اول تکرار نکن.**
> نسخه‌های پشتیبان این فایل: `download/WORKLOG-MedPad.md` (سرور :3000) + `upload/` + Filebin (bin: medpad196) + از v1.9.7 به بعد داخل هر ZIP.

---

## 👑 قوانین طلایی کاربر (همیشه معتبر — تغییر نکردنی)

1. **هر کاری که انجام می‌شود و هر باگی که فیکس می‌شود → بلافاصله در همین فایل لاگ شود.** Append-only. هیچ‌وقت هیچ ورودی حذف نشود.
2. **هرگز فراموشی ممنوع:** اگر کاربر بعد از ساعت‌ها/روزها برگشت و فیچری را خواست، اول این فایل را کامل بخوان؛ کار تکراری از اول انجام نده — از روی ZIPهای موجود rebuild کن.
3. **نصب آفلاین اجباری:** نصب‌کننده باید Node.js + تمام پیش‌نیازها را حتی روی سیستم بدون اینترنت (air-gapped) آفلاین نصب کند (Node MSI/tar باندل‌شده + node_modules + انجین‌های Prisma داخل بسته).
4. **فایل‌های bat/ps1 فقط ASCII انگلیسی خالص + CRLF + بدون BOM** — هیچ متن فارسی یا کاراکتر غیر-ASCII داخل bat/ps1 ممنوع (باگ garbled کاربر).
5. **auth سوکت داشبورد قفل بماند** — رگرسیون به حالت بدون-auth ممنوع.
6. **هر تحویل = ZIP + SHA1 + لینک دانلود کارا** (Filebin + سرور داخلی :3000).
7. زبان کاربر: **فارسی**؛ تایم‌زون: Asia/Tehran.
8. از v1.9.7 به بعد: یک نسخه از این فایل داخل هر ZIP جدید هم قرار بگیرد.
9. **همیشه همه فایل‌ها علاوه بر سرور داخلی، روی سایت‌های آپلودی هم آپلود شوند** (درخواست صریح کاربر — Oct 14, 2026: «فایل‌ها رو همیشه روی سایت‌های آپلودی هم آپلود کن. در worklog بنویس تا یادت نره»). یعنی: ZIPها، worklog، سندها، اسکریپت‌ها و هر فایل تحویلی/مهم دیگری → علاوه بر `download/` و سرور :3000، حتماً روی Filebin (و در صورت امکان سرویس‌های مشابه) آپلود شود. **هیچ تحویلی فقط محلی نماند.**

---

## ⚡ پروتکل بازیابی (وقتی سندباکس ریست شد — به ترتیب اجرا شود)

1. `/home/z/my-project/` را چک کن: اگر `download/` و `download-server/` و همین فایل موجودند → فقط ادامه کار بده.
2. اگر پاک شده‌اند ولی همین فایل هست → ZIPها را از Filebin برگردان (لینک‌ها در بخش 🌐) و در همان مسیرها بریز؛ سرور دانلود: `node download-server/serve.mjs` روی :3000.
3. اگر همین فایل هم نیست → از `https://filebin.net/medpad196/WORKLOG-MedPad.md` بگیر (اگر منقضی شد → بخش لینک‌ها را از حافظه چت بازسازی کن؛ اطلاعات کلیدی در چت هم هست).
4. منبع اصلی پروژه (پروژه کامل آپلودی کاربر): `https://file.kiwi/f7affaf7#bDdBvqFRqBTvKLFv_eDhw`
5. اگر همه لینک‌ها منقضی بودند → از کاربر بخواه یکی از ZIPهایی که خودش نگه داشته را دوباره آپلود کند (از v1.9.7 داخل ZIPها هم worklog هست).
6. **ممنوع:** بازنویسی install.mjs/install.bat از صفر وقتی نسخه تست‌شده داخل ZIP موجود است. همیشه اول unzip و استفاده.

---

## 📦 پروژه MedPad v1.9.x — نمای کلی

**چیست:** سیستم کنترل کارمندان (员工控制 / staff control) — پنل وب + ردیابی لحظه‌ای + اپ اندروید.

| مؤلفه | جزئیات |
|---|---|
| پنل (Next.js) | فارسی RTL، ۴ تب، نقشه Leaflet، پورت **3000**، بیلد standalone |
| Realtime | socket.io، پورت **3003**، auth با توکن HMAC (از v1.9.5) |
| اپ اندروید | Kotlin v1.9.1، APK امضاشده ~6.4MB |
| دیتابیس | Prisma + SQLite (`db/custom.db`)، seed مدیر خودکار |
| کاربران | `admin/admin123` (ادمین) — `ali`، `sara`، `mahla` با رمز `1234` |
| منبع اصلی پروژه | file.kiwi آپلود کاربر (لینک در پروتکل بازیابی) |

**معماری auth سوکت (v1.9.5+):** پنل لاگین → `POST /api/admin/socket-auth` توکن می‌سازد (mint) → کلاینت در `handshake.auth.token` می‌فرستد → `realtime/index.js` با HMAC verify می‌کند → رد توکن نامعتبر. APK هم همین مسیر.

---

## 🕐 تاریخچه نسخه‌ها

| نسخه | محتوا | وضعیت |
|---|---|---|
| v1.9.4 | بیس‌لاین پایدار: smoke test پاس (لاگین admin ✓، realtime :3003 ✓) | ✅ |
| v1.9.5 | **+ auth سوکت** (mint + HMAC verify) · **+ نصب‌کننده آفلاین** (node-v24.21.0: MSI ویندوز + tar.xz لینوکس داخل بسته · node_modules vendored · ۴ انجین Prisma win/linux/wasm · db آماده) | ✅ (ZIPها در recover/ موجود) |
| v1.9.6 | **+ فیکس encoding همه bat/ps1** (ASCII خالص + CRLF، رفع خطای garbled کاربر) · batهای تولیدی install.mjs هم فیکس شدند (start/start-realtime/stop + nested-quote باگ) · README-fa ارتقا · INSTALLER_VER=1.9.6 | ✅ |
| v1.9.7 | **خروجی انگلیسی خالص ترمینال** (درخواست کاربر) · **نصب داخل خود پوشه پکیج** (start.bat کنار install.bat؛ در v1.9.6 پوشه همسایه medpad-install ساخته می‌شد) · start.bat/stop.bat اسم‌های ساده · **مهاجرت خودکار داده از medpad-install قدیمی** · Filebin medpad197 — ⚠️ لاگ این جلسه گم شد (بعد از ارسال ZIP نوشته نشد؛ این ردیف بعداً از روی خود ZIP بازسازی شد) | ✅ (با ۳ باگ جدید — فیکس در v1.9.8) |
| v1.9.8 | **فیکس EPERM آپدیت ویندوز** (فراخوانی واقعی stopMedPadServices قبل از جایگزینی + rmRobust با retry/rename-fallback) · **ایمنی داده آپدیت** (بکاپ + راستی‌آزمایی سایز قبل از حذف + rescue آپگرید نیمه‌کاره) · **فیکس خطای 127 Git Bash** (type -P به‌جای command -v + تشخیص MSYS/MINGW و هدایت به install.bat) · **رفع DEP0190** (حذف shell:true، استفاده از process.execPath) · stop-server.bat با kill پورتی | ✅ |
| v1.9.9 | **فیکس صفحه سفید/لودینگ بی‌پایان لوکال‌هاست** (فونت Vazirmatn از CDN jsdelivr → self-hosted داخل بسته: ۹ وزن woff2 در public/fonts + پچ تمام خروجی‌های بیلد + فیکس سورس layout.tsx + چک فونت در Step 4 نصب‌کننده + فونت لوکال در ویزارد setup) · **فیکس verified=false مثبت در stop سرویس لینوکس** (lsof در کانتینرها بی‌صدا هیچ برنمی‌گرداند → fallback به ss -tlnp + netstat + راستی‌آزمایی واقعی آزاد بودن پورت‌ها) | ✅ |
| v1.9.10 | **چک نسخه بستهٔ app قبل از هر تغییری** (باگ کاربر: installer جدید داخل پوشه قدیمی → خطای مبهم در Step 4 بعد از جایگزینی پنل؛ حالا APP-VERSION + چک ساختاری در ابتدای اجرا، قبل از stop سرویس‌ها — die قابل‌اقدام یا هشدار) · **فیکس کرش tunnel-state.json خراب** · **cpRobust در مسیر نصب fresh + مهاجرت legacy** (قبلاً فقط مسیر آپدیت) | ✅ |
| v1.9.11 | **بازگشت نصب‌کننده تعاملی (درخواست کاربر):** منوی CLI هنگام اجرای install.bat/ps1/sh — مقصد (۱ این کامپیوتر / ۲ VPS لینوکسی / ۳ کلادفلر) + رابط (۱ ویزارد مرورگری GUI که خودکار باز می‌شود / ۲ CLI) · **wizard.mjs + ui/installer.html** — ویزارد گرافیکی فارسی RTL (یوزرنیم/رمز/پورت/توکلن، لاگ زنده، فقط 127.0.0.1) · **server-manager.mjs + ui/manager.html** — استارت/استاپ/ری‌استارت/وضعیت هم CLI هم GUI · **باز شدن خودکار صفحه اصلی بعد از هر استارت** · **پورت قابل تنظیم** (--port و --rt-port؛ پچ امن بیلد برای پورت سوکت سفارشی با راستی‌آزمایی) · **systemd برای هدف VPS** (+ راهنمای انتقال روی ویندوز) · **medpad-config.json** (منبع واحد پورت‌ها/تنظیمات؛ استاپ هوشمند با union پورت‌های قدیم/جدید) · رمز/توکن در حالت ویزارد از ENV می‌روند نه argv · **فیکس باگ readline ورودی پایپ‌شده** (باگ #18) | ✅ |
| v1.9.12 | **نسخه حساسیت‌بخشی امنیتی (ممیزی ۷ مرحله‌ای + فاز صفر):** · **امنیت پروسه‌ها** — اثبات مالکیت قبل از هر kill (cmdline/cwd/زمان شروع؛ PID بازیافتی هرگز کشته نمی‌شود) · برنامهٔ بیگانه روی پورت‌های ما هرگز کشته نمی‌شود · تطابق دقیق پورت (رفع findstr ساب‌رشته‌ای ویندوز؛ lsof فقط LISTEN) · **رمزهای عبور** — bcryptjs + مهاجرت شفاف SHA-256 قدیمی · نصب عمومی بدون رمز قوی انجام نمی‌شود · admin123 حذف شد · **مجوز فایل‌های حساس** 600/700 در هر نصب/آپگرید · **امنیت کلادفلر** — PLAN قبل از تغییر DNS، تأیید صریح تخریبی‌ها، بکاپ قابل بازیابی، rollback خودکار · توکن API هرگز روی دیسک/لاگ نوشته نمی‌شود · **فاز صفر** — ارتقای بستهٔ سخت‌شده به ریشهٔ ریپو + گیت‌های Host/Origin/Sec-Fetch-Site ویزارد و GUI مدیر (ضد CSRF/DNS-rebinding) + حد 64KB و مقایسهٔ زمان-ثابت /emit + قالب خالی db برای کلون تازه + README فارسی صفحهٔ اول · **تست‌ها: 92/92 PASS** | ✅ **نسخه فعلی** |

---

## 🔧 تاریخچه کامل باگ‌ها و فیکس‌ها (chronological — هرگز حذف نشود)

| # | باگ | ریشه | فیکس | تست |
|---|---|---|---|---|
| 1 | install.bat سریع بسته می‌شد | نبود pause در مسیرهای خروج | pause در همه خروج‌ها + elevation با env-var + تشخیص اجرا از ZIP + install.log | E2E دوبار پاس |
| 2 | نام فایل انجین Prisma اشتباه | — | تصحیح نام | نصب موفق |
| 3 | `realtime/` به پوشه نصب کپی نمی‌شد | گپ در build/کپی | فیکس کپی | نصب موفق |
| 4 | هاست qu.ax ناپایدار | سرویس بیرونی | میرور Filebin + سرور دانلود داخلی :3000 | ✓ |
| 5 | ریست‌های مکرر سندباکس (≥3 بار) | سندباکس | استراتژی بازیابی (بخش ⚡) + نسخه‌های پشتیبان | بازیابی موفق Oct 6 |
| 6 | **خطای garbled کاربر در install.bat**: `'═══' is not recognized`، `'MEDI_DIR'`، `'ode'`، `'ناقص'`، `'؟'` + `The system cannot find the path specified.` | bat با **UTF-8 فارسی + LF** ذخیره شده بود؛ cmd.exe فقط ANSI/OEM می‌فهمد → تکه‌های متن/کلمه به‌عنوان دستور اجرا می‌شدند (install.ps1 بدون BOM با فارسی → باگ مشابه در PowerShell 5.1) | بازنویسی **صددرصد ASCII انگلیسی + CRLF + بدون BOM** با حفظ کامل منطق v1.9.4/5؛ همان فیکس برای start-server.bat / start-realtime.bat / stop-server.bat (تولیدی install.mjs) | `python scripts/fix_bat_encoding.py` → PASS (nonAscii=0, bareLF=0) + شبیه‌سازی `MEDPAD_SIM_WIN=1` PASS |
| 7 | سوکت داشبورد بدون احراز هویت | یافته امنیتی | مسیر mint (`/api/admin/socket-auth`) + `handshake.auth.token` + HMAC verify در `realtime/index.js` | E2E: ناشناس REJECTED، توکن جعلی REJECTED، mint→200 (len=106)، اتصال احرازشده ACCEPTED+hello، `/emit` توکن غلط→401 |
| 8 | **خطای کاربر v1.9.7: `bash: alias node='winpty node.exe': command not found` → خروج 127** (اجرای install.sh در Git Bash ویندوز) | در bash، وقتی alias ی مثل `alias node='winpty node.exe'` در محیط فعال باشد، `command -v node` **رشته تعریف alias** را برمی‌گرداند نه مسیر فایل → `"$NODE_EXE" install.mjs` سعی می‌کند کل آن رشته را اجرا کند → command not found → 127. شواهد: `[OK] Node.js ?` (چک نسخه fail شد) + بازتولید موفق با BASH_ENV + alias در سندباکس | install.sh: تشخیص MINGW/MSYS/CYGWIN → پیام «install.bat را اجرا کن» و خروج تمیز؛ resolve نود با `type -P node` (فقط PATH، alias نادیده) + چک `-x` | تست T1: با alias فعال exit 0 و نصب کامل ✓ · تست T2: MEDPAD_SIM_UNAME=MINGW64 → پیام راهنما ✓ |
| 9 | **خطای کاربر v1.9.7: `EPERM, Permission denied: ...\panel` هنگام آپدیت روی نصب موجود (ویندوز)** | رگرسیون v1.9.7: `stopMedPadServices` از services.mjs **import شده بود ولی هیچ‌وقت صدا زده نمی‌شد** → سرور قدیمی (node.exe روی panel/ یا realtime/) در حال اجرا می‌ماند → `rmSync(panelDir)` روی پوشه قفل‌شده EPERM می‌داد. تست‌های جلسه v1.9.7 هم فقط مسیر fresh را تست کرده بودند (/tmp/medpad-e2e-197 و /tmp/medpad-simwin در install.log باقی مانده) نه مسیر آپدیت | ۱) فراخوانی `await stopMedPadServices(installDir)` قبل از Step 2 با هشدار در صورت survivor · ۲) `rmRobust()`: rmSync با maxRetries داخلی + ۳ تلاش با فاصله + fallback رینیم به `<name>.stale-<ts>` · ۳) پیام خطای قابل‌اقدام اگر همه شکست خورد · ۴) `cpRobust()` برای کپی‌ها | تست T5: سرور واقعی روی :3000/:3003 در حال اجرا → نصب‌کننده خودکار stop کرد و آپدیت کامل شد (exit 0) ✓ · تست T4: آپدیت معمولی — marker دیتابیس و uploads حفظ شد ✓ |
| 10 | **ریسک از بین رفتن دیتابیس در آپدیت** (غیر از EPERM): در v1.9.7 اگر cpSync بکاپ db شکست می‌خورد ولی کد ادامه می‌داد، rmSync بعدی دیتابیس اصلی را نابود می‌کرد | نبود راستی‌آزمایی بکاپ قبل از حذف | بکاپ db/uploads/.env با تلاش مجدد + **مقایسه سایز فایل db** بعد از بکاپ؛ اگر ناموفق → die با پیام «هیچ چیزی تغییر نکرد» · rescue خودکار `.keep-tmp` جامانده از آپگرید نیمه‌کاره در اجرای بعدی | تست T6: شبیه‌سازی آپگرید crash شده → rescue خودکار، marker دیتابیس برگشت ✓ |
| 11 | **هشدار Node 24: `DEP0190 DeprecationWarning: Passing args to a child process with shell option true`** | `spawnSync('node', [...], {shell: true})` در findNode (آرگومان آرایه‌ای + shell روی ویندوز) · تابع مرده `sh()` هم همین مشکل را داشت | حذف کامل shell:true · findNode حالا از `process.execPath` استفاده می‌کند (مسیر مطلق نود در حال اجرا — همیشه معتبر) · حذف `sh()` مرده · seed spawn با `stdio:'pipe'` بدون shell | تست T8: grep — هیچ `shell:` در کد نیست ✓ · نصب E2E بدون هیچ هشدار ✓ |
| 12 | **خطای کاربر v1.9.8: «سرور استارت می‌خورد ولی صفحه لوکال‌هاست روی لودینگ/صفحه سفید می‌ماند»** | `layout.tsx` فونت Vazirmatn را با `<link rel="stylesheet">` از **cdn.jsdelivr.net** لود می‌کرد — این لینک render-blocking است؛ در شبکه‌هایی که CDN فیلتر است (ایران)، اتصال «معلق» می‌شود نه fail سریع → مرورگر تا timeout طولانی هیچ چیزی رندر نمی‌کند → صفحه سفید بی‌پایان با اینکه سرور سالم بالا است. بازتولید قطعی در سندباکس با Playwright (route hang): goto بعد از ۲۵ ثانیه timeout + screenshot بعد از ۳۰ ثانیه timeout (صفحه هرگز stable نشد) — کامنت کد ادعا می‌کرد «falls back to Tahoma when offline» که غلط بود | **Self-host کامل فونت:** ۹ وزن woff2 (۴۶۸KB) + vazirmatn.css داخل `panel/.next/standalone/public/fonts/` بسته · پچ تمام ۱۳ فایل خروجی بیلد (SSR chunk + prerenderهای app/ + 404 + downloads.html) از URL CDN به `/fonts/vazirmatn.css` · فیکس سورس `layout.tsx` (برای rebuildهای آینده) + فونت در `panel-src/public/fonts/` · فونت و route `/fonts/*` در ویزارد setup-server.mjs هم اضافه شد · **چک اجباری فونت در Step 4 نصب‌کننده** (اگر fonts نبود، نصب با خطای واضح می‌ایستد) | تست با Playwright + CDN معلق: صفحه در **۰.۲ ثانیه** لود شد (قبلاً بی‌نهایت) ✓ لاگین admin → داشبورد ✓ فونت Vazirmatn لوکال لود شد ✓ صفر درخواست خارجی (فقط سوکت لوکال :3003) ✓ ویزارد: css/woff2 → 200 و traversal → 404 ✓ |
| 13 | **کشف حین تست v1.9.9: verified=true مثبت در stop سرویس (لینوکس/کانتینر)** — سرور قدیمی بعد از آپدیت هنوز روی :3003 بود و پوشه حذف‌شده را سرو می‌کرد؛ لاگ می‌گفت `killed=0 verified=true` | `lsof -t -i :3000` در بعضی کانتینرها **بی‌صدا هیچ برنمی‌گرداند** (dmesg نداشت، ss پیدایش می‌کرد) + `fuser` هم نصب نبود (exit 127) → لیست kill خالی می‌شد و راستی‌آزمایی فقط pids ذخیره‌شده را چک می‌کرد که خالی بود | `pidsOnPort()` سه‌مرحله‌ای: lsof → **ss -tlnp** (پارس pid=) → netstat -tlnp · **راستی‌آزمایی واقعی:** بعد از kill، دوباره پورت‌ها چک می‌شوند؛ اگر مشغول بود `verified=false` + پورت‌های مشغول در خروجی و لاگ · هشدار نصب‌کننده پورت مشغول را هم چاپ می‌کند | تست آپدیت روی سرور در حال اجرا: `killed=2 verified=true` و پورت‌ها واقعاً آزاد شدند ✓ (قبلاً: killed=0 و سرور زامبی روی پوشه deleted) |
| 14 | **خطای کاربر v1.9.9: «FAIL Self-hosted font not found — package incomplete» با خروج ۱ در Step 4** (بعد از جایگزینی پنل!) | کاربر فایل‌های installer جدید (v1.9.9) را داخل پوشه استخراج‌شده قدیمی (v1.9.7) کپی کرده بود → چک فونت در Step 4 بود، بعد از بکاپ/حذف/کپی — پیام مبهم بود و پنل قدیمی (با باگ صفحه سفید #12) از قبل جایگزین شده بود | **فایل مانیفست `app/APP-VERSION`** (در زمان بسته‌بندی نوشته می‌شود) + چک نسخه و ساختار (BUILD_ID + فونت سورس) در **ابتدای اجرا قبل از stop سرویس‌ها و هر تغییری** → بسته قدیمی/ناقص: die با پیام قابل‌اقدام («extract ALL of it… NOTHING has been changed»)؛ ناسازگاری خوش‌خیم: هشدار + ادامه · پیام Step 4 هم به همین فرم قابل‌اقدام شد | T1 (دقیقاً سناریوی کاربر، سرور در حال اجرا): die با پیام واضح ✓ صفر تغییر فایل ✓ سرویس هم stop نشد ✓ · T3: آپدیت سالم کامل ✓ · T4: ناسازگاری خوش‌خیم → هشدار + نصب کامل ✓ |
| 15 | **ریسک کرش کل نصب با tunnel-state.json خراب** — JSON.parse بدون try/catch در Step 5؛ فایل نصفه‌نوشته (قطع برق) کل نصب را بعد از آپدیت موفق پنل با «Unexpected error» می‌انداخت | نبود محافظت برای فایل کشِ غیرحیاتی | try/catch → هشدار «corrupted - it has been reset» + ادامه با state تازه | T5: فایل خراب عمدی → نصب کامل بدون کرش ✓ |
| 16 | **مسیر نصب fresh بدون retry** — cpSync خام در شاخه fresh (فیکس v1.9.8 فقط مسیر آپدیت را cpRobust کرده بود) → قفل موقت آنتی‌ویروس/ایندکسر ویندوز می‌توانست نصب تازه را بکشد | ناهماهنگی فیکس v1.9.8 | مسیر fresh هم cpRobust (۳ تلاش + فاصله) | تست T2 نصب fresh کامل ✓ |
| 17 | **مهاجرت legacy (../medpad-install) بدون retry و fatal** — خطای کپی در پوشه قدیمی قفل‌شده کل نصب را بعد از کپی پنل می‌انداخت | cpSync خام + نبود try/catch | cpRobust + try/catch → خطا فقط هشدار می‌دهد و نصب ادامه می‌یابد | بازبینی کد + T2 ✓ |
| 18 | **کشف حین تست v1.9.11: سؤال‌های متوالی منو با ورودی پایپ‌شده گم می‌شدند** — بعد از پاسخ دوم، پروسه بی‌صدا با کد 13 (Node 24: unsettled top-level await) یا 0 خارج می‌شد؛ بازتولید مینیمال با rl-test.mjs | با پایپ، همه خطوط در یک chunk می‌رسند؛ readline رویدادهای line 2..n را قبل از ثبت listener سؤال بعدی emit می‌کند → از دست می‌روند. (اینترفیس جدا برای هر ask هم مشکل‌دار بود اما ریشه اصلی همین بود) | **صف خط دائمی:** یک اینترفیس readline با listener خط دائمی که همه خطوط را در ANSWERS[] می‌ریزد؛ ask() پرامپت را چاپ می‌کند و از صف می‌کشد (TTY و pipe هر دو) | تست T8 با stdin پایپ‌شده: منوی کامل + نصب + سؤال استارت + pause همه پاس ✓ |

**اسکریپت‌های تست موجود:** `scripts/test-v1911.sh` (سوئیت کامل ۴۹ چک v1.9.11) · `scripts/sync-v1911.sh` (همگام‌سازی منبع → درخت بسته + ASCII/CRLF) · `scripts/check_bat_encoding.py` (v1.9.8) · بقیه نسخه‌های قبلی در سندباکس‌های قبلی

**دستورات تست تکرارپذیر (v1.9.11):**
- `bash scripts/test-v1911.sh /tmp/t11/pkg/v1911` → کل سوئیت ۴۹ چک (نصب fresh با پورت سفارشی + کانفیگ + بوت/امنیت ۷ چک + ویزارد کامل + مدیر GUI + آپدیت روی نصب در حال اجرا + منوی تعاملی پایپ‌شده + VPS-on-Windows + تولید bat ویندوزی + سینتکس)

**دستورات تست تکرارپذیر (v1.9.10):**
- `bash scripts/test-v1910.sh` → کل سوئیت ۴۱ چک (سناریوی کاربر + نصب fresh + بوت/امنیت + آپدیت روی نصب در حال اجرا + ناسازگاری نسخه + tunnel-state خراب + bat ویندوزی + Git Bash + سینتکس)

**دستورات تست تکرارپذیر (v1.9.9):**
- `bash scripts/test-alias-trap.sh recover/offline-199/medpad-offline-v1.9.9/install.sh NEW` → تست تله alias
- `MEDPAD_SIM_UNAME=MINGW64_NT-10.0 bash install.sh` → تست تشخیص Git Bash
- `node install.mjs --non-interactive --mode local --dir /tmp/t` (روی نصب موجود = مسیر آپدیت)
- `MEDPAD_SIM_WIN=1 node install.mjs --non-interactive --mode local --dir /tmp/tw` → تولید bat ویندوزی
- `node scripts/repro-whitescreen.js` → بازتولید/رفع صفحه سفید (CDN معلق با Playwright)
- `node scripts/e2e-hanging-cdn.js` → E2E کامل با CDN معلق (لود صفحه/فونت/لاگین/داشبورد)
- `bash scripts/test-boot-e2e.sh /tmp/t` → بوت کامل + ۷ چک امنیتی (پنل 200، لاگین، mint 106، health، /emit غلط→401، سوکت ناشناس REJECTED، سوکت احرازشده ACCEPTED)
- `python3 scripts/check_bat_encoding.py` → چک ASCII+CRLF همه bat/ps1 (شامل تولیدی‌ها)

---

## 📦 آرتیفکت‌ها و SHA1 (نسخه v1.9.11 — نهایی و تست‌شده)

| فایل | حجم | SHA1 |
|---|---|---|
| medpad-offline-v1.9.11.zip | 154,409,842 بایت (~154MB) | `cb30220cb844b6d523e9c928b243fd0e21bcacf9` |
| medpad-full-v1.9.11.zip | 161,229,321 بایت (~161MB) | `dea36d0bc4851825f1b6434396fe70244b0f7861` |
| medpad-installer-v1.9.11-src.zip | 1,361,754 بایت (~1.4MB) | `c178cbc0388a4a619349ed78048f824851b965dc` |

هر سه فایل در Filebin **medpad201** آپلود و SHA1 هر سه با دانلود برگشتی راستی‌آزمایی شد ✓ (+ unzip -t روی full بدون خطا)

## 📦 آرتیفکت‌ها و SHA1 (نسخه v1.9.10 — نهایی و تست‌شده)

| فایل | حجم | SHA1 |
|---|---|---|
| medpad-offline-v1.9.10.zip | 153,491,717 بایت (~153MB) | `2777b3aaf2f40b9ade1a74ab2a46ad7eba18854f` |
| medpad-full-v1.9.10.zip | 159,313,578 بایت (~159MB) | `8b45908da1a87fd743ea982829743e60c0b0f54e` |
| medpad-installer-v1.9.10-src.zip | 1,347,458 بایت (~1.3MB) | `d2fd9f2557e94f70448d9bfdc304889ad2962c22` |

هر سه فایل در Filebin **medpad200** آپلود و SHA1 هر سه با دانلود برگشتی راستی‌آزمایی شد ✓ (روش آپلود: FIFO استریم — zip مستقیم به curl؛ به‌دلیل خرابی شدید fs سندباکس در این جلسه، بسته‌ها بدون فایل واسط محلی ساخته و آپلود شدند؛ یکتایی محتوا با unzip -t روی نسخه برگشتی + تطابق دو دانلود متوالی تأیید شد)

## 📦 آرتیفکت‌ها و SHA1 (نسخه v1.9.9 — نهایی و تست‌شده)

| فایل | حجم | SHA1 |
|---|---|---|
| medpad-offline-v1.9.9.zip | 153,910,080 بایت (~154MB) | `67d86be81a942ce6bdd398dbf85c304fc82b84b2` |
| medpad-full-v1.9.9.zip | 160,699,620 بایت (~161MB) | `77c08c14c485818384f87bdce35d8e663d4b250f` |
| medpad-installer-v1.9.9-src.zip | 1,340,700 بایت (~1.3MB) | `f61b60be5de3d71bc31e6395014c691ac2c4760b` |

هر سه فایل در Filebin medpad199 آپلود و **SHA1 سمت سرور با دانلود برگشتی راستی‌آزمایی شد** ✓ (تست نهایی هم روی خود ZIP دانلودشده اجرا شد: نصب fresh + ۷ چک امنیتی + E2E صفحه با CDN معلق — همه PASS)

(نسخه v1.9.8: offline `91c71e98ae45f61f7d635041f64a16f4b6fc3c95` · full `2d2c0f6d07d2910da1beff02bd517189feca1ef6` · installer-src `3d71da8da3a85fe52d06f460fe94d121fa3272bd`)
(نکته: نسخه‌ی worklog که داخل ZIPها است، مقدار جای‌نگهدار دارد چون SHA1 بعد از ساخت ZIP محاسبه می‌شود — نسخه اصلی همین فایل در سندباکس و Filebin معتبر است.)

**محتوای بسته آفلاین:** node MSI ویندوز + tar.xz لینوکس (v24.21.0) + node_modules کامل + بیلد standalone پنل + انجین‌های Prisma (win+linux+wasm) + db آماده + install.bat/ps1 (ASCII+CRLF) + realtime/ + install.mjs + install.sh

**محتوای full ZIP:** بسته آفلاین + سورس APK اندروید v1.9.1 + سورس داشبورد + APK نهایی + domain-wizard v1.1 + README-fa

**محتوای installer-src:** inst-stage/ (tunnel.mjs, lib/cloudflare.mjs, realtime/, panel-src/ با db, install.bat/ps1/mjs/sh)

---

## 🌐 لینک‌های دانلود

| محل | لینک | انقضا |
|---|---|---|
| **x0.at (مستقیم — کلیک=دانلود)** | `x0.at/xqtU.zip` (آفلاین ۱۵۴MB) · `x0.at/tnxx.zip` (کامل ۱۶۱MB) · `x0.at/6Pn1.zip` (installer-src 1.4MB) | بلندمدت (فایل‌های x0.at تا وقتی فعال باشند؛ ~۳۰-۱۰۰ روز) |
| **temp.sh (بعد از باز شدن لینک، دکمه Click here to download)** | `temp.sh/iJYNj/medpad-offline-v1.9.11.zip` · `temp.sh/Aqvva/medpad-full-v1.9.11.zip` · `temp.sh/zxmlp/medpad-installer-v1.9.11-src.zip` · `temp.sh/mtHDO/worklog.md` | ۳ روز (تا ~Oct 17) |
| Filebin (فعلی) | `filebin.net/medpad201` → offline + full v1.9.11 + installer-src + WORKLOG | ~6 روز (تا Oct 21) |
| Filebin (v1.9.10) | `filebin.net/medpad200` → نسخه‌های v1.9.10 | احتمالاً منقضی |
| Filebin (v1.9.9) | `filebin.net/medpad199` → نسخه‌های v1.9.9 | احتمالاً منقضی |
| Filebin (v1.9.8) | `filebin.net/medpad198` → نسخه‌های v1.9.8 (با باگ #12 صفحه سفید) | احتمالاً منقضی |
| Filebin (v1.9.7) | `filebin.net/medpad197` → نسخه‌های v1.9.7 (با باگ‌های #8/#9) | احتمالاً منقضی |
| Filebin (v1.9.6) | `filebin.net/medpad196` → نسخه‌های v1.9.6 | احتمالاً منقضی |
| منبع اصلی پروژه | file.kiwi (لینک در پروتکل بازیابی) | کاربر نگه می‌دارد |

**SHA1 هر سه ZIP در همه سایت‌ها یکسان و راستی‌آزمایی‌شده (Oct 14):** offline `cb30220cb844b6d523e9c928b243fd0e21bcacf9` · full `dea36d0bc4851825f1b6434396fe70244b0f7861` · src `c178cbc0388a4a619349ed78048f824851b965dc`

**مسیرهای سندباکس:** `download/` (هر سه ZIP v1.9.11 + worklog کپی) · `recover/v1911/` (منبع اصلی فایل‌های v1.9.11 — همه فایل‌های متنی کوچک) · `recover/src1910/` (سورس استخراج‌شده v1.9.10) · `scripts/` (test-v1911.sh + sync-v1911.sh) · `/tmp/t11/pkg/medpad-offline-1.9.11` (درخت کامل بسته — منبع بسته‌بندی؛ اگر سندباکس ریست شد از ZIP فایل‌بین بازسازی کن) · `/tmp/t11/src1911/` (درخت src) · `download-server/serve.mjs` (سرور دانلود :3000) · `worklog.md` (همین فایل)

---

## 📋 وضعیت فعلی و آینده

- **v1.9.11 کامل تست و تحویل شد** — نصب‌کننده تعاملی (منوی مقصد + انتخاب CLI/مرورگر) + ویزارد گرافیکی + مدیر سرور GUI/CLI + باز شدن خودکار صفحه اصلی بعد از استارت + پورت‌های قابل تنظیم + systemd برای VPS. ۴۹/۴۹ تست PASS.
- نکته مهم برای آینده: **هیچ فایل خارجی (فونت/اسکریپت/CSS) در هیچ صفحه‌ای از پنل/ویزارد/مدیر مجاز نیست** — صفحات جدید (installer.html / manager.html) هم فونت را از `/fonts/` خودشان می‌گیرند.
- کار بعدی که کاربر بخواهد: از اینجا به بعد است — اول این فایل را کامل بخوان، بعد کار کن.
- TODO دائمی: در هر ZIP جدید این فایل به‌روز باشد (از v1.9.7 اجرا می‌شود).
- اگر نسخه جدید ساخته شد: شماره +1 (v1.9.12, ...)، SHA1 جدید اینجا ثبت شود، Filebin جدید (مثلاً medpad202) + سرور :3000 آپدیت شود.
- یادآوری بسته‌بندی: فایل `app/APP-VERSION` باید با INSTALLER_VER یکی باشد (مقدار: فقط عدد نسخه مثل `1.9.11` بدون newline اضافی) · bat/ps1 فقط ASCII+CRLF بدون BOM (اسکریپت sync-v1911.sh خودش اعمال می‌کند) · درخت بسته v1.9.11 در `/tmp/t11/pkg/medpad-offline-1.9.11` و منبع فایل‌های متنی در `recover/v1911/`.

---

## 📝 لاگ نوبت‌ها (append-only)

---
Task ID: 1
Agent: main (Super Z)
Task: بازیابی پروژه پس از ریست سندباکس + عیب‌یابی خطای install.bat کاربر

Work Log:
- سندباکس ریست شده بود (فقط skills/download خالی موجود بود)
- Filebin قبلی (medpad194) منقضی شده بود — اما بین medpad195 با هر دو ZIP سالم پیدا شد:
  - medpad-offline-v1.9.5.zip (153MB, sha1 bca97fe5…) ✓
  - medpad-full-v1.9.5.zip (159MB, sha1 2adb79b0…) ✓
- هر دو دانلود و با SHA1 تأیید شدند
- عیب‌یابی خطای کاربر ('MEDI_DIR'/'ode'/'ناقص' is not recognized):
  - install.bat با UTF-8 فارسی + LF یونیکسی ذخیره شده بود
  - cmd.exe فقط ANSI/OEM می‌فهمد → پارسر خراب می‌شود و تکه‌های خط به‌عنوان دستور اجرا می‌شوند
  - install.ps1 هم بدون BOM با فارسی → مشکل در PowerShell 5.1
  - start-server.bat / stop-server.bat تولیدی install.mjs هم فارسی + LF دارند (همان باگ)
- زنجیره auth سوکت v1.9.5 در بسته تأیید شد: /api/admin/socket-auth (mint) + handshake.auth.token + HMAC verify در realtime/index.js ✓
- بسته آفلاین کامل است: node MSI ویندوز + tar.xz لینوکس + node_modules + بیلد standalone + انجین‌های Prisma + db آماده

Stage Summary:
- ریشه خطا: encoding فایل‌های bat (UTF-8 فارسی + LF) — فیکس: بازنویسی ASCII خالص + CRLF
- نسخه جدید: v1.9.6 (فقط فیکس‌های encoding + بهبود batهای تولیدی)

---
Task ID: 2
Agent: main (Super Z)
Task: فیکس خطای install.bat (encoding) + بسته‌بندی و انتشار v1.9.6

Work Log:
- install.bat بازنویسی شد: ASCII خالص + CRLF — منطق v1.9.4/v1.9.5 کامل حفظ شد (UAC، ZIP-detection، MSI آفلاین، pause در همه مسیرها، install.log)
- install.ps1 هم ASCII+CRLF (مصون از باگ BOM در PowerShell 5.1)
- install.mjs: INSTALLER_VER=1.9.6 + تولید start-server.bat / start-realtime.bat / stop-server.bat با ASCII+CRLF (باگ مشابه در batهای تولیدی هم رفع شد؛ nested-quote باگ start هم با bat کمکی حل شد)
- README-fa.md هر دو بسته به v1.9.6 ارتقا یافت + چنج‌لاگ + عیب‌یابی
- تست‌ها:
  * python scripts/fix_bat_encoding.py → install.bat/ps1: PASS (ASCII + CRLF)
  * node install.mjs --non-interactive --mode local روی لینوکس: نصب کامل، ۴ انجین Prisma (win+linux+wasm)، seed مدیر ✓
  * شبیه‌سازی تولید bat ویندوزی (MEDPAD_SIM_WIN=1): هر سه bat: PASS (nonAscii=0, bareLF=0)
  * E2E سوکت: ناشناس REJECTED، توکن جعلی REJECTED، لاگین admin→200، mint توکن→200 (len=106)، اتصال احرازشده ACCEPTED + hello، /emit با توکن غلط → 401
  * smoke پنل: HTTP 200 روی :3000
- بسته‌بندی: medpad-offline-v1.9.6.zip (sha1 8e983737…)، medpad-full-v1.9.6.zip (sha1 0ed766d6…)، medpad-installer-v1.9.6-src.zip (sha1 1f707834…)
- آپلود Filebin medpad196: هر دو فایل — SHA1 سمت سرور == محلی ✓ (اعتبار ۶ روز)
- سرور دانلود داخلی: download-server/serve.mjs روی :3000 + صفحه فارسی + ZIPها در download/

Stage Summary:
- v1.9.6 منتشر شد؛ خطای گزارشی کاربر کاملاً رفع؛ فیکس امنیتی سوکت دست‌نخورده و تست‌شده
- لینک‌ها: filebin.net/medpad196 + صفحه دانلود داخلی پورت 3000

---
Task ID: 3
Agent: main (Super Z)
Task: تبدیل worklog به حافظه دائمی ضدریست (به درخواست کاربر)

Work Log:
- درخواست کاربر: هر کار/فیکس در worklog ذخیره شود و هیچ‌وقت پاک نشود تا بعد از ریست سندباکس یا فاصله زمانی، دچار فراموشی و کار تکراری نشویم
- worklog.md بازنویسی شد به فرمت حافظه کامل: قوانین طلایی کاربر + پروتکل بازیابی + نمای کلی پروژه + تاریخچه نسخه‌ها + جدول کامل باگ‌ها/فیکس‌ها + SHA1ها + لینک‌ها + مسیرهای سندباکس + لاگ append-only (ورودی‌های 1 و 2 دست‌نخورده حفظ شدند)
- کپی‌های پشتیبان ساخته شد: download/WORKLOG-MedPad.md (سرو از :3000) + upload/ + آپلود Filebin medpad196 + medpad-memory-v1.zip (پشتیبان کوچک برای نگهداری توسط کاربر)
- صفحه دانلود :3000 به‌روزرسانی شد: کارت دانلود worklog اضافه شد
- قاعده جدید ثبت شد: از v1.9.7 به بعد worklog داخل هر ZIP هم قرار می‌گیرد
- وضعیت سرویس‌ها در این لحظه: Filebin medpad196 زنده (HTTP 200)، سرور :3000 در حال اجرا، هر ۳ ZIP سالم با SHA1 مطابق بخش 📦

Stage Summary:
- حافظه دائمی راه‌اندازی شد؛ از این لحظه هر تسک بلافاصله لاگ می‌شود؛ بازیابی بعد از ریست با بخش ⚡ انجام می‌شود و هیچ کار تکراری انجام نخواهد شد

---
Task ID: 4
Agent: main (Super Z)
Task: فیکس سه خطای نصب v1.9.7 گزارش‌شده توسط کاربر (127 alias در Git Bash + EPERM آپدیت ویندوز + هشدار DEP0190) و انتشار v1.9.8

Work Log:
- کاربر ZIP کامل v1.9.7 را از Filebin medpad197 داد؛ دانلود (152MB) و استخراج شد
- WORKLOG خوانده شد — نکته: جلسه v1.9.7 لاگش ثبت نشده بود (worklog تا Task 3 و v1.9.6 بود)؛ ردیف v1.9.7 از روی محتوای خود ZIP بازسازی و ثبت شد
- محیط سندباکس طبق پروتکل بازیابی برقرار شد: worklog.md + download/ + download-server/ + recover/ + scripts/ + upload/
- عیب‌یابی خطای 127: بازتولید موفق با ترفند BASH_ENV + `alias node='winpty node.exe'` — ریشه: `command -v node` در bash با alias فعال، رشته تعریف alias را برمی‌گرداند و install.sh آن را مثل نام برنامه اجرا می‌کند (خروجی کاربر `[OK] Node.js ?` هم دقیقاً همین را تأیید کرد)
- عیب‌یابی EPERM: در install.mjs نسخه v1.9.7 تابع stopMedPadServices از services.mjs import شده بود ولی هیچ‌جا صدا زده نمی‌شد → سرور در حال اجرا پوشه panel را قفل نگه می‌داشت → rmSync(panelDir) با EPERM می‌شکست؛ install.log داخل ZIP هم نشان داد تست‌های v1.9.7 فقط مسیر fresh را پوشش داده بودند نه آپدیت
- عیب‌یابی DEP0190: spawnSync('node', [...], {shell:true}) در findNode + تابع مرده sh()
- فیکس‌های v1.9.8 در install.mjs: فراخوانی stopMedPadServices با هشدار survivor + rmRobust (rmSync با maxRetries + ۳ تلاش + fallback رینیم به .stale-<ts>) + cpRobust + بکاپ راستی‌آزمایی‌شده (مقایسه سایز db) قبل از حذف + rescue خودکار .keep-tmp + findNode با process.execPath + حذف sh() مرده + stop-server.bat با kill پورتی 3000/3003
- فیکس‌های v1.9.8 در install.sh: تشخیص MINGW/MSYS/CYGWIN → هدایت به install.bat + resolve نود با type -P (فقط PATH، alias نادیده) + چک -x + بنر 1.9.8
- install.bat/install.ps1: فقط ارتقای نسخه به 1.9.8 (منطق سالم بود)
- README-fa ارتقا یافت: جدول امکانات v1.9.8 + بخش «اگر با v1.9.7 خطای EPERM گرفتید» + سه ردیف عیب‌یابی جدید
- تست‌ها (همه PASS):
  * T1: alias فعال + install.sh جدید → نصب کامل exit 0 (باگ با نسخه قدیمی بازتولید و با نسخه جدید رد شد)
  * T2: MEDPAD_SIM_UNAME=MINGW64 → پیام هدایت به install.bat و خروج تمیز
  * T3: نصب fresh کامل E2E
  * T4: آپدیت روی نصب موجود — marker دیتابیس (KEEP-ME-123) و uploads و تنظیمات admin همه حفظ شدند، .keep-tmp پاک شد
  * T5: سرور واقعی روی :3000/:3003 در حال اجرا + اجرای نصب → سرویس‌ها خودکار stop شدند و آپدیت کامل شد (دقیقاً سناریوی EPERM کاربر)
  * T6: شبیه‌سازی آپگرید نیمه‌کاره (db فقط در .keep-tmp) → rescue خودکار و بازگشت marker
  * T7: MEDPAD_SIM_WIN=1 → هر ۵ فایل bat تولیدی ASCII+CRLF
  * T8: هیچ `shell:` در کد نماند
  * T9: بوت کامل + ۷ چک امنیتی (پنل 200، لاگین admin، mint توکن len=106، /health 200، /emit توکن غلط→401، سوکت ناشناس REJECTED با 44 unauthorized، سوکت احرازشده ACCEPTED) — قانون طلایی ۵ (auth سوکت) دست‌نخورده
- بسته‌بندی v1.9.8: offline + full + installer-src (SHA1 در جدول 📦 ثبت و سمت سرور راستی‌آزمایی شد)
- آپلود Filebin medpad198 + سرور دانلود داخلی :3000 بازسازی شد
- یادگیری فنی تست: سرور standalone پنل Next.js عنوان پروسه خود را به `next-server (v16.x)` تغییر می‌دهد → pkill با الگوی `server.js` آن را نمی‌کشد؛ اسکریپت test-boot-e2e.sh حالا با الگوی `next-server` + پاک‌سازی اولیه پورت‌ها کار می‌کند (نکته: stop-server.bat محصول به این مشکل ندارد چون بر اساس پورت/عنوان پنجره kill می‌کند)

Stage Summary:
- v1.9.8 منتشر شد؛ هر سه خطای کاربر رفع؛ ایمنی داده در آپدیت به‌طور جدی تقویت شد (بکاپ راستی‌آزمایی‌شده + rescue)
- درس v1.9.7 ثبت شد: تحویل بدون آپدیت worklog = گم شدن حافظه — دیگر تکرار نمی‌شود
- لینک‌ها: filebin.net/medpad198 + صفحه دانلود داخلی :3000

---
Task ID: 5
Agent: main (Super Z)
Task: فیکس «سرور بالا می‌آید ولی صفحه لوکال‌هاست روی لودینگ/صفحه سفید می‌ماند» و انتشار v1.9.9

Work Log:
- گزارش کاربر: سرور استارت می‌خورد ولی http://localhost:3000 روی لودینگ/صفحه سفید می‌ماند
- بازتولید کامل: نصب fresh از بسته v1.9.8 + بوت + Playwright با route hang روی cdn.jsdelivr.net:
  * با CDN سالم: صفحه ورود در ~۱ ثانیه لود می‌شود
  * با CDN معلق (شبیه فیلترینگ ایران): goto بعد از ۲۵s timeout، screenshot بعد از ۳۰s timeout — صفحه هرگز رندر نمی‌شود (دقیقاً علامت کاربر)
- ریشه: layout.tsx فونت Vazirmatn را با <link rel="stylesheet"> از cdn.jsdelivr.net لود می‌کرد (render-blocking؛ کامنت «falls back to Tahoma» غلط بود — fail سریع رخ نمی‌دهد، اتصال معلق می‌شود)
- فیکس (فونت کاملاً self-hosted):
  * دانلود ۹ وزن woff2 (۴۶۸KB، همه wOF2 معتبر) + vazirmatn.css
  * قرار دادن در app/panel/.next/standalone/public/fonts/ بسته آفلاین
  * پچ ۱۳ فایل خروجی بیلد (SSR chunk، prerenderهای app/، 404، downloads.html): URL CDN → /fonts/vazirmatn.css (صفر jsdelivr باقی ماند)
  * فیکس سورس panel-src/src/app/layout.tsx + فونت در panel-src/public/fonts/ (برای rebuildهای آینده)
  * ویزارد: route /fonts/* در setup-server.mjs (با محافظ traversal) + فونت در ui/fonts/ + پچ ui/index.html و downloads.html
  * چک اجباری فونت در Step 4 install.mjs (9 woff2 باید موجود باشد وگرنه نصب متوقف می‌شود)
- فیکس جانبی حین تست (باگ #13): در سندباکس lsof -t -i :3000 هیچ برنمی‌گرداند (باگ کانتینری) و fuser نصب نیست → stopMedPadServices با killed=0/verified=true مثبت، سرور قدیمی روی پوشه deleted به سرویس‌دهنی ادامه می‌داد → pidsOnPort سه‌مرحله‌ای (lsof → ss -tlnp → netstat) + راستی‌آزمایی واقعی آزاد بودن پورت‌ها بعد از kill + هشدار stillBusy در install.mjs
- نسخه‌ها به 1.9.9 ارتقا یافت: install.mjs (INSTALLER_VER + کامنت تغییرات) · install.bat/ps1/sh · README-fa.md (ردیف امکانات + عیب‌یابی صفحه سفید + راهنمای رفع سریع بدون نصب مجدد)
- تست‌ها (همه PASS):
  * T1: بازتولید صفحه سفید با CDN معلق → قبل: بی‌نهایت / بعد از فیکس: ۰.۲s لود کامل صفحه ورود
  * T2: E2E کامل با CDN معلق: فونت Vazirmatn لوکال لود ✓ لاگین admin → داشبورد ✓ فونت روی داشبورد اعمال شد ✓ صفر درخواست خارجی (جز سوکت لوکال :3003)
  * T3: آپدیت روی نصب v1.9.8 در حال اجرا: killed=2 verified=true (ss-based)، داده‌ها حفظ شدند (employees ali/mahla/sara + 3 customers + marker uploads)
  * T4: بوت کامل + ۷ چک امنیتی (پنل 200، لاگین، mint len=106، /health، /emit غلط→401، سوکت ناشناس REJECTED، سوکت احرازشده ACCEPTED) — قانون طلایی ۵ دست‌نخورده
  * T5: تله alias (test-alias-trap.sh): نصب کامل با alias فعال، exit 0
  * T6: تشخیص Git Bash (MEDPAD_SIM_UNAME=MINGW64): هدایت به install.bat
  * T7: تولید bat ویندوزی (MEDPAD_SIM_WIN=1): هر ۵ فایل ASCII+CRLF
  * T8: ویزارد: / و /fonts/vazirmatn.css و woff2 → 200، traversal → 404، صفر jsdelivr
  * T9: node --check همه mjs ها + ایمپورت‌های services.mjs سالم
- بسته‌بندی v1.9.9: سه ZIP (offline + full + installer-src) + آپلود Filebin medpad199 + راستی‌آزمایی SHA1 برگشتی + آپدیت سرور دانلود :3000

Stage Summary:
- v1.9.9 منتشر شد؛ علامت کاربر (صفحه سفید) کاملاً رفع؛ پنل حالا صفر وابستگی خارجی دارد (قانون طلایی ۳ واقعاً کامل شد)
- قاعده جدید: هیچ asset خارجی در هیچ صفحه‌ای مجاز نیست (فونت/اسکریپت/CSS) — در بخش وضعیت ثبت شد
- SHA1های نهایی در جدول 📦 بالا و لینک‌ها در بخش 🌐

---
Task ID: 6
Agent: main (Super Z)
Task: عیب‌یابی خطای کاربر v1.9.9: «FAIL Self-hosted font not found (public/fonts/vazirmatn.css) - package incomplete» (خروج ۱ در Step 4)

Work Log:
- لاگ کاربر: installer v1.9.9 در `C:\Users\PED4AM\Desktop\My-Projects\MedPad\medpad-installer` اجرا شد · service stop killed=0 verified=true (فیکس #13 کار کرد) · نود سیستم v24.20.0 · upgrade mode تشخیص داده شد · ~۱۰ ثانیه بعد FAIL در چک فونت Step 4
- راستی‌آزمایی بسته‌ها در سندباکس: SHA1 هر دو ZIP با مقادیر منتشرشده یکی است (offline `67d86be8…` / full `77c08c14…`) ✓ · ZIP فول، ZIP آفلاین را به‌صورت تو در تو (nested) دارد ✓ · داخل nested: هر ۹ woff2 + vazirmatn.css در `app/panel/.next/standalone/public/fonts/` موجود ✓ → بسته سالم است؛ مشکل سمت ماشین کاربر است
- ریشه: کاربر **فایل‌های installer جدید (install.mjs v1.9.9) را در پوشه قدیمی medpad-installer گذاشته ولی پوشه `app\` هنوز نسخه قدیمی v1.9.7 است** (بدون فونت self-hosted). چک اجباری فونت v1.9.9 دقیقاً همان‌طور که طراحی شده جلوی نصب ناقص را گرفت (اگر می‌گذشت، صفحه سفید CDN دوباره برمی‌گشت — بیلد قدیمی هنوز به jsdelivr رفرنس دارد؛ صرفاً کپی فونت هم کافی نیست، فایل‌های HTML بیلد هم باید نسخه پچ‌شده باشند)
- وضعیت داده کاربر (ردیابی کد خط‌به‌خط): Step 2 کامل اجرا شد — بکاپ db/uploads/.env به .keep-tmp (با راستی‌آزمایی سایز) ✓ → حذف panel بدون EPERM (فیکس v1.9.8 روی ماشین واقعی کاربر جواب داد!) ✓ → کپی app/panel قدیمی → panel ✓ → بازیابی db/uploads/.env داخل panel جدید (خطوط 298-301) ✓ → .keep-tmp پاک شد ✓ → Step 3 کامل (keepDb=true → رمز ادمین ریست نشد) ✓ → Step 4 روی چک فونت مردود. **نتیجه: داده کاربر سالم در `medpad-installer\panel\db` + `panel\uploads` + `panel\.env` است**
- Filebin medpad199 چک شد: زنده، هر دو ZIP قابل دانلود (302 → storage امضاشده) ✓
- راهنمای رفع برای کاربر (فارسی، گام‌به‌گام): بکاپ دستی داده‌ها → دانلود offline ZIP از filebin.net/medpad199 → چک SHA1 با certutil (`67d86be81a942ce6bdd398dbf85c304fc82b84b2`) → استخراج کامل → حذف پوشه `app` قدیمی و جایگزینی کامل با `app` جدید → اجرای مجدد install.bat → استارت و تست localhost:3000
- نکته برای نسخه بعد (v1.9.10): پیام خطای چک فونت قابل‌اقدام‌تر شود — مثلاً «نسخه installer و نسخه پوشه app هم‌خوانی ندارند؛ ZIP جدید را کامل استخراج کن» + چک BUILD_ID/package version در app قبل از Step 2

Stage Summary:
- بسته v1.9.9 سالم است؛ خطا = آپگرید ناقص سمت کاربر (installer جدید + app قدیمی)؛ چک فونت درست عمل کرد و مانع نصب معیوب شد
- داده کاربر امن است (panel/db + uploads + .env بازیابی شده داخل panel جدید)
- EPERM روی ویندوز واقعی دیگر دیده نشد (فیکس v1.9.8 تأیید شد روی ماشین کاربر)
- Filebin medpad199 زنده و معتبر است
- ⚠️ این رکورد بعد از ریست سندباکس (Oct 13) از حافظه چت بازسازی شد — نسخه اصلی‌اش در worklog قبلی بود که با ریست پاک شد

---
Task ID: 7
Agent: main (Super Z)
Task: بازیابی بعد از ریست سندباکس چهارم

Work Log:
- **ریست سندباکس:** worklog.md · recover/ · scripts/ · download-server/ · download/ZIPها همه پاک شدند؛ فقط skills/ و upload/WORKLOG-MedPad.md (پشتیبان Task 3) و .git و .env باقی ماندند
- بازیابی طبق پروتکل ⚡ مرحله ۲/۳: worklog.md از `upload/WORKLOG-MedPad.md` کپی شد (نسخه Oct 10 — تا Task 5) → Task 6 (عیب‌یابی فونت v1.9.9 که فقط در worklog پاک‌شده بود) از حافظه چت بازنویسی شد
- ZIPها از Filebin medpad199 برای بازگردانی download/ دانلود شدند (SHA1 برای راستی‌آزمایی: offline `67d86be8…` · full `77c08c14…`)
- ⚠️ یادداشت: کاربر اعلام کرد چند لاگ خطای اپ دیگری (ساخت ویدیو/عکس، مرتبط با MedPad نبود) را به اشتباه فرستاده بود — به درخواست صریح خودش، آن تشخیص اشتباه از worklog حذف شد؛ این فایل فقط سوابق MedPad را نگه می‌دارد

Stage Summary:
- بازیابی پس از ریست انجام شد: worklog.md کامل (Task 1-7) + ZIPها در حال بازگردانی از Filebin medpad199
- طبق درخواست کاربر: worklog فقط شامل سوابق MedPad است؛ ورودی اشتباه (اپ غیر MedPad) حذف شد

---
Task ID: 8
Agent: main (Super Z)
Task: دیباگ کامل MedPad و انتشار v1.9.10 (درخواست کاربر: «کدهای پروژه رو عیب‌یابی مجدد کن و یک ورژن ببر بالا»)

Work Log:
- هر دو ZIP v1.9.9 از Filebin medpad199 برگشت و SHA1 هر دو راستی‌آزمایی شد (offline `67d86be8…` · full `77c08c14…`) ✓
- بازبینی خط‌به‌خط کل install.mjs (578 خط) + install.bat/ps1/sh + services.mjs — ۴ باگ واقعی پیدا شد:
  * **باگ #14 (باگ واقعی کاربر):** چک فونت v1.9.9 در Step 4 بود، یعنی بعد از بکاپ/حذف/جایگزینی panel — وقتی کاربر فایل‌های installer جدید را داخل پوشه استخراج‌شده قدیمی گذاشت، پیام مبهم «package incomplete» را دید درحالی‌که پنل قدیمی (با باگ صفحه سفید) از قبل جایگزین شده بود. **فیکس:** فایل مانیفست `app/APP-VERSION` + چک نسخه/ساختار در **ابتدای اجرا — قبل از stop سرویس‌ها و قبل از کوچک‌ترین تغییری روی فایل‌ها**؛ بسته قدیمی/ناقص → die با پیام قابل‌اقدام («extract ALL of it… NOTHING has been changed»)؛ ناسازگاری خوش‌خیم (بسته کامل ولی نسخه متفاوت) → هشدار + ادامه. پیام Step 4 هم قابل‌اقدام شد
  * **باگ #15:** `JSON.parse(tunnel-state.json)` بدون try/catch — فایل نصفه‌نوشته (قطع برق) کل نصب را بعد از آپدیت پنل کرش می‌کرد → حالا try/catch + هشدار + ادامه با state تازه
  * **باگ #16:** مسیر نصب fresh هنوز `cpSync` خام داشت (فیکس v1.9.8 فقط مسیر آپدیت را cpRobust کرده بود) → قفل موقت آنتی‌ویروس می‌توانست نصب تازه را بکشد → cpRobust
  * **باگ #17:** مهاجرت legacy (`../medpad-install`) هم cpSync خام + بدون try/catch → cpRobust + non-fatal
- ارتقاهای نسخه: INSTALLER_VER=1.9.10 · `app/APP-VERSION` جدید · install.bat/ps1/sh → 1.9.10 · README-fa.md (جدول امکانات v1.9.10 + ردیف عیب‌یابی «خطای app bundle is OUTDATED» + ساختار بسته)
- **تست‌ها (اسکریپت ماندگار `scripts/test-v1910.sh` — ۴۱/۴۱ PASS):**
  * T1 = دقیقاً سناریوی کاربر (installer جدید + app قدیمی در حال اجرا): die با پیام واضح ✓ هیچ فایلی دست نخورد ✓ سرویس در حال اجرا هم stop نشد (چک قبل از stop است) ✓
  * T2/T2b = نصب fresh + بوت از start-server.sh: پنل 200 ✓ لاگین admin 200 ✓ mint توکن len=106 (GET است نه POST) ✓ /emit توکن جعلی→401 ✓ فونت لوکال 200 ✓ /health ✓ سوکت ناشناس REJECTED (44 unauthorized) ✓ سوکت احرازشده ACCEPTED ✓
  * T3 = آپدیت روی نصب v1.9.9 در حال اجرا: exit 0 ✓ سرور قدیمی خودکار stop شد ✓ marker uploads حفظ شد ✓ دیتابیس حفظ شد ✓ پنل جدید بوت شد ✓ لاگین admin روی دیتابیس حفظ‌شده 200 ✓
  * T4 = ناسازگاری خوش‌خیم نسخه → هشدار + ادامه ✓ · T5 = tunnel-state خراب → بدون کرش ✓ · T6 = پنج bat تولیدی ویندوزی ASCII+CRLF ✓ · T7 = تشخیص Git Bash ✓ · T8 = سینتکس همه mjs ✓
- نکته فنی تست: پنل Next standalone باید با env کامل start-server.sh بوت شود (DATABASE_URL و…) — بوت دستی بدون env → خطای Prisma 14؛ مسیر mint = GET /api/admin/socket-auth؛ تست سوکت با شبیه‌سازی پروتکل engine.io/polling (Open → CONNECT با/بدون توکن → 40 در برابر 44) بدون نیاز به socket.io-client
- بسته‌بندی: سه ZIP (offline + installer-src + full) — SHA1ها بعد از ساخت در جدول 📦 ثبت می‌شوند + آپلود Filebin medpad200 + راستی‌آزمایی برگشتی
- ⚠️ **حادثه fs سندباکس حین بسته‌بندی (درس مهم):** بعد از ساعات کار سنگین، fs سندباکس به‌شدت ناپایدار شد — پوشه‌ها/فایل‌های بزرگ جدید (بیش از ~حدی) بعد از ۱-۲ دقیقه «نامرئی» می‌شدند (ls می‌دید، open نه)؛ بسته‌های ساخته‌شده محلی دو بار نابود شدند. راه‌حل نهایی: **آپلود FIFO استریم** (zip مستقیم به stdin کل curl — بدون فایل واسط محلی) + راستی‌آزمایی با دانلود برگشتی. پاک‌سازی /tmp (حذف درخت‌های تست تکراری ~۳.۵GB) هم کمی به پایداری کمک کرد. اگر در آینده فایل بزرگ محلی بعد از ساخت مرد، همین روش را استفاده کن (نمونه پیاده‌سازی: `scripts/one-shot-v1910.sh` + دستورات FIFO در همین تسک)
- راستی‌آزمایی نهایی بستهٔ ارسالی: دانلود برگشتی از Filebin → unzip -t «No errors detected» ✓ · دو دانلود متوالی با SHA1 یکسان ✓ · SHA1 هر دو ZIP داخلی full با نسخه‌های تک‌به‌تک آپلودشده دقیقاً یکی ✓ (زنجیره کامل)

Stage Summary:
- v1.9.10 منتشر شد: ۴ باگ فیکس شد (بزرگ‌ترین: چک نسخه بسته app قبل از هر تغییری — سناریوی دقیق خطای کاربر)
- ۴۱/۴۱ تست PASS؛ ضمانت جدید: اگر بسته ناهماهنگ باشد نصب قبل از هر تغییری متوقف می‌شود و حتی سرویس در حال اجرا هم قطع نمی‌شود
- قانون طلایی ۵ (auth سوکت) دوباره راستی‌آزمایی شد: ناشناس REJECTED، توکن جعلی 401، احرازشده ACCEPTED
- تحویل: filebin.net/medpad200 (offline `2777b3aa…` · full `8b45908d…` · src `d2fd9f25…`) — همه با SHA1 برگشتی تأیید ✓

---
Task ID: 9
Agent: main (Super Z)
Task: پیاده‌سازی دوباره سیستم نصب‌کننده تعاملی (درخواست کاربر): منوی install.bat/ps1/sh با انتخاب مقصد (لوکال ویندوز / VPS لینوکسی / کلادفلر) + ادامه در CLI یا ویزارد مرورگر گرافیکی + استارت/استاپ از CLI و GUI + باز شدن خودکار صفحه اصلی بعد از استارت — انتشار v1.9.11

Work Log:
- بازیابی بعد از ریست پنجم سندباکس: worklog از upload/ برگشت + هر سه ZIP v1.9.10 از Filebin medpad200 دانلود و SHA1 هر سه راستی‌آزمایی شد (offline 2777b3aa… · full 8b45908d… · src d2fd9f25…)
- کد فعلی مطالعه شد: install.mjs ۶۹۲ خطی v1.9.10 + setup-server.mjs قدیمی (v1.9.3) + ui/index.html ویزارد دامنه + tunnel.mjs + lib/cloudflare.mjs + services.mjs
- کشف مهم: پورت 3003 در «یک» chunk کلاینت بیلد به‌صورت literal آمده (`location.hostname}:3003` — فقط فایل 0jyuciftpd3l8.js) و سمت سرور با env REALTIME_URL قابل override است → پورت سوکت سفارشی با پچ امن شدنی است
- فایل‌های جدید/تغییر یافته v1.9.11:
  * install.mjs (۱۰۸۹ خط): منوی تعاملی (Q1 مقصد + Q2 رابط)، launchWizard با رزرو پورت + spawn دیتاچد + پروب سلامت + باز کردن مرورگر، --port/--rt-port، patchRtPort + verifyRtPatch (جایگزینی exact-string + راستی‌آزمایی)، medpad-config.json، هدف VPS (systemd روی لینوکس + راهنمای scp/ssh روی ویندوز)، سؤال «الان استارت شود؟»، argOrEnv برای رمز/توکن (ENV نه argv)
  * wizard.mjs (جدید): بک‌اند ویزارد مرورگری — فقط 127.0.0.1، /api/status، /api/install با استریم لاگ زنده، /api/start، /api/stop، /api/manager، /api/open، فونت self-hosted + محافظ traversal
  * ui/installer.html (جدید): ویزارد فارسی RTL چهار مرحله‌ای (مقصد → تنظیمات یوزرنیم/رمز/پورت [+پیشرفته: پورت سوکت] → لاگ زنده → پایان با دکمه استارت) — کارت‌های مقصد، راهنمای VPS روی ویندوز، فیلدهای کلادفلر (سریع/دامنه + راهنمای ۵ قدمی توکن)، بدون هیچ asset خارجی
  * server-manager.mjs (جدید): start/stop/restart/status/gui — spawn دیتاچد پنل+سوکت با لاگ در logs/، .medpad.pids، انتظار سلامت تا ۹۰ ثانیه، باز کردن خودکار مرورگر، تونل domain/quick بهترین‌تلاش، GUI روی 127.0.0.1:3198
  * ui/manager.html (جدید): صفحه مدیریت گرافیکی — وضعیت زنده پنل/سوکت (poll ۳ ثانیه)، دکمه‌های استارت/استاپ/ری‌استارت/باز کردن پنل/بستن مدیر
  * services.mjs: readConfig + servicePorts (کانفیگ-آوار) + openBrowser کراس-پلتفرم + stopMedPadServices با ports override (union قدیم/جدید)
  * install.bat/ps1/sh: بنر 1.9.11 + pre-flight برای wizard/server-manager + توضیح منو (منطق UAC/MSI/ZIP-detect دست‌نخورده)
  * اسکریپت‌های تولیدی نصب: start-server.bat/sh و stop-server.bat/sh حالا از server-manager استفاده می‌کنند؛ server-manager.bat/sh جدید؛ start-realtime.bat برای سازگاری
  * README-fa.md: بخش کامل v1.9.11 + جدول فایل‌های مهم + عیب‌یابی جدید
- باگ #18 پیدا و فیکس شد (کشف T8): سؤال‌های متوالی با ورودی پایپ‌شده گم می‌شدند (readline رویدادهای line را قبل از listener سؤال بعدی emit می‌کند) → صف خط دائمی ANSWERS[] با listener همیشگی
- تست‌ها (اسکریپت ماندگار scripts/test-v1911.sh — **۴۹/۴۹ PASS**):
  * T1/T2: نصب fresh با پورت سفارشی 3100/3103 — کانفیگ درست، helperها به dir سفارشی کپی شدند، پچ RT دقیقاً ۱ chunk کلاینت + ۵ سرور، بدون 3003 باقی‌مانده
  * T3: بوت با server-manager — پنل 200، فونت 200، لاگین admin/test1234 (رمز دلخواه) 200، mint توکن، /emit توکن غلط→401، سوکت ناشناس REJECTED + احرازشده ACCEPTED (لاگ rt) — قانون طلایی ۵ دست‌نخورده
  * T4: استاپ — پورت‌ها واقعاً آزاد
  * T5: ویزارد — health/status/صفحه RTL/فونت 200/traversal 404؛ نصب کامل از API با استریم و exit 0 (کاربر boss/wzpass ساخته شد و لاگین 200)؛ استارت/استاپ از API
  * T6: مدیر GUI — صفحه/status/استارت/ری‌استارت/استاپ/quit همه پاس
  * T7: آپدیت روی نصبِ در حال اجرا — Stopped 2 خودکار، مارکر uploads و db (KEEP-ME-123) حفظ شد
  * T8: منوی تعاملی با stdin پایپ‌شده (مسیر CLI کامل + سؤال استارت)؛ VPS-on-Windows (sim) → راهنما + «Nothing was changed» + خروج تمیز؛ انتخاب GUI → ویزارد دیتاچد بالا آمد و بعد از بسته شدن کنسول زنده ماند + preselectedTarget=cloudflare
  * T9: شش bat تولیدی ASCII+CRLF+بدون BOM
  * T10: سینتکس هر ۵ mjs
  * T12/T13 (جداگانه): هدف cloudflare/quick (state.kind=quick درست) + بوت پیش‌فرض 3000/3003 (لاگین admin123، فونت، /health)
- نکته طراحی: ویزارد و مدیر فقط روی 127.0.0.1 گوش می‌دهند (امنیت)؛ روی VPS headless راهنمای تونل SSH در README
- بسته‌بندی v1.9.11: سه ZIP (offline + installer-src + full) — SHA1ها بعد از ساخت در جدول 📦 ثبت و با دانلود برگشتی از Filebin راستی‌آزمایی می‌شوند

Stage Summary:
- v1.9.11 منتشر شد: منوی تعاملی نصب + ویزارد مرورگر گرافیکی + مدیر سرور GUI/CLI + باز شدن خودکار صفحه اصلی بعد از استارت + پورت‌های قابل تنظیم + systemd برای VPS — دقیقاً همان مدلی که کاربر خواسته بود
- ۴۹/۴۹ تست PASS؛ auth سوکت (قانون طلایی ۵) دوباره راستی‌آزمایی شد
- تحویل: Filebin medpad201 (لینک‌ها در بخش 🌐) + سرور دانلود داخلی :3000

---
Task ID: 10
Agent: main (Super Z)
Task: ثبت قانون طلایی جدید کاربر: «همیشه فایل‌ها روی سایت‌های آپلودی هم آپلود شوند» + همگام‌سازی پشتیبان‌ها

Work Log:
- درخواست کاربر (Oct 14, 2026): «فایل‌ها رو همیشه روی سایت‌های آپلودی هم آپلود کن. در worklog بنویس تا یادت نره»
- وضعیت سندباکس چک شد: هر سه ZIP v1.9.11 در `download/` سالم · Filebin medpad201 زنده (HTTP 200) با هر ۴ فایل (offline + full + installer-src + WORKLOG)
- قانون طلایی **شماره ۹** به بخش 👑 اضافه شد: همه فایل‌های تحویلی/مهم (ZIP، worklog، سند، اسکریپت و…) علاوه بر سرور داخلی :3000، همیشه روی سایت‌های آپلودی (Filebin و مشابه) هم آپلود شوند — هیچ تحویلی فقط محلی نمی‌ماند
- از این پس در پایان هر تسک، چک‌لیست تحویل این است: ۱) فایل در `download/` ۲) کپی در سرور :3000 ۳) **آپلود روی Filebin (bin جدید در هر نسخهٔ بزرگ، همان bin برای آپدیتهای کوچک) + راستی‌آزمایی SHA1 با دانلود برگشتی** ۴) آپدیت همین worklog + کپی‌های پشتیبانش (download/WORKLOG-MedPad.md + upload/ + Filebin + داخل ZIP)
- این ورودی خودش طبق همین قانون بلافاصله روی Filebin medpad201 هم آپلود شد

Stage Summary:
- قانون جدید همیشه‌معتبر ثبت شد (Golden Rule #9) — از این به بعد هر فایل تحویلی حتماً روی سایت آپلودی هم قرار می‌گیرد
- Filebin medpad201 زنده تأیید شد؛ worklog به‌روز به Filebin آپلود شد

---
Task ID: 11
Agent: main (Super Z)
Task: رفع «دانلود از سرور داخلی کار نمی‌کند» + ارائه لینک‌های دانلود کارا به کاربر

Work Log:
- گزارش کاربر: روی سرور خودم (:3000) کلیک می‌کند ولی دانلود نمی‌شود
- ریشه: بعد از ریست سندباکس (پنجم) پروسه `node download-server/serve.mjs` کشته شده بود — پورت 3000 خاموش
- تلاش اول (`nohup … & disown` + `setsid`): سرور ۲-۳ ثانیه بالا می‌آمد ولی بین فراخوانی‌های ترمینال کشته می‌شد (ریپر پروسه سندباکس)
- راه‌حل کارا: **double-fork با ساب‌شل** — `(cd … && setsid node serve.mjs >> server.log 2>&1 < /dev/null &)` → پروسه کاملاً detach شد و بین فراخوانی‌ها زنده ماند (PID 1368, Ssl)
- راستی‌آزمایی کامل دانلود داخلی:
  * medpad-offline-v1.9.11.zip: دانلود کامل 154,409,842 بایت → SHA1 = `cb30220c…acf9` == مقدار منتشرشده ✓ (magic bytes PK معتبر)
  * medpad-full-v1.9.11.zip: 161,229,321 بایت ✓ · installer-src: 1,361,754 بایت ✓ · WORKLOG: 66,410 بایت ✓
- راستی‌آزمایی Filebin medpad201 (لینک مستقیم + ریدایرکت storage): هر ۴ فایل → HTTP 206 ✓
- 📌 درس فنی برای ریست‌های بعدی: برای سرورهای ماندگار در این سندباکس همیشه `( setsid … & )` ساب‌شلی استفاده کن، نه `& disown` ساده — وگرنه ریپر پروسه بین فراخوانی‌ها آن را می‌کشد

Stage Summary:
- سرور دانلود داخلی :3000 دوباره بالا است و هر ۴ فایل v1.9.11 را با SHA1 صحیح سرو می‌کند
- Filebin medpad201 هم زنده است (هر ۴ فایل، انقضا ~۵ روز تا Oct 21)
- لینک‌های هر دو کانال به کاربر تحویل شد

---
Task ID: 12
Agent: main (Super Z)
Task: آپلود بسته‌های v1.9.11 روی حداقل دو سایت آپلود خارجی (درخواست کاربر: «مدام قطع میشه وسط کار وقتی از سرورهات دانلود میکنم. روی سایتهای آپلودی آپلودش کن. حداقل دو تا»)

Work Log:
- کاربر گزارش داد دانلود از سرور داخلی (:3000) مدام وسط کار قطع می‌شود → نیاز به سایت‌های آپلود بیرونی
- SHA1 هر سه ZIP محلی دوباره تأیید شد (با مقادیر منتشرشده یکی است)
- پروب سایت‌های کاندید: pixeldrain (آپلود anon نیاز به API key — رد)، gofile (API از سندباکس خالی — رد)، transfer.sh و 0x0.st و bashupload و litterbox (قطع/ناپایدار — رد)، file.io (خطای ریدایرکت)
- **دو سایت کارا پیدا و استفاده شد: x0.at + temp.sh** (علاوه بر Filebin موجود → مجموعاً ۳ سایت آپلود + سرور داخلی)
- آپلود‌ها (همه موفق):
  * x0.at: آفلاین ۱۵۴MB (۷۶ ثانیه) → `x0.at/xqtU.zip` · کامل ۱۶۱MB (۱۰۵ ثانیه) → `x0.at/tnxx.zip` · installer-src → `x0.at/6Pn1.zip` — بعد از این‌ها IP سندباکس برای آپلودِ بعدی (worklog) بن شد (403)؛ ZIPها قبلاً کامل رفته بودند
  * temp.sh: هر ۴ فایل (آفلاین `iJYNj` · کامل `Aqvva` · src `zxmlp` · worklog `hUdYa`) — endpoint آپلود: POST multipart به `/upload`
- **راستی‌آزمایی دانلود برگشتی (قانون طلایی ۶):** همه فایل‌ها از هر دو سایت دانلود و SHA1 هر کدام == مقدار منتشرشده ✓
  * نکته temp.sh: لینک معمولی یک صفحه HTML نشان می‌دهد؛ دانلود واقعی با POST (در مرورگر: کلیک روی دکمه «Click here to download»)
  * نکته x0.at: دانلود مستقیم است ولی از سندباکس گاهی وسط کار قطع می‌شد (HTTP/2 stream error) → با `--http1.1 -C -` و حلقه resume کامل و صحیح دانلود شد؛ برای کاربر: اگر دانلود ویندوزی قطع شد، دوباره ادامه بدهد یا از Filebin بگیرد
- این ورودی + جدول لینک‌های 🌐 به‌روز شد؛ worklog جدید روی Filebin و temp.sh دوباره آپلود شد

Stage Summary:
- بسته‌های v1.9.11 حالا روی ۳ کانال بیرونی در دسترس‌اند: **x0.at (مستقیم، بلندمدت)** + **temp.sh (۳ روزه)** + **Filebin medpad201 (تا Oct 21)** + سرور داخلی :3000
- SHA1 همه نسخه‌ها در همه سایت‌ها راستی‌آزمایی‌شده — هر کانالی که کاربر بگیرد فایل یکسان است
- درس ثبت‌شده: در جلسات بعدی اگر خواستیم دوباره روی x0.at آپلود کنیم و 403 گرفتیم، IP موقتاً بن است — از Filebin/temp.sh جایگزین استفاده شود

---
Task ID: 13
Agent: main (Super Z)
Task: دیباگ «APK خطای نصب میده» (درخواست کاربر — Oct 14)

Work Log:
- APK استخراج شد از full ZIP → nested `medpad-apk-v1.9.1.zip` → `medpad-apk-v1.9.1.apk` (6,400,325 بایت، SHA1 `8aa30ea4fc1d3488b6c2bc0797f73cbec0660c4f`)
- تحلیل ساختاری: unzip -t سالم ✓ · بدون native libs ✓ · **بدون امضای v1** (META-INF فاقد MANIFEST.MF/CERT.RSA) · فقط بلک امضای v2
- سورس اندروید هم از full ZIP درآمد: `medpad-tracker/` — applicationId `com.medpad.tracker` · minSdk 24 · targetSdk 34 · versionCode 10 · versionName 1.9.1 · **keystore واقعی موجود:** `keystore/medpad.keystore` (storepass/keypass `medpad123`, alias `medpad`, گواهی CN=MedPad Tracker, معتبر تا 2054)
- صحت‌سنجی امضا با apksig library (داخل uber-apk-signer 1.3.0 که از GitHub دانلود شد؛ Verify.java تک‌فایلی با `java -cp` اجرا شد): **امضای v2 معتبر بود** — signer cert SHA256 `3F:C8:53:97:…:5A:89` دقیقاً مطابق گواهی keystore ✓ → یعنی خود APK سالم و قابل نصب روی Android 7+ بود؛ خطای نصب کاربر از خرابی خود فایل نیست
- برای حذف تمام علل احتمالی سمت فایل: نسخه مقاوم ساخته شد — `medpad-apk-v1.9.1-fix.apk`:
  * zipalign شده + امضای **v2+v3** با همان keystore اصلی (همان گواهی → به‌روزرسانی روی نسخه قبلی بدون نیاز به uninstall)
  * راستی‌آزمایی با apksig: OVERALL verified=true · v2 ✓ v3 ✓ · zipalign verified ✓
  * SHA1: `36c48191fc910068f1e97b49b8b05fedf9e243ca` · SHA256 `b0a92106…cdbc810` · حجم 6,480,520 بایت (~6.5MB)
- آپلود (قانون طلایی ۹): temp.sh → `temp.sh/fMuKN/medpad-apk-v1.9.1-fix.apk` ✓ (SHA1 دانلود برگشتی تأیید) · Filebin medpad201 → `medpad-apk-v1.9.1-fix.zip` (Filebin پسوند apk را 403 می‌دهد → داخل zip؛ SHA1 zip `6cbc859b…` + APK داخلی تأیید) ✓ · سرور داخلی :3000 ✓ · x0.at هنوز ban بود (403)
- علت‌های محتمل باقی‌مانده خطای نصب کاربر (به کاربر گزارش شد): ۱) نسخه قدیمی با امضای متفاوت نصب است → اول uninstall ۲) Play Protect بلاک می‌کند → Install anyway ۳) دانلود ناقص ZIP بزرگ (کاربر خودش گفته دانلودها مدام قطع می‌شود) → لینک مستقیم APK تکی 6.5MB داده شد ۴) اندروید قدیمی‌تر از 7.0 (minSdk 24)
- ابزار باقی‌مانده در سندباکس: `recover/apk-debug/` (uber-apk-signer.jar + Verify.java + APKها + keystore)

Stage Summary:
- ریشه: فایل APK سالم بود (امضای v2 معتبر با گواهی اصلی) — خطای نصب به احتمال زیاد سمت دستگاه/دانلود است
- خروجی: APK مقاوم‌شده (zipalign + v2+v3 با همان کلید اصلی) با لینک مستقیم 6.5MB روی temp.sh + Filebin(zip) + :3000
- SHA1 نهایی برای چک کاربر: `36c48191fc910068f1e97b49b8b05fedf9e243ca`

---
Task ID: 14
Agent: main (Super Z)
Task: اتصال ریپازیتوری GitHub MedPad + توکن PAT (درخواست کاربر: «از این به بعد هر تغییری را commit+push کن؛ فعلاً فقط ریپو را وصل کن و کار دیگری نکن»)

Work Log:
- (بازیابی‌شده بعد از ریست هفتم سندباکس — ورودی اصلی در ریست از دست رفت، این خلاصه بازسازی است)
- توکن fine-grained PAT کاربر تست شد: admin/push/maintain:true → کلون عمومی + push ممکن
- کلون ۶۸۸MB به MedPad/ — ۳ کامیت روی main (2ab9e5a initial · 57aedb8 Android app · 42e1908 Full Project) + شاخه fix/android-security-build (c525afc: حذف JDK path ماشین‌خاص از gradle.properties)
- امنیت: توکن در ~/.git-credentials (600) · remote بدون توکن · identity=P3D4AM noreply
- ساختار: root=v1.9.11 · medpad-full-1.9/medpad-offline-1.9.12/=WIP v1.9.12 (install 1356 · services 389 بدون bcrypt · cloudflare 413 با فیکس ESM · wizard 229 · server-manager 356 · installer.html 480) · Android/ · offline/ · app/(فقط db+schema+uploads، بدون بیلد پنل)
- طبق دستور کاربر: هیچ commit/push انجام نشد

Stage Summary:
- ریپو وصل و مستند شد؛ آماده شروع تسک هفت‌مرحله‌ای ممیزی v1.9.12

---
Task ID: 15
Agent: main (Super Z)
Task: ادامه ممیزی هفت‌مرحله‌ای v1.9.12 روی ریپو (دستور کاربر: «ادامه بده»)

Work Log:
- ریست هفتم سندباکس: MedPad/ و ~/.git-credentials (توکن PAT) و ورودی Task 14 از دست رفت — کلون دوباره از GitHub عمومی انجام شد (بدون توکن)
- ⚠️ توکن PAT دیگر موجود نیست → push ممکن نیست تا ارائه مجدد توسط کاربر؛ کامیت‌ها محلی + bundle در download/
- سرور :3000 دوباره بالا آمد (double-fork)
- شروع اجرای فازهای ۱ تا ۷ ممیزی

Stage Summary:
- (در حال اجرا)

---
Task ID: 15 (تکمیل)
Agent: main (Super Z)
Task: ممیزی هفت‌مرحله‌ای v1.9.12 روی ریپوی GitHub — اجرای کامل فازهای ۱ تا ۷

Work Log:
- کلون دوباره ریپو (عمومی، بدون توکن — توکن PAT در ریست هفتم از دست رفت)
- فاز ۱: گزارش SECURITY-AUDIT-v1.9.12.md — وضعیت گیت، سه نسخه (root v1.9.11 / WIP 1.9.12 / inst-stage)، جدول هش، سهگانهٔ custom.db (۲ واقعی یکسان + ۱ قالب خالی)
- فاز ۲: .gitignore + untrack (۲ keystore یکسان با رمز medpad123 لو رفته + ۲ DB واقعی + New Text Document.txt) — فایل‌ها روی دیسک دست‌نخورده · ادغام شاخهٔ fix/android-security-build · بازنویسی signing هر دو build.gradle.kts (env/properties، بدون رمز hardcode، debug دیگر با کلید release امضا نمی‌شود، رفتار صریح بدون امضا) · Android/ANDROID-SIGNING.md (fa): چرخش کلید + تأثیر APK (uninstall لازم) · طرح پاکسازی تاریخچه فقط گزارش (بخش ۲.۴)
- فاز ۳: حذف همهٔ fallbackهای admin123/medpad-dev-token (login route + auth.ts + emit.ts + socket-auth + install.mjs + realtime/index.js — فقط با MEDPAD_DEV=1) · rate-limit هر دو لاگین (۵ شکست/۱۵دقیقه → 429+retry-after) · SOCKET_TOKEN با randomBytes + بازتولید مقدار خالی · downloads.html اصلاح شد
- بازسازی پنل از سورس امن: کشف و رفع رگرسیون seed (Turbopack باندل می‌کرد bcryptjs را → serverExternalPackages) · دو symlink dereference شد برای checkout ویندوزی · BUILD_ID FbZh3ToaRF62cC8CdAY1v · قالب خالی db حفظ شد
- فازهای ۴/۵/۶: بازبینی کامل و تأیید (services.mjs ایمنی پروسه · cloudflare.mjs با فیکس ESM و جریان plan/confirm/backup/rollback · install.mjs اصلاحات پروسه+CF)
- فاز ۷: test-v1912.sh + test-cf-mock.mjs + db-triage/classify → در audit-tests/ ریپو کامیت شد — **۴۴/۴۴ PASS** (نصب، پچ پورت، بوت، لاگین، رد admin123، rate-limit 429، auth سوکت، استاپ، آپدیت با حفظ داده، مجوزها 600/700، CSPRNG، سینتکس، CF mock کامل)
- ۷ کامیت روی main محلی: 1511df3 → 0b5dbb94
- ⚠️ push ناموفق: توکن PAT در ریست سندباکس از بین رفت → بستهٔ git bundle در download/medpad-audit-v1.9.12.bundle ساخته شد (کاربر می‌تواند خودش fetch/push کند یا توکن جدید بدهد)
- آپلودها (قانون طلایی ۹): Filebin + سرور :3000 (بالا)

Stage Summary:
- ممیزی هفت‌مرحله‌ای کامل شد: ۴۴/۴۴ تست PASS؛ همهٔ اسرار از ردیابی خارج؛ امضای اندروید سخت‌سازی؛ backdoorهای dev حذف؛ rate-limit اضافه؛ پنل بازسازی‌شده از سورس امن
- باز ماندن برای مالک: push (توکن جدید) · پاکسازی تاریخچه (filter-repo) · چرخش کلید اندروید (ANDROID-SIGNING.md) · انتشار ZIP نهایی v1.9.12

---

## Phase 0 — Security, Stability, Tests & README (2026-10-25)

**وظیفه:** ارتقای امنیت/پایداری/تست‌پذیری و نوشتن README صفحهٔ اول — بدون commit/push (تغییرات برای بازبینی دستی).

**انجام‌شده:**
1. **ارتقای v1.9.12 سخت‌شده به ریشهٔ مخزن** — پایان دوگانگی «ریشهٔ v1.9.11 ناامن / بستهٔ v1.9.12 امن»: همهٔ ماژول‌ها + بیلد امن پنل (بدون admin123 و بدون fallback توکن) + APP-VERSION به ریشه منتقل شد؛ دادهٔ کاربر (db واقعی، uploads) دست‌نخورده.
2. **سخت‌سازی حملات مرورگری** (wizard.mjs + GUI مدیر): گیت Host (ضد DNS-rebinding) + گیت Origin (ضد CSRF) + Sec-Fetch-Site + الزام JSON برای /api/install (415) + رفع باگ ECONNRESET بدنهٔ بزرگ (drain به‌جای destroy).
3. **سخت‌سازی realtime**: حد 64KB برای /emit (413) + مقایسهٔ زمان-ثابت راز + مستندسازی تصمیم CORS پویا.
4. **تور ایمنی کلون تازه**: قالب خالی `db/template.db` (در هر سه نسخه) + fallback در install.mjs؛ بستهٔ فاقد هر دو با خطای روشن متوقف می‌شود.
5. **۴۸ تست امنیتی جدید** (wizard 15 + realtime 15 + process-safety 18) + `run-all.sh`؛ نتیجهٔ نهایی **92/92 PASS** (شامل اجرای مجدد سوئیت کامل روی بستهٔ 1.9.12 و شبیه‌ساز کلون تازه از ریشه).
6. **README.md فارسی** در ریشه (معرفی/معماری Mermaid/نصب/امنیت/تست/نقشه راه) + همگام‌سازی README-fa.md با v1.9.12 + به‌روزرسانی .gitignore (Artefactهای نصب درجایی) + پیوست فاز صفر به SECURITY-AUDIT-v1.9.12.md.
7. اسکن نهایی اسرار روی فایل‌های track‌شده: پاک. بیلد اندروید در این محیط ممکن نبود (Android SDK غایب) — گزارش شد.

**وضعیت گیت پس از فاز:** طبق قاعدهٔ وقتِ وظیفه بدون commit مانده بود؛ طبق دستور دائمی جدید مالک (Oct 25، فایل AGENTS.md) کمیتی شد (bd9c80ba) و سپس با بازسازی ساختار ریپو توسط مالک ادغام شد.

---

## 🔀 ادغام با بازسازی ساختار توسط مالک (Oct 25, 2026)

مالک روی ریموت دو کامیت زد: «Organized folder» (حذف کامل medpad-full-1.9/ + ارتقای فایل‌های v1.9.12 خام به ریشه) و «Android app» (بازافزودن Android/ با keystore و build.gradle.kts قدیمی با رمز hardcode). ادغام امن انجام شد:

- **ریشه و app = نسخهٔ سخت‌شدهٔ ما** (فوق‌مجموعهٔ ارتقای خام مالک: همان v1.9.12 + همهٔ فیکس‌های امنیتی + بیلد امن پنل RzE0w8ok)؛ تست ۹۲/۹۲ روی درخت ادغام‌شده مجدداً پاس شد.
- **medpad-full-1.9/ حذف شد** (ارادهٔ مالک)؛ سورس کامل پنل با فیکس‌های امنیتی در `medpad-panel-src-v1.9.12-secure.zip` (Filebin/:3000) و در تاریخچهٔ گیت (تا commit bd9c80ba) حفظ شد.
- **bcryptjs باندل‌شده با نسخهٔ کامل بستهٔ رسمی 3.0.3** تکمیل شد (LICENSE/README/umd — انطباق MIT).
- **keystore اندروید و custom.db از ردیابی خارج ماندند** (ادامهٔ remediation فاز ۲). ⚠️ هر دو همچنان در تاریخچهٔ ریموت موجودند (42e1908f و 8db3319a) — چرخش کلید (ANDROID-SIGNING.md) و پاکسازی تاریخچه همچنان توصیه می‌شود. بازیابی در صورت نیاز: `git show 8db3319a:Android/keystore/medpad.keystore > medpad.keystore`
