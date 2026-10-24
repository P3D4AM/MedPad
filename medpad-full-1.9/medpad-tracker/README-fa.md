# MedPad Tracker APK v1.9.1 — اپ اندروید بازاریاب

## امکانات

- 🔐 **ورود** با آدرس سرور + نام کاربری + رمز (ورود مهمان حذف شده)
- 🎙 **چک‌این** با متن + ضبط صدا (میکروفون) — آپلود multipart به پنل
- ✍️ **چک‌اوت اجباری** — متن حداقل ۱۰ نویسه (**Paste غیرفعال**) + ضبط صدا الزامی
- 📶 **صف آفلاین** — بدون اینترنت، داده در Room ذخیره و با اولین اتصال ارسال می‌شود
- 🛡 **ضدثبت‌دوبل سه‌لایه** — localId یکتا + قفل ارسال همزمان + ددوپ سرور
- 📋 **«گزارش‌های من»** با تقویم شمسی و پخش دوباره صداهای خودتان
- 🗺 **نقشه مشتریان** (osmdroid) — پن تک‌انگشتی، افزودن مشتری با نگه‌داشتن ۱ ثانیه، ادیت بدون حذف
- 🔔 **اعلان‌ها** — اختصاص مشتری جدید + حضور در شعاع محدوده مشتری (هر ۴۵ ثانیه پول)
- 📍 **ردیابی پس‌زمینه** — سرویس Foreground با نوتیف دائمی

## نصب APK

فایل `app-release.apk` را روی گوشی نصب کنید.

> ⚠️ **امضا:** این APK با کی‌استور جدید (`CN=MedPad Tracker`) امضا شده است.
> اگر نسخه قبلی را با امضای متفاوت نصب کرده‌اید، اول آن را **حذف** و سپس این را نصب کنید
> (داده اپ روی گوشی پاک می‌شود؛ گزارش‌ها روی سرور سالم‌اند).

اولین ورود:
1. آدرس سرور پنل (مثال: `http://192.168.1.10:3000` یا آدرس https تونل)
2. نام کاربری و رمز کارمند (دمو: `ali / 1234`، `sara / 1234`، `mahla / 1234`)
3. مجوزهای موقعیت + میکروفون + اعلان را Allow کنید

## بیلد از سورس

پیش‌نیاز: JDK 17، Android SDK 34

```bash
./gradlew assembleRelease
# خروجی: app/build/outputs/apk/release/app-release.apk
```

- امضای release: `keystore/medpad.keystore` (alias: `medpad` — رمزها در `app/build.gradle.kts`)
- برای انتشار واقعی، کی‌استور خودتان را جایگزین کنید

## معماری

```
com.medpad.tracker/
├── api/            ApiClient (OkHttp، تایم‌اوت ۱۲۰ث برای صدا) + Fallback JSON
├── db/             Room v6: Doctor، PendingCheckin (localId)، PendingLocation
├── sync/           CheckinSync (صف + LWW) ، DoctorsSync، AppNotifier
├── location/       TrackingService (سرویس foreground + geofence + پول نوتیف)
├── ui/             Login، Main، CheckinDialog، CheckoutDialog (NoPasteEditText)
│                   ReportsActivity (تقویم شمسی JalaliDate)، DoctorsMapActivity (osmdroid)
└── voice/          VoiceRecorder (MediaRecorder + پخش async)
```

## نکته‌های v1.9.1

- `startNow()` با پرانتز صدا زده می‌شود (باگ ضبط بار دوم)
- تایم‌اوت آپلود صدا ۱۲۰ ثانیه (قبلاً ۱۰ ثانیه → صف گیر می‌کرد)
- اگر پنل قدیمی باشد multipart را 400 می‌کند → fallback JSON بدون صدا + هشدار «پنل قدیمی»
- چک‌اوت **همه** ویزیت‌های باز را می‌بندد (رفع «دوتا در جریان»)
