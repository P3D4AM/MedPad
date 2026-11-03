# گزارش فنی: پشتیبان‌گیری و بازگردانی + انتقال داده از نسخه‌های قدیمی (v1.9.14-dev.2)

> مأموریت مالک (Nov 3, 2026): «به نرم افزار قابلیت بکاپ گیری هم اضافه کن. یک ابزار اضافی هم تولید کن تا بتونم اطلاعات ورژنهای قبلی رو که بکاپ نداشتن و توشون اطلاعات وارد کردم رو بکاپ بگیرم به صورتی که به این نرم افزار که ورژن جدیدتر هست بتونم وارد کنم»
> وضعیت: **DONE_VERIFIED** (کد + تست + بیلد + پوش + انتشار v1.9.14-dev.2 / versionCode 20)

---

## ۱) خلاصه معماری

یک قالب JSON واحدِ نسخه‌دار، سه تولیدکننده و یک مصرف‌کننده:

```
┌─────────────────────────────┐   ┌──────────────────────────────────┐
│ اپ اندروید — «خروجی JSON»   │   │ ابزار رایانه‌ای tools/legacy-     │
│ (SAF CREATE_DOCUMENT)       │   │ backup/medpad_legacy_backup.py    │
└──────────────┬──────────────┘   │ (هر medpad.db قدیمی v4..v14)      │
               │                  └──────────────┬───────────────────┘
               │                                 │
               │        ┌────────────────────────┴──────────────┐
               └───────▶│  قالب مشترک: formatVersion=1          │
                        │  { manifest, entities: {table:[…]} }  │
                        └────────────────────────┬──────────────┘
                                                │
┌───────────────────────────────────────────────┴──────────────┐
│ مصرف‌کنندهٔ واحد: applyEntities — یک تراکنش Room، ادغامی،      │
│ LWW توسط updatedAt برای doctors / fetchedAt برای پرونده‌ها،   │
│ REPLACE برای آینه‌های سروری، dedupe با localId برای صف‌ها      │
└───────────────────────────────────────────────────────────────┘
                        ▲
┌───────────────────────┴──────────────────────────────────────┐
│ مسیر سوم: «بازگردانی از پایگاه‌داده قدیمی (.db)» — کپی خصوصی  │
│ از فایل انتخابی + باز شدن با زنجیرهٔ migration واقعی 4→14؛     │
│ خود migration نقشهٔ قدیم→جدید است؛ سپس همان export→apply      │
└──────────────────────────────────────────────────────────────┘
```

**نکتهٔ کلیدی طراحی:** به‌جای نگاشت دستی ستون‌ها، فایل قدیمی با **خود Room** و **همان زنجیرهٔ migration تولیدی** باز و ارتقا می‌یابد — Room خروجی را ولیدیت می‌کند؛ نگاشت همیشه با واقعیت schema هم‌زمان است (تاریخچهٔ migration مبنا است، طبق دستور مأموریت).

## ۲) قرارداد قالب (formatVersion 1)

```json
{
  "manifest": {
    "app": "MedPad",
    "formatVersion": 1,
    "schemaVersion": 14,
    "exportedAt": "2026-11-03T12:00:00Z",
    "appVersion": "1.9.14-dev.2"
  },
  "entities": { "doctors": [ … ], "customer_links": [ … ], … }
}
```

- کلیدها = نام ستون‌های SQLite (= نام فیلدهای کاتلین؛ هیچ @ColumnInfo rename در schema نیست).
- فیلدهای تهی **حذف می‌شوند** (هرگز `null` صریح نوشته نمی‌شود)؛ خوانندهٔ کاتلین کلید گم‌شده و `null` صریح را یکسان می‌فهمد (ابزار پایتون همیشه حذف می‌کند؛ تست جداگانه دارد).
- بولین‌ها `true/false`؛ ستون‌های `uid` (خودافزای محلی) **هرگز** جزو قالب نیستند — هویت پایدار: `id`/`customerId` و کلید idempotency یعنی `localId`.
- اعتبارسنجی manifest پیش از هر تماس با داده: `app=="MedPad"`، `formatVersion==1`، `1≤schemaVersion≤14`. نسخهٔ ساختار کمتر از ۱۴ مجاز است (ستون‌های افزوده با پیش‌فرض خوانده می‌شوند — سازگاری رو-به-عقب قالب).
- ابزار پایتون دو فیلد اثبات‌المنشع اضافه می‌کند: `sourceSchemaVersion` و `sourceDb` (خواننده نادیده می‌گیردشان).

### منطق ادغام (هیچ رکوردی هرگز حذف نمی‌شود)

| جدول | کلید | قاعده |
|---|---|---|
| doctors | id | آخرین‌نوشته با updatedAt |
| customer_profiles | customerId | آخرین‌نوشته با fetchedAt |
| customer_links | id | REPLACE (آینهٔ فقط‌خواندنی سرور) |
| planned_visits | id | REPLACE (آینهٔ فقط‌خواندنی سرور) |
| pending_checkins | localId (یا کلید ترکیبی kind:customerId:createdAt برای ردیف‌های pre-v1.9.1 با localId خالی) | درج بدون تکرار |
| pending_locations | localId (یا lat:lng:createdAt برای ردیف‌های pre-dev.5) | درج بدون تکرار |
| op_events / pending_customer_ops | localId | درج بدون تکرار |

هم‌زمانیِ صف‌ها امن است: `sending`های ایمپورت‌شده در flush بعدی به `pending` برمی‌گردند (resetSending موجود) و idempotency سروری با localId دوبار-ثبتی نمی‌سازد.

## ۳) جزئیات پیاده‌سازی اندروید

- **`backup/BackupManager.kt`** (هسته، بدون وابستگی جدید): export JSON هشت‌جدولی، `importJson` (ولیدیشن + `withTransaction`)، `applyEntities` (خط‌لولهٔ ادغام با تحمل ردیف‌خراب = «ردشده»)، `importLegacyDb` (کپی خصوصی + pre-flight نسخه + نرمال‌سازی v4/ایندس تاریخی + بازکردن Room با `AppDatabase.migrations()` + export→apply)، `exportDbCopy` (چک‌پوینت WAL + کپی)، اسنپ‌شات‌های ایمنی (`files/backups/`، نگهداری ۳ نسخه).
- **نرمال‌سازی خاص v4/ایندس:** MIGRATION_4_5 علاوه بر ستون، ایندس `index_pending_checkins_localId` می‌ساخت که entity فعلی تعریفش نمی‌کند — برای فایل v4 نیمهٔ ستونیِ همان migration دستی اعمال می‌شود (بدون ایندس) و ایندس سرگردان از فایل‌های v5+ حذف می‌شود؛ در غیر این صورت ولیدیشن open-time رد می‌کرد. کامنت درون کد مستند کرده.
- **`ui/BackupDialogs.kt`**: بخش «پشتیبان‌گیری و بازگردانی» در sheet حساب کاربری (پنج عمل + مرور نسخه‌های ایمنی با تاریخ جلالی). SAF با `startActivityForResult` کلاسیک (از دیالوگ؛ `MainActivity.onActivityResult` هدایت می‌کند — بازآفرینی اکتیویتی را هم SAFE پوشش می‌دهد). دیالوگ پیشرفت غیرقابل‌لغو؛ خلاصهٔ فارسی «افزوده/به‌روزرسانی/ردشده» جدول‌به‌جدول.
- **ایمنی داده:** هر بازگردانی ابتدا اسنپ‌شات خودکار؛ اگر اسنپ‌شات ممکن نشود بازگردانی **لغو** می‌شود (کد خطای اختصاصی CODE_SAFETY_FAILED با پیام فارسی). بازگردانی از خودِ اسنپ‌شات‌ها (undo) از همان مسیر legacy ممکن است.
- **DAO additions**: `all()` برای چهار جدولی که نداشتند + `upsertAll` برای customer_profiles — همه با کامنت «Backup & Restore».
- رشته‌های فارسی: ۳۱ رشتهٔ جدید در strings.xml (مطمئن از هیچ متن hardcode نه).

## ۴) ابزار مستقل `tools/legacy-backup/`

- **`medpad_legacy_backup.py`** — فقط stdlib (sqlite3/json)، پایتون 3.8+: کپی خصوصی از فایل (+ `-wal` کناری → بازیابی آخرین نوشته‌ها)، تشخیص `PRAGMA user_version` (v4..v14؛ خارج از بازه = خطای صادقانهٔ فارسی)، خواندن فقط ستون‌های موجود، تبدیل بولین، حذف null و uid، خروجی pretty-print UTF-8 + یادآوری انتقال صوت‌ها.
- **`README-fa.md`** — راهنمای کامل فارسی: سه مسیر استخراج (adb backup + استخراج tar / روت با su / فایل .db موجود)، اجرا، بازگردانی در اپ، انتقال صوت‌ها (مسیر مطلق یکسان بین دستگاه‌ها)، قرارداد قالب، محدودیت‌ها.
- **`test_legacy_backup.py`** — ۱۰ تست stdlib-unittest: فیکسچرهای v6/v11/v13/v14 + wal-real + خراب/قدیمی/آینده + قرارداد null. **۱۰/۱۰ سبز.**

## ۵) شواهد QA (همه واقعی، همین سشن)

| مورد | نتیجه |
|---|---|
| تست واحد اندروید (`:app:testDebugUnitTest`) | **۱۹۳/۱۹۳ سبز** (۳۴ کلاس؛ ۱۷۷ موجود + ۱۶ جدید: BackupManagerTest×۱۰ + LegacyDbImportTest×۶) |
| تست ابزار پایتون | **۱۰/۱۰ سبز** |
| `:app:assembleDebug` | BUILD SUCCESSFUL — `app-debug.apk` **9,121,918 بایت**، aapt: versionCode=20 / versionName=1.9.14-dev.2 |
| `:app:assembleRelease` | BUILD SUCCESSFUL — `app-release-unsigned.apk` (بدون keystore رسمی، طبق سیاست امضا؛ خروجی مطابق انتظار) |
| `zipalign -c 4` | OK |
| `apksigner verify` | Signer: CN=Android Debug — SHA-256 گواهی `0dd11fcd…61e1` |
| `:app:lintDebug` | **۰ خطا**، ۹۷ هشدار (همه پیش‌دار؛ صفر هشدار جدید از کد بکاپ بعد از وایر دو رشتهٔ بلااستفاده) |
| SHA-256 APK | `04e4845f…06b31c8` |
| باندل پنل (`app/panel/.next`, `patches/`) | **دست‌نخورده** (diff این مأموریت فقط اندروید/tools/نصب‌کننده‌ها/مستندات) |

### باگ پیدا و رفع‌شده در جاروی QA
- **TrackingService.kt:130 (پیش‌دار):** `WrongConstant` در lint — فراخوانی خام `registerReceiver(receiver, filter, flags)` با ثابت ContextCompat؛ اصلاح با الگوی خود کدبیس (`ContextCompat.registerReceiver` مثل MainActivity) + حذف import بلااستفاده. lint از «۱ خطا» به «۰ خطا» رسید.

## ۶) تصمیم‌های مستند

1. **WorkManager بکاپ خودکار دوره‌ای اجرا نشد** (اختیاری در مأموریت): مقصد SAF نیازمند تعامل کاربر است و اسنپ‌شات در حافظهٔ خصوصی، حذف‌اپ را جان نمی‌دهد؛ پوشش ریسک با «اسنپ‌شات ایمنی قبل از هر بازگردانی» + بکاپ دستری JSON. در صورت نیاز، فاز جداگانه.
2. **صوت‌ها داخل JSON منتقل نمی‌شوند** (باینری در قالب متنی = تورم ۳۳٪): مسیرها حفظ می‌شوند و برنامه با نبود فایل بی‌صدا ادامه می‌دهد؛ راهنمای انتقال جداگانه در README-fa ابزار (مسیر مطلق روی دستگاه مقصد با همان پکیج یکسان است).
3. **جاروب تخصیص doctors پس از sync** (رفتار موجود، عمداً دست‌نخورده): مشتریانِ غیرمنتسب به مدرپ در sync بعدی از کش حذف می‌شوند — آینهٔ پنل است؛ ردیف‌های آفلاین‌ساخته (pendingLocalId) و کل صف گزارش‌ها مصون‌اند و در پنل ثبت می‌شوند.
4. **خروجی .db ثانویه است** (گزینهٔ مأموریت): JSON مسیر توصیه‌شده است؛ .db برای پشتیبان بایت‌دقیق و انتقال فنی.
5. **امضای Debug با کلید تازهٔ سندباکس** (شفاف): کلید dev.1 (`5875ccd7…`) در `medpad-work/keystore-debug/` بیرون مخزن بود و با بازیافت سندباکس از بین رفت (در git هم نبود — بررسی شد). این ریلیز با گواهی جدید `0dd11fcd…` امضا می‌شود → ارتقا از dev.1 نیازمند حذف/نصب مجدد است (در یادداشت ریلیز و README هشدار داده شد؛ مسیر نجات داده با ابزار legacy مستند شد). کپی keystore برای مالک: `download/medpad-debug-keystore-v1.9.14-dev.2.keystore` (رمز استاندارد `android`). راه‌حل اصلح همچنان keystore رسمی مالک است (RELEASE-CHECKLIST §۴).

## ۷) فایل‌های تغییر/افزوده

**افزوده:** `Android/.../backup/BackupManager.kt` · `Android/.../ui/BackupDialogs.kt` · `Android/.../test/.../backup/BackupManagerTest.kt` · `Android/.../test/.../backup/LegacyDbImportTest.kt` · `tools/legacy-backup/{medpad_legacy_backup.py, test_legacy_backup.py, README-fa.md}` · همین گزارش

**تغییر:** `Entities.kt` (DAO additions) · `AppDatabase.kt` (`migrations()` تولیدی + کامنت) · `AccountDialogs.kt` (بخش جدید) · `MainActivity.kt` (onActivityResult) · `TrackingService.kt` (رفع lint) · `strings.xml` (۳۱ رشته) · `build.gradle.kts` (vCode 20 + versionName + robolectricRepoUrl + کامنت) · `APP-VERSION`/`INSTALLER_VER`/`WIZARD_VER`/`MANAGER_VER`/بنرهای bat/ps1/sh (CRLF حفظ: ۱۹۲×CRLF/۰×LF) · `README-fa.md` (بخش dev.2) · `PROGRESS-MEDPAD.md` · `WORKLOG-MedPad.md`
