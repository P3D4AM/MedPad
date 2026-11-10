# گزارش فنی یکپارچه‌سازی Holoo API با MedPad — فازهای ۱ تا ۶

**تاریخ:** Nov 10, 2026 · **شاخه:** `develop` (پایه: `1b5770d` — فاز صفر) · **وضعیت `main`:** دست‌نخورده
**مبنا:** دستور جامع یکپارچه‌سازی مالک + نقشهٔ فاز صفر (`HOLOO-PHASE0-REPORT-fa.md`)

## ۰. خلاصهٔ اجرا

| فاز | خروجی | وضعیت |
|---|---|---|
| ۱ — داده و اتصال | ۴ مدل Prisma جدید + `Customer.erpCode` + `Purchase.holooJson` + DDL بوت + `lib/holoo-config.ts` + `lib/holoo.ts` | ✅ |
| ۲ — موتور سینک | `lib/holoo-sync.ts` (ارکستراتور + زمان‌بند درون همان پروسهٔ پنل) | ✅ |
| ۳ — APIها | ۵ مسیر `admin/holoo/*` + بلوک‌های `erp` در `/api/app/customers` و هر دو پروفایل ۳۶۰ | ✅ |
| ۴ — پنل | تب «هلو» (HolooTab) + بلوک مالی در پروندهٔ ۳۶۰ | ✅ |
| ۵ — اندروید | ستون‌های erp در `Doctor` (Room v15) + پارس آینه + رندر مالی آفلاین | ✅ |
| ۶ — بیلد/تست | وصلهٔ phase9 + باندل + template.db + سوئیت audit ۶۶چکی + ۲۰۴ تست اندروید | ✅ |

## ۱. پژوهش وب‌سرویس (پیش از پیاده‌سازی)

- **PDF رسمی** «وب سرویس هلو» نسخهٔ api-1401-v1.7 (نمایندگی محاسب‌پرداز حکمت): نسل قدیمی‌تر (`/api/customer/getall` با Bearer خریداری‌شده).
- **نسل TncHoloo** (هدف دستور مالک): کلاینت متن‌باز `zoghal/HolooWebService` (مسیرها: `Customer`، `Customer/count`، `Product`، `Invoice/Invoice`، `MainGroup`/`SideGroup`/`Unit`) + مستندات توسعه‌دهندهٔ chortkey.ir:
  - `POST {base}/Login` با بدنهٔ `{"userinfo":{"username","userpass":"<Base64>","dbname"}}`
  - پاسخ: `{"Login":{"State":"True","Token":"Bearer <tok>","ErrorCode":0,"Error":""}}`
  - درخواست‌های بعدی: هدر `Authorization: Bearer <tok>`
  - وب‌هوک هم وجود دارد (`webhook/subscribe`) اما **استفاده نشد** — مدل دستور مالک سینک دوره‌ای است و آدرس عمومی تضمین‌شده نیست.
- هر نقطهٔ نامطمئن (نام دقیق پارامتر `GetCustPay`، وجود فیلد اقلام ریز فاکتور، معنای date.from/to، فرمت تاریخ واقعی) به‌صورت **ASSUMPTION** در کد مستند شد و «تست اتصال» آن را روی سرویس واقعی **کاوش و نمایش** می‌دهد (§۶).

## ۲. مدل داده و مالکیت

جداول جدید (DDL بوت idempotent، بایت‌همسان با `prisma db push` — ایندکس‌های یکتا به‌صورت `CREATE UNIQUE INDEX` جدا، مطابق خروجی Prisma):

- **`ErpCustomer`** (مالک: هلو) — erpCode یکتا، code، name، mandeh، credit، nationalId، economicId، tel، mobile، city، ostan، mantagheh، zipCode، address، job، email، active (فقط غیرفعالیِ اعلام‌شدهٔ صریح)، contentHash، lastSyncedAt.
- **`ErpProduct`** (مالک: هلو) — erpCode یکتا، code، name، few، buyPrice، sellPrice، group، active، contentHash.
- **`ErpPayment`** (مالک: هلو) — (customerErpCode, code) یکتا، name، price، date، mandeh، contentHash. برای پرداخت‌های بدون کد، کد قطعی از هش رکورد ساخته می‌شود (ایدمپوتنت).
- **`HolooSyncLog`** (مالک: MedPad) — kind (initial/incremental/manual)، status، startedAt/finishedAt، شمارش‌های checked/created/updated/unchanged/deactivated، errors (آرایهٔ JSON از پیام‌های **امن**)، checkpoint.

ستون‌های جدید: `Customer.erpCode` (nullable + ایندکس یکتا — **تنها پل**؛ هیچ فیلد عملیاتی MedPad از مسیر سینک بازنویسی نمی‌شود، حتی `Customer.active` و `updatedAt`) · `Purchase.holooJson` (سرصفحهٔ خام فاکتور برای provenance).

**قواعد مالکیت پیاده‌شده:** هلو صاحب مانده/اعتبار/قیمت/فاکتور/پرداخت است (فقط جداول Erp* + ردیف‌های `source='holoo'`)؛ MedPad صاحب دادهٔ عملیاتی. `Customer.erpCode` تنها نقطهٔ اتصال. تطبیق: erpCode → نام نرمال‌شدهٔ یکتا (ي→ی، ك→ک، أ/إ/آ→ا، ة→ه، فشرده‌سازی فاصله‌ها؛ فقط تطبیق یکتا — نام مبهم = بدون تطبیق) → ایجاد مشتری جدید با type='other' **بدون هیچ تخصیص خودکار** (§۱۰ دستور). شهر فقط با تطبیق dataset آفلاین ایران پر می‌شود (جفت استان+شهر، وگرنه فقط نام یکتای شهر؛ مبهم = خالی)؛ مختصات = مرکز شهر تطبیقی یا 0/0.

## ۳. کلاینت و سینک

`lib/holoo.ts` — بدون وابستگی جدید (fetch سراسری Node 24):
- Login با بدنهٔ مستند؛ پارس مدارا (شکل `{"Login":{…}}` یا مسطح؛ Token با/بدون پیشوند Bearer نرمال می‌شود).
- توکن در Config کش می‌شود (`holoo.token`)؛ تغییر هر fld اعتبارنامه کش را می‌اندازد. **دقیقاً یک** re-login + retry روی 401/403.
- تایم‌اوت ۳۰ث (پروب ۱۰ث)؛ خطاها طبقه‌بندی: not-configured / network / timeout / auth / http / invalid-response — پیام‌ها هرگز حاوی رمز/توکن نیستند (`holooSafeError`).
- `extractItems` مدارا با آرایه/`{data:[…]}`/تک‌رکورد؛ مپرهای چندنامی (ErpCode/erpCode/…، SumPrice/sumPrice/Total/…، Date/FacDate/…).
- `holooContentHash`: sha256 روی JSON کانونی (کلیدهای مرتب‌شده) — کلید تشخیص تغییر.

`lib/holoo-sync.ts` — **مقایسهٔ دوره‌ای کامل** (مسیر مصوب فاز صفر):
- هر اجرا: Customerها (کامل) → اتصال/ایجاد MedPad → Productها (کامل) → Invoiceها (`Invoice` و در 404 fallback به `Invoice/Invoice`) → پرداخت‌ها (per-customer؛ initial/manual: همه با فاصلهٔ مؤدبانهٔ ۲۰۰ms و سقف ۵۰۰۰؛ incremental: فقط مشتریان با فاکتور جدید/تغییرکردهٔ همان اجرا).
- فاکتور → `Purchase(source='holoo', externalId=ErpCode ?? holoo-no-Code, invoiceNo=Code, discount=SumDiscount/Scot, date=پارس شمسی/میلادی/epoch, paymentStatus=null — حدس زده نمی‌شود)` + **یک** قلم جمع‌کل `PurchaseItem(total=SumPrice, productName='جمع کل فاکتور هلو')` + `holooJson`. اقلام ریز ادعا نمی‌شوند (Endpoint اثبات‌نشده — §۶).
- عدم-حذف: غیبت ≠ حذف؛ `active=false` فقط با اعلام صریح (IsActive=false یا IsBlackList=true). رکورد حذف‌شده از پاسخ، فعال می‌ماند (با تست اثبات شد).
- قفل همزمانی (globalThis) — هم زمان‌بند هم دکمهٔ دستی؛ ردیف‌های running یتیم در بوت بعد از ۳۰ دقیقه error می‌شوند.
- زمان‌بند: تایمر ۳۰ثانیه‌ای در همان پروسهٔ پنل (شروع در instrumentation؛ unref)، در هر تیک Config تازه خوانده می‌شود (تغییر بازه بدون ری‌استارت)؛ شلیش فقط وقتی enabled + تنظیم کامل + بازه سپری + اجرایی در جریان نیست.

## ۴. APIها

| مسیر | متد | دسترس | نکته |
|---|---|---|---|
| `/api/admin/holoo/settings` | GET/PUT | مدیر | PUT اعتبارسنجی فارسی (baseUrl با http(s) الزامی، بازهٔ ۱..۱۴۴۰)؛ رمز فقط-نوشتنی؛ GET فقط `hasPassword`؛ تغییر اعتبارنامه توکن کش را می‌اندازد؛ audit بدون رمز |
| `/api/admin/holoo/test` | POST | مدیر | **ورود واقعی** (کش دور ریخته می‌شود) + کاوش فقط-خواندنی ۵ Endpoint با status/شکل/نمونه‌کلید؛ پاسخ صادقانهٔ fail |
| `/api/admin/holoo/sync` | POST | مدیر | fire-and-forget (اجراهای طولانی درخواست را نگه نمی‌دارند)؛ ۴۰۹ اگر اجرا در جریان است |
| `/api/admin/holoo/status` | GET | مدیر | تنظیمات ساده‌شده + running + آخرین ۱۰ لاگ + شمارش‌ها + nextRunInSec |
| `/api/admin/holoo/products` | GET | مدیر | فهرست کالاهای همگام (جستجو/صفحه‌بندی؛ فقط-خواندنی) |
| `/api/app/customers` | GET | کارمند | **افزایشی**: بلوک `erp` + محرک `ErpCustomer.lastSyncedAt > since`؛ اپ هرگز مستقیم به هلو وصل نیست |
| پروفایل ۳۶۰ (پنل + اپ) | GET | مدیر/کارمند تخصیص‌یافته | `erpAccount` (مانده/اعتبار/…) + `erpPayments` (۵۰ مورد اخیر) |

## ۵. پنل و اپ

- **HolooTab**: فرم تنظیمات (enabled/baseUrl/dbname/username/رمز mask/بازه) + هشدار HTTP داخلی + دکمه‌های ذخیره/تست اتصال واقعی/سینک دستی + جدول نتایج کاوش Endpointها + کارت‌های شمارش + جدول لاگ اجراها (نوع/وضعیت/آمار/خطاها) + کالاهای همگام با جستجو + نظرسنجی وضعیت ۱۰ثانیه‌ای. تمام رابط فارسی.
- **CustomerProfile (پنل)**: کارت «اطلاعات مالی هلو» (مانده رنگ‌بندی‌شده، اعتبار، کد هلو، آخرین ۱۰ پرداخت) یا حالت خالی صادقانه.
- **اندروید**: `Doctor` += erpCode/erpMandeh/erpCredit (nullable؛ Room **v14→v15** افزایشی) · `CheckinSync.parseErpMirror` (خالص؛ بلوک `erp` غایب/ناقص → null، نه 0/NaN) · بلوک مالی در CustomerProfileActivity از کش پروفایل (**آفلاین**) · رشته‌های فارسی. **vCode بدون تغییر (۲۲) — هیچ ریلیزی mandated نبود.**

## ۶. ریسک‌ها و ASSUMPTIONهای باز (برای سرویس واقعی)

| مورد | برخورد |
|---|---|
| پارامتر دقیق `GetCustPay` | `customerErpCode` فرستاده می‌شود (طبق دستور)؛ «تست اتصال» شکل پاسخ واقعی را نشان می‌دهد |
| اقلام ریز فاکتور | ادعا نمی‌شود؛ Endpoint کاندید در تست اتصال کاویده می‌شود |
| `date.from/date.to` | استفاده نمی‌شوند (مقایسهٔ کامل + contentHash)؛ معنایشان باید روی سرویس واقعی اثبات شود |
| فرمت تاریخ واقعی | پارس مدارا (شمسی/میلادی/epoch)؛ تاریخ غیرقابل‌تجزیه = زمان سینک + ثبت در خطاها |
| مسیر فاکتور | `Invoice` و fallback `Invoice/Invoice` (پروب هر دو را نشان می‌دهد) |
| رمز قابل‌بازیافت | Base64 در Config سمت سرور (مبهم‌سازی، نه رمزنگاری — مستند)؛ هرگز به فرانت/لاگ برنمی‌گردد |
| HTTP بدون TLS | هشدار دائمی در تب؛ سرویس فقط شبکهٔ داخلی |

## ۷. شواهد (همه واقعی، همین سشن)

- **بیلد پنل:** زنجیرهٔ ۱۹-وصله‌ای (phase9-holoo جدید؛ ۱۶ فایل؛ راند-تریپ بایت‌به‌بایت: اعمال وصله روی زنجیرهٔ ۱۸ == درخت توسعه) · کامپایل موفق · سخت‌گیری **۴۱/۴۱ PASS** (۷ چک جدید) · tsc: ۱۴ خطای بدهی قدیمی، **صفر جدید** · باندل ترویج‌شده: ۲٬۱۳۹ فایل، صفر symlink · template.db بازتولید (۲۵ جدول) · DDL بوت == خروجی db push.
- **سوئیت جدید `audit-tests/test-holoo.sh` + mock صریح `holoo-mock.mjs`: ۶۶/۶۶ سبز** — H0 بوت/مایگریشن · H1 قرارداد تنظیمات (۴۰۱/پیش‌فرض‌ها/۴۰۰ فارسی/hasPassword/audit) · H2 تست اتصال واقعی (اعتبارنامهٔ غلط = خطای طبقه‌بندی‌شده؛ ۵ پروب؛ رمز فقط Base64 در سیم؛ ۴۰۴ صادقانهٔ مسیر fallback) · H3 سینک کامل (۳ مشتری/۲ کالا/۳ پرداخت/۳ فاکتور؛ تاریخ شمسی ۱۴۰۴/۰۸/۱۵=2025-11-06 و میلادی 2026-09-16؛ قلم جمع‌کل=SumPrice؛ مانده از گزارش پرداخت؛ blacklist→ErpCustomer.active=false ولی Customer.active=1؛ تطبیق نام با ي عربی؛ صفر تخصیص خودکار؛ فیلدهای عملیاتی دست‌نخورده) · H4 بلوک‌های erp در اپ (تخصیص‌یافته بله، غیرمجاز ۴۰۳، پروفایل اپ و پنل) · H5 تشخیص تغییر + **عدم-حذف** (مشتری حذف‌شده از پاسخ زنده ماند؛ مانده/اعتبار/جمع فاکتور به‌روز؛ شمارش‌های لاگ صادق) · H6 **زمان‌بند** (اجرای افزایشی خودکار ≤ ~۲دق) · H7 جاروب امنیتی (رمز/توکن در هیچ پاسخ API، هیچ لاگ mock، هیچ ردیف HolooSyncLog) · H8 آرتیفکت‌های باندل (۵ مسیر + مارکرهای UI).
- **رگرسیون:** customer360 **۴۶/۴۶** · settings-center **۵۷/۵۷** — SC9 صادقانه re-pin شد: پس از فاز ۸ (تصمیم مالک)، انتساس دیگر PanelNotification/اعلان مدیریتی نمی‌سازد؛ اثبات Push زنده به رویداد **چک‌این** منتقل شد + چک جدید: «انتساس هیچ اعلان مدیریتی نمی‌سازد». (ریشهٔ ۲ شکست اولیه این سوئیت، رفتار فاز ۸ بود که در Task 63 این سوئیت اجرا نشده بود — نه تغییرات هلو.)
- **اندروید:** محیط از صفر (JDK 21.0.13 + SDK 34/bt-34.0.0 + mirror هواوی از طریق `beforeSettings` در init.gradle — درس: allprojects با FAIL_ON_PROJECT_REPOS ناسازگار است؛ `-ProbolectricRepoUrl`؛ run-gradle.sh با `</dev/null` + pkill دیمن‌ها) · **testDebugUnitTest ۲۰۴/۲۰۴ سبز، ۳۶ کلاس** (+۸ جدید: Migration14to15Test ۲ + ErpMirrorParseTest ۶؛ به‌روزرساری فیکسچرهای 9to10/13to14/TestFixtures برای حذف ستون‌های v15 هنگام downgrade + BackupManager CURRENT_SCHEMA_VERSION=۱۵ + LegacyDbImportTest future=+۱) · **assembleDebug BUILD SUCCESSFUL** — APK ۹٬۱۵۲٬۰۰۲ بایت (aapt: vCode 22 / 1.9.15-dev.1).

## ۸. محدودیت‌های اعلامی

- **دسترسی واقعی به سرویس هلو (IP/dbname/اعتبارنامه) در دسترس نیست** — تمام تست‌های اتصال/سینک با mock صریح محلی انجام شد و همه‌جا «mock» نامیده شده است؛ هیچ اتصال واقعی ادعا نمی‌شود.
- هیچ API نوشتنی به هلو صدا زده نمی‌شود (فقط Login + GETها).
- برای فعال‌سازی واقعی: تب «هلو» → آدرس (`http://IP:8080/TncHoloo/api`) + dbname + کاربر + رمز → ذخیره → «تست اتصال» → سینک دستی → روشن‌کردن سینک خودکار. سرویس فقط در شبکهٔ داخلی.
- `main` دست نخورده؛ همهٔ کامیت‌ها روی `develop`؛ بدون force-push.
