# امضای اندروید MedPad — راهنمای کلید جدید (پس از افشا)

> این سند بخشی از ممیزی امنیتی v1.9.12 (فاز ۲) است.
> **خلاصه:** کلید امضای قبلی به‌همراه رمزش در یک مخزن عمومی منتشر شد؛ از این به بعد
> آن کلید باید «لو رفته» تلقی شود و هرگز برای بیلد جدید استفاده نشود.

## ۱. چه اتفاقی افتاد؟

فایل `keystore/medpad.keystore` با رمزهای `medpad123` (store و key) و نام `medpad`
مستقیماً در مخزن گیت عمومی commit شده بود (دو نسخهٔ یکسان در `Android/keystore/` و
`medpad-full-1.9/medpad-tracker/keystore/`). هر کسی که به مخزن دسترسی داشته باشد
می‌تواند:

- APKهای جعلی با شناسهٔ بستهٔ `com.medpad.tracker` بسازد و با **امضای معتبر** منتشر کند؛
- روی دستگاه‌هایی که نسخهٔ قبلی نصب است، آن را به‌صورت «ارتقا» نصب کند
  (چون گواهی امضا همان است و اندروید اعتماد می‌کند).

به همین دلیل ادامهٔ استفاده از این کلید **غیرممکن** است، حتی اگر تاریخچهٔ گیت پاک شود
(کلید عموماً دانلود شده و باید فرض کنیم در دسترس است).

## ۲. تأثیر روی APKهای نصب‌شده (مهم — قبل از انتشار بخوانید)

اندروید فقط وقتی اجازهٔ **آپدیت روی نسخهٔ قبلی** را می‌دهد که گواهی امضای新旧 یکسان
باشد. با عوض شدن کلید:

| موضوع | وضعیت |
|---|---|
| APK امضاشده با کلید جدید روی نسخهٔ قدیمی | **نصب نمی‌شود** — خطای `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| راه‌حل برای کاربر | اول `uninstall` نسخهٔ قبلی، بعد نصب APK جدید |
| دادهٔ اپ با uninstall | صف آفلاین محلی (Room DB) پاک می‌شود — **کاربر باید قبل از حذف، check-in های همگام‌نشده را سینک کند** |
| توصیهٔ انتشار | versionCode را افزایش دهید (مثلاً 11 / versionName 1.9.2) و در صفحهٔ دانلود دستور «اول سینک → بعد حذف → بعد نصب» را قرار دهید |

## ۳. تولید کلید جدید (خارج از گیت!)

کلید جدید را **هرگز** داخل پوشهٔ ریپو نسازید (`.gitignore` جلوی `*.keystore` را می‌گیرد،
اما بهتر است اصلاً جای دیگری باشد):

```bash
keytool -genkeypair -v \
  -keystore /secure/path/medpad-release.keystore \
  -alias medpad \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12
```

- رمز **طولانی و یکتا** انتخاب کنید و در مدیر رمز نگه دارید؛
  (رمز حداقل ۸ کاراکتر، ترجیحاً یک جملهٔ عبور)
- خروجی پشتیبان را در جای امن (نه روی همان دیسکِ تنها) نگه دارید؛
  **گم شدن کلید = ناتوانی در آپدیت اپ برای همیشه** (ماندگاری: 30 سال).
- این فایل را به‌هیچ‌وجه در گیت، ابر، ایمیل یا پیام قرار ندهید.

## ۴. معرفی کلید به بیلد (دو روش — هر دو خارج از گیت)

**روش ۱ — متغیرهای محیطی:**

```bash
export MEDPAD_STORE_FILE=/secure/path/medpad-release.keystore
export MEDPAD_STORE_PASSWORD='your-store-password'
export MEDPAD_KEY_ALIAS=medpad
export MEDPAD_KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

**روش ۲ — فایل properties محلی:**

`Android/keystore/medpad-release.properties` بسازید (در `.gitignore` است و هرگز
commit نمی‌شود):

```properties
storeFile=/secure/path/medpad-release.keystore
storePassword=your-store-password
keyAlias=medpad
keyPassword=your-key-password
```

## ۵. وقتی پیکربندی امضا وجود نداشت چه می‌شود؟

- `assembleRelease` با موفقیت اجرا می‌شود اما خروجی **بدون امضا** است:
  `app/build/outputs/apk/release/app-release-unsigned.apk`
- Gradle هنگام بیلد یک هشدار روشن چاپ می‌کند (مسیر و دستور امضا).
- بیلد debug از این به بعد با **کلید debug استاندارد اندروید** امضا می‌شود
  (قبلاً به‌اشتباه با کلید release امضا می‌شد — اصلاح شد).
- امضای دستیِ بعدی:

```bash
zipalign -p -f 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks /secure/path/medpad-release.keystore --ks-key-alias medpad \
  --out medpad-tracker-v1.9.2.apk app-release-aligned.apk
apksigner verify --verbose medpad-tracker-v1.9.2.apk
```

## ۶. وضعیت فایل‌ها پس از این تغییر

| فایل | وضعیت |
|---|---|
| `Android/keystore/medpad.keystore` (کلید لو رفته) | از ردیابی گیت خارج شد (`git rm --cached`) — فایل روی دیسک کاربر باقی است تا صرفاً به‌عنوان مرجع/آرشیو؛ **برای بیلد جدید استفاده نکنید** |
| `medpad-full-1.9/medpad-tracker/keystore/medpad.keystore` | همان بالا |
| `*.keystore` / `*.jks` / `keystore/medpad-release.properties` | در `.gitignore` — هرگز وارد گیت نمی‌شوند |
| `app/build.gradle.kts` | بدون هیچ رمز hardcode؛ خواندن از env/properties؛ رفتار صریح وقتی تنظیم نیست |
| `medpad-apk-v1.9.1.apk` داخل ریپو | با کلید لو رفته امضا شده — فقط به‌عنوان آرشیو؛ نسخهٔ بعدی باید با کلید جدید ساخته شود |
