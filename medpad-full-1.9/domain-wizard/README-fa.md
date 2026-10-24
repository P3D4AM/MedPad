# MedPad Domain Wizard v1.1 — تنظیم خودکار دامنه Cloudflare

این ابزار برای سرورهایی است که IP عمومی ندارند (پشت NAT / اینترنت خانگی) و می‌خواهند پنل MedPad
روی یک **آدرس ثابت HTTPS** مثل `https://panel.example.com` در دسترس باشد — بدون VPS و بدون تنظیم دستی.

## چه کاری می‌کند؟

1. اعتبارسنجی توکن API کلادفلر
2. پیدا کردن Zone دامنه (با جست‌وجوی هوشمند زیردامنه → دامنه اصلی)
3. ساخت یا بازیابی تونل نامی (Named Tunnel) به اسم `medpad`
4. تنظیم مسیر (ingress): `panel.example.com → http://localhost:3000`
5. ساخت رکورد `CNAME` پروکسی‌شده (رکوردهای متعارض `A/AAAA` خودکار حذف می‌شوند)
6. تولید فایل `config.yml` و credentials برای اجرای `cloudflared`

## پیش‌نیازها

- یک دامنه که **NS آن روی Cloudflare** باشد (پلن رایگان کافی است)
- توکن API با ۳ دسترسی:
  - `Account` → Cloudflare Tunnel → **Edit**
  - `Zone` → DNS → **Edit**
  - `Zone` → Zone → **Read**
- پایتون ۳.۸+ (بدون هیچ وابستگی خارجی)

## استفاده سریع

```bash
export CLOUDFLARE_API_TOKEN='توکن-شما'
./setup_domain.sh example.com panel http://localhost:3000
```

یا حالت خودکار کامل:

```bash
python3 domain_setup_wizard.py --non-interactive \
  --domain example.com --subdomain panel \
  --service http://localhost:3000 --protocol http2
```

بعد از اتمام، تونل را اجرا کنید (پنجره باز بماند):

```bash
./cloudflared tunnel --config config.yml run
```

## نکته‌های مهم

- **پروتکل پیش‌فرض `http2` است** — QUIC روی UDP در ایران اغلب مسدود/کند است
- توکن API **هرگز ذخیره نمی‌شود**؛ فقط فایل credentials تونل (بدون توکن) ساخته می‌شود
- اجرای دوم idempotent است: تونل موجود بازسازی نمی‌شود و رکورد CNAME درست دست‌نخورده می‌ماند
- اگر پشت همین نصب‌کننده MedPad هستید، بهتر است از ویزارد داخل خود نصب‌کننده استفاده کنید
  (حالت «دامنه خودم») — این ابزار نسخه مستقل همان قابلیت است

## تست بدون توکن واقعی

```bash
python3 test_wizard_mock.py
```

یک سرور Mock از API کلادفلر روی پورت 3789 بالا می‌آید و هر ۳ سناریو (نصب تازه / اجرای دوباره / توکن مرده) را با ۱۸ ادعا تست می‌کند.

## رفع ۷ باگ نسبت به نسخه ۱.۰

| # | باگ | رفع |
|---|-----|-----|
| ۱ | `{account_id}` جایگزین نمی‌شد | شناسه از `/accounts` یا `zone.account.id` استخراج می‌شود |
| ۲ | AccountTag خالی بود | از API پر می‌شود (تونل بدون آن بالا نمی‌آید) |
| ۳ | `self.completed_steps` موجود نبود | state ساده و JSON-based |
| ۴ | حالت non-interactive گیر می‌کرد | هیچ `input()` در مسیر خودکار |
| ۵ | `config_src` اشتباه | `local` تولید می‌شود |
| ۶ | تداخل رکورد A/AAAA | رکورد متعارض حذف و CNAME ساخته می‌شود |
| ۷ | پیش‌فرض QUIC (مسدود در ایران) | پیش‌فرض `http2` |
