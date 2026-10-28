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
10. **قانون دائمی Git (درخواست صریح کاربر — Oct 25, 2026):** پس از تکمیل هر وظیفه/فیکس/تغییر معنادار → بازبینی تغییرات + اجرای تست‌های مرتبط با نتیجهٔ واقعی + Stage فقط فایل‌های مرتبط (بدون `git add .` کورکورانه) + Commit با پیام دقیق + **Push به GitHub + تأیید ثبت روی Remote**. اسرار (keystore/custom.db/.env/توکن) هرگز Commit نشوند؛ تغییرات Commit‌نشدهٔ کاربر هرگز جذب/حذف نشوند؛ `reset --hard`/`clean -fd`/بازنویسی تاریخچه/Force Push بدون اجازهٔ صریح ممنوع؛ اگر Push ناموفق بود (اعتبارنامه/اختلاف تاریخچه) دقیقاً علت را گزارش کن و راه‌حل امن (merge) را اجرا کن — هرگز ادعای انتشار نکردنی منتشر‌شده مکن. متن کامل: `AGENT-DIRECTIVES.md` + `MedPad/AGENTS.md`. **این قاعده قاعدهٔ «بدون commit» وظیفهٔ Phase 0 را لغو کرد (پیام جدیدتر کاربر، Oct 25).**

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
| v1.9.11 | **بازگشت نصب‌کننده تعاملی (درخواست کاربر):** منوی CLI هنگام اجرای install.bat/ps1/sh — مقصد (۱ این کامپیوتر / ۲ VPS لینوکسی / ۳ کلادفلر) + رابط (۱ ویزارد مرورگری GUI که خودکار باز می‌شود / ۲ CLI) · **wizard.mjs + ui/installer.html** — ویزارد گرافیکی فارسی RTL (یوزرنیم/رمز/پورت/توکلن، لاگ زنده، فقط 127.0.0.1) · **server-manager.mjs + ui/manager.html** — استارت/استاپ/ری‌استارت/وضعیت هم CLI هم GUI · **باز شدن خودکار صفحه اصلی بعد از هر استارت** · **پورت قابل تنظیم** (--port و --rt-port؛ پچ امن بیلد برای پورت سوکت سفارشی با راستی‌آزمایی) · **systemd برای هدف VPS** (+ راهنمای انتقال روی ویندوز) · **medpad-config.json** (منبع واحد پورت‌ها/تنظیمات؛ استاپ هوشمند با union پورت‌های قدیم/جدید) · رمز/توکن در حالت ویزارد از ENV می‌روند نه argv · **فیکس باگ readline ورودی پایپ‌شده** (باگ #18) | ✅ **نسخه فعلی** |

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
Task ID: 16
Agent: main (Super Z)
Task: Phase 0 — Security, Stability, Tests & Project README (دستور جدید کاربر روی ریپوی MedPad)

Work Log:
- بازرسی کامل وضعیت: شاخهٔ main تمیز، ۹ کامیت جلوتر از origin (کار هفت‌مرحله‌ای قبل)؛ کشف دوگانگی: ریشه = v1.9.11 ناامن (admin123 در چانک پنل، medpad-dev-token در realtime) در برابر بستهٔ v1.9.12 سخت‌شده در medpad-full-1.9/
- ارتقای کامل v1.9.12 به ریشه: install/wizard/services/server-manager/cloudflare/installer.html/realtime(+lock)/APP-VERSION/install.sh-ps1-bat + بیلد امن پنل (RzE0w8ok4YZardyOBcaAv، بدون admin123/fallback) — دادهٔ کاربر (db واقعی + uploads) دست‌نخورده
- سخت‌سازی جدید فاز صفر: گیت‌های Host/Origin/Sec-Fetch-Site در wizard.mjs + GUI مدیر (ضد CSRF/DNS-rebinding) + الزام JSON برای /api/install + رفع باگ ECONNRESET (drain به‌جای destroy)؛ حد 64KB و مقایسهٔ زمان-ثابت در /emit؛ مستندسازی تصمیم CORS پویا
- تور ایمنی کلون تازه: db/template.py? نه — db/template.db (قالب خالی) در سه نسخه + fallback در install.mjs — با نصب واقعی از کلون شبیه‌سازی‌شده راستی‌آزمایی شد
- ۴۸ تست امنیتی جدید در audit-tests/ (wizard 15 + realtime 15 + process-safety 18) + run-all.sh — مجموع ۹۲/۹۲ PASS (سوئیت کامل دوبار: بستهٔ 1.9.12 + شبیه‌ساز کلون از ریشه، هر دو 44/44)
- README.md فارسی جدید در ریشه (صفحهٔ اول GitHub) + همگام‌سازی README-fa.md + پیوست فاز صفر به SECURITY-AUDIT + ورودی WORKLOG-MedPad + به‌روزرسانی .gitignore (12 قاعدهٔ جدید Artefactهای نصب درجایی)
- اسکن نهایی اسرار: پاک (dry-run add راستی‌آزمایی شد — فقط template.db از پوشهٔ db وارد گیت می‌شود، هرگز custom.db)
- طبق قاعدهٔ صریح وظیفه: هیچ commit/push انجام نشد — working tree آمادهٔ بازبینی

Stage Summary:
- Phase 0 کامل شد: ریشه = v1.9.12 سخت‌شده + سخت‌سازی مرورگری جدید + ۹۲/۹۲ تست PASS + README فارسی صفحهٔ اول
- بیلد اندروید اجرا نشد (Android SDK غایب) — JDK 21/wrapper موجود؛ گزارش شد
- تغییرات در working tree بدون commit — بررسی دستی و commit توسط مالک

---
Task ID: 17
Agent: main (Super Z)
Task: ثبت دستور دائمی Commit+Push مالک + انتشار کارهای commitنشده + ادغام با بازسازی ساختار ریموت

Work Log:
- دستور دائمی جدید کاربر (Oct 25) در ۳ محل ثبت شد: AGENT-DIRECTIVES.md + MedPad/AGENTS.md (کامیت 34a87bf7) + قاعدهٔ طلایی ۱۰ همین فایل — قاعدهٔ «بدون commit» فاز صفر لغو شد
- بازرسی وضعیت: ۹ کامیت محلی منتشرنشده (کار هفت‌مرحله‌ای) + کل فاز صفر commitنشده در working tree + دو کامیت جدید مالک روی ریموت (ac6760bf حذف medpad-full-1.9/ + ارتقای v1.9.12 خام به ریشه؛ 8db3319a بازافزودن Android/ با keystore و gradle قدیمی) + ⚠️ PAT از بین رفته (ریست هفتم؛ ~/.git-credentials غایب)
- راستی‌آزمایی فاز صفر: run-all.sh با «.» سه شکست کاذب داد (باگ مسیر نسبی: symlink نسبی node_modules + import نسبی lib؛ A2 با module-not-found تصادفی PASS می‌شد!) → اصلاح سوئیت (پیش‌فرض=ریشه + مطلق‌سازی PKG) → 92/92 PASS
- کامیت فاز صفر: bd9c80ba (۱۸۳ فایل؛ + template.db ریشه + package-lock + تست‌ها + README)
- بکاپ پیش از ادغام: recover/pre-merge-backup/ (۳ نسخهٔ custom.db با 600 + medpad.keystore) + recover/pre-merge-head.bundle (129MB)
- ادغام امن origin/main: ۲۵۴ تداخل حل شد — ریشه/app = نسخهٔ سخت‌شدهٔ ما (فوق‌مجموعهٔ ارتقای خام مالک؛ wizard/services خودکار ادغام شدند و دقیقاً برابر نسخهٔ من بودند)؛ medpad-full-1.9 حذف (ارادهٔ مالک)؛ bcryptjs با بستهٔ کامل رسمی 3.0.3 از طرف مالک تکمیل شد (انطباق MIT)؛ keystore و custom.db از ردیابی خارج ماندند (هر دو همچنان در تاریخچهٔ ریموت!)؛ build.gradle.kts مالک = بایت‌به‌بایت نسخهٔ قدیمی با medpad123 → نسخهٔ سخت‌شده برد
- اسناد هماهنگ شدند: README.md (۳ ارجاع به medpad-full اصلاح) + .gitignore (قواعد مرده حذف) + WORKLOG (ردیف v1.9.12 از نسخهٔ مالک بازگردانده + پیوست ادغام) + SECURITY-AUDIT (پیوست ۲)
- سورس یکتای پنل پیش از حذف بایگانی شد: download/medpad-panel-src-v1.9.12-secure.zip (162 فایل، از git archive bd9c80ba، بدون DB واقعی)
- تست درخت ادغام‌شده: 92/92 PASS → کامیت ادغام 864b707b (بدون force، بدون بازنویسی)
- push ناموفق (PAT غایب): «could not read Username» → bundle جایگزین: download/medpad-phase0-merge-v1.9.12.bundle (پیش‌نیاز 8db3319a؛ 211KB) — روی کلون شبیه‌سازی‌شدهٔ تازه از GitHub تست عملی شد (fetch ✓، fast-forward push ممکن ✓)
- آپلودها (قانون طلایی ۹): Filebin medpad202 (bundle+zip، SHA1 دانلود برگشتی تأیید) + temp.sh/AorcB (bundle) + سرور :3000 (زنده) + گزارش نهایی PHASE0-FINAL-REPORT-fa.md

Stage Summary:
- دستور دائمی ماندگار شد؛ فاز صفر + ادغام کامل و تست‌شده (92/92) در ۳ کامیت: 34a87bf7 → bd9c80ba → 864b707b
- منتشرنشده روی GitHub (PAT لازم)؛ مسیر جایگزین bundle تست‌شده و در ۳ کانال موجود
- باز مانده برای مالک: push (دستورهای دقیق در گزارش) · چرخش کلید اندروید + پاکسازی تاریخچه (keystore/custom.db در تاریخچهٔ ریموت عمومی) · تصمیم دربارهٔ شاخهٔ panel-src · PAT جدید برای ایجنت

---
Task ID: 18
Agent: main (Super Z)
Task: انتشار Phase 0 روی GitHub با PAT جدید مالک (دستور: تأیید کامیت‌ها → بررسی diff با GitHub → گزارش bundle → push به develop → تأیید ریموت → تست روی کامیت منتشرشده → release)

Work Log:
- **ریست هشتم سندباکس**: کلون `MedPad/`، هر دو bundle (audit + phase0-merge)، `~/.git-credentials` و به‌روزرسانی‌های این فایل (پایان Task 17) از بین رفت — working tree ریپوی محلی پاک شد
- **بازیابی کامل از Filebin (bin `medpad202` — قانون طلایی ۹ نجات‌دهنده بود)**: `medpad-phase0-merge-v1.9.12.bundle` (215,797 بایت، SHA-256 `81c2213f…a6c5`) + `medpad-panel-src-v1.9.12-secure.zip` (806,412 بایت، SHA-256 `89b11a6d…e6328c` — نکته: در گزارش قبلی یک کاراکتر آن اشتباه رونویسی شده بود؛ این مقدار با متادیتای Filebin به‌صورت برنامه‌ای راستی‌آزمایی شد) + `PHASE0-FINAL-REPORT-fa.md` + آخرین WORKLOG — هر چهار فایل با hash یکسان تأیید شدند
- PAT جدید مالک به‌صورت امن برقرار شد: `~/.git-credentials` (مجوز 600) + remote بدون توکن — دسترسی admin/push/maintain از طریق API تأیید شد
- وضعیت GitHub: `main` = `8db3319a` (دو کامیت جدید مالک: `ac6760bf` Organized folder + `8db3319a` Android app — پنج کامیت کل)؛ شاخهٔ `develop` از قبل روی `8db3319a` موجود بود؛ `phase0-security-review` موجود نبود
- `git bundle verify` ✓ → fetch ✓ → زنجیرهٔ `34a87bf7` (AGENTS.md) → `bd9c80ba` (فاز صفر، ۱۸۳ فایل) → `864b707b` (ادغام با 8db3319a) **دقیقاً و بدون تغییر** بازیابی شد
- **اثبات عدم بازنویسی تاریخچهٔ مالک**: هر ۵ کامیت مالک (`2ab9e5ae` → `57aedb8d` → `42e1908f` → `ac6760bf` → `8db3319a`) با SHA یکسانِ ریموت، ancestor های `864b707b` هستند
- Diff نهایی با GitHub فعلی: **۱۷۱ فایل (۲۱A / ۱۴D / ۱۳۲M / ۴R)** — حذف `Android/keystore/medpad.keystore`، `custom.db`→`template.db` (R100)، هاردنینگ install.mjs/server-manager.mjs/wizard.mjs، سوئیت audit-tests، README/AGENTS/SECURITY-AUDIT؛ اسکن اسرار روی خطوط افزوده: پاک (۲۵ تطبیق همگی مستندات/لیست‌سیاه رمز ضعیف/کد تست)
- **PUSH**: `develop` از `8db3319` به `864b707` (fast-forward، بدون force) + شاخهٔ جدید `phase0-security-review` روی همان کامیت — `main` دست‌نخورده ماند
- **تأیید ریموت (API)**: هر دو شاخه روی `864b707bca2c2c3db69b941e085cb03d4a447cd6`؛ `Android/keystore/medpad.keystore` و `app/panel/db/custom.db` روی develop → HTTP 404؛ `README.md`/`AGENTS.md`/`audit-tests/run-all.sh`/`SECURITY-AUDIT-v1.9.12.md` → HTTP 200
- **تست روی کامیت منتشرشده** (کلون تازه از GitHub، HEAD = `864b707b`): **92/92 PASS — ALL-SUITES-PASS** (ایمنی پروسه‌ها ۱۸ + امنیت ویزارد ۱۵ + امنیت realtime ۱۵ + سوئیت کامل v1.9.12: ۴۴)
- **Release**: `v1.9.12-phase0` (pre-release) روی همان کامیت `864b707b` + ضمیمهٔ `medpad-panel-src-v1.9.12-secure.zip` (۸۰۶KB)
- AGENTS.md: سیاست شاخهٔ `develop` (دستور جدید مالک: از این به بعد push روی develop) + اجازهٔ release ثبت شد

Stage Summary:
- **Phase 0 + ممیزی کامل روی GitHub منتشر و راستی‌آزمایی شد**: `develop` و `phase0-security-review` هر دو روی `864b707b`؛ `main` دست‌نخورده؛ 92/92 تست روی کامیت منتشرشده PASS؛ release با سورس پنل ضمیمه شد
- bundle اصلی به‌عنوان artefact دست‌نخورده در `recover/phase0-recovery/` نگهداری می‌شود (دستور مالک: حذف/بازسازی ممنوع)
- باز مانده برای مالک: بازبینی و مرج `develop` → `main` · چرخش کلید اندروید + پاکسازی تاریخچه (`medpad123` و custom.db در تاریخچهٔ عمومی باقی‌اند) · تصمیم دربارهٔ شاخهٔ `panel-src`

---
Task ID: 19
Agent: main (Super Z)
Task: ساخت و انتشار ریلیز آزمایشی develop برای وب و اندروید (تست واقعی نصب/اجرا + APK + Release على GitHub — دستور شش‌مرحله‌ای مالک)

Work Log:
- **مبنا:** شاخهٔ `develop` روی کامیت `1e9c983` (ادامهٔ امن fast-forward از `864b707` سخت‌شده + کامیت docs انتشار فاز صفر) — `phase0-security-review` روی `864b707`، `main` دست‌نخورده روی `8db3319`
- **تأیید سلامت مبنا:** Checkout تمیز develop → `audit-tests/run-all.sh .` → **92/92 PASS, ALL-SUITES-PASS** (خروجی واقعی در گزارش ریلیز)؛ working tree بدون هیچ تغییر untracked؛ تمام ۲۱۱۴ فایل (شامل ۱۶۲۹ فایل باندل `.next` و ۳۷۲ فایل `realtime/node_modules`) در گیت ردیابی‌شده
- **محیط:** JDK 21.0.12 · Node 24.21.0 · Android SDK نصب‌شده در سندباکس (cmdline-tools 11076708 + platform-tools + platforms;android-34 + build-tools;34.0.0) · Gradle wrapper 8.7 · AGP 8.4.2 / Kotlin 1.9.24
- **تغییرات این ریلیز (کمترین تغییر لازم):** `Android/app/build.gradle.kts`: versionName `1.9.1` → `1.9.12-dev.1`، versionCode `10` → `11` (هماهنگ با سرور v1.9.12؛ ارتقا روی نصب قبلی ممکن) · فایل جدید `INSTALL-GUIDE-fa.md` (راهنمای فارسی کوتاه داخل ZIP: پیش‌نیازها/آفلاین بودن، نصب ویندوز، استارت/استاپ، نشانی پنل، رمز اولیهٔ مدیر، آدرس اتصال اپ اندروید `http://<IP>:3000`، مسیر لاگ‌ها — همه از کد واقعی استخراج‌شده)
- **خروجی‌های این ریلیز:** `MedPad-Web-Offline-develop-v1.9.12-test.1.zip` (از git archive کامیت مبنا؛ بدون .git/Android/audit-tests/گزارش‌ها؛ با باندل کامل آفلاین: Node MSI+tar، node_modules، انجین‌های Prisma win/linux، فونت self-hosted، template.db) + `MedPad-Tracker-Android-develop-v1.9.12-test.1.apk` (**Debug APK با کلید Debug استاندارد اندروید** — نه keystore لو رفته و نه medpad123) + `DEVELOP-TEST-REPORT-fa.md` + `SHA256SUMS.txt`
- نتایج کامل تست‌ها، SHA-256 هر فایل، محدودیت‌ها (ویندوز واقعی/Emulator در سندباکس غایب) و لینک‌ها → در `DEVELOP-TEST-REPORT-fa.md` پیوست ریلیز GitHub

Stage Summary:
- (در حال اجرا — تکمیل پس از تست‌ها و انتشار)

---
Task ID: 19 (تکمیل)
Agent: main (Super Z)
Task: تکمیل ریلیز آزمایشی develop — تست‌ها، APK، گزارش، انتشار

Work Log:
- **دو باگ واقعی حین ساخت ریلیز کشف و فیکس شد** (هر دو در کامیت جداگانه):
  1. `7368e20` — **رگرسیون CRLF**: install.bat/install.ps1/gradlew.bat در کامیت‌های فاز صفر با LF ذخیره شده بودند (ZIPهای v1.9.11 همه CRLF بودند؛ قانون طلایی ۴/باگ #6) + `Android/.gitattributes` با `* text=auto` دوباره LF می‌کرد → تبدیل به CRLF + `.gitattributes` ریشه (`*.bat/*.ps1 -text`) + چک‌های جدید A1W/T2W در test-v1912.sh
  2. `3b277f2` — **خطای کامپایل Kotlin DSL**: بلاک امضای build.gradle.kts هیچ‌وقت کامپایل نشده بود؛ `java.util.Properties()` داخل بلاک android{} خطای Unresolved می‌دهد → `import java.util.Properties` سطح بالا
- **تست وب روی ZIP نهایی** (استخراج در مسیر خالی مستقل): سوئیت کامل `run-all.sh <pkg>` → **94/94 PASS, ALL-SUITES-PASS** (46 چک v1912 شامل A1W جدید + 18 process-safety + 15 wizard + 15 realtime)
- **E2E دستی جریان واقعی کاربر** (نصب تعاملی پایپ‌شده: منو→۱ این کامپیوتر→۲ CLI→رمز خالی=تولیدشده→پورت‌های 3210/3213→دادهٔ نمونه): نصب exit 0 + بنر INSTALL COMPLETE؛ رمز تصادفی `Mp-***` فقط یک بار چاپ شد؛ Health پنل 200 در 2ms؛ صفحهٔ ورود 200؛ **ورود مدیر با رمز تولیدشده** ok:true؛ ورود کارمند ali/1234 → توکن؛ لیست مشتریان؛ **چک‌این با فرمت دقیق multipart اپ اندروید** (X-Medpad-Token) ثبت شد و در API ادمین دیده شد؛ سوکت: ناشناس REJECTED + احرازشده ACCEPTED (لاگ rt)؛ پورت غلط→refused، IP غلط→timeout، رمز غلط→خطای فارسی JSON؛ دسترسی با IP شبکهٔ محلی 200؛ استاپ→پورت‌ها آزاد→ری‌استارت→داده (چک‌این) ماندگار
- **شبیه‌سازی مسیر ویندوز** (`MEDPAD_SIM_WIN=1`): نصب exit 0 + هر ۶ فایل bat ویندوزی تولید شد (ASCII+CRLF تأییدشده) — سیمولیشن لینوکسی، نه ویندوز واقعی
- **ساخت APK**: سیستم فقط JRE داشت (بدون javac/jlink، بدون sudo) → Temurin JDK 21.0.13 portable در سندباکس؛ SDK: cmdline-tools 11076708 + platform-tools + platforms;android-34 + build-tools;34.0.0؛ `assembleDebug` → **BUILD SUCCESSFUL**؛ تأیید aapt: `com.medpad.tracker` versionCode **11** versionName **1.9.12-dev.1** minSdk 24 target 34؛ تأیید apksigner: **v2 VERIFIED، امضاکننده CN=Android Debug, O=Android, C=US (کلید Debug استاندارد — نه keystore افشاشده، نه medpad123)**؛ حجم ~7.5MB
- **تست‌های موجود اندروید**: هیچ تست واحدی در پروژه نیست (فقط `src/main`) — گزارش صادقانه در DEVELOP-TEST-REPORT
- **محدودیت‌ها**: ویندوز واقعی/Emulator/دستگاه اندرویدی در سندباکس غایب — دستورالعمل تست مالک در گزارش؛ تست‌های لینوکسی/سیمولیشن به‌عنوان تست ویندوز معرفی نشدند
- **خروجی نهایی**: ZIP وب + APK + `SHA256SUMS.txt` + `DEVELOP-TEST-REPORT-fa.md` → GitHub **Pre-release با tag `develop-v1.9.12-test.1`** روی کامیت مبنا (Artifacts از همان کامیت)؛ `main` دست‌نخورده

Stage Summary:
- ریلیز آزمایشی develop کامل شد: 94/94 تست وب PASS + E2E واقعی (نصب/لاگین/چک‌این/سوکت/خطاها/ماندگاری) + APK امضاشده با کلید Debug استاندارد تأییدشده با apksigner/aapt
- دو رگرسیون واقعی (CRLF + کامپایل DSL) پیدا و فیکس شد و گارد رگرسیون به سوئیت اضافه شد
- محدودیت صادقانه: ویندوز واقعی و دستگاه اندرویدی تست نشده (دستورالعمل مالک در گزارش)

---
Task ID: 20
Agent: main (Z.ai)
Task: پورت فیکس‌های v1.9.12-test.2 (EPERM کپی دیتابیس + فریز صفحهٔ نصب گرافیکی — دو باگ گزارش‌شدهٔ مالک روی test.1) روی درخت سخت‌سازی‌شدهٔ develop + پوش به ریپو (درخواست صریح مالک: «این پروژه رو روی این ریپو پوش کن»)

Work Log:
- منبع: بستهٔ src مربوط به v1.9.12-test.2 (ساختهٔ Task 14 جلسهٔ گمشده) از کانال پشتیبان x0.at بازیابی و با SHA1 ثبت‌شده راستی‌آزمایی شد (c3c040ff0f16dbe615528f9996c7f2ee41b46a9d — بایت‌به‌بایت مطابق)
- تحلیل تقاطعی: test.2 روی پایهٔ v1.9.11 ساخته شده بود (سورس test.1 آن زمان در سندباکس گم شده بود)؛ درخت develop ریپو = نسخهٔ سخت‌سازی‌شدهٔ کامل (فاز ۰-۷: bcrypt، ایمنی پروسه‌ها، rate limit، ایمنی Cloudflare، پنل بازساخته) ⇒ تصمیم: پیوند جراحی فیکس‌ها روی develop، نه جایگزینی فایل‌ها
- ریشه‌یابی EPERM در همین درخت: خط کپی template.db با cpSync خالی (بدون retry) + lockDown یوزرنیم‌محور (icacls /inheritance:r + grant بر اساس USERNAME) که «قبل از» کپی/seed روی پوشهٔ db اعمال می‌شد — ACL خراب = قفل روی همان فایلی که مرحلهٔ بعدی لازم داشت
- تغییرات install.mjs (+۲۸۵/−۴۵):
  * copyFileRobust: ۱۰ تلاش با backoff فزاینده (300ms→5s) + fallback کپی جریانی + fallback «cmd /c copy» + راستی‌آزمایی size هر مسیر موفق + حذف مقصد stale قبل از شروع
  * قرنطینهٔ DB خراب: custom.db صفربایتی/کوچک‌تر از 8KB (باقی‌ماندهٔ کرش) → تغییرنام به custom.db.broken-<ts> (غیرمخرب) + die فقط اگر قفل باشد
  * پاک‌سازی sidecarهای stale شدهٔ sqlite (journal/wal/shm) قبل از کپی قالب
  * پیام die جدید با راهنمای آنتی‌ویروس (استثنا دادن پوشه / اجرای دوباره)
  * lockDown: شاخهٔ ویندوزی حذف شد (chmod لینوکس دست‌نخورده — fail-secure) → ACL ویندوز به «انتها» منتقل شد
  * بلوک جدید بعد از Step 7: restrictAcl فقط روی win32 واقعی — grant با SID حساب (*S-1-5-...، مصون از یوزرنیم/بومی‌سازی)، راستی‌آزمایی با probe واقعی فایل، auto-revert با icacls /reset، هرگز fatal نیست
  * seed/finalize: رمز ادمین فقط از ENV (MEDPAD_ADMIN_PASS — قبلاً در command line قابل‌مشاهده بود) + ۳ تلاش با backoff + شکست = هشدار بلند با راهنمای اجرای دوباره (شکست بی‌صدا روی نصب تازه = قفل‌شدن کامل پنل)
  * خط ماشین‌خوان [RANDOM-ADMIN-PASS] برای ویزارد مرورگر (فقط بعد از seed موفق)
- تغییرات ui/installer.html (+۴۲): بنر خطای JS سراسری (#jsErr + window error + unhandledrejection — کلاس باگ «دکمه‌ها بی‌صدا می‌میرند» برای همیشه بسته شد) + هشدار بلند شکست api/status + پارس [RANDOM-ADMIN-PASS] از استریم نصب و نمایش رمز تولیدشده در صفحهٔ پایان + راهنمای EPERM/آنتی‌ویروس در showFail — همهٔ فیچرهای امنیتی موجود (passProblem، cfConfirm، الزام رمز عمومی) دست‌نخورده
- install.bat/ps1/sh: فقط کامنت‌های نسخه v1.9.11→v1.9.12 (قانون طلایی ۴: ASCII خالص + CRLF + بدون BOM راستی‌آزمایی شد)
- اعتبارسنجی کامل:
  * سوئیت کامل `audit-tests/run-all.sh` روی درخت مرج‌شده: **ALL-SUITES-PASS — 46/46 چک v1912 (نصب/بوت/لاگین/rate limit/سوکت/T5 آپدیت روی نصب در حال اجرا/T6 مجوزها/T8 کلادفلر) + 18 process-safety + 15 wizard + 15 realtime**
  * X1 بازیابی DB خراب: custom.db صفر‌بایتی → قرنطینه + قالب تازه + exit 0 ✓
  * X2 رمز تصادفی: نصب لوکال بدون رمز → [RANDOM-ADMIN-PASS] چاپ شد ✓
  * W1-W3 ویزارد: installer.html با بنر جدید سرو شد (id="jsErr") + api/status سالم + نصب کامل از API با استریم زنده → INSTALL COMPLETE + رمز تصادفی در استریم + دادهٔ نمونه در DB (ali/sara/mahla + ۳ مشتری + ۶ انتساب + هش bcrypt$) ✓
- محدودیت صادقانه: ویندوز واقعی در سندباکس غایب — مسیر restrictAcl/copyFileRobust روی ویندوز واقعی توسط مالک تست شود (رفتار لینوکسی + سیمولاسیون کامل تست شد)

Stage Summary:
- هر دو باگ گزارش‌شدهٔ مالک روی develop-v1.9.12-test.1 (EPERM مرحلهٔ ۳ + گیرکردن صفحهٔ نصب گرافیکی) ریشه‌یابی و روی درخت سخت‌سازی‌شده فیکس شد — بدون از دست دادن هیچ فیچر امنیتی فاز ۰-۷
- همهٔ ۹۴ چک خودکار + تست‌های هدفمند جدید PASS؛ آمادهٔ پوش به develop و تست ویندوز واقعی توسط مالک

---
Task ID: 21
Agent: main (Z.ai)
Task: فاز ۱ / تسک ۱ — ممیزی عملکردی کامل هسته، شناسایی و رفع باگ‌های تأییدشده، بازسازی باندل پنل و قفل رگرسیون

Work Log:
- مبنا ثبت شد: develop @ b717237 (نوک origin، درخت تمیز)؛ run-all.sh روی مبنا: ALL-SUITES-PASS (18+15+15+46)
- ممیزی کد: هر ۲۵ هندلر API (فرم کامپایل‌شدهٔ باندل + سورس TS از تاریخچهٔ bd9c80b)، realtime/index.js، ۱۹ فایل Kotlin اپ، اسکیما Prisma، اسکریپت‌های نصب
- باگ‌های تأییدشده (با تست بازتولید روی بیلد قدیمی: ۹ FAIL هدفمند):
  * P1-T1: زمان واقعی صف آفلاین گم می‌شد (createdAt = زمان سینک نه زمان رویداد؛ پنجرهٔ dedup هم غلط مقایسه می‌شد)
  * P1-T2: DELETE کارمند با سابقه = 500 (FK restrict روی Checkin/Visit)
  * P2-T3: نشت هش bcrypt رمز کارمندان در GET/POST/PUT /api/employees
  * P0-T4 (احتیاطی): next@16.3.8 با ۳ advisory بحرانی/بالا (RCE در مسیرهای استفاده‌نشده) — تنها پکیج آسیب‌پذیرِ ship‌شده
- فیکس‌های سورس پنل (سورس سخت‌شده از تاریخچهٔ گیت بازیابی شد، اصلاح و rebuild):
  * lib/client-time.ts جدید: resolveEventTime با پنجرهٔ سلامت (۲ دقیقه آینده / ۷ روز گذشته، خارج = fallback ساعت سرور)
  * checkin/checkout: فیلد اختیاری clientAt (ISO 8601) → createdAt + startAt/endAt ویزیت‌ها از زمان رویداد + گارد endAt ≥ startAt
  * employees: select بدون password در GET/POST/PUT؛ DELETE → 409 قابل‌اقدام با شمارش سابقه (بدون سابقه = حذف مثل قبل)
  * ارتقای next → 16.4.3 (جراحی‌شده، فقط همین پکیج)
- بازسازی باندل: next build standalone + static + public + дерefence سیم‌لینک‌های node_modules داخلی (درس f4425ac) + حذف .env ساخت build + راستی‌آزمایی سخت‌سازی بیلد جدید (صفر admin123، صفر fallback بدون گیت، rate-limit و clientAt و bcrypt در chunks، فونت ۹تایی، literal :3003 برای پچ نصب‌کننده، انجین‌های Prisma ویندوز+دبیان، بدون سیم‌لینک)
- فیکس اپ اندروید: ApiClient.checkin/checkout پارامتر clientAt + isoTime()؛ CheckinSync ارسال item.createdAt — پروتکل backward-compatible
- وصلهٔ سورس پنل در مخزن: patches/panel-src-phase1-task1.patch (مبنای bd9c80b؛ git apply --check سبز) تا rebuildهای آینده فیکس‌ها را گم نکنند
- تست جدید: audit-tests/test-functional-core.sh — ۳۴ چک عملکردی (auth اپ، نشت هش، حذف کارمند، idempotency، clientAt در/خارج پنجره + راستی‌آزمایی مستقیم Visit در SQLite، اعتبارسنجی checkout، جداسازی داده، rate-limit اپ) + افزودن به run-all.sh (الان ۵ سوئیت)
- نتیجهٔ نهایی: **ALL-SUITES-PASS — 18 + 15 + 15 + 46 + 34 = ۱۲۸ چک سبز**
- امنیت: اسکن اسرار در فایل‌های tracked → پاک؛ هیچ پکیج آسیب‌پذیر شناخته‌شده‌ای در باندل runtime ارسالی نماند؛ یافته‌های باز (G-1 تا G-6) با دلیل در PHASE1-TASK1-REPORT-fa.md ثبت شد
- محدودیت‌های صادقانه: تست روی دستگاه اندروید واقعی و ویندوز واقعی در این محیط ممکن نبود (سطح HTTP سرور برای clientAt کامل تأیید شد؛ سمت اپ فقط سورس)

Stage Summary:
- ۴ باگ تأییدشده رفع شد (بزرگ‌ترین: صحت زمان حضور و غیاب در سناریوی آفلاین — قلب محصول) + ارتقای امنیتی Next
- گزارش کامل: PHASE1-TASK1-REPORT-fa.md — وضعیت کلی: READY برای تسک ۲
- تسک پیشنهادی بعدی: چرخهٔ عمر نشست اپ + کنترل دسترسی مشتریان + UI مدیریت کارمند (G-1/G-2/G-3)

---
Task ID: 22
Agent: main (Z.ai)
Task: فاز ۱ / تسک ۱ (ادامه) — راستی‌آزمایی مستقل پس از ریست سندباکس: کلون تازه، تأیید ریموت و اجرای مجدد کل ۱۲۸ چک

Work Log:
- ریست ششم سندباکس: کلون محلی و درخت audit/panel-src-hardened حذف شدند؛ فقط upload/ (توکن فعال + پشتیبان worklog + سیاست Git) و skills/ زنده ماندند
- احراز هویت با توکن فعال از credential store فایل لوکال (۶۰۰) بازسازی شد — توکن هرگز در URL ریموت یا خروجی ترمینال قرار نگرفت؛ کلون تازه: https://github.com/P3D4AM/MedPad.git → develop
- تأیید وضعیت ریموت (git fetch + GitHub API):
  * develop = 58b45f7 «docs: phase 1 / task 1 audit report + worklog» = نوک origin — هر ۴ کامیت تسک ۲۱ موجود و سالم: 010ad64 (panel) · 0e025f1 (android) · b4beaf9 (tests) · 58b45f7 (docs)
  * main = 8db3319 — دست‌نخورده (تطابق با مبنا)
  * PHASE1-TASK1-REPORT-fa.md (۱۱۳ خط) و AGENTS.md و patches/panel-src-phase1-task1.patch همگی در درخت مخزن موجودند
- اجرای مجدد مستقل کل سوئیت روی محیط تازه (بدون هیچ کش از جلسه قبل):
  * فرمان: bash audit-tests/run-all.sh (روی کلون تازه از develop @ 58b45f7)
  * نتیجه: خروج ۰ — **ALL-SUITES-PASS** — process-safety ۱۸ ✓ + wizard-security ۱۵ ✓ + realtime-security ۱۵ ✓ + test-v1912 ۴۶/۴۶ ✓ + test-functional-core ۳۴/۳۴ ✓ = **۱۲۸ چک سبز**
  * سوئیت عملکردی شامل: auth اپ (رمز غلط/درست/بدون توکن/غیرفعال)، عدم نشت هش، حذف کارمند 409/200، idempotency، پنجرهٔ clientAt (در/خارج/خراب + راستی‌آزمایی مستقیم ردیف‌های Visit در SQLite)، اعتبارسنجی checkout، جداسازی داده، rate-limit اپ — همه روی پنل واقعیِ بوت‌شده (نصب fresh در /tmp، پورت‌های 3240/3243)
- پاک‌سازی پس از تست: /tmp/tfunccore حذف؛ پورت‌ها آزاد
- گزارش نهایی فارسی طبق قالب بخش ۱۱ دستور تسک، به کاربر در چت تحویل شد

Stage Summary:
- وضعیت READY تسک ۱ به‌طور مستقل بازتولید شد: روی کلون تازه از GitHub، هر ۵ سوئیت و ۱۲۸ چک بدون هیچ خطا سبز شدند — شواهد تسک ۲۱ فقط به کش سندباکس قبلی وابسته نیست
- develop روی GitHub همچنان 58b45f7 است؛ main دست‌نخورده؛ هیچ تغییر کدی در این جلسه لازم نبود (فقط مستندسازی راستی‌آزمایی)
- محدودیت‌های قبل همچنان معتبر: دستگاه اندروید واقعی و ویندوز واقعی در محیط تست نبودند

---
Task ID: 23
Agent: main (Z.ai)
Task: فاز ۱ / تسک ۲ — رفع شش یافتهٔ باز ممیزی (G-1 تا G-6): مجوز دسترسی، چرخهٔ عمر توکن، مدیریت کارمند، مجوز صوت، خروج مدیر، اعتبارسنجی GPS

Work Log (بخش ۱ — راستی‌آزمایی یافته‌ها روی کد فعلی develop @ a9119cb):
- محیط: کلون تازه + بازیابی سورس پنل از تاریخچه (bd9c80b + patches/panel-src-phase1-task1.patch اعمال شد و تمیز پچ شد) — سورس با باندل ارسالی هم‌خوان است (نسخهٔ next باندل 16.4.3؛ لاک سورس 16.3.8 → این بار package.json/lock هم داخل وصلهٔ فاز ۲ ثبت می‌شود تا بازتولید کامل باشد)
- CI: ریپو هیچ GitHub Workflow‌ای ندارد (.github وجود ندارد) → سوئیت‌های محلی audit-tests معادل CI هستند؛ در گزارش نهایی تصریح می‌شود
- یافته‌ها همه روی کد فعلی تأیید شدند (بدون اتکا به گزارش قبلی):
  * G-1 ✔ تأیید: GET /api/app/customers همهٔ مشتریان را به هر کارمند می‌دهد؛ PUT /api/app/customers/[id] بدون چک انتساب؛ checkin فقط وجود مشتری را چک می‌کند نه انتساب (proximity چک انتساب دارد ✓). سمت اپ: نقشه همهٔ مشتریان را کش می‌کند
  * G-2 ✔ تأیید: AppToken بدون expiresAt/revokedAt؛ appEmployee() هیچ انقضایی چک نمی‌کند؛ هر ورود توکن جدید تجمعی می‌سازد؛ هیچ API ادمینی برای ابطال نیست؛ اپ روی 401 فقط سینک را متوقف می‌کند و در حالت logged-in می‌ماند
  * G-3 ✔ تأیید: PUT /api/employees/[id] وجود دارد (name/phone/password/active) اما بدون اعتبارسنجی، بدون audit log، غیرفعال‌سازی توکن‌ها را باطل نمی‌کند (فعال‌سازی مجدد، توکن‌های قدیمی را زنده برمی‌گرداند)؛ UI فقط افزودن+انتساب دارد — بدون ویرایش/غیرفعال‌سازی/فعال‌سازی مجدد/بازنشانی رمز/ابطال
  * G-4 ✔ تأیید: در GET /api/media/[...path] چک مالکیت فقط وقتی رکورد Checkin مرجع وجود دارد اعمال می‌شود → فایل یتیم برای هر کارمند احرازشده قابل دانلود است؛ ادمین همه‌چیز می‌تواند ✓؛ safeName تراورسال را می‌بندد ✓
  * G-5 ✔ تأیید: POST /api/logout بدون هیچ احراز هویت است — هر کسی در شبکه می‌تواند نشست مدیر را باطل کند؛ معماری نشست مدیر تک‌نشستی است (Config.adminSession یک اسلات)
  * G-6 ✔ تأیید و بدتر از گزارش قبلی: سرور در checkin مختصات مشتری را جای GPS نامعتبر می‌گذارد (lat: valid ? lat : customer.lat)؛ و سمت اپ CheckinDialogFragment اصلاً GPS دستگاه را نمی‌فرستد — مختصات مشتری را می‌فرستد (کامنت "use customer coords server-side")؛ هیچ مفهوم accuracy وجود ندارد؛ هیچ سیاست فاصله سمت سرور روی checkin نیست (radius فقط برای geofence پروکسیمیتی اپ است، پیش‌فرض 150m)
- طرح اجرا (۶ کامیت مستقل + تست برای هر یافته + بازسازی باندل در هر کامیت مؤثر):
  * G-1: فیلتر انتساب سمت سرور در GET customers (فقط انتساب‌یافته‌ها) + چک انتساب در PUT و checkin + پاک‌سازی کش محلی اپ در سینک کامل؛ ادمین همه‌چیز
  * G-2: ستون‌های expiresAt/revokedAt روی AppToken + TTL قابل‌تنظیم ادمین (Config appTokenTtlHours، دامنهٔ 0..8760، پیش‌فرض 720=30 روز؛ 0 = بدون انقضا) + چک در appEmployee + API ابطال ادمین + endpoint تنظیمات ادمین + اپ: رفتار 401 → هدایت به ورود مجدد؛ توکن‌های موجود (expiresAt=NULL) معتبر می‌مانند
  * G-5: /api/logout نیازمند نشست ادمین + /api/logout-all جدا با دامنهٔ روشن (تک‌نشستی: هر دو اسلات adminSession را پاک می‌کنند؛ تفاوت دامنه مستند می‌شود)
  * G-3: endpointهای deactivate/reactivate/revoke-tokens + PUT سخت‌شده (اعتبارسنجی) + بازنشانی رمز با ابطال توکن‌ها + مدل AuditLog + نمایشگر ادمین + UI کامل چرخهٔ عمر در EmployeesTab؛ غیرفعال‌سازی = ابطال فوری توکن‌ها؛ فعال‌سازی مجدد توکن‌ها را برنمی‌گرداند
  * G-4: مجوز صوت = مالکیت رکورد + انتساب مشتری؛ یتیم فقط ادمین (لیست بازبینی ادمین: GET /api/admin/media-orphans)؛ حذف خودکار ممنوع
  * G-6: حذف جایگزینی مختصات مشتری (null = بدون موقعیت + locationMissing) + فیلد accuracy از اپ + تحمل دقت قابل‌تنظیم (gpsMaxAccuracyM، پیش‌فرض 50m، 0=خاموش) + فاصله بر اساس radius موجود مشتری (مکانیزم ادمینی موجود، پیش‌فرض 150m) + پرچم outOfRadius + حالت سخت‌گیرانهٔ اختیاری (gpsStrictRadius، پیش‌فرض خاموش = ثبت با پرچم) + ستون‌های Checkin: accuracy/distanceM/outOfRadius/locationMissing + اپ: ارسال موقعیت واقعی دستگاه (TrackingService.lastLocation با accuracy) نه مختصات مشتری + مهاجرت Room
  * مهاجرت DB: instrumentation.ts (register) با ALTER TABLEهای idempotent برای نصب‌های موجود + بازتولید template.db برای نصب تازه؛ اسکریپت build-panel.sh با ۹ چک سخت‌سازی
- محدودیت محیط (ثابت): دستگاه اندروید واقعی/ویندوز واقعی در دسترس نیست — تغییرات اپ در سطح سورس + تست رفتار سرور در سطح HTTP؛ APK بیلد نمی‌شود (SDK غایب)

Work Log (بخش ۲ — Stage A اجرا و تست شد):
- G-1 کامیت 6e8d6a9 — فیلتر انتساب سمت سرور در GET /api/app/customers (فقط انتساب‌یافته‌ها) + 403 در PUT ویرایش مشتری و checkin بدون انتساب (قبل از لایه‌های idempotency تا replay نتواند دسترسی احیا کند) + اپ اندروید: تطبیق assignedIds در هر سینک + حذف آیتم 403 از صف آفلاین (DropAndContinue) — تست: ۳۳ چک G1 جدید
- G-2 کامیت a694ff7 — ستون‌های expiresAt/revokedAt + TTL ادمینی (appTokenTtlHours؛ 0..8760؛ پیش‌فرض 720؛ 0=بدون انقضا) + چک در هر درخواست appEmployee بدون کش + /api/admin/settings (GET/PUT با اعتبارسنجی) + /api/employees/[id]/tokens (لیست بدون افشای secret) + /tokens/revoke (ابطال فوری) + instrumentation.ts برای مهاجرت ALTER TABLE نصب‌های موجود + UI (دیالوگ تنظیمات + دکمه قطع دسترسی اپ) + اپ: sessionExpired روی 401 → دیالوگ ورود مجدد — تست: ۳۳ چک G2 جدید (شامل مهاجرت واقعی: حذف ستون‌ها → ری‌بوت → بازگشت ستون‌ها)
- G-5 کامیت 76e8d66 — /api/logout حالا isAdmin می‌خواهد + بدنه نادیده گرفته می‌شود + /api/logout-all جدا با scope=all (معماری تک‌نشستی مستند شد) — تست: ۱۴ چک G5 جدید
- تغییر test-functional-core.sh: انتساب alice قبل از جریان‌های checkin اضافه شد (تغییر سیاست G-1) — پوشش منفی در سوئیت جدید
- build-panel.sh (در سندباکس): ۱۲ چک سخت‌سازی بیلد (فونت/انجین‌ها/:3003/بدون symlink/بدون .env/…) — همه سبز در هر سه rebuild
- Stage A regression: bash audit-tests/run-all.sh → ALL-SUITES-PASS = ۱۸+۱۵+۱۵+۴۶+۳۴+۸۰ = ۲۰۸ چک، خروج ۰
- تذکر: هر سه کامیت شامل باندل بازسازی‌شده کامل است (BUILD_ID جدید در هر rebuild) + وصلهٔ سورس تجمعی patches/panel-src-phase2-remediation.patch (مبنای bd9c80b+فاز۱؛ این بار package.json/lock داخل وصله است)

Work Log (بخش ۳ — Stage B اجرا و تست شد):
- G-3 کامیت b37d919 — deactivate/reactivate اختصاصی (ابطال توکن + حفظ سابقه + audit؛ reactivation توکن‌ها را برنمی‌گرداند) + PUT سخت‌شده (اعتبارسنجی؛ تغییر رمز = ابطال توکن‌ها) + مدل AuditLog + نمایشگر + UI کامل EmployeesTab — تست: ۳۵ چک
- G-4 کامیت 27a85a9 — ماتریس مجوز صوت (مالک + مشتری منتسب؛ یتیم=۴۰۴ کارمند/فقط ادمین) + GET /api/admin/media-orphans + کشف و فیکس باگ مهم ازدست‌رفتن صوت‌ها در آپدیت (بکاپ/بازیابی standalone-uploads در install.mjs) — تست: ۳۰ چک شامل رگرسیون کامل آپدیت واقعی
- G-6 کامیت 60f3766 — src/lib/gps.ts (هرگز جایگزینی مختصات مشتری؛ دقت ناممکن/بدتر از تحمل → locationMissing) + ستون‌های Checkin + distanceM/outOfRadius بر اساس radius موجود مشتری + strict اختیاری (422) + تنظیمات gpsMaxAccuracyM/gpsStrictRadius + UI + اپ: DeviceGps (fix واقعی ≤۵ دقیقه با accuracy؛ Room v6→v7) — تست: ۳۵ چک شامل مهاجرت واقعی
- باگ‌های تست خودم در مسیر (ترتیب G2.5، پرانتز g6ago، json.load بدون open، برخورد با پنجرهٔ dedup ۹۰ ثانیه‌ای در G6) شناسایی و اصلاح شدند — سرور در همهٔ موارد درست عمل کرده بود
- Stage B regression: bash audit-tests/run-all.sh → ALL-SUITES-PASS = ۱۸+۱۵+۱۵+۴۶+۳۴+۱۸۰ = ۳۰۸ چک، خروج ۰
- گزارش کامل فارسی: PHASE1-TASK2-REPORT-fa.md

Stage Summary:
- شش یافتهٔ G-1..G-6 پیاده‌سازی، تست و در ۶ کامیت مستقل ثبت شدند: 6e8d6a9 · a694ff7 · 76e8d66 (Stage A) · b37d919 · 27a85a9 · 60f3766 (Stage B) + کامیت‌های docs
- جمع تست‌های نهایی: ۳۰۸ چک سبز (۱۲۸ چک قبلی + ۱۸۰ چک جدید Remediation) + ۱۲ چک سخت‌سازی بیلد در هر rebuild
- کشف جانبی مهم و فیکس‌شده: نابودی فایل‌های صوتی در هر آپدیت پنل (uploads واقعی داخل standalone بود و بکاپ نمی‌شد)
- محدودیت‌ها: تست دستگاه اندروید واقعی/ویندوز واقعی ممکن نبود؛ HTTP بدون TLS در LAN باقی است؛ GPS spoofing ذاتی حل‌شدنی نیست (مستند)

---
Task ID: 25
Agent: main (Z.ai)
Task: فاز ۲ / تسک ۱ — زیرساخت مدیریت برنامهٔ بازدیدهای میدانی (Planned Visits)
(توضیح شماره‌گذاری: Task 24 در worklog سندباکس برای جلسهٔ ثبت‌شدهٔ تسک G-1..G-6 استفاده شد؛ اینجا دنبالهٔ مستقیم Task 23 ریپو است)

Work Log:
- مبنا ثبت شد: develop @ 4ef31a1 (نوک origin، درخت تمیز)؛ main = 8db3319 دست‌نخورده
- بازیابی و راستی‌آزمایی سورس پنل (مرحلهٔ ۱ دستور):
  * زنجیرهٔ بازسازی موجود اجرا و تأیید شد: bd9c80b (medpad-full-1.9/inst-stage/panel-src از تاریخچهٔ گیت) + patches/panel-src-phase1-task1.patch + patches/panel-src-phase2-remediation.patch — هر دو وصله تمیز اعمال شدند
  * شکاف ردیابی کشف و مستند شد: وصلهٔ phase2-remediation فقط «تغییر فایل‌های موجود» را داشت؛ ۱۱ فایل جدید آن (settings.ts، gps.ts، audit.ts، instrumentation.ts و ۸ روت API) فقط در درخت کاری وجود داشتند و در وصله نبودند → در این تسک با وصلهٔ جدید بسته شد (پایین)
  * درخت کاری audit/panel-src (هم‌خوان با باندل ارسالی: نشانگرهای G-1..G-6 در هر دو، next 16.4.3) مبنای توسعه قرار گرفت
- مدل داده (مرحلهٔ ۲):
  * PlannedVisit جدید در prisma/schema.prisma: employee (RESTRICT) + customer (RESTRICT) + scheduledAt + durationMin (۵..۴۸۰، پیش‌فرض ۳۰) + priority (low|normal|high) + status (scheduled|done|cancelled|needs_reschedule) + note + createdById (نام کاربری ادمین هنگام ایجاد) + createdAt/updatedAt
  * پیوند ۱:۱ برنامه↔بازدید واقعی: کلید خارجی visitId با UNIQUE روی خود PlannedVisit → هیچ ستونی به Visit اضافه نمی‌شود؛ مهاجرت نصب‌های موجود فقط CREATE TABLE IF NOT EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS است (بدون دست‌کارسازی جداول موجود)
  * DDL مهاجرت با prisma db push روی دیتابیس پروب تولید و با خروجی واقعی تطبیق داده شد (بایت‌به‌بایت) — در instrumentation.ts
  * تصمیم‌های وضعیت: «done» فقط توسط سرور از طریق پیوند چک‌این تنظیم می‌شود (ادمین نمی‌تواند دستی «انجام‌شده» بزند تا شمارش انجام‌شده همیشه = بازدیدهای واقعیِ پیوندخورده باشد)؛ «cancelled» پایانی است؛ «reschedule» همان ردیف را به زمان جدید می‌برد و وضعیت را به scheduled برمی‌گرداند
- API و مجوزها (مرحلهٔ ۳) — همه admin-only (بررسی سمت سرور با isAdmin) و همه audit-دار:
  * GET /api/planned-visits: نمای روز (پیش‌فرض امروزِ سرور) یا بازهٔ from/to + فیلتر employeeId/customerId/status (اعتبارسنجی شده)؛ هر ردیف شامل بازدید پیوندخورده + actualVisit (بازدید واقعی هم‌روزِ بدون پیوند برای آشکارسازی پیوندهای ازدست‌رفته)؛ سقف ۵۰۰ + truncated؛ window.dayKey برای ناوبری روز
  * POST /api/planned-visits: اعتبارسنجی کامل (وجود/فعال‌بودن کارمند، وجود مشتری، بازهٔ منطقی زمان ±۱ سال گذشته/۲ سال آینده، مدت، اولویت، طول توضیح) + گارد تکرار (همان کارمند+مشتری+زمان دقیق در وضعیت scheduled → 409) + status همیشه scheduled
  * GET/PUT /api/planned-visits/[id]: جزئیات؛ ویرایش فقط برای برنامه‌های باز؛ status محدود به دو وضعیت باز؛ بسته‌شدن نهایی با updateMany شرطی (امن در برابر هم‌زمانی)
  * POST .../cancel: لغوی پایانی + دلیل اختیاری (به note اضافه می‌شود، جایگزین نمی‌کند) + رد لغو برنامهٔ done
  * POST .../reschedule: زمان جدید (الزامی) + مدت/یادداشت اختیاری؛ وضعیت → scheduled
  * چک‌این (app/checkin): پیوند خودکار — نخستین برنامهٔ scheduled همان کارمند+مشتری در همان روزِ محلیِ سرورِ زمان رویداد → status=done + visitId؛ updateMany شرطی + UNIQUE ایندکس = مقاوم در برابر رخداد هم‌زمان؛ برنامه‌های لغوشده/نیازمند برنامه‌ریزی مجدد هرگز لمس نمی‌شوند؛ رخداد realtime با فیلد planLinked غنی‌تر شد
  * اینترلاک حذف: حذف کارمند حالا شمارش برنامه‌ها را هم می‌بیند (409 قابل‌اقدام)؛ حذف مشتری گارد 409 جدید گرفت (ویزیت‌ها + برنامه‌ها) — باگ ازپیش‌موجود 500 روی FK ویزیت هم بسته شد
- رابط کاربری (مرحلهٔ ۴) — تب جدید «برنامهٔ بازدیدها» در Dashboard (فارسی، راست‌چین، هم‌سبک پنل):
  * نمای روز: دکمه‌های قبلی/بعدی/امروز + جعبهٔ تاریخ + برچسب شمسی روز با همان مسیر fa-IR موجود (faDate جدید در realtime.ts — بدون کتابخانه یا تبدیل تاریخ جدید)
  * فیلتر کارمند/مشتری/وضعیت + خلاصهٔ عددی روز (انجام‌شده/باقی‌مانده/عقب‌افتاده/نیازمند برنامه‌ریزی مجدد/لغوشده) — بدون نمودار تزئینی
  * دیالوگ‌های ایجاد/ویرایش/برنامه‌ریزی مجدد/لغو(با دلیل)؛ هایلایت ردیف عقب‌افتاده؛ نشان «بازدید واقعی: ساعت» برای انجام‌شده‌ها؛ نشان «بازدید ثبت‌شده بدون پیوند»؛ هشدار «منتسب نیست» هنگام انتخاب جفت کارمند+مشتری بدون انتساب (چون چک‌این توسط سرور رد می‌شود)
  * زمان‌ها با scheduledAtLocal (آینهٔ YYYY-MM-DDTHH:mm محلیِ سرور که API برمی‌گرداند) — مرورگر کاملاً از محاسبهٔ منطقهٔ زمانی خارج است
- سازگاری آفلاین و دیتابیس (مرحلهٔ ۵):
  * صفر وابستگی npm جدید (package.json دست‌نخورده)؛ نصب‌کننده و سرویس‌ها بدون تغییر؛ هیچ فایل نصب آفلاین حذف نشد
  * template.db برای نصب تازه بازتولید شد (جدول PlannedVisit + ایندکس یکتا داخلش)
  * مسیر ارتقا: بوت روی کپی یک دیتابیس قدیمی (قبل از PlannedVisit) اجرا شد — مهاجرت اجرا، جدول با ۱۲ ستون و ایندکس ساخته شد، جداول/ستون‌های موجود (شامل G-6) دست‌نخورده ماندند
  * بازسازی باندل: build-panel.sh → next build standalone + همگام‌سازی + template.db + هر ۱۲ چک سخت‌سازی سبز؛ BUILD_ID بیرونی هم‌گام شد (شکاف کوچک اسکریپت که فقط می‌خواند)
- وصلهٔ سورس: patches/panel-src-phase2-task1.patch (۲۴۳۷ خط) — شامل تغییرات این تسک + ۱۱ فایل جدید فاز ۱/تسک ۲ که در وصلهٔ قبلی نبودند؛ زنجیرهٔ کامل روی استخراج تازه از bd9c80b اعمال و خروجی آن با درخت کاری بایت‌به‌بایت یکسان تأیید شد
- commit ساخته شد و به develop پوش شد: **5a41f84** «feat(phase2/task1): planned visit management...» — تأیید مستقل با GitHub API: develop@5a41f84، main@8db3319 دست‌نخورده؛ درخت تمیز؛ بدون force-push
- طبق دستور مالک: هیچ مجموعه‌آزمونی اجرا نشد و هیچ نتیجه‌آزمونی «سبز» اعلام نمی‌شود؛ فقط بررسی یکپارچگی بوت (سرور روی دیتابیس قدیمی بالا آمد، مهاجرت اعمال شد، /api/planned-visits و /api/stats هر دو 401 برمی‌گردانند نه 500) به‌عنوان کنترل سلامت تحویل انجام و صادقانه تفکیک شد

Stage Summary:
- قابلیت مدیریت برنامهٔ بازدید به‌صورت کامل سمت سرور + رابط مدیریتی فارسی پیاده و در باندل آفلاین ساخته شد؛ مدل، API، UI، مهاجرت دو-مسیره (نصب تازه/ارتقا) و زنجیرهٔ بازسازی سورس همگی آمادهٔ آزمون مالک
- فایل‌های تغییرکرده در مخزن: باندل app/panel/.next (بازسازی) + db/template.db + prisma-schema.prisma + patches/panel-src-phase2-task1.patch (جدید) + WORKLOG + گزارش PHASE2-TASK1-REPORT-fa.md
- آزمون‌نشده (صریح): کل audit-tests/run-all.sh، رفتار HTTP کامل APIهای جدید، اپ اندروید (بدون تغییر در این تسک)، ویندوز/دستگاه واقعی
- محدودیت‌ها: منطقهٔ زمانی مرورگرِ ادمین با سرور فرق دارد (زمان‌ها محلیِ سرور تعریف می‌شوند)؛ اپ اندروید هنوز برنامه‌ها را نمایش نمی‌دهد (گام بعدی محصولی)

---
Task ID: 26
Agent: main (Z.ai)
Task: فاز ۲ / تسک ۲ — اتصال اپ اندروید به برنامه‌های بازدید + انتشار نسخهٔ Dev (پنل + APK)

Work Log:
- مبنا ثبت شد: develop @ 87c93de (نوک origin، درخت تمیز)؛ main = 8db3319 دست‌نخورده؛ سندباکس ریست شده بود → کلون تازه + بازسازی سورس پنل از زنجیرهٔ وصله‌ها (bd9c80b → phase1-task1 → phase2-remediation → phase2-task1) — پیش از هر تغییری
- API امن اپ (بخش ۲.۱): GET /api/app/planned-visits جدید — هویت فقط از توکن اپ (appEmployee: منقضی/باطل/غیرفعال → 401)؛ employeeId سمت سرور روی توکن قفل است (پارامتر ارسالی کلاینت اصلاً خوانده نمی‌شود)؛ برنامه‌های سایر کارمندان هرگز برگردانده نمی‌شوند؛ اطلاعات تماس مشتری (نام/نشانی/تلفن) فقط برای مشتریان «در انتساب فعلی» افشا می‌شود — برنامهٔ مشتری منتسب‌نشده می‌آید ولی مشتری mask می‌شود (assigned:false + nullها)؛ بازه: ?date یا ?from&to (حداکثر ۳۱ روز؛ تاریخ خراب → 400 صریح نه سقوط به امروز)؛ سقف ۲۰۰ ردیف + truncated؛ هر ردیف آینه‌های محاسبه‌شدهٔ سرور را دارد (dayKey/timeLocal/visitStartLocal) تا اپ هیچ محاسبهٔ منطقهٔ زمانی نکند؛ overdue روی ساعت سرور
- رابط کاربری اپ (بخش ۲.۲): PlansActivity جدید فارسی/راست‌چین (layoutDirection=rtl روی ریشه) — کارت «برنامهٔ بازدیدها» در صفحهٔ اصلی؛ ناوبری روز (قبل/بعد/امروز) با برچسب شمسی و «امروز» = امروزِ سرور (window.today)؛ خلاصهٔ چیپ‌های روز (انجام‌شده/باقی‌مانده/عقب‌افتاده/نیازمند برنامه‌ریزی مجدد/لغوشده)؛ بخش‌بندی فهرست: باقی‌مانده (اول، قابل‌اقدام) → انجام‌شده (با «بازدید واقعی: ساعت») → نیازمند برنامه‌ریزی مجدد → لغوشده؛ دیالوگ جزئیات: نوع/نشانی/تلفن (تماس با ACTION_DIAL بدون مجوز) + یادداشت + دکمهٔ «شروع بازدید (چک‌این)» فقط برای برنامهٔ بازِ مشتریِ منتسب؛ حالت‌های بارگذاری/خالی/خطا/401 (دیالوگ ورود مجدد)؛ هدف‌های لمسی ≥44dp برای کار یک‌دستی
- رفتار ثبت بازدید (بخش ۲.۳): چک‌این فقط از مسیر موجود CheckinDialogFragment (همان GPS واقعی + صدا + صف + idempotency)؛ فقط پارامتر اختیاری preselectCustomerId اضافه شد (کلاینت قدیمی بدون تغییر کامپایل می‌شود)؛ برنامهٔ لغوشده/غیرمنتسب دکمهٔ ثبت ندارد؛ «انجام‌شده» فقط از سمت سرور (پیوند چک‌این) می‌آید — اپ هرگز برنامه را locally «done» نمی‌کند؛ بعد از ثبت موفق → sync(true) دوباره وضعیت را از سرور می‌گیرد؛ اگر چک‌این در صف ماند → توست صادقانه «پس از اتصال به‌روز می‌شود»
- آفلاین (بخش ۳): جدول Room جدید planned_visits (v7→v8 — فقط CREATE TABLE جدید، هیچ جدول/ستون موجودی لمس نمی‌شود؛ DDL با اسکیمای انتظاری Room هم‌خوان)؛ PlansSync: پنجرهٔ device-today−8 تا +15 (حاشیهٔ ±1 روز اختلاف منطقهٔ زمانی دستگاه/سرور) → جایگزینی اتمی کل جدول در withTransaction (کش فقط-خواندنی است؛ سرور مرجع کامل پنجره است)؛ آخرین زمان همگام‌سازی + server_today در SharedPreferences؛ خطای 401 همان سیگنال CheckinSync.sessionExpired را تغذیه می‌کند (markSessionExpired)؛ sync برنامه‌ها هرگز pending_checkins را لمس نمی‌کند (جدول/DAO جدا — تفکیک ساختاری)؛ سینک در onResume اپ برنامه‌ها + onAvailable شبکه
- نسخهٔ Dev پنل (بخش ۴): APP-VERSION و INSTALLER_VER و بنرهای bat/ps1/sh و WIZARD_VER و MANAGER_VER و fallbackهای UI → 1.9.13-dev.1 (در رابط پنل از طریق Config.panelVersion که نصب‌کننده می‌کارد، به‌شکل Dev دیده می‌شود)؛ بازسازی کامل باندل از سورس: npm install + prisma generate (هر دو انجین win/debian) + next build standalone + template.db تازه (جدول PlannedVisit داخلش، Config خالی) + ۱۳ چک سخت‌سازی سبز (بدون admin123، بدون dev-token گیت‌شده، ۹ فونت، بدون jsdelivr، literal :3003، انجین‌ها، بدون symlink — با cp -rL مطابق باندل‌های قبلی، بدون .env، bcryptjs external، next 16.4.3، BUILD_ID همگام، روت برنامه‌ها در بیلد)؛ وصلهٔ سوم تجمعی جدید: patches/panel-src-phase2-task2.patch — زنجیرهٔ کامل روی استخراج تازه اعمال و با درخت کاری بایت‌به‌بایت یکسان تأیید شد
- APK (بخش ۵): محیط ساخت بازسازی شد (JDK Temurin 21.0.13 + cmdline-tools + build-tools 34.0.0 + platform android-34 + Gradle wrapper 8.7)؛ فیکس‌های لازم: exec-bit روی gradlew (در گیت 100644 بود — حالا 100755) + فیکس دو خطای کامپایل Kotlin (GregorianCalendar.getInstance → constructor؛ ترتیب پارامترهای CheckinDialogFragment برای trailing-lambda)؛ BUILD SUCCESSFUL (exit 0)؛ خروجی واقعی 7,894,621 بایت؛ apksigner verify: v2 VERIFIED با کلید Debug استاندارد (CN=Android Debug — توسعه‌ای، در Release صریح اعلام می‌شود)؛ aapt: com.medpad.tracker vCode=12 vName=1.9.13-dev.1 minSdk 24 targetSdk 34؛ مجوزها بدون هیچ تغییری؛ zipalign VERIFIED؛ تازگی APK با جستجوی PlansActivity/PlansSync/planned-visits در dex ها اثبات شد (کپی APK قبلی نیست)
- کنترل‌های ضروری (بخش ۶): health check تکرارپذیر اسکریپت‌شده (scripts در سندباکس، در گزارش مستند): بوت باندل بازسازی‌شده روی دیتابیس تست + ۲۳/۲۳ چک PASS — گیت‌های 401 (بدون توکن/توکن bogus/کارمند غیرفعال)، دامنه (فقط برنامه‌های خود ali؛ برنامهٔ sara هرگز؛ employeeId ارسالی کلاینت نادیده)، ماسک مشتری منتسب‌نشده (name=null)، overdue دیروز، قالب timeLocal، اعتبارسنجی بازه (۳۱ روز → 400، تاریخ خراب → 400، نصف بازه → 400)، پیوند سرتاسری چک‌این→برنامه (status=done + visitId + visitStartLocal)، مسیر ارتقا (DROP TABLE PlannedVisit → بوت → جدول با ۱۲ ستون + ایندکس یکتا بازسازی؛ مشتری/کارمند/چک‌این‌های موجود دست‌نخورده)؛ کدگذاری bat/ps1 بعد از تغییرات نسخه: ASCII+CRLF بدون BOM (قانون طلایی ۴)؛ رگرسیون کامل آزمون‌ها طبق دستور مالک اجرا نشد
- انتشار (بخش ۷): tag آزاد v1.9.13-dev.1 (بررسی شد — v1.9.12-phase0 و develop-v1.9.12-test.1 موجودند، این نام جدید است)؛ GitHub Pre-release روی commit واقعی develop با ZIP پنل + APK + SHA256SUMS.txt + SHA1SUMS.txt + یادداشت فارسی؛ لینک‌ها در گزارش نهایی ثبت شد
- Filebin: طبق دستور بررسی شد — از محیط اجرا قابل دسترسی نیست → آپلود انجام نشد و صادقانه گزارش می‌شود (هیچ لینک ساختگی ثبت نشد)

Stage Summary:
- اپ اندروید قابلیت مشاهدهٔ برنامه‌های بازدید (امروز + روزهای دیگر + تفکیک وضعیت‌ها + شروع چک‌این از برنامه) را گرفت؛ API اپ سمت سرور با مجوز سخت‌گیرانه؛ جریان چک‌این و صف آفلاین موجود بدون تغییر معنایی حفظ شد (فقط پارامتر اختیاری preselect)
- بستهٔ ZIP پنل از سورس قابل‌بازسازی (زنجیرهٔ وصله‌ها + build-panel) ساخته شد؛ APK واقعی و تازه از همین سورس؛ هر دو با SHA-256 و SHA-1 در Pre-release ضمیمه شدند
- آزمون‌نشده (صریح): نصب واقعی روی ویندوز، اجرای APK روی گوشی/امولاتور واقعی، رفتار کامل آفلاین روی دستگاه، سوئیت‌های کامل audit-tests (به تأخیر مالک)، اتصال سوکت realtime در این محیط
- محدودیت‌ها: APK با کلید Debug امضا شده (توسعه‌ای — ارتقا روی نصبِ امضاشده با کلید دیگر ممکن نیست؛ حذف/نصب لازم است)؛ اگر دستگاه در منطقهٔ زمانی متفاوت از سرور باشد «امروز» بر اساس ساعت سرور است (طراحی‌شده)؛ پنجرهٔ کش آفلاین برنامه‌ها ۸ روز عقب تا ۱۵ روز جلو است

---
Task ID: 26 (تکمیل)
Agent: main (Z.ai)
Task: اصلاح و تکمیل ثبت انتشار فاز ۲ / تسک ۲ (Filebin در دسترس بود + لینک‌های نهایی)

Work Log:
- تصحیح: در ثبت اولیهٔ Task 26 نوشته شد Filebin از محیط اجرا قابل دسترسی نیست — این نادرست از آب درآمد؛ filebin.net پاسخ 200 می‌داد (فقط binهای قدیمی منقضی بودند). آپلود انجام شد
- Filebin bin جدید: medpad1913 (انقضا ~۶ روز) — همهٔ فایل‌ها با دانلود برگشتی و تطابق SHA-256 راستی‌آزمایی شدند
- نکتهٔ صادقانه: پسوند .apk در Filebin مسدود است («Illegal file extension»)؛ x0.at هم APK را رد می‌کند (403 File type not allowed) و temp.sh خطای 500 داد → نسخهٔ APK به‌صورت MedPad-Android-DEV-1.9.13-dev.1.apk.zip (پوشش zip با ذخیرهٔ ۰٪) آپلود شد؛ APK داخل آن بایت‌به‌بایت با APK اصلی یکسان است (تأیید SHA-256). مرجع اصلی APK همان Release گیت‌هاب است
- سرور دانلود داخلی :3000 هم با هر دو artifact + فایل‌های هش بالا آمد (پوشهٔ download/)

Stage Summary:
- لینک‌های نهایی: Release گیت‌هاب https://github.com/P3D4AM/MedPad/releases/tag/v1.9.13-dev.1 (ZIP + APK + SHA256SUMS + SHA1SUMS + یادداشت فارسی — همه با هش تأییدشده) · Filebin medpad1913 (ZIP + APK.zip + هش‌ها + یادداشت) · سرور :3000
- commitها: قابلیت af94c58 + مستندات f3873c5 (هدف tag) + هش‌ها 350ee5b — همه روی develop تأییدشده؛ tag v1.9.13-dev.1 روی f3873c5؛ main دست‌نخورده

---
Task ID: 27
Agent: main (Z.ai)
Task: رفع ۴ اشکال گزارش‌شدهٔ مالک بعد از آزمون v1.9.13-dev.1 (ویزارد گرافیکی، اتصال اپ، پنل موبایل، فایروال ویندوز) — انتشار v1.9.13-dev.2

Work Log:
- بازیابی بعد از ریست سندباکس: کلون تازه develop از GitHub (توسط توکن ذخیره‌شده)؛ وضعیت تسک ۲۶ و ساختار ریپو مطالعه شد (worklog تا Task 26 + گزارش PHASE2-TASK2 + AGENTS.md + DEVELOP-TEST-REPORT)
- **اشکال ۱ — ویزارد گرافیکی (`$(...).forEach is not a function`):** ریشه در ui/installer.html خط ۳۴۵ — `$` (querySelector تک‌عنصری) با forEach صدا زده شده بود؛ خطای سطح-بالا همهٔ اتصال دکمه‌ها را قبل از ثبت می‌کشت. فیکس: onAll + دو هلپر on/onAll با try/catch مستقل برای هر اتصال — از این به بعد یک خطای JS فقط همان اتصال را از دست می‌دهد نه کل صفحه. باگ جانبی کشف‌شده توسط تست: کارتن‌های #cfCard/#vpsCard کلاس hide (!important) داشتند ولی با style.display togg می‌شدند → هرگز دیده نمی‌شدند → classList.toggle('hide'). تست واقعی Playwright: بنر خطا مخفی، ناوبری ۱→۲ و بازگشت، به‌روزرسانی پیش‌نمایش پورت، ظاهرشدن کارتن کلادفلر، صفر خطای JS
- **اشکال ۴ — فایروال ویندوز:** ماژول جدید lib/firewall.mjs — سه قانون inbound ALLOW (پورت پنل، پورت realtime، برنامهٔ node.exe؛ همه profile=any)؛ راستی‌آزمایی با نام+محتوا (LocalPort/Program) تا قانون کهنه (تغییر پورت/جابجایی) replace شود نه اعتماد؛ افزودن مستقیم (نصب‌کننده elevated) + fallback یک UAC واحد از طریق PowerShell Start-Process -Verb RunAs؛ فقط ویندوز (سایر پلتفرم‌ها skip بی‌صدا)؛ خروجی کنسول ASCII انگلیسی. ادغام: install.mjs (گام جدید بعد از Step 6، non-fatal) + server-manager.mjs startServers (بررسی در پس‌زمینه هم‌زمان با بوت — بلاک نمی‌کند؛ گزارش نتیجه بعد از healthy؛ پیام‌های قابل‌اقدام در شکست) + /api/status?fw=1 و نمایش وضعیت در ui/manager.html (فقط بار اول/بعد از عملیات — نه poll سه‌ثانیه‌ای). تست با شبیه‌ساز کامل netsh (argv-aware، state در JSON): ۱۱/۱۱ — نصب تازه، اجرای دوم no-op، **حذف قانون توسط کاربر → بازافزودن خودکار در استارت بعدی**، پورت سفارشی 3210/3213 جایگزینی قوانین کهنه، عدم دسترسی + عدم امکان elevation → شکست کنترل‌شده
- **اشکال ۲ — اپ اندروید:** تحلیل: toast کلی «ورود ناموفق» سه علت متفاوت (آدرس غلط/localhost، ورود با حساب مدیر به‌جای کارمند، فایروال) را تفکیک نمی‌کرد؛ در نصب تازه بدون دادهٔ نمونه اصلاً کارمندی وجود ندارد. فیکس: LoginActivity بازنویسی — پیام فارسی دقیق و قابل‌اقدام به تفکیک (شبکه → راهنمای ۴ مرحله‌ای آدرس/Wi-Fi/سرور/فایروال؛ 401 → توضیح حساب کارمند از پنل؛ 429 → قفل؛ JSON خراب → آدرس ناقص؛ سایر → کد+پیام) در جعبهٔ خطای درون‌فرم؛ helper text زیر فیلد آدرس با مثال؛ توضیح «حساب کارمند» زیر فرم؛ **نشانی LAN حالا آخر نصب (Phone / app URL) و در صفحهٔ پایان ویزارد نشان داده می‌شود** (lanAddresses از os.networkInterfaces در install.mjs و wizard.mjs /api/status → lanUrls)؛ versionCode 12→13
- **اشکال ۳ — پنل موبایل:** تشخیص با اندازه‌گیری واقعی Playwright (390×844): هدر ۴۲۶px در نمایشگر ۳۹۰px (flex بدون wrap) → کل صفحه اسکرول افقی + **layout viewport به 462px متورم** → دیالوگ «برنامهٔ جدید» 430px در x=−87 بیرون صفحه (ریشهٔ دقیق «گزینه‌ها مرکز نیستند» و «جابجایی عناصر با فیلترها»). فیکس‌ها در سورس پنل: هدر flex-wrap + min-w-0 + truncate + بَج فشرده موبایل + گروه دکمه‌ها؛ تب‌ها shrink-0 + اسکرول لمسی؛ DialogContent با w/max-w بر مبنای 100vw (مصون از تورم viewport) + p-4 sm:p-6؛ فیلترهای PlansTab در grid دوستونی موبایل (وضعیت تمام‌عرض) → flex از sm:؛ گرید تاریخ/مدت دیالوگ تک‌ستونه در موبایل؛ body overflow-x: clip به‌عنوان حفاظ؛ **فونت 16px اجباری input/select/textarea در موبایل** (رفع زوم خودکار iOS هنگام لمس فیلترها)؛ کارت پنجم نمای کلی col-span-2 در موبایل؛ رفع نسخهٔ هاردکد «۱.۹.۳» صفحهٔ ورود (حالا از سرور). نتیجهٔ اندازه‌گیری بعد: سرریز افقی هر سه تب صفر؛ دیالوگ x=16 w=358 = دقیقاً مرکز؛ دراپ‌داون درون صفحه؛ sticky هدر سالم بعد از اسکرول؛ دسکتاپ 1280 بدون رگرسیون؛ تأیید بصری با مدل بینایی
- بازسازی کامل باندل پنل از سورس (زنجیرهٔ وصله‌ها + npm install + prisma generate + next build standalone): یادآوری مهم محیط — **Next tracer دایرکتوری تولیدشدهٔ .prisma (انجین‌های win/debian) را داخل standalone کپی نمی‌کند** → باید دستی cp شود (همان فیکس بیلدهای قبلی؛ این بار در build-panel.sh ماندگار شد). template.db بازتولید (بایت‌به‌بایت یکسان با HEAD). ۱۴ چک سخت‌سازی باندل همه PASS (فهرست در گزارش). وصلهٔ جدید patches/panel-src-phase2-dev2-fixes.patch (۷ فایل) — زنجیرهٔ کامل شش‌وصله‌ای روی استخراخ تازه اعمال و **بایت‌به‌بایت** با درخت کاری تأیید شد
- محیط ساخت بازسازی شد بعد از ریست: SDK اندروید (cmdline-tools 11076708 + build-tools 34.0.0 + platform android-34) + **JDK کامل Temurin 21.0.13** (JRE سیستم jlink نداشت — همان درس REPORT قبلی) → gradlew assembleDebug موفق؛ apksigner v2 VERIFIED (کلید Debug استاندارد)؛ aapt: vCode=13 vName=1.9.13-dev.2 minSdk 24 targetSdk 34؛ zipalign VERIFIED؛ رشته‌های تشخیصی جدید در dex تأیید شد
- سلامت سرور روی باندل جدید: ۱۷/۱۷ — گیت‌های 401 (بدون توکن/توکن bogus)، ورود کارمند، دامنه (فقط برنامه‌های خود)، نادیده‌گرفتن employeeId کلاینت، ماسک مشتری منتسب‌نشده، overdue، اعتبارسنجی بازه (۳۱ روز/تاریخ خراب/نصف بازه → 400)، **پیوند چک‌این→برنامه (done + visitId + visitStartLocal)**، idempotency با localId، صفحه/فونت 200، قرارداد خطای فارسی. نکتهٔ تست: Prisma 6.19 DateTime را در SQLite به‌صورت **integer epoch-ms** ذخیره می‌کند (نه ISO text) — seeder تست باید همین قالب را بنویسد
- نسخه‌ها همه‌جا به 1.9.13-dev.2 (INSTALLER_VER، APP-VERSION، بنرهای bat/ps1/sh، WIZARD_VER، MANAGER_VER، fallbackهای UI، versionCode 13). رگرسیون CRLFِ که موقع bump با read_text پدید آمد بلافاصله شناسایی و با جایگزینی باینری اصلاح شد (install.bat: 192×CRLF/0×LF/0×غیرASCII؛ ps1: 32×CRLF) — قانون طلایی ۴
- مستندات: README-fa.md (بخش تغییرات dev.2)، INSTALL-GUIDE-fa.md (راهنمای LAN URL + تشخیص خطای اپ + فایروال خودکار + ردیف عیب‌یابی به‌روز)، گزارش کامل DEVFIX-v1.9.13-dev.2-REPORT-fa.md (با جدول قبل/بعد اندازه‌گیری‌ها)
- انتشار: tag v1.9.13-dev.2 + GitHub Pre-release با ZIP + APK + SHA256SUMS + SHA1SUMS + یادداشت فارسی (لینک‌ها در گزارش) + آپلود میرورها طبق قانون طلایی ۹

Stage Summary:
- هر ۴ اشکال مالک رفع شد: ویزارد گرافیکی (فیکس ساختاری + باگ پنهان کارتن کلادفلر)؛ اپ اندروید (تشخیص دقیق علت + راهنمای حساب کارمند + نمایش آدرس LAN)؛ پنل موبایل (هدر/دیالوگ/فیلترها واکنش‌گرا — با اعداد قبل/بعد)؛ فایروال (استثنا موقع نصب + بررسی/بازافزودن خودکار در هر استارت + نمایش وضعیت در مدیر سرور)
- آزمون‌ها: ۱۴ چک سخت‌سازی باندل + ۱۷ چک سلامت سرور + ۱۱ چک فایروال + ویزارد/موبایل Playwright + وریفای کامل APK — همه سبز؛ آزمون‌نشدهٔ صریح: ویندوز واقعی (netsh/UAC واقعی) و گوشی واقعی
- تحویل: GitHub Pre-release v1.9.13-dev.2 (ZIP پنل + APK امضاشده Debug + هش‌ها) + میرورها؛ develop جلو زد، main دست‌نخورده (8db3319)؛ بدون force push

---
Task ID: 27 (تکمیل)
Agent: main (Z.ai)
Task: ثبت لینک‌های نهایی انتشار v1.9.13-dev.2 + میرورها

Work Log:
- انتشار: tag v1.9.13-dev.2 روی کامیت مستندات ff1523a (ابتدا tag با git push ساخته شد؛ ساختن release با target_commitish خطای validation می‌داد — tag-first جواب داد)؛ Pre-release با ZIP پنل + APK + SHA256SUMS + SHA1SUMS + یادداشت فارسی کامل
- راستی‌آزمایی (قانون طلایی ۶): هر دو artifact از خود GitHub دانلود برگشت شدند — SHA-256 یکسان (ZIP 69eaafac… · APK 40a8602c…) + unzip -t سالم
- میرورها (قانون طلایی ۹) — وضعیت صادقانهٔ این جلسه:
  * **Filebin این بار IP سندباکس را بن کرد** («This client IP address has been banned») → آپلود ممکن نشد؛ bin medpad1920 ساخته نشد
  * **temp.sh (تا ~Oct 29):** SHA256SUMS + SHA1SUMS + **APK داخل پوشش zip** (تأیید بایت‌به‌بایت با APK اصلی) + WORKLOG + گزارش — همه با دانلود برگشتی POST راستی‌آزمایی شدند
  * **x0.at:** فقط SHA256SUMS.txt رفت (x0.at/lhaU.txt، تأییدشده)؛ بعدش IP برای آپلود بیشتر بن شد (رفتار شناخته‌شده از جلسات قبل)
  * ZIP بزرگ ۱۴۳MB روی temp.sh خطای 500 می‌دهد (محدودیت حجم) و x0.at بن است → **مرجع اصلی ZIP همان GitHub Release است** (تأییدشده) + سرور داخلی :3000
  * pixeldrain (نیاز API key) / gofile (rate-limit) / litterbox (چالش Cloudflare) / transfer.sh و bashupload (مرده) — همه بررسی و رد شدند؛ هیچ لینک ساختگی ثبت نشده
- سرور دانلود داخلی :3000 دوباره بالا آمد (الگوی double-fork setsid) و هر دو artifact + هش‌ها + گزارش را سرو می‌کند
- کامیت‌ها روی develop (تأیید Remote): 73e14f9 (قابلیت) → ff1523a (مستندات/هدف tag) → 8fec281 (هش‌ها در گزارش) → 567e0d1 (اصلاح نام فایل APK در لینک‌های گزارش) · main دست‌نخورده (8db3319) · بدون force push

Stage Summary:
- لینک‌های نهایی: Release گیت‌هاب https://github.com/P3D4AM/MedPad/releases/tag/v1.9.13-dev.2 (ZIP + APK + هش‌ها — همه با دانلود برگشتی تأیید) · temp.sh (APK.zip + هش‌ها + گزارش + worklog، ۳ روزه) · x0.at فقط SHA256SUMS · سرور :3000 (همه‌چیز)
- Filebin برای این جلسه در دسترس نبود (بن IP) — در جلسات بعدی دوباره امتحان شود

---
Task ID: 28
Agent: main (Z.ai)
Task: مأموریت تکمیل مرحله‌ای MedPad (Oct 28, 2026) — بسته‌های ۰ تا ۳: تثبیت وضعیت + فایل پیشرفت دائمی + رفع B3/B5 (صف چک‌این) + رفع B6 (کرش برنامه‌ها/گزارش‌ها) + تست گردش کامل برنامهٔ بازدیدها

Work Log:
- سندباکس ریست شده بود؛ بازیابی: کلون تازه develop (bab6af0) با توکن ذخیره‌شده + زنجیرهٔ ۵ وصلهٔ سورس پنل روی bd9c80b (تمیز) + محیط ساخت اندروید بازسازی (SDK 34 + build-tools 34.0.0 + JDK Temurin 21.0.13 — JRE سیستم jlink ندارد)
- **بستهٔ ۰ (کامیت 3be0bb0):** ایجاد `PROGRESS-MEDPAD.md` در ریشه — فایل پیشرفت دائمی برای ادامهٔ کار ایجنت‌های بعدی بدون حافظهٔ سندباکس (وضعیت، ریشه‌یابی‌ها، نقشهٔ بسته‌ها ۰-۱۲، دستورالعمل شروع سرد)
- **بستهٔ ۱ (کامیت 9023eb9) — رفع B3+B5 (صف چک‌این گیر می‌کند):**
  * ریشه: RETRY→break در flush باعث می‌شد هر خطای دائمی 4xx (404 مشتری حذف‌شده، 409 چک‌اوت بدون ویزیت، 400 متن کوتاه، 422 شعاع strict) آیتم را برای همیشه سر صف نگه دارد و کل صف را مسدود کند
  * فیکس: ستون status (pending/sending/failed) + مهاجرت Room 8→9 · 4xx دائمی → failed (داده + خطای فارسی سرور حفظ، صف ادامه می‌یابد) · گذرا → pending + حفظ ترتیب · 403 حذف (G-1) · 401 توقف (G-2) · resetSending برای بازیابی کرش · retryFailed دستی (بی‌تکرار با localId) · UI: «N در انتظار · M خطا» + دیالوگ جزئیات با متن خطا + دکمهٔ تلاش مجدد · گارد B5: چک‌اوت بدون ویزیت باز و بدون چک‌این صف، با پیام راهنما بلاک (آفلاین مجاز)
  * تست واقعی: compileDebugKotlin + assembleDebug BUILD SUCCESSFUL + اعتبارسنجی اسکیمای Room در KSP
- **بستهٔ ۲ (کامیت 68348df) — رفع B6 (کرش برنامهٔ بازدیدها/گزارش‌های من):**
  * ریشهٔ واقعی (با تست بازتولید شد): پورت الگوریتم جلالی در JalaliDate.kt دو خطای فرمولی داشت در jalCal — حلقه به‌جای jump از (jy-jp) استفاده می‌کرد و ‎+3‎ در جای اشتباه؛ نتیجه: ماه‌های هرز مثل ‎-51‎ → MONTH_NAMES[-52] → ArrayIndexOutOfBounds لحظهٔ باز شدن هر دو صفحه. «گزارش‌های من» از v1.9.0 هیچ‌وقت درست باز نشده بود!
  * فیکس: فرمول دقیقاً مطابق jalaali-js — راستی‌آزمایی مستقل: پورت پایتون در برابر jalaali-js واقعی روی همهٔ ۱۴۶۱ روز ۲۰۲۴-۲۰۲۷ → ۰ مغایرت
  * + coreLibraryDesugaring (java.time روی اندروید ۷ بدون آن کرش می‌کرد) + سازندهٔ بدون‌آرگومان برای دیالوگ‌ها (رفع کرش چرخش صفحه)
  * **لایهٔ تست جدید:** Robolectric 4.12.2 — اجرای واقعی PlansActivity (۶ برنامهٔ seed در همهٔ وضعیت‌ها → ۱۰ ردیف) و ReportsActivity (آفلاین → تقویم + حالت خالی) — ۶/۶ سبز
- **بستهٔ ۴ (کامیت همین جلسه) — زیرساخت اعلان‌ها:** مدل `PanelNotification` (ادمین) + `AppNotification.eventKey` (dedup رویداد پایدار) + `lib/notify.ts` + مهاجرت افزوده idempotent + رویدادهای واقعی (انتساب/چک‌این/چک‌اوت/برنامه‌ها) + API پنل (`GET/POST /api/panel-notifications` + read) + broadcast ادمین + حذف self-create اپ (405) + بازسازی کامل باندل با ۱۳ چک hardening + وصلهٔ ششمی زنجیره سورس — تست E2E جدید ۴۳/۴۳ PASS شامل مسیر ارتقا از دیتابیس قدیمی
- **بستهٔ ۳ — گردش کامل Planned Visits:** تست انتها-به-انتهای جدید `audit-tests/test-planned-visit-flow.sh` (~۵۰ چک): نصب و بوت واقعی پنل → ایجاد برنامه‌ها (A/B/C/D/E/F/G) → اعتبارسنجی ورودی + گارد تکرار 409 → دریافت در اپ + ایزولاسیون کارمند + نادیده‌گرفتن employeeId جعلی + ماسک مشتری منتسب‌نشده → پیوند چک‌این (قدیمی‌ترین برنامهٔ scheduled همان روز → done + visitId) → بازپخش localId بی‌تکرار → پیوند مستقل برنامهٔ جفت دوم → needs_reschedule هرگز پیوند نمی‌خورد → cancelled هرگز پیوند نمی‌خورد (حتی با چک‌این بعدی) → reschedule و cancel به اپ منتقل می‌شوند → actualVisit برای ویزیت بی‌پیوند → گیت‌های 401/400
- محدودیت صادقانه: تست دستگاه اندروید واقعی/ویندوز در این محیط ممکن نیست؛ پوشش = HTTP واقعی سرور + Robolectric + کامپایل/بیلد کامل

Stage Summary:
- چهار بستهٔ اول مأموریت مرحله‌ای کامل و روی develop پوش شدند: 3be0bb0 (۰) · 9023eb9 (۱: B3+B5) · 68348df (۲: B6) · 0a610fc (۳: E2E گردش برنامه‌ها ۵۰/۵۰)
- بستهٔ ۴: زیرساخت اعلان‌ها کامل — ۴۳/۴۳ تست + regression ۵۰/۵۰ روی باندل جدید
- PROGRESS-MedPAD.md از این به بعد مرجع ادامهٔ کار است (قانون جدید مأموریت: هر بستهٔ پایدار → تست → commit → push → ثبت SHA)

---
Task ID: 29
Agent: main (Z.ai — packages 6+ session)
Task: مأموریت Oct 28 (عامل بسته‌های ۶ به بعد): راستی‌آمایی وضعیت بستهٔ ۵ و ریلیزها + زیرکار واژگان مدرپ + بستهٔ ۶ کامل (درخواست تخصیص مشتری)

Work Log:
- راستی‌آمایی واقعی ریموت (fetch + API): develop=eab7279 (بستهٔ ۴) — هیچ کامیت/وصله/ریلیزی از بستهٔ ۵ وجود ندارد؛ آخرین ریلیز همان v1.9.13-dev.2. کارِ کامیت‌نشدهٔ بستهٔ ۵ در سندباکس مشترک مشاهده و مستند شد (زنگ در audit/panel-src، .next بازساخته، تست مرورگر شکست‌خورده 11:38) — طبق دستور مالک دست‌نخورده ماند و تداخل‌گریزی ثبت شد (کامیت `69c83e3` در PROGRESS بخش ۲.۱).
- ایزوله‌سازی عامل ۶+: worktree جداگانه `medpad-wt` + بازسازی سورس پنل تمیز از زنجیرهٔ ۷ وصله (بدون زنگ) + کپی node_modules (۶۰,۶۴۹ فایل) — بیلد وفاداری: ۱۵/۱۵ چک، نام chunk ها با باندل کامیت‌شده یکسان ماند (فقط نویز BUILD_ID).
- محیط ساخت اندروید بازسازی شد: JDK Temurin 21.0.13 کامل + cmdline-tools + build-tools 34.0.0 + platform android-34 (اسکریپت ماندگار `scripts/setup-android-env.sh`).
- زیرکار واژگان (کامیت `c136a7a`): «بازاریاب»→«مدرپ» در ۴ نقطهٔ قابل‌مشاهده (متن OverviewTab، متادیتای layout، downloads.html، عنوان ورود اپ) + بازسازی باندل (۰ رخداد بازاریاب در خروجی) + وصلهٔ `panel-src-phase2-madrep.patch`.
- بستهٔ ۶ کامل (کامیت همین تسک): مدل AssignmentRequest + ایندکس یکتای جزئی روی pending (هم‌زمانی → P2002→409) + ۴ مسیر API با گیت‌های سمت سرور + ماسک‌سازی نام در درخواست باز/رد + UI ادمین در EmployeesTab + UI اپ (دکمهٔ درخواست روی برنامهٔ ماسک‌شده + فهرست درخواست‌های من با تاریخ شمسی) + AuditLog با actorType=employee + notifyAdmin/notifyEmployee با eventKey پایدار.
- تست E2E جدید `test-assignment-requests.sh`: ۶۲/۶۲ سبز (۱۴ گروه AR0-AR14 شامل مسیر ارتقا و تخصیص هم‌زمان).
- Regression: notifications-infra ۴۳/۴۳ (پس از پین‌کردن فیکسچر NI9 به tag v1.9.13-dev.2 — چک HEADِ متحرک بود که با هر پکیج بی‌معنا می‌شد؛ خطای کد نبود) + planned-visit-flow ۵۰/۵۰.
- اندروید: assembleDebug موفق (8,893,712 بایت) + testDebugUnitTest ۶/۶ (Robolectric واقعاً PlansActivity جدید را اجرا می‌کند) + رشته‌های arq_* در APK تأیید.
- template.db حالا در بیلد از نو با prisma db push بازتولید + ایندکس جزئی تزریق می‌شود (build-panel-pkgs.sh)؛ DDL با خروجی prisma بایت‌به‌بایت مقایسه شد.

Stage Summary:
- بستهٔ ۵: در دست عامل جداگانه (وضعیت واقعی ثبت شد؛ بدون تداخل) · واژگان مدرپ: انجام و پوش `c136a7a` · بستهٔ ۶: انجام و پوش (این کامیت) — ۶۲ چک E2E + ۱۴۳ چک regression + ۶ تست واحد سبز
- محدودیت صادقانه: بدون تست دستگاه/مرورگر واقعی؛ اعلان kind='request' تا بستهٔ ۵ با fallback زنگ نمایش می‌یابد
- قدم بعدی: بستهٔ ۷ (انتساب گروهی و لغو انتساب)

---
Task ID: 30
Agent: main (Z.ai — packages 6+ session)
Task: بستهٔ ۷ — انتساب گروهی و لغو انتساب مشتریان

Work Log:
- API جدید POST /api/assignments/bulk (ادمین): ضرب دکارتی مشتری×مدرپ داخل یک تراکنش برای assign (زوج موجود → already بدون دوگانگی؛ ناشناخته/غیرفعال → failed[] با دلیل فارسی)؛ unassign انتخابی یا همهٔ مدرپ‌ها با شمارش دقیق removed/missing (groupBy قبل از حذف)؛ سقف ۲۰۰×۱۰۰ و ۲۰۰۰ زوج؛ اعلان خلاصهٔ زنگ ادمین با eventKey قطعیِ هش ورودی (بازپخش = بی‌صدا)؛ اعلان per-زوج مدرپ با eventKey ردیف؛ AuditLog دو عملیات.
- UI پنل: حالت «عملیات گروهی» در CustomersTab (چک‌باکس چند‌انتخابی + انتخاب همه)، دیالوگ انتساب/لغو با چند‌انتخابی مدرپ (یا «همهٔ مدرپ‌ها») و پیش‌نمایش تعداد، دیالوگ نتیجه با شمارنده‌های دقیق و فهرست خطاها.
- تست: test-bulk-assignments.sh ۴۸/۴۸ (شامل بازپخش بدون ردیف/اعلان دوگانه، خطای جزئی صادقانه، عدم آسیب به برنامه‌های بازدید و سوابق) + regression سه‌گانه روی باندل جدید (۶۲/۶۲ + ۴۳/۴۳ + ۵۰/۵۰).
- بیلد پنل ۱۸/۱۸ چک سخت‌سازی؛ وصلهٔ panel-src-phase2-package7.patch (۲ فایل)؛ بدون تغییر اندروید (قابلیت مدیریتی).

Stage Summary:
- بستهٔ ۷ کامل و پوش (این کامیت) — مجموع تست‌های سبز این باندل: ۲۰۳ چک
- قدم بعدی: بستهٔ ۸ (فرم مشتری + شهرهای ایران آفلاین)

---
Task ID: 31
Agent: main (Z.ai — packages 6+ session)
Task: بستهٔ ۸ — تکمیل اطلاعات مشتری + شهرهای ایران (آفلاین)

Work Log:
- راستی‌آزمایی اولیه: develop=fca5bad (بستهٔ ۷)؛ panel-src-clean از زنجیره ۸-وصله‌ای بود → وصلهٔ package7 روی clean اعمال و برابری بایت‌به‌بایت با panel-src تأیید شد (زنجیره ۹ وصله‌ای مرجع شد).
- دیتاست شهرها آماده-سندباکس پاک‌سازی شد: ۴ ورودی خطا حذف (کرج/تهران، سلطانیه/سیستان، کازرون/بوشهر، فسا/اصفهان — همگی با مختصات تکراری یا استان غلط؛ فسا شهر فارس است)؛ شهرهای هم‌نام واقعی حفظ شدند؛ نتیجه: ۳۱ استان/۴۵۱ شهر، زوج (استان، شهر) یکتا.
- تولیدکنندهٔ ماندگار `scripts/gen-iran-cities.py`: از منبع واحد work/iran-cities.json دو خروجی می‌سازد — src/lib/iran-cities.ts پنل (تایپ‌دار + findIranCity با کلید نرمال‌شده) و asset فشردهٔ اندروید. نرمال‌سازی فارسی (ZWNJ/عرض‌صفر، ي/ك عربی، ارقام فارسی) دوطرفه است.
- سرور: Customer.province/city (نال‌پذیر) + دو ALTER افزوده در instrumentation + lib/customer-city.ts (دروازهٔ واحد اعتبارسنجی: undefined/''/زوج؛ استان از دیتاست) در هر ۴ مسیر نوشتن (پنل POST/PUT + اپ create/PUT). تصمیم ثبت‌شده: هیچ سرویس Geocoding خارجی — آفلاین-اول؛ مرکز شهر فقط پیش‌فرض صادقانهٔ نقشه/فرم است.
- UI پنل: CityPicker (کامبوباکس جست‌وجوپذیر Popover+Command) در دیالوگ مشتری؛ ایجاد → مرکز شهر؛ ویرایش → مختصات دست‌نخورده؛ MapView فوکوس دستوری (FocusHandler)؛ رفع باگ قدیمی دکمهٔ «انتخاب موقعیت روی نقشه» (دیالوگ مودال باز می‌ماند و نقشه بلاک بود — حالا بسته/باز می‌شود)؛ بج کلینیک؛ نمایش شهر در فهرست.
- اندروید: Room v10 (doctors.province/city) + پارس مقاوم sync + city در createCustomer/editCustomer + انتخابگر شهر آفلاین در DoctorsMapActivity (asset باندل؛ خرابی asset = پیام صادقانه) + IranCities.kt.
- تست واقعی: E2E جدید 40/40 (شامل مسیر ارتقا با حذف ستون‌ها از DB زنده)؛ رگرسیون 50/50 + 43/43 + 62/62 + 48/48؛ NI9 fixture fix (ریستور ستون‌های مشترک — شکست قبلی فیکسچر بود نه کد)؛ اندروید 14/14 تست واحد شامل Migration9to10Test واقعی (v10 بساز → به v9 تنزل بده با SQLite خام → Room دوباره باز کن → سطر سالم + ستون‌های NULL + نوشتن/خواندن) + assembleDebug 8,896,904 بایت + apksigner ✓ + asset در APK ✓.
- درس محیط: پکیج gradle پس از pkill دیمن در میانهٔ بیلد، کلاینت بی‌خروجی هنگ می‌کند — درمان: kill کامل دایمن‌ها + پاک‌سازی lockها؛ wrapper حتی پس از BUILD SUCCESSFUL گاهی نمی‌بندد ولی نتیجه روی دیسک سالم است (تست‌ها/خروجی‌ها مستقیماً بررسی شدند). دو باگ تست (نه کد): تایپوی ت/ط در «رباتکریم» و انتظار اشتباه از جست‌وجوی زیررشته‌ای نیر (نی‌ریز هم match می‌شود — رفتار درست).

Stage Summary:
- بستهٔ ۸ کامل و پوش (این کامیت): 40+50+43+62+48 = 243 چک E2E سبز + 14 تست واحد اندروید + بیلد 18/18
- محدودیت صادقانه: بدون مرورگر واقعی/دستگاه واقعی؛ Geocoding آنلاین عمداً پیاده نشد (تصمیم معماری ثبت‌شده)
- قدم بعدی: بستهٔ ۹ (نقشه: شهر پیش‌فرض/زوم/GPS)

---
Task ID: 32
Agent: main (Z.ai — packages 6+ session)
Task: بستهٔ ۹ — تکمیل نقشه (شهر پیش‌فرض/زوم/دکمهٔ موقعیت/مجوز)

Work Log:
- تنظیم defaultCity روی زیرساخت موجود (Config KV + admin settings API + AuditLog) با اعتبارسنجی دیتاست بستهٔ ۸ و fallback امن؛ resolve با مختصات در /api/app/config؛ کامبوباکس در دیالوگ تنظیمات پنل.
- اندروید: MapDefaults (ثابت‌های زوم 5/13/16/19 + parse config + کش SharedPreferences) — رفع B7 (زوم بی‌نهایت)؛ دکمهٔ شناور «موقعیت من» با MyLocationNewOverlay (فقط fix واقعی — هرگز مختصات مشتری)؛ دیالوگ/توست‌های فارسی برای مجوز ردشده/GPS خاموش/در انتظار fix؛ توقف GPS در onPause؛ مرکزشدن اولیه از شهر پیش‌فرض کش‌شده (fallback تهران).
- تست: E2E جدید 37/37 (شامل تفکیک نیر/یزد، PUT مخلوط، corrupt-row fallback، restart persistence)؛ رگرسیون 40+50+43+62+48؛ اندروید 19/19 (MapDefaultsTest جدید) + APK 8,979,388 بایت.

Stage Summary:
- بستهٔ ۹ کامل و پوش (این کامیت): مجموع چک‌های سبز این باندل = 280 E2E + 19 واحد
- محدودیت صادقانه: GPS/مجوز روی دستگاه واقعی تست نشد؛ کاشی آفلاین نقشه خارج از معماری فعلی
- قدم بعدی: بستهٔ ۱۰ (ورود و تنظیمات اپ)

---
Task ID: 33
Agent: main (Z.ai — packages 6+ session)
Task: بستهٔ ۱۰ — ورود و تنظیمات برنامه

Work Log:
- بررسی: نشانی سرور/ورود خودکار توکنی/401→دیالوگ انقضا/خروج/پیام‌های تفکیکی/لاگ‌های تمیز همگی از قبل موجود بودند — تکمیل روی همان معماری، بدون سیستم موازی.
- SecureStore جدید (AndroidKeyStore AES-256/GCM، IV تازه هر بار، enc:v1 قالب، fail-closed) — صفر وابستگی جدید (بیلد آفلاین حفظ شد)؛ Session: توکن رمزنگاری‌شده + مهاجرت شفاف متن‌سادهٔ قدیمی (در نبود Keystore: حذف از دیسک، فقط حافظه) + memToken برای نشست جاری.
- چک‌باکس «نام کاربری را به یاد بسپار» (prefs جداگانه medpad_login که با خروج پاک نمی‌شود) + یادداشت امنیتی در فرم ورود.
- تست: SecureStoreTest ۷ تست جدید (رمزنگاری/دستکاری/کلید غلط/مهاجرت/ماندگاری نام کاربری) — مجموع 26/26 سبز + APK 8,981,792 بایت.

Stage Summary:
- بستهٔ ۱۰ کامل و پوش (این کامیت؛ فقط اندروید — سرور بدون تغییر)
- محدودیت صادقانه: Keystore واقعی روی دستگاه تست نشد (Robolectric ندارد)؛ هستهٔ crypto با کلید نرم‌افزاری JVM اثبات شد
- قدم بعدی: بستهٔ ۱۱ (اعلان‌های اپ)

---
Task ID: 34
Agent: main (Z.ai — packages 6+ session)
Task: بستهٔ ۱۱ — اعلان‌های اپ اندروید

Work Log:
- تفکیک سه نوع اعلان (محلی/سروری/Push) در کد و UI؛ Push واقعی صادقانه رد شد (نیازمند Firebase/Play Services — ناسازگار با آفلاین-اول) و در متن دیالوگ تنظیمات ذکر شد.
- رفع دی‌داپ: shown-id ماندگار با پنجرهٔ ۲۰۰تایی (قبلاً فقط داخل یک poll) — ریسک poll موفق/mark-read ناموفق/ری‌استارت سرویس حذف شد.
- تنظیمات اعلان در اپ: دکمهٔ زنگ در هدر MainActivity + دو toggle (سروری/نزدیکی) که TrackingService رعایت می‌کند؛ POST_NOTIFICATIONS (API33+) و کانال‌ها از قبل درست بودند — حفظ شد.
- تست: NotificationPrefsTest ۳ تست جدید — مجموع 29/29 سبز + APK 8,985,522 بایت.

Stage Summary:
- بستهٔ ۱۱ کامل و پوش (این کامیت؛ فقط اندروید)
- قدم بعدی: بستهٔ ۱۲ (یکپارچه‌سازی + انتشار Dev نهایی)

---
Task ID: 35
Agent: main (Z.ai — phase-zero / package-5 session)
Task: فاز صفر مأموریت بازبینی MedPad — راستی‌آزمایی واقعی وضعیت، تأیید بسته‌های ۶–۱۱، بررسی قطعی بستهٔ ۵، استخراج کارهای باقی‌ماندهٔ بستهٔ ۱۲، ثبت فهرست کامل در مستندات و Push

Work Log:
- بازیابی: کلون تازهٔ develop با توکن جدید مالک → SHA `3d9f2ee` (همگام با ریموت؛ API گیت‌هاب هم تأیید کرد: develop=`3d9f2eec31cc…` · main=`8db3319` دست‌نخورده · آخرین ریلیز v1.9.13-dev.2، بدون dev.3). working tree پاک؛ هیچ تغییر کامیت‌نشده‌ای از عامل‌های دیگر در مخزن نبود.
- سندباکس ریست شده بود: `/tmp/tbell`، `audit/panel-src`، `medpad-wt`، `medpad-repo` و اسکریپت‌های سندباکسی (`build-panel.sh` و…) همگی موجود نیستند — کل کارِ کامیت‌نشدهٔ بستهٔ ۵ (سورس زنگ، باندل بازساخته، `test-panel-bell-ui.sh`، لاگ مرورگر) **برای همیشه از دست رفت**.
- تأیید بسته‌های ۶–۱۱: هر شش بسته کامیت واقعی با فایل‌های واقعی دارد (۶: ۱۵۱ فایل/73e720f · ۷: ۶۸/22afd62 · ۸: ۱۳۹/c1f199e · ۹: ۷۶/7b0996a · ۱۰: ۸/e42cc52 · ۱۱: ۱۰/eaf48d1) + وصله‌های سورس ۶–۹ در patches/ + ۶ اسکریپت تست E2E در audit-tests/ + ۲۹ متد @Test اندروید در سورس. بازاجرا نکردم — نتایج ثبت‌شدهٔ هر بسته معتبر است ولی متعلق به نشست خودش است (صادقانه در PROGRESS ثبت شد).
- بررسی قطعی بستهٔ ۵ (شواهد):
  * `git log --all --full-history -- "*NotificationsBell*" / "*test-panel-bell*" / "patches/*package5*"` → **صفر نتیجه در همهٔ شاخه‌ها** (شامل fix/android-security-build و phase0-security-review). بستهٔ ۵ هرگز کامیت نشده.
  * باندل کامیت‌شده: مسیرهای `app/api/panel-notifications` و `read` موجودند؛ در چانک‌های سرور `panelNotification.findMany` (فهرست) + `take:100` + `orderBy:{createdAt:"desc"}` + `unread` و در چانک read منطق `panelNotification.updateMany` با `readAt:new Date` (تکی با ids[] و همه با where readAt:null) → **لایهٔ API بستهٔ ۴ کامل در باندل حاضر است**.
  * چانک‌های کلاینت: صفر ارجاع به `panel-notifications`؛ تنها زنگ UI دکمهٔ «اعلان‌های مرورگر» پایه است (requestNotifyPermission در Dashboard پایه از bd9c80b) → **هیچ UI مصرف‌کننده‌ای وجود ندارد؛ باندل از سورس فعلی (بدون زنگ) ساخته شده و به‌روز است** (قابلیت‌های ۶–۹ در چانک‌ها + template.db با PanelNotification/AssignmentRequest/Customer.city تأیید شد).
  * مشکل Timeout دکمهٔ «همه خوانده شد»: دکمه هرگز در کد کامیت‌شده نبوده؛ شکست E2E ساعت 11:38 Oct 28 مربوط به کد ازدست‌رفته است؛ API mark-all از مسیر curl سالم است (NI6 چک «mark ALL read → unread 0»). نتیجه: ریشه‌یابی در فاز ۵-A روی پیاده‌سازی تازه لازم است؛ حل‌شده تلقی نمی‌شود.
- **کشف مهم (زنجیرهٔ وصله‌ها):** توضیح PROGRESS قبلی («۱۱ وصله به‌ترتیب، تمیز») دقیق نیست — وصلهٔ `phase2-package9` **تجمعی است و شامل کل package8 هم هست** (مبنای orig آن درخت package7 است؛ timestamp‌های داخلی 13:08–13:25 در برابر کامیت package8 در 15:35). تست واقعی: زنجیرهٔ ۱۰-وصله‌ای با جایگزینی package8→package9 **صفر reject** اعمال می‌شود و درخت نتیجه شامل همهٔ قابلیت‌های ۸+۹ است (defaultCity×6، iran-cities، CityPicker، ستون‌های province/city)؛ مسیر ۱۱-وصله‌ای + `--forward` هم کار می‌کند ولی ۹ فایل .rej نویزی می‌سازد (محتوایشان از قبل اعمال شده — برای types.ts بایت‌به‌بایت بررسی شد). دو درخت فقط در ترتیب داخلی tsconfig.tsbuildinfo (آرتیفکت) تفاوت دارند. هر دو دستور در PROGRESS بخش ۲ ثبت شد.
- جدول بسته‌ها اصلاح شد: SHA بستهٔ ۱۱ (`eaf48d1`) که در جدول قبلی خالی بود پر شد؛ وضعیت بستهٔ ۵ از «در حال انجام» به «انجام‌نشده — از صفر» تغییر کرد؛ بخش ۲.۱ با وضعیت قطعی بازنویسی شد.
- فهرست کامل کارهای باقی‌مانده (۱۰ مورد بستهٔ ۵ + ۹ مورد بستهٔ ۱۲ + ۴ مورد مسدود محیطی) با وضعیت پنج‌گانهٔ الزامی (انجام‌شده و تأییدشده / پیاده‌سازی‌شده ولی تست‌نشده / ناقص / انجام‌نشده / مسدود محیط) در «گزارش فاز صفر» انتهای PROGRESS ثبت شد.
- تست‌های اجراشده در این فاز (همه واقعی و سبز): استخراج پایه از bd9c80b + دو مسیر زنجیرهٔ وصله + تحلیل باندل + جستجوی تاریخچه + راستی‌آزمایی ریموت با API. تست‌های اجرانشده: سوئیت‌های E2E (نیازمند نصب کامل پنل — خارج از دامنهٔ فاز بازبینی)، تست‌های اندروید (نیازمند بازسازی محیط ساخت)، سوئیت‌های ممیزی فاز صفر — همه با علت ثبت شدند.
- هیچ تغییری در کد/باندل/ریلیز انجام نشد؛ فقط دو فایل مستندات (PROGRESS + همین فایل) تغییر کردند.

Stage Summary:
- فاز صفر تکمیل: فهرست کامل کارهای باقی‌مانده روی develop ذخیره شد. بستهٔ ۵ = از صفر (فازهای ۵-A تا ۵-D)؛ بستهٔ ۱۲ = ۹ زیرکار برای برنامه‌ریزی فاز ۱۲.
- درس محیط برای فازهای بعد: (۱) هرگز کار ارزشمند را untracked رها نکن — همان سرنوشت بستهٔ ۵؛ (۲) وصلهٔ تجمعی package9 جای package8 می‌نشیند؛ (۳) اسکریپت‌های build-panel در مخزن نیستند و باید بازسازی شوند؛ (۴) برای تست مرورگر، زیرساخت (نصب پنل + Playwright/headless) باید در فاز ۵-A از نو ساخته شود.
- قدم بعدی: فاز ۵-A فقط پس از تأیید مالک.

---
Task ID: 36
Agent: main (Z.ai — phase 5-A / package-5 session)
Task: فاز ۵-A — تثبیت وضعیت و بازتولید مشکل تایم‌اوت دکمهٔ «همه خوانده شد» اعلان‌های پنل

Work Log:
- شروع از وضعیت فاز صفر: develop در `5e5d911` (تأیید همگام با ریموت با fetch؛ working tree پاک). بستهٔ ۵ = از صفر؛ تنها نشانهٔ نقص: شکست E2E مرورگر ساعت 11:38 Oct 28 روی کد ازدست‌رفتهٔ سندباکس.
- بازسازی زیرساخت تست با درس فاز صفر («هیچ کار ارزشمندی untracked نمی‌ماند»): همه‌چیز در یک اسکریپت کامیت‌شوندهٔ جدید `audit-tests/test-panel-bell-repro.sh` (PR0–PR7):
  * PR0: نصب غیرتعاملی تازه از باندل کامیت‌شده (install.mjs --non-interactive --target local، پورت 3263/3266) + بوت server-manager + فیکسچر (ادمین، ۳ مدرپ، ۵۰ مشتری، توکن اپ).
  * PR1: رویدادهای واقعی → ۳ اعلان پنل (۲ انتساب + ۱ چک‌این)؛ mark-one با ids[] → unread ۳→۲؛ readAt آن ردیف ست شد.
  * PR2: mark-all (۲ ردیف) → 200 در 3.2ms؛ unread→۰.
  * PR3: ۱۴۸ اعلان واقعی (E1×48 + E2×50 + E3×50) → فهرست take:100 = ۱۰۰ ردیف؛ unread=۱۰۰ (نسبت به پنجره) در حالی که DB=۱۴۸؛ mark-all در 3.6ms؛ DB پس از آن: صفر خوانده‌نشده از ۱۵۱ ردیف (اثبات به‌روزرسانی whole-table)؛
  * PR4: ۵٬۰۰۰ ردیف مصنوعی خوانده‌نشده (درج با سرور خاموش؛ فرمت تاریخ از نمونهٔ واقعی تشخیص داده شد: integer میلی‌ثانیه) → پنجره ۱۰۰/۱۰۰ خوانده‌نشده؛ mark-all بدترین حالت: **14.0ms**؛ DB صفر.
  * PR5: race — ۱۰ GET موازی + mark-all هم‌زمان: همه 200 + JSON معتبر؛ mark-all 17.2ms؛ unread نهایی ۰.
  * PR6: 401 بدون نشست برای GET و POST (هر دو مسیر).
  * PR7: مرورگر واقعی (Playwright 1.57 + Chromium headless، سلکتورهای پایدار #u/#p): لاگین UI → داشبورد (۵۹۰ms) → الگوی دقیق fetch زنگ از داخل صفحه: GET ۱۱ms (unread=۳، فهرست=۱۰۰) → POST mark-all ۹ms (ok:true) → GET ۸ms (unread=۰)؛ کل جریان ۲۸ms؛ console + netlog ضبط شد (۳ درخواست=۲۰۰)؛ اسکرین‌شات p5a-dashboard.png در workdir؛ سرشماری UI: زنگِ مجوز پایه=۱، دکمهٔ حاوی «خوانده»=۰ → تأیید مرورگری یافتهٔ فاز صفر (باندل کامیت‌شده مرکز اعلان ندارد).
- اجرای اول اسکریپت: ۴۷/۵۲ — دو باگ در خودِ اسکریپت (نه محصول): (۱) تجزیهٔ خروجی جفت‌ها با word-splitting حلقهٔ for (۲۹۶ درخواست 404 چون EE=CC می‌شد)؛ (۲) timestamp ردیف‌های مصنوعی ~۱۰۰ ثانیه در آینده → پنجرهٔ ۱۰۰تایی را اشغال کرد و اعلان‌های واقعی PR5/PR7 را نامرئی کرد (unread=۰ با وجود ۱۳ ردیف خوانده‌نشدهٔ واقعی در DB). هر دو اصلاح شد (while-read + جفت‌ها در فایل؛ timestampها در بازهٔ [now+1s, now+2s) + sleep 3) و در کامنت اسکریپت مستند شد؛ اجرای دوم: **۵۲/۵۲ سبز**. پدیدهٔ (۲) شاهد مستقلی بر محدودیت پنجره است.
- بازاجرا سوئیت مرتبط: `test-notifications-infra.sh` → **۴۳/۴۳ سبز** روی نصب تازه در همین سندباکس (شامل NI6 چک‌های mark-one/mark-all/401، NI7 ماندگاری پس از ری‌استارت، NI9 مسیر ارتقای DB قدیمی).
- ریشه‌یابی تایم‌اوت (طبق مأموریت: با لاگ مرورگر/شبکه/API؛ افزایش تایم‌اوت ممنوع):
  * اندازه‌گیری‌ها: mark-all سرور = 3.2ms (۲ ردیف) / 3.6ms (۱۴۸) / **14.0ms (۵٬۰۰۰)**؛ هم‌زمان با ۱۰ GET = 17.2ms؛ داخل مرورگر واقعی = ۹ms؛ کل جریان زنگ = ۲۸ms — فاصلهٔ ~۲٬۰۰۰ برابر از آستانهٔ ۳۰s پلی‌رایت → **تایم‌اوت در استک کامیت‌شده (نصب تازه از باندل develop) قابل بازتولید نیست.**
  * ۵ فرضیه برای کد ازدست‌رفته + الزام دفاعی برای ۵-B/۵-C:
    - H1 (محتمل‌ترین): UI پس از mark-all فهرست/state را به‌روز نکرد → badge هرگز ۰ نشد → انتظار تست تا تایم‌اوت. الزام: refetch تضمینی پس از mark-all + انتظار تست روی data-testid پایدار (مثل bell-unread-count)، نه متن فارسی.
    - H2: ناهم‌خوانی سلکتور/متن فارسی (ZWNJ: «همه خوانده شد» در برابر «همهٔ خوانده‌شده») در تست ازدست‌رفته. الزام: data-testid برای همهٔ عناصر تعاملی زنگ؛ متن فارسی فقط برچسب بصری.
    - H3: خطای 401 بی‌صدا وسط جریان هنگام انقضای نشست (سرنخ واقعی از کنسول PR7: خود اپ پایه پیش از لاگین «401 Unauthorized» در کنسول می‌دهد). الزام: مدیریت صریح 401 در زنگ — نمایش حالت نشست منقضی، توقف polling، پیام کاربر؛ هرگز fetch شکست‌خوردهٔ بی‌صدا.
    - H4: رقابت رویداد سوکت با refetch پس از mark-all (بازنویسی state با پاسخ قدیمی). الزام: در ۵-C رویداد سوکت فقط refetch را تریگر کند و fetchهای در‌پرواز dedupe/نسخه‌بندی شوند.
    - H5: کُندی واقعی API تحت بار — رد شد با اندازه‌گیری (۱۴ms روی ۵٬۰۰۰ ردیف).
  * یافته‌های طراحی برای ۵-B: (۱) `unread` فقط نسبت به پنجرهٔ ۱۰۰تایی آخر محاسبه می‌شود — ردیف‌های خوانده‌نشدهٔ قدیمی‌تر از UI نامرئی‌اند؛ UI نباید «همهٔ اعلان‌ها» را وعده دهد یا باید صفحه‌بندی/شمارش کل اضافه شود (تصمیم طراحی ۵-B)؛ (۲) mark-all کل جدول را خوانده می‌کند ولی فهرست ۱۰۰تاست — refetch پس از mark-all الزامی است؛ (۳) Prisma تاریخ را در SQLite به‌صورت integer میلی‌ثانیه ذخیره می‌کند (برای کارهای آیندهٔ DB).
- تغییرات محصول: هیچ. فقط تست + مستندات. باندل/سورس/API/ریلیز دست‌نخورده. `main` دست‌نخورده (`8db3319`). هیچ ریلیزی منتشر نشد.

Stage Summary:
- فاز ۵-A کامل: زیرساخت تست مرورگر از باندل کامیت‌شده بازسازی و برای همیشه در مخزن ثبت شد؛ تایم‌اوت در ۷ لایه بازتولید نشد (بدترین ۱۴ms)؛ علت به لایهٔ UI/تستِ ازدست‌رفته محدود شد؛ ۵ فرضیه + الزامات دفاعی + ۳ یافتهٔ طراحی برای ۵-B/۵-C ثبت شد.
- قدم بعدی: فاز ۵-B فقط پس از تأیید مالک.

---
Task ID: 37
Agent: main (Z.ai — permanent-protocol session)
Task: P00 (تطبیق و ثبت پروتکل دائمی) + آغاز P05-B طبق بخش ۹ پروتکل
Date: Oct 29, 2026

Work Log:
- دریافت «پروتکل دائمی اجرای مرحله‌ای» از مالک: شناسه‌های ثابت P00–P13، وضعیت‌های شش‌گانهٔ فاز، چرخهٔ Commit/Push/تأیید اجباری، قاعدهٔ ادامهٔ خودکار پس از Push و قالب گزارش بخش ۸.
- بازخوانی وضعیت از شواهد واقعی (قانون: اعتماد نکردن به علامت «انجام شد» بدون مدرک): کلون موجود `/home/z/my-project/medpad-work/MedPad` روی develop، working tree پاک، بدون stash. fetch تازه: local develop = origin/develop = `558d056` (همگان). main = origin/main = `8db3319` (دست‌نخورده). شاخه‌های دیگر ریموت: fix/android-security-build (`c525afc`) و phase0-security-review (`864b707`) — قدیمی و دست‌نخورده.
- راستی‌آزمایی تازه با API گیت‌هاب (Oct 29، همین اجرا): develop=`558d056abc3854b3871d27d85065d966faa0c361` · main=`8db3319aef5f736d03bea94b825c1d7ecd9fc990` · آخرین ریلیز: v1.9.13-dev.2 (2026-10-26, prerelease) — **هیچ ریلیز جدیدی وجود ندارد** (تأیید مجدد ادعای مستندات).
- تطبیق مستندات با واقعیت: فاز صفر (Task 35 / کامیت `5e5d911`) و فاز ۵-A (Task 36 / کامیت `89cfb95` + ثبت SHA در `558d056`) هر دو با شواهد معتبر DONE_VERIFIED هستند — تکرار نمی‌شوند. اولین فاز ناقص: **P05-B**.
- ثبت پروتکل دائمی در PROGRESS (بخش ۲.۲ جدید: خلاصهٔ قوانین + جدول وضعیت P00–P13 با شواهد + نکتهٔ مهم نگاشت شماره‌های جدید P06–P13 با بسته‌های قدیمی ۶–۱۲).
- اصلاح ارجاع‌های «WORKLOG Task 36» → «WORKLOG (Task ID: 36)» در سه نقطهٔ PROGRESS (رجوع دقیق به ساختار واقعی لاگ نوبت‌ها).
- در همین اجرا پس از این کامیت: ادامهٔ مستقیم به P05-B طبق بخش ۹ پروتکل (بدون توقف برای تأیید مالک، چون مانع واقعی/تصمیم مالک/عملیات نیازمند مجوز وجود ندارد).

Stage Summary:
- P00 تحت پروتکل جدید تثبیت شد: وضعیت واقعی مخزن و فازها با شواهد تازه ثبت و پروتکل برای اجراهای بعدی ماندگار شد. عامل بعدی باید مستقیماً از جدول بخش ۲.۲ PROGRESS ادامه دهد: اولین فاز ناقص = P05-B.

---
Task ID: 38
Agent: main (Z.ai — permanent-protocol session)
Task: فاز P05-B — تکمیل منطق داده و API اعلان‌های پنل (طبق پروتکل دائمی؛ ادامهٔ مستقیم پس از P00)
Date: Oct 29, 2026

Work Log:
- مبنای سورس: استخراج تازهٔ blob پایه + زنجیرهٔ ۱۰-وصله‌ای تمیز در p5b-src → بایت‌به‌بایت هم‌ارز chain-test2 (درخت تأییدشدهٔ فاز صفر؛ صفر تفاوت، صفر rej). بررسی کامل سورس API اعلان‌ها + auth + notify + اسکیما.
- یافته‌های بررسی: احراز هویت isAdmin (کوکی medpad_admin ↔ Config.adminSession) سالم و جدا از توکن‌های اپ؛ اعلان‌های پنل سراسری (تک‌مدیره، طراحی بستهٔ ۴)؛ دی‌داپ eventKey + P2002 سالم؛ دو نقص اثبات‌شده (شمارندهٔ پنجره‌ای ۱۰۰تایی؛ سقوط ids خراب به mark-all خاموش + 500 برای ids غیررشته‌ای).
- اصلاحات (۲ فایل، افزایشی): GET فیلد totalUnread (count کل جدول) افزود؛ unread دست‌نخورده. POST /read سخت‌سازی: bad-body برای بدنهٔ غیرشیء JSON، bad-ids برای ids تهی/غیرآرایه/غیررشته‌ای/بیش از ۱۰۰۰؛ ids غایب/null = قرارداد mark-all (سازگار با NI6). پاسخ بدون شمارنده ماند (اجبار refetch — دفاع H1).
- فرایند بیلد بازسازی‌شده (برای P11/5-D قابل تکرار):
  1. `npm install --no-audit --no-fund` (npm 11.19.1؛ هشدار install-scripts ولی انجین‌ها داخل tarball @prisma/engines حاضر بودند)
  2. `DATABASE_URL="file:./dev.db" npx prisma generate` → node_modules/.prisma/client با هر سه انجین (debian .so + windows .dll + wasm) — فهرست فایل‌ها با باندل مرجع مقایسه شد
  3. `DATABASE_URL="file:./build-time.db" npm run build` (next 16.4.3 standalone + cp static/public)
  4. بررسی‌ها: هر دو مسیر اعلان در فهرست روت‌ها ✓ · static/public داخل standalone ✓ · بدون .env ✓ · totalUnread/bad-ids در چانک‌های سرور ✓ · این بار tracer خودش .prisma را کپی کرد (بررسی شد، نه فرض)
  5. استیجینگ: `git archive HEAD` + جایگزینی app/panel با بیلد جدید؛ template.db و prisma-schema.prisma و uploads از باندل کامیت‌شده (اسکیما تغییر نکرد — cmp یکسان)
- تست‌ها (همه واقعی، نصب تازه از install.mjs + بوت server-manager):
  - سوئیت جدید test-panel-notifications-api.sh (PKG_DIR=استیجینگ) → اجرای اول ۶۰/۶۲: دو باگ خود اسکریپت (جابجایی sys.argv[1]/[2] در heredoc پایتونی PN8) → اصلاح → اجرای دوم ۶۲/۶۲ MODE=full (ALL-P5API-CHECKS-PASS). درس ۵-A (باگ اجرای اول اسکریپت) تکرار شد و صادقانه ثبت می‌شود.
  - رگرسیون NI روی استیجینگ بدون .git → ۳۸+۱: شکست محیطی NI9 (استخراج template قدیمی از git history)؛ استیجینگ دوم p5b-repo (کلون کامل + باندل جدید) → NI کامل ۴۳/۴۳ (ALL-NOTIF-CHECKS-PASS). درس: برای NI9، PKG باید مخزن git کامل باشد.
  - سوئیت جدید روی باندل کامیت‌شده (repo خود) → MODE=baseline: ۳۲ سبز/۰ شکست/۴ skip — probe قرارداد به‌طور خودکار totalUnread را تشخیص می‌دهد؛ تا بازسازی باندل ۵-D شکست کاذبی نیست.
  - زنجیرهٔ ۱۱-وصله‌ای (۱۰ وصله + panel-src-phase2-package5.patch) روی استخراج تازه: صفر reject؛ خروجی بایت‌به‌بایت هم‌ارز سورس تست‌شده (cmp).
- وصلهٔ سورس: patches/panel-src-phase2-package5.patch با قرارداد orig/b (مشابه وصله‌های قبلی؛ اعمال با patch -p1 روی نتیجهٔ زنجیرهٔ ۱۰-وصله‌ای).
- تغییرات محصول در باندل کامیت‌شده: هیچ (عمدی — بازسازی باندل در ۵-C/۵-D). main دست‌نخورده (8db3319). بدون ریلیز.
- پس از Push و تأیید SHA: ادامهٔ مستقیم به فاز ۵-C طبق بخش ۹ پروتکل.

Stage Summary:
- فاز ۵-B کامل: قرارداد API اعلان‌های پنل مستند/آزمون‌شد (62 چک full + 43 رگرسیون + 32 baseline)، دو نقص اثبات‌شده در سورس اصلاح و در وصلهٔ panel-src-phase2-package5 ماندگار شد. UI فاز ۵-C باید totalUnread ?? unread مصرف کند، فهرست را «آخرین اعلان‌ها» بنامد، و پس از mark-all همیشه refetch کند.
- تأیید Push فاز ۵-B: SHA ریموت `4386f192241b9c1d3a8d818946fada49bc71bf69` با fetch تازه + API گیت‌هاب تأیید شد (develop). `main` دست‌نخورده: `8db3319` (تأیید API). وضعیت P05-B → DONE_VERIFIED.

---
Task ID: 39
Agent: main (Z.ai — permanent-protocol session)
Task: فاز P05-C — رابط کاربری زنگ مرکز اعلان‌ها (طبق پروتکل دائمی؛ ادامهٔ مستقیم پس از P05-B)
Date: Oct 29, 2026

Work Log:
- بررسی نقاط ادغام: Dashboard.tsx (هدر، ۳۰۵ خط)؛ تنها زنگ موجود = دکمهٔ «اعلان‌های مرورگر» پایه (requestNotifyPermission)؛ سوکت با useRealtimeSocket و رویدادهای checkin/checkout/notification/proximity/customer-added → bumpRefresh (debounce 400ms).
- پیاده‌سازی NotificationsBell.tsx (جدید، ~290 خط): نشانگر totalUnread ?? unread با سقف نمایش 99+، polling 30s (غیرفعال وقتی tab hidden)، refetch در باز شدن popover و پس از هر علامت‌گذاری، علامت‌گذاری تکی/همه، حالت‌های loading/empty/error+retry/expired+relogin، آیکون kind با fallback، تاریخ فارسی، dir=rtl، popover واکنش‌گرا w-[min(92vw,24rem)] با max-h-80 scroll، برچسب پنجرهٔ ۱۰۰تایی.
- دفاع‌های ۵-A: H1 refetch تضمینی (پاسخ POST بدون شمارنده) · H2 data-testid برای همهٔ عناصر (bell-button/bell-unread-count/bell-list/bell-item-*/bell-mark-one-*/bell-mark-all/bell-empty/bell-loading/bell-error/bell-retry/bell-session-expired/bell-relogin) · H3 توقف polling پس از 401 + حالت صریح + ورود مجدد · H4 نسخه‌بندی پاسخ‌ها (seq ref) + رویداد سوکت فقط از طریق refreshSignal از والد.
- ادغام Dashboard: <NotificationsBell refreshSignal={refreshKey} onSessionExpired={logout}/> در هدر قبل از زنگ مجوز؛ زنگ پایه دست‌نخورده (قابلیت مکمل).
- بیلد: همان فرایند ثبت‌شدهٔ Task 38 (npm install موجود + prisma generate + npm run build)؛ کد زنگ در چانک‌های SSR و static تأیید شد.
- درس مهم مونتاژ باندل: کپی کامل .next (به‌جای چیدمان قراردادی {BUILD_ID, standalone, static}) دو شکست سخت‌سازی داد: (۱) sourcemap غیرقابل‌ship حاوی متن کامنت سورس login/route.ts با رشتهٔ admin123 (در چانک‌های minified نیست — بررسی شد؛ false-positive از نظر امنیتی ولی طبق قرارداد نباید در باندل باشد)؛ (۲) symlinkهای .next/node_modules و standalone/.next/node_modules (bcryptjs/@prisma). حل: مونتاژ باریک فقط سه مسیر با cp -rL. پس از آن ۱۵/۱۵ چک سخت‌سازی سبز (روی باندل مرجع هم اجرا شد — baseline معتبر).
- تست UI جدید test-panel-bell-ui.sh (BU0–BU8، Playwright): اجرای اول ۲۹/۳۱ — باگ انتظار خود تست (شمارش ۴ ردیف بعد از mark-one + دو رویداد جدید؛ محصول درست بود)؛ اصلاح شد؛ اجرای دوم روی باندل باریک‌شده ۳۱/۳۱ (ALL-P5UI-CHECKS-PASS).
- رگرسیون روی باندل نهایی: NI 43/43 · P5API 62/62 (MODE=full) — هر دو پس از باریک‌سازی دوباره اجرا شدند.
- وصلهٔ panel-src-phase2-package5.patch بازتولید شد (۴ فایل: دو route از ۵-B + Dashboard.tsx + NotificationsBell.tsx جدید)؛ زنجیرهٔ ۱۱-وصله‌ای روی استخراج تازه: صفر reject، هر ۴ فایل بایت‌به‌بایت هم‌ارز سورس تست‌شده، هیچ فایل دیگری تفاوت ندارد.
- باندل مخزن (app/panel/.next) با بیلد جدید جایگزین شد (۸۲ M + ۱۵ D + ۶ A در git)؛ template.db/prisma-schema/uploads بدون تغییر.
- تغییرات محصول اندروید: هیچ. main دست‌نخورده (8db3319). بدون ریلیز.

Stage Summary:
- فاز ۵-C کامل: زنگ مرکز اعلان‌ها در سورس و باندل کامیت‌شده حاضر است؛ همهٔ الزامات دفاعی H1–H4 در مرورگر واقعی اثبات شد (۳۱/۳۱)؛ رگرسیون سبز (NI 43/43، P5API 62/62)؛ سخت‌سازی ۱۵/۱۵؛ زنجیرهٔ وصله‌ها تمیز. قدم بعدی: ۵-D (رگرسیون ۶ سوئیت + بسته‌بندی نهایی).
- تأیید Push فاز ۵-C: SHA ریموت `73806a6a57b9c8933347fbdb75f127eab5bb378b` با fetch تازه + API گیت‌هاب تأیید شد (develop؛ ۱۰۳ فایل). `main` دست‌نخورده: `8db3319` (تأیید API). وضعیت P05-C → DONE_VERIFIED.
