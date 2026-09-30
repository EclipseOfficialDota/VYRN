تو مسئول طراحی، پیاده‌سازی، تست، استقرار و تحویل کامل پروژه VYRN هستی. فقط برنامه‌ریزی یا ارائه نمونه‌کد کافی نیست. تا وقتی APK قابل‌نصب، Backend قابل‌استقرار و مستندات کامل آماده نشده‌اند، کار تمام‌شده محسوب نمی‌شود.

در اولین پاسخ فقط اطلاعات بخش «پرسش‌های اولیه» را یک‌جا از من بپرس. درباره معماری، نام، امکانات، طراحی یا تنظیمات محصول سؤال نکن؛ همه تصمیم‌ها در این سند نهایی شده‌اند. بعد از دریافت پاسخ‌ها، بدون توقف غیرضروری پروژه را اجرا کن.

## پرسش‌های اولیه

فقط این فرم را در اولین پاسخ نمایش بده:

VPS:
- تأیید IPv4 عمومی:
- تأیید سیستم‌عامل:
- مسیر SSH Private Key روی سیستم محلی:
- آیا ورود SSH با کلید فعال است؟
- آیا UDP ورودی توسط Provider تأیید شده است؟

Cloudflare:
- آیا `CLOUDFLARE_API_TOKEN` در Environment Variable موجود است؟
- آیا `CLOUDFLARE_ZONE_ID` در Environment Variable موجود است؟

Assets:
- مسیر فایل اصلی لوگوی VYRN:
- فرمت و ابعاد لوگو:

Android Signing:
- مسیر Release Keystore:
- نام Environment Variable مربوط به Alias:
- نام Environment Variable مربوط به Store Password:
- نام Environment Variable مربوط به Key Password:

Backup:
- مقصد خارجی Backup:
- نوع مقصد: S3 / SFTP / سرور دوم / موجود نیست

Repository:
- مسیر پوشه پروژه:
- آدرس Git remote، در صورت وجود:

مقادیر شناخته‌شده:

- IPv4: `217.18.90.114`
- OS: `Ubuntu 24.04.3 LTS`
- SSH user: `root`
- SSH port: `3939`
- Virtualization: `VMware`
- RAM: `4GB DDR3`
- Disk: `40GB SSD`
- Network: `1Gbps`
- Traffic: Unlimited
- Datacenter: Tehran, Iran
- IPv6: Not configured
- Timezone: `Asia/Tehran`
- Domain: `eclipseofficial.xyz`

از من نخواه Token، Password، Private Key، رمز root یا رمز Keystore را داخل چت ارسال کنم.

## حافظه پروژه و Secrets

پس از دریافت پاسخ‌ها، اطلاعات غیرمحرمانه را در فایل‌های زیر ذخیره کن:

- `PROJECT_MEMORY.md`
- `config/project.yaml`
- `docs/DECISIONS.md`

قبل از ادامه هر مرحله این فایل‌ها را بخوان و تنظیمات قبلی را دوباره سؤال نکن.

Secrets فقط از Environment Variable یا فایل محلی Git-ignored خوانده شوند. مقادیر محرمانه را هرگز در پیام، Log، Markdown، Git یا خروجی فرمان نمایش نده.

این موارد حتماً در `.gitignore` باشند:

- `.env`
- `.env.*`
- `*.jks`
- `*.keystore`
- `google-services.json`
- فایل‌های Credential
- Backupهای خصوصی

## هویت محصول

- Name: `VYRN`
- Package ID: `xyz.eclipseofficial.vyrn`
- Backend: `VYRN Core`
- Admin panel: `VYRN Control`
- Update service: `VYRN Updates`
- Minimum Android: Android 8
- Languages: فارسی و انگلیسی
- Default language/theme: تنظیم دستگاه
- RTL: کامل
- Tablet support: فعال
- Google Services: اختیاری
- Firebase: برای کارکرد اصلی اجباری نباشد

دامنه‌ها:

- `vyrn.eclipseofficial.xyz`
- `api.vyrn.eclipseofficial.xyz`
- `ws.vyrn.eclipseofficial.xyz`
- `media.vyrn.eclipseofficial.xyz`
- `push.vyrn.eclipseofficial.xyz`
- `admin.vyrn.eclipseofficial.xyz`
- `updates.vyrn.eclipseofficial.xyz`
- `turn.vyrn.eclipseofficial.xyz`

تمام رکوردها به IP سرور متصل شوند. رکورد TURN حتماً DNS-only باشد. سرویس اصلی به CDN، Firebase یا اینترنت خارجی وابسته نباشد.

## هدف و UI

VYRN یک پیام‌رسان مستقل برای فعالیت روی سرور ایران هنگام قطعی اینترنت بین‌المللی است.

هدف «شبیه Telegram» نیست؛ UI باید مستقیماً از رابط Telegram Android گرفته شود و ساختار صفحات، Chat، Dialog list، Drawer، Settings، Profile، Groups، Message bubbles، Composer، Voice recorder، Media viewer، Context menu، انیمیشن‌ها، Gestureها، فاصله‌ها و Themeها حفظ شوند.

از یک Tag یا Commit مشخص سورس رسمی Telegram Android استفاده و آن را Pin کن. نام، لوگو و Branding رسمی Telegram را حذف کن. مجوز GPL، NOTICE و الزامات انتشار سورس را رعایت کن.

UI را مستقیماً به Backend متصل نکن. معماری:

Telegram UI
→ VYRN Compatibility Layer
→ Domain Models
→ Repositories/Use Cases
→ REST + WebSocket
→ VYRN Backend

وابستگی‌های UI به `TLRPC`، `MessagesController` و `ConnectionsManager` را تدریجی و کنترل‌شده جایگزین کن.

## حساب کاربری

- فقط Email + Password
- بدون شماره موبایل
- بدون OTP
- بدون ارسال یا تأیید ایمیل
- ثبت‌نام فقط با Invite Code
- ایمیل صرفاً شناسه ورود
- Email normalization و Case-insensitive uniqueness
- جلوگیری از User Enumeration
- Hash رمز با Argon2id
- حداقل رمز 8 کاراکتر
- Rate limiting و Lock موقت
- Recovery Code یک‌بارمصرف
- Reset رمز توسط Admin
- حداکثر 3 دستگاه
- Access Token کوتاه‌مدت
- Refresh Token قابل ابطال و ذخیره‌شده به‌شکل Hash
- ذخیره امن Token در Android Keystore
- مشاهده و خروج‌دادن Sessionهای دیگر

## قابلیت‌های MVP

- چت خصوصی
- گروه تا 250 عضو
- پروفایل و تصویر
- Online، Last Seen و Typing
- متن، عکس، ویدئو، فایل و Voice
- Reply، Forward، Edit و Delete
- Reactions و Pin
- Search و Draft
- Delivery و Read receipts
- Pagination و Offline synchronization
- Block user
- نقش‌ها و مدیریت گروه
- Invite codes
- Multi-device
- Disappearing messages
- اعلان داخلی
- Update checker

Channel، Bot، Story، Live Stream و Group Call برای فاز بعد باشند.

## Backend

Backend سبک و مناسب VPS چهار گیگابایتی باشد:

- Go
- Modular Monolith
- REST API
- WebSocket Realtime Gateway
- PostgreSQL
- Redis فقط در صورت نیاز واقعی
- Local media storage با abstraction سازگار با S3
- Caddy یا Nginx
- Coturn
- Docker Compose
- Versioned migrations
- Structured logging
- Health checks و Metrics
- Admin API
- Background workers سبک

WebSocket باید Authentication، Reconnect، Heartbeat، Resume cursor، Acknowledgement، Idempotency، Ordered events، Offline sync، Backpressure و Retry محدود داشته باشد. پیام‌ها Client-generated ID داشته باشند تا Retry پیام تکراری نسازد.

## امنیت

رمزنگاری اختصاصی طراحی نکن. فقط از پروتکل و کتابخانه بررسی‌شده استفاده کن.

- TLS 1.2+
- Cleartext خاموش
- Certificate validation واقعی
- E2EE برای چت خصوصی
- Private key فقط روی دستگاه
- عدم ثبت متن پیام در Log
- Key rotation
- Multi-device key handling
- Device verification
- Replay protection
- تست Encrypt/decrypt، پیام خارج از ترتیب، خرابی Ciphertext و تعویض دستگاه
- رمزنگاری گروه‌ها برای فاز بعد طراحی شود

## محدودیت منابع

- حداکثر فایل: 250MB
- تصویر پروفایل: 10MB
- Voice: 100MB
- اعضای گروه: 250
- دستگاه هر کاربر: 3
- متن پیام: 16,384 کاراکتر
- Soft quota هر کاربر: 2GB
- حداقل فضای آزاد اضطراری: 10GB

اگر فضای آزاد به 10GB رسید، Upload متوقف ولی پیام متنی فعال بماند. فایل Orphan پس از 7 روز حذف شود. Media خصوصی فقط با Authorization دانلود شود.

## Push و اینترنت ملی

- WebSocket برای حالت فعال
- Background sync
- Push داخلی VYRN
- UnifiedPush اختیاری
- Firebase فقط Adapter اختیاری
- بدون نیاز اجباری به Play Services
- Retry با Exponential Backoff
- Endpoint جایگزین امن
- عدم دورزدن TLS با IP خام
- Remote configuration فقط به‌صورت امضاشده

## تماس

WebRTC + Coturn طراحی شود. TURN روی UDP، TCP و TLS fallback داشته باشد. Credential کوتاه‌مدت استفاده شود. تا زمان تأیید UDP ورودی، تماس صوتی و تصویری پشت Feature Flag و در Production خاموش باشند.

## پنل مدیریت

- ورود امن و 2FA مدیر
- Dashboard
- مدیریت کاربران، Sessionها و Inviteها
- مدیریت گروه‌ها
- Block، Reset password و Revoke session
- Storage و Server health
- Feature flags
- تنظیم محدودیت Upload
- Audit log
- بدون دسترسی به متن پیام‌های E2EE

## سرور

ابتدا وضعیت سرور را Read-only بررسی کن. سپس:

- کاربر مدیریتی غیر root بساز
- SSH key را تأیید کن
- فقط با اجازه صریح من Root login و Password authentication را غیرفعال کن
- Firewall و Fail2ban
- Security updates
- Docker و Compose
- Container resource limits
- Log rotation
- PostgreSQL و Redis غیرعمومی
- Admin panel محدود
- Timezone: `Asia/Tehran`

پورت‌ها:

- `3939/tcp`: SSH
- `80/tcp`
- `443/tcp`
- `3478/tcp+udp`
- `5349/tcp+udp`
- UDP relay range محدود و مستند پس از تأیید Provider

## Cloudflare و TLS

Credentialها فقط از:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ZONE_ID`

خوانده شوند. رکوردهای موجود را قبل از تغییر بررسی کن، بدون بررسی Overwrite نکن و Token را چاپ نکن. تمام Endpointها HTTPS و Certificate renewal خودکار و مانیتورشده باشند.

## Backup

- Backup روزانه PostgreSQL و تنظیمات
- رمزنگاری Backup
- انتقال به مقصد خارجی
- 7 نسخه روزانه
- 4 نسخه هفتگی
- Integrity check
- Restore documentation
- Restore test
- هشدار شکست Backup

Backup روی همان دیسک VPS، Backup واقعی محسوب نمی‌شود.

## Update

- APK manifest امضاشده
- SHA-256
- Signature verification
- Changelog
- Minimum supported version
- Optional forced update
- دانلود امن
- Release signing key روی VPS عمومی ذخیره نشود

## خروجی Android

Build flavors:

- `dev`
- `staging`
- `production`

خروجی‌ها:

- Signed Universal APK
- APK برای `arm64-v8a`
- APK برای `armeabi-v7a`
- AAB در صورت امکان
- Release build با Minification و Obfuscation صحیح

## تست

Backend:
- Unit، Integration و Migration tests
- Auth و Rate-limit
- WebSocket reconnect
- Idempotency
- Media authorization
- Permissions
- Backup/restore smoke test

Android:
- Authentication
- Message sync
- Offline/online
- RTL
- Dark/light theme
- Process restart
- Multi-device
- Large conversation performance
- APK installation smoke test

End-to-end:
- Register with invite
- Login
- Private chat
- Offline send/reconnect
- Upload/download
- Voice
- Group
- Edit/delete/reaction
- Revoke session
- Recovery code
- Server restart recovery

## ساختار Repository

vyrn/
├── android/
├── backend/
├── admin/
├── deployment/
├── config/project.yaml
├── docs/
├── scripts/
├── PROJECT_MEMORY.md
├── NOTICE.md
├── LICENSES/
├── .env.example
├── .gitignore
└── README.md

مستندات لازم:

- `ARCHITECTURE.md`
- `DECISIONS.md`
- `SECURITY.md`
- `DEPLOYMENT.md`
- `BACKUP_RESTORE.md`
- `UPDATE_PROCESS.md`
- `API.md`
- `GPL_COMPLIANCE.md`

## ترتیب اجرا

1. دریافت پرسش‌های اولیه و ذخیره Memory غیرمحرمانه
2. بررسی Workspace و Git
3. Pin کردن Telegram Android
4. Branding و APK اولیه
5. Backend، PostgreSQL، Auth و WebSocket
6. اتصال UI از طریق Compatibility Layer
7. Chat، Group، Media و Voice
8. E2EE و Multi-device
9. Push، Admin، Update و Backup
10. Coturn و تماس پشت Feature Flag
11. Hardening، DNS، TLS و Deployment
12. Signing، تست نهایی و تحویل

اگر پروژه در یک Turn تمام نشد، وضعیت دقیق را در `PROJECT_MEMORY.md` ذخیره و دفعه بعد از همان نقطه ادامه بده.

پروژه فقط وقتی تمام است که APK قابل‌نصب، Backend فعال، Docker Compose، پنل مدیریت، DNS، TLS، Login، Invite، Chat، Group، Media، Voice، Sync، Push، Update، Backup خارجی، مستندات و گزارش تست‌ها تحویل شده باشند.

اکنون فقط پرسش‌های اولیه را نمایش بده و هیچ Secretی داخل چت درخواست نکن.