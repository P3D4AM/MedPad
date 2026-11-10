# گزارش فنی فاز صفر — یکپارچه‌سازی Holoo API با MedPad

**تاریخ:** Nov 10, 2026 · **شاخه:** `develop` (پایه: `1051aa2`) · **وضعیت `main`:** دست‌نخورده

## ۱. روش بررسی

- کلون تازه `develop` @ `1051aa2` (همگام با ریموت؛ آخرین ریلیز v1.9.15-dev.1).
- بازسازی کامل سورس پنل با زنجیرهٔ ۱۸-وصله‌ای (`scripts/build-panel.sh`، گام‌های ۱–۲) در محیط کاری جدا.
- مطالعهٔ مستقیم: اسکیمای Prisma، `instrumentation.ts` (مایگریشن‌های بوت)، `lib/auth.ts`، `lib/settings.ts`، `lib/customer360.ts`، `lib/audit.ts`، مسیرهای API (customers / app/customers / customers/[id]/profile / app/customers/[id]/profile / admin/settings)، `Dashboard.tsx` (ساختار تب‌ها)، اپ اندروید (`CheckinSync.kt`، `Customers360.kt`، `Entities.kt`، `CustomerProfileActivity.kt`، `AppDatabase.kt`)، الگوی تست‌های `audit-tests/`.
- جستجوی «Holoo / TncHoloo / ErpCode / erp» در کل ریپو.

## ۲. یافته‌های کلیدی (زیرساخت موجود)

1. **طرح آداپتور هلو از قبل مستند است:** گزارش CUSTOMER360-PHASE1 صراحتاً نوشته: «آداپتور آینده: خواندن از هلو → upsert بر اساس `externalId` → ثبت `source='holoo'`». جدول‌های `Purchase`/`PurchaseItem` با `source` (`manual|holoo`) و `externalId` یکتا **موجودند** و DDL	idempotentشان در `instrumentation.ts` اجرا می‌شود.
2. **مدل‌های موجود و نقششان در این پروژه:**
   - `Customer` (+ فیلدهای ۳۶۰: lastName، title، specialty، …، active، appLocalId) — موجودیت عملیاتی CRM؛ **فاقد ستون اتصال ERP**.
   - `Purchase`/`PurchaseItem` — مقصد طبیعی فاکتورهای هلو (طبق طرح مستند پروژه).
   - `Config` (KV) — جایگاه تنظیمات سرور (الگوی `SETTING_DEFS` عددی + کلیدهای رشته‌ای مثل defaultCity)؛ محل ذخیرهٔ تنظیمات اتصال، توکن و واترمارک‌های سینک.
   - `AuditLog` — رد حسابرسی تغییرات مدیریتی (با `writeAudit`).
   - `PanelNotification` — اعلان درون‌پنلی مدیر (برای خطاهای سینک).
3. **اپ اندروید آماده است:** `CustomerProfileActivity` تب «خریدها» دارد که رکوردهای `source=holoo` را با برچسب «هلو» رندر می‌کند و empty-state صادقانه («تا زمان اتصال هلو») نمایش می‌دهد؛ پروفایل ۳۶۰ به‌صورت JSON در `CustomerProfileCache` کش می‌شود (آفلاین-فرستندلی از همان امروز).
4. **سینک آفلاین-فرستندلی:** `/api/app/customers?since=` — فقط مشتریان «تخصیص‌یافته»، LWW روی `Customer.updatedAt` + واترمارک `serverTime` (اصلاح مالک Task 63). تغییرات هلو باید از همین مسیر به اپ برسند، نه اتصال مستقیم اپ به هلو.
5. **زمان‌بند:** هیچ cron/worker جدا وجود ندارد؛ سرور پنل Next.js standalone است و `instrumentation.register()` در هر بوت اجرا می‌شود — محل طبیعی زمان‌بند سینک دوره‌ای (بدون زیرساخت موازی).
6. **احراز هویت:** مدیر با کوکی `medpad_admin` (مقایسه با `Config.adminSession`)؛ اپ با هدر `x-medpad-token`. مسیرهای مدیریتی هلو باید `isAdmin` بگیرند.
7. **هیچ زیرساخت ERP/Import فعالی وجود ندارد** — این پروژه آن را می‌سازد.
8. **Node 24 با `fetch` سراسری** → کلاینت HTTP هلو **بدون وابستگی جدید**.
9. **تقویم:** `lib/jalali.ts` پنل `toGregorian` دارد → تبدیل تاریخ شمسی هلو (مثل 1403/08/15) به میلادی با الگوریتم jalaali موجود.

## ۳. نقشهٔ تغییرات (طرح مصوب این فاز)

### جداول جدید (DDL idempotent در instrumentation + مدل Prisma)
| جدول | نقش | مالک |
|---|---|---|
| `ErpCustomer` | دادهٔ حسابداری مشتری هلو (mandeh، credit، nationalId، economicId، tel، mobile، city، ostan، mantagheh، zipCode، address، job، email، code، **erpCode یکتا**، contentHash، lastSyncedAt) | هلو |
| `ErpProduct` | کالاها (code، name، few، buyPrice، sellPrice، group، erpCode، تصویر در صورت وجود، contentHash) | هلو |
| `ErpPayment` | پرداخت‌های مشتری از `GET /Report/GetCustPay` (customerErpCode، code، name، price، date، mandeh) | هلو |
| `HolooSyncLog` | لاگ هر اجرای سینک (شروع/پایان/نوع/وضعیت/تعداد بررسی-جدید-ویرایش-بدون‌تغییر/خطاها/چک‌پوینت) | MedPad |

### ستون‌های جدید
- `Customer.erpCode` (nullable + ایندکس یکتا جزئی) — **تنها نقطهٔ اتصال**؛ هیچ فیلد عملیاتی MedPad بازنویسی نمی‌شود.
- `Purchase.holooJson` (nullable) — سرصفحهٔ خام فاکتور (SumNaghd/Nesiyeh/Check/Levy/Scot/SanadCode/Type/Time) برای نمایش جزئیات.

### نگاشت فاکتورها
هر فاکتور هلو → یک ردیف `Purchase(source='holoo', externalId=ErpCode, invoiceNo=Code, discount=SumDiscount)` + **یک قلم جمع‌کل** در `PurchaseItem` (total=SumPrice) + `holooJson`. این دقیقاً همان طرح مستند پروژه است و خط زمانی مالی ۳۶۰/اپ را یکپارچه نگه می‌دارد.

### سرویس‌های جدید (سمت سرور، بدون وابستگی جدید)
- `lib/holoo.ts` — کلاینت: Login (رمز → Base64 طبق مستند؛ Base64 رمزنگاری نیست)، کش توکن در Config (`holoo.token`، `holoo.tokenAt`)، هدر `Authorization`، **یک** Login مجدد خودکار روی 401، تایم‌اوت، اعتبارسنجی پاسخ، طبقه‌بندی خطا؛ رمز/توکن هرگز لاگ نمی‌شوند.
- `lib/holoo-config.ts` — تنظیمات (enabled، baseUrl، dbname، username، password فقط-نوشتنی، syncIntervalMin ۱..۱۴۴۰ پیش‌فرض ۳).
- `lib/holoo-sync.ts` — ارکستراتور: Initial/Incremental/Manual، upsert ایدمپوتنت با کلید خارجی پایدار (ErpCode، در نبودش ترکیب پایدار)، تشخیص تغییر با `contentHash`، سیاست عدم-حذف (غیبت در پاسخ ≠ حذف؛ هلو حذف را گزارش نمی‌کند → رکورد فعال می‌ماند و مستند می‌شود)، تفکیک مالکیت (فقط جداول/فیلدهای متعلق به هلو نوشته می‌شوند)، قفل همزمانی، لاگ کامل.

### مسیرهای API جدید (همگی admin، جز مسیر اپ)
- `GET/PUT /api/admin/holoo/settings` — رمز عبور **هرگز** برگردانده نمی‌شود (فقط `hasPassword`).
- `POST /api/admin/holoo/test` — تست اتصال واقعی (Login) + کاوش فقط-خواندنی وضعیت Endpoint جزئیات اقلام فاکتور.
- `POST /api/admin/holoo/sync` — اجرای دستی.
- `GET /api/admin/holoo/status` — وضعیت کامل (آخرین سینک، شمارش‌ها، خطاها، لاگ‌های اخیر).
- `GET /api/admin/holoo/products` — فهرست کالاها برای پنل.
- توسعهٔ `/api/app/customers` (بلوک `erp` با mandeh/credit + تریگر سینک `ErpCustomer.lastSyncedAt > since`) و پروفایل‌های ۳۶۰ (بلوک `erpAccount` + `erpPayments`).

### پنل و اپ
- تب جدید «هلو» در Dashboard (HolooTab) با همهٔ امکانات §۸ دستور (فعال/غیرفعال، تنظیمات، تست، سینک دستی، شمارش‌ها، خطاها، وضعیت Endpoint اقلام، رمز ماسک‌شده).
- پروفایل ۳۶۰ پنل: بلوک اطلاعات مالی هلو (مانده/اعتبار/پرداخت‌ها).
- اپ: ستون‌های erp در `Doctor` (Room v14→v15، مایگریشن افزایشی) + رندر بلوک مالی در `CustomerProfileActivity` از کش موجود.

## ۴. ریسک‌ها و Endpointهای نامشخص

| ریسک | برخورد |
|---|---|
| **ریز اقلام فاکتور:** مستند فعلی فقط سرصفحه را اثبات می‌کند | ادعا نمی‌شود که اقلام قابل دریافت‌اند؛ در تست اتصال، Endpointهای کاندید فقط-خواندنی کاوش و وضعیتشان ثبت/نمایش داده می‌شود؛ در نبود دسترسی واقعی، محدودیت مستند و راهکارهای جایگزین فقط «پیشنهاد» می‌شوند |
| **معنای فیلترهای date.from/date.to** (تاریخ ثبت/سند ≠ آخرین تغییر) | روش پیش‌فرض: **مقایسهٔ دوره‌ای کامل با contentHash** (مسیر مطمئن مجاز دستور)؛ فیلتر تاریخ فقط پس از آزمایش روی سرویس واقعی فعال می‌شود |
| **فرمت تاریخ هلو** (احتمالاً شمسی رشته‌ای) | تجزیهٔ انعطاف‌ف (شمسی با toGregorian موجود / ISO / epoch)؛ نتیجه در لاگ سینک |
| **پرداخت‌ها per-customer است** (N درخواست) | سینک اولیه/دستی: همهٔ مشتریان با فاصلهٔ مؤدبانه؛ افزایشی: فقط مشتریان با فاکتور جدید/تغییرکرده در همان دوره |
| **مشتری هلو ↔ مشتری موجود MedPad** | تطبیق: `erpCode` ← نام نرمال‌شدهٔ یکتا؛ در نبود تطبیق، رکورد جدید (بدون تخصیص خودکار — طبق §۱۰) |
| **HTTP بدون TLS** | سرویس فقط در شبکهٔ داخلی؛ در پنل هشدار شبکهٔ داخلی |
| **رمز هلو باید قابل بازیافت باشد** (برخلاف bcrypt مدیر) | ذخیره در Config سمت سرور (پروندهٔ SQLite با مجوز 600/700)؛ Base64 ذخیره می‌شود (مستند: مبهم‌سازی است نه رمزنگاری)؛ هرگز به فرانت‌اند برنمی‌گردد و لاگ نمی‌شود |
| **بدون سرویس واقعی هلو در سندباکس** | توسعه و تست با **Mock صریح**؛ Mock هرگز «اتصال واقعی» معرفی نمی‌شود؛ فهرست اطلاعات لازم برای فعال‌سازی واقعی در گزارش پایانی |

## ۵. محدودیت‌های اعلامی فاز صفر

- دسترسی واقعی به سرویس هلو (IP، dbname، اعتبارنامه) **در دسترس نیست** — همهٔ تست‌های اتصال با Mock محلی انجام می‌شود و صراحتاً برچسب Mock می‌خورد.
- هیچ API نوشتنی به هلو (ایجاد فاکتور و...) فراخوانی نمی‌شود؛ فقط Login (احراز هویت) و GETها.
- `main` دست نمی‌خورد؛ همهٔ کامیت‌ها روی `develop`.
