# گزارش تست ریلیز آزمایشی develop — MedPad نسخهٔ v1.9.12-test.1

> **این نسخه یک ریلیز آزمایشی (Pre-release) برای تست مالک است — نه نسخهٔ پایدار و نه مناسب انتشار عمومی.**

## شناسهٔ انتشار

| مورد | مقدار |
|---|---|
| شاخهٔ مبنا | `develop` (ادامهٔ امن fast-forward از شاخهٔ سخت‌شدهٔ `phase0-security-review`) |
| **Commit دقیق ساخت هر دو Artifact** | `3c827f187ca2b661e1d468a6f15f9687b30cc141` |
| Tag ریلیز | `develop-v1.9.12-test.1` (روی همان کامیت) |
| شاخهٔ `main` | **دست‌نخورده** (روی `8db3319a` باقی ماند؛ هیچ کامیت/push/mergeای روی آن انجام نشد) |
| لینک Release | https://github.com/P3D4AM/MedPad/releases/tag/develop-v1.9.12-test.1 |
| دانلود بستهٔ وب | https://github.com/P3D4AM/MedPad/releases/download/develop-v1.9.12-test.1/MedPad-Web-Offline-develop-v1.9.12-test.1.zip |
| دانلود APK اندروید | https://github.com/P3D4AM/MedPad/releases/download/develop-v1.9.12-test.1/MedPad-Tracker-Android-develop-v1.9.12-test.1.apk |

## فایل‌های خروجی (Artifactها)

| فایل | اندازه | SHA-256 |
|---|---|---|
| `MedPad-Web-Offline-develop-v1.9.12-test.1.zip` | 142,802,835 بایت (~136 MB) | `806961a2f34919b956e290a419c94bda76fda4114198b8c0434ab37b827bda39` |
| `MedPad-Tracker-Android-develop-v1.9.12-test.1.apk` | 7,829,498 بایت (~7.5 MB) | `fc4cf92d68e2d6e5c300463c64db7f96e1f200716237b242fadbfe522464cb44` |
| `SHA256SUMS.txt` | — | داخل خود فایل + پیوست Release |

محتوای ZIP وب (۲٬۴۳۰ فایل): نصب‌کننده‌های ویندوز/لینوکس (`install.bat`/`install.ps1`/`install.sh`/`install.mjs` + `wizard.mjs`)، پنل Next.js باندل‌شده (`.next/standalone` کامل با `node_modules` و انجین‌های Prisma برای ویندوز/لینوکس)، سرویس Realtime با `node_modules`، دیتابیس قالب خالی (`app/panel/db/template.db`)، بستهٔ Node.js برای نصب آفلاین (`offline/node-v24.21.0-x64.msi` برای ویندوز + tar.xz لینوکس)، فونت self-hosted، `README-fa.md` و راهنمای سریع جدید `INSTALL-GUIDE-fa.md`. **هیچ دیتابیس واقعی، `.env`، توکن، رمز یا Keystoreای داخل بسته نیست** (بررسی خودکار انجام شد).

## محیط ساخت

| ابزار | نسخه |
|---|---|
| Node.js | v24.21.0 |
| JDK | OpenJDK 21.0.13 LTS (Temurin؛ JDK کامل شامل javac/jlink — JRE سیستم کافی نبود) |
| Gradle | 8.7 (wrapper رسمی پروژه) |
| Android Gradle Plugin / Kotlin | 8.4.2 / 1.9.24 (KSP 1.9.24-1.0.20) |
| Android SDK | cmdline-tools 11076708 · build-tools 34.0.0 · platform android-34 · platform-tools |
| سیستم عامل ساخت | Linux x64 (Debian 13) |

## دستورات ساخت (تکرارپذیر)

```bash
# ── بستهٔ وب (از کامیت 3c827f1) ──
cd MedPad
git archive --format=zip --prefix=MedPad-Web-Offline-develop-v1.9.12-test.1/ \
  -o MedPad-Web-Offline-develop-v1.9.12-test.1.zip 3c827f1 -- \
  install.bat install.ps1 install.sh install.mjs wizard.mjs services.mjs \
  server-manager.mjs tunnel.mjs lib ui offline app README-fa.md \
  INSTALL-GUIDE-fa.md WORKLOG-MedPad.md

# ── APK اندروید (Debug، امضا با کلید Debug استاندارد) ──
cd Android
export ANDROID_HOME=<sdk> JAVA_HOME=<jdk-21>
./gradlew assembleDebug --no-daemon
# خروجی: app/build/outputs/apk/debug/app-debug.apk → تغییر نام به
#         MedPad-Tracker-Android-develop-v1.9.12-test.1.apk
```

## نتیجهٔ تست‌ها

### الف) سوئیت ممیزی خودکار (روی ZIP نهایی استخراج‌شده از کامیت مبنا)

| سوئیت | نتیجه |
|---|---|
| test-process-safety (ایمنی پروسه‌ها) | **PASS** ۱۸/۱۸ |
| test-wizard-security (امنیت ویزارد نصب) | **PASS** ۱۵/۱۵ |
| test-realtime-security (auth سوکت fail-closed) | **PASS** ۱۵/۱۵ |
| test-v1912 (نصب/بوت/لاگین/رمز/سوکت/آپدیت/مجوزها/CF) | **PASS** ۴۶/۴۶ (شامل ۲ چک جدید A1W) |
| **جمع کل** | **PASS — 94/94 — ALL-SUITES-PASS** |

### ب) تست E2E دستی جریان واقعی کاربر (نصب تعاملی با منو، روی ZIP نهایی)

| # | تست | نتیجه |
|---|---|---|
| 1 | نصب تعاملی پایپ‌شده (منو → «۱ این کامپیوتر» → «۲ ویزارد CLI» → رمز خالی = تولید تصادفی → پورت‌های 3210/3213 → دادهٔ نمونه) | **PASS** — خروج 0 + بنر INSTALL COMPLETE |
| 2 | رمز مدیر تصادفی فقط یک بار چاپ شد (`SAVE THIS NOW` — در هیچ فایلی ذخیره نشد) | **PASS** |
| 3 | پاسخ Health پنل (realtime `:3213/health` → `{"ok":true}`) | **PASS** |
| 4 | باز شدن صفحهٔ ورود (`/` → HTTP 200 در 2ms؛ فونت لوکال 200) | **PASS** |
| 5 | **ورود مدیر با رمز تولیدشده هنگام نصب** (`/api/login` → `{"ok":true}`) | **PASS** |
| 6 | رد رمز پیش‌فرض `admin123` + rate-limit (۵ شکست → 429 + retry-after) | **PASS** (در سوئیت) |
| 7 | صحت مسیرهای API (`/api/login`، `/api/app/login`، `/api/app/customers`، `/api/app/checkin`، `/api/checkins`، `/api/admin/socket-auth`) | **PASS** |
| 8 | ورود کارمند نمونه (`ali`/`1234` از دادهٔ نمونه → توکن `X-Medpad-Token`) | **PASS** |
| 9 | **ثبت check-in با فرمت دقیق multipart اپ اندروید** (customerId/note/localId/lat/lng) | **PASS** — در دیتابیس ثبت شد |
| 10 | نمایش همان check-in در API پنل ادمین | **PASS** |
| 11 | اتصال Realtime احرازشده (توکن mint شده → ACCEPTED در لاگ rt) | **PASS** |
| 12 | **رد اتصال غیرمجاز سوکت** (ناشناس → `rejected unauthenticated`) | **PASS** |
| 13 | رفتار خطا: پورت غلط → Connection refused؛ IP غلط → timeout؛ رمز غلط → خطای فارسی JSON | **PASS** |
| 14 | دسترسی از IP شبکهٔ محلی (شبیه‌سازی اتصال گوشی: پنل 200 + ورود کارمند ok) | **PASS** |
| 15 | استاپ سرویس‌ها → آزاد شدن واقعی پورت‌ها → ری‌استارت → **ماندگاری داده** (check-in بعد از ری‌استارت موجود بود) | **PASS** |
| 16 | شبیه‌سازی مسیر ویندوز (`MEDPAD_SIM_WIN=1`): تولید هر ۶ فایل bat ویندوزی با ASCII+CRLF | **PASS** (سیمولیشن، نه ویندوز واقعی) |

### ج) تست اندروید

| # | تست | نتیجه |
|---|---|---|
| 1 | کامپایل `assembleDebug` (پس از فیکس خطای Kotlin DSL — بخش «باگ‌ها») | **PASS** — BUILD SUCCESSFUL |
| 2 | تأیید امضا با `apksigner verify` | **PASS** — v2 VERIFIED |
| 3 | شناسهٔ امضاکننده | **PASS** — `CN=Android Debug, O=Android, C=US` (**کلید Debug استاندارد اندروید**؛ نه keystore لو رفته، نه رمز `medpad123`) |
| 4 | تأیید `aapt dump badging` | **PASS** — بستهٔ `com.medpad.tracker` · versionCode **11** · versionName **1.9.12-dev.1** · minSdk 24 · targetSdk 34 |
| 5 | `zipalign -c` | **PASS** — VERIFIED |
| 6 | تست‌های واحد موجود پروژه | **NOT RUN** — پروژه هیچ تست واحدی ندارد (فقط `src/main`؛ پوشهٔ `test`/`androidTest` وجود ندارد) |
| 7 | نصب و اجرا روی Emulator یا دستگاه واقعی | **NOT RUN** — Emulator/دستگاه در محیط ساخت موجود نبود (دستورالعمل تست مالک در پایین) |

### د) تست‌های انجام‌نشده (محدودیت‌های محیط)

| تست | وضعیت | علت |
|---|---|---|
| اجرای `install.bat`/`install.ps1` روی **ویندوز واقعی** | **NOT RUN** | محیط ساخت لینوکسی است؛ ویندوز/WSL در دسترس نبود. سیمولیشن `MEDPAD_SIM_WIN` و چک‌های ASCII+CRLF جایگزین شد ولی **به‌عنوان تست ویندوز معرفی نمی‌شود**. |
| نصب APK روی دستگاه/Eemulator و ورود واقعی | **NOT RUN** | دستگاه اندرویدی/Emulator در محیط ساخت نبود. رفتار سیمی اپ (لاگین/چک‌ین/هدر توکن) روی HTTP سطح سرور به‌طور کامل تأیید شد. |
| تست هم‌زمانی چند کاربر / بار سنگین | **NOT RUN** | خارج از دامنهٔ این ریلیز آزمایشی |

## باگ‌های کشف‌شده و فیکس‌شده حین ساخت این ریلیز

هر دو در سفرِ ساخت پیدا شدند، فیکس شدند و گارد رگرسیون گرفتند (کامیت‌های `7368e20` و `3b277f2` — قبل از ساخت Artifactهای نهایی):

1. **رگرسیون CRLF در فایل‌های ویندوزی**: `install.bat`/`install.ps1`/`Android/gradlew.bat` در کامیت‌های فاز صفر با LF خالص ذخیره شده بودند (قانون طلایی ۴ / ریشهٔ باگ garbled ویندوزی قدیم). فیکس: تبدیل به CRLF + `.gitattributes` (قواعد `*.bat/*.ps1 -text`) + دو چک جدید `A1W`/`T2W` در سوئیت ممیزی تا هرگز بی‌صدا تکرار نشود.
2. **خطای کامپایل Kotlin DSL در `Android/app/build.gradle.kts`**: بلاک امضای سخت‌شدهٔ فاز ۲ هیچ‌وقت کامپایل نشده بود (`java.util.Properties()` داخل بلاک `android{}` resolve نمی‌شود). فیکس: `import java.util.Properties` در سطح فایل.

## چه چیزهایی فقط Build شده و چه چیزهایی اجرای واقعی تأیید شده است

**اجرای واقعی تأییدشده (روی همین بسته‌ها):**
- نصب کامل از ZIP استخراج‌شده (مسیر خالی مستقل) با موفقیت + به‌روزرسانی روی نصب در حال اجرا (سوئیت T5)
- بوت پنل + Realtime، پاسخ Health، صفحهٔ ورود، ورود مدیر (با رمز تولیدشده و رمز انتخابی)، رد admin123، rate-limit
- ورود کارمند، لیست مشتریان، **ثبت check-in واقعی روی سرور** و مشاهدهٔ آن در پنل ادمین
- سوکت Realtime: پذیرش احرازشده + رد ناشناس/توکن غلط
- مسیرهای خطا (پورت/IP/رمز غلط) + ماندگاری داده در چرخهٔ استاپ/استارت
- امضای APK با apksigner و شناسهٔ بسته/نسخه با aapt

**فقط Build شده (اجرای واقعی تأییدنشده):**
- نصب‌کنندهٔ ویندوزی روی ویندوز واقعی (`install.bat`/`install.ps1` — فقط سیمولیشن + چک کدگذاری)
- خود APK روی دستگاه اندرویدی (رفتار UI/GPS/ضبط صدا/همگام‌سازی آفلاین Room)
- تانل Cloudflare روی اینترنت واقعی (فقط mock تست شد)

## راهنمای نصب و تست وب روی ویندوز (خلاصه)

راهنمای کامل فارسی داخل خود ZIP هست: **`INSTALL-GUIDE-fa.md`** (کنار `install.bat`). خلاصه:

1. ZIP را کامل استخراج کنید (مثلاً `C:\MedPad`؛ خارج از OneDrive).
2. `install.bat` را دوبل‌کلیک کنید → منو: **۱** (این کامپیوتر) → **۱** (نصب‌کنندهٔ گرافیکی مرورگر؛ توصیه‌شده) یا **۲** (ویزارد متنی).
3. رمز مدیر: یا خودتان وارد کنید یا خالی بگذارید تا رمز تصادفی تولید و **فقط یک بار** نمایش داده شود — همان لحظه یادداشت کنید. اگر گم شد: `node install.mjs --admin-pass رمز-جدید`
4. پنل: `http://localhost:3000` (یا پورت انتخابی). از گوشی: `http://<IP-کامپیوتر>:3000` (IP با `ipconfig`).
5. روشن/خاموش: `start.bat` / `stop.bat` / `server-manager.bat` (GUI).
6. لاگ‌ها: `install.log` (نصب) و `logs/panel.log` + `logs/realtime.log` (اجرا) داخل پوشهٔ نصب.
7. نصب کاملاً آفلاین است (Node.js داخل بسته).

## راهنمای نصب APK و اتصال به سرور

1. APK را به گوشی منتقل کنید و نصب کنید (فعال‌کردن «نصب از منابع ناشناس» لازم است).
   - ⚠️ **این APK با کلید Debug امضا شده است.** اگر نسخهٔ قدیمی MedPad (امضاشده با کلید قبلی) روی گوشی نصب است، اندروید برای نصب، حذف نسخهٔ قبلی را می‌خواهد. **قبل از حذف، مطمئن شوید گزارش‌ها و check-inهای همگام‌نشده‌تان را از داخل اپ قبلی بررسی/منتقل کرده‌اید** (داده‌های همگام‌شده روی سرور می‌مانند و از دست نمی‌روند).
2. سرور را روی کامپیوتر روشن کنید (`start.bat`) و از گوشی، نشانی `http://<IP-کامپیوتر>:3000` را در مرورگر گوشی باز کنید تا از دسترسی مطمئن شوید.
3. در صفحهٔ ورود اپ، فیلد **Server URL** را دقیقاً `http://<IP-کامپیوتر>:3000` پر کنید (با `http://`؛ پورت Realtime را وارد نکنید — اپ از همان نشانی پنل کار می‌کند).
4. با حساب **کارمند** وارد شوید (حساب ادمین پنل مخصوص وب است). برای تست سریع، هنگام نصب سرور دادهٔ نمونه را فعال کنید: کاربران `ali` / `sara` / `mahla` با رمز `1234`.
5. یک مشتری را انتخاب و check-in بزنید؛ سپس در پنل ادمین (تب check-inها) ثبت آن را ببینید.

## دستورالعمل تست مالک (مواردی که در محیط ساخت قابل اجرا نبود)

**روی ویندوز (حدود ۱۵ دقیقه):**
1. ZIP را در مسیری تازه استخراج و `install.bat` را اجرا کنید (اگر SmartScreen هشدار داد: More info → Run anyway).
2. تمام مراحل منو را طبق راهنمای بالا طی کنید؛ رمز تولیدشده را یادداشت کنید.
3. ورود ادمین به `http://localhost:3000` + یک استاپ (`stop.bat`) و استارت (`start.bat`) + لاگین مجدد.
4. اگر خطا دیدید: `install.log` و `logs/*.log` را برای پشتیبانی بفرستید.

**روی گوشی اندرویدی (اندروید 7 به بالا):**
1. سرور را روشن نگه دارید؛ IP کامپیوتر را با `ipconfig` پیدا کنید.
2. APK را نصب کنید (اگر نسخهٔ قبلی بود، اول از داده‌های آن مطمئن شوید و بعد حذفش کنید).
3. در اپ: Server URL = `http://<IP>:3000` → ورود با کارمند نمونه (`ali`/`1234`) → انتخاب مشتری → check-in (با/بدون موقعیت GPS) → تأیید ثبت در پنل.
4. تست خطا: یک بار آدرس غلط (مثلاً پورت 3001) وارد کنید — اپ باید خطای اتصال نشان دهد نه کرش؛ بعد آدرس درست را بزنید.
