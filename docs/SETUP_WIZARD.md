# Guided server setup / راه‌انداز تعاملی

VYRN includes a small bilingual terminal panel for a dedicated Ubuntu24.04 server. It uses Python's standard library and the existing Docker Compose stack; no separate UI framework is required. This installs the development-preview server, not a certified Stable Android release.

## Start

Install Git if missing (`sudo apt-get update && sudo apt-get install -y git`), then:

```sh
git clone https://github.com/EclipseOfficialDota/VYRN.git && cd VYRN && sudo bash setup.sh
```

Choose English or فارسی. To open directly in Persian later:

```sh
cd /path/to/VYRN
sudo bash setup.sh --lang fa
```

Read-only prerequisite check: `bash setup.sh --check` (Python3 must already be installed to avoid the optional Python-install prompt). The panel itself requires an interactive root/sudo terminal. If needed, connect with `ssh -t`. Server setup does not run on Windows/macOS; Android builds stay on the Windows workstation.

## What it asks

| Input | Purpose |
| --- | --- |
| Service domain | Your hostname, e.g. `chat.yourdomain.com`; no scheme/path |
| Public server IPv4 | DNS targets and readiness checks |
| Operator IP or VPN CIDRs | Restrict the admin panel; an SSH client IP is suggested when available |
| Docker installation choice | Install Docker from its official Ubuntu repository when absent |
| Optional Cloudflare Zone ID/token | Create missing DNS-only A records; token input is hidden and not saved |
| Administrator email/password | Create the first account only when the database is empty |
| Authenticator-key display choice | Privately import the generated time-based key into your authenticator |

For Cloudflare, use a token limited to DNS editing for the intended zone and its corresponding Zone ID. Existing conflicting DNS records are preserved; the wizard stops for correction. With another provider, it prints the exact seven records and lets you recheck after saving them in that provider's panel. Domain registration and DNS propagation cannot be automated by this script.

## Setup flow

1. Check Ubuntu/disk, Docker availability, project ownership and occupied80/443 ports.
2. Collect inputs, show the public configuration summary and save new settings only after confirmation.
3. Generate the database password and TOTP key into `/etc/vyrn/runtime.env`, mode0600, in a private directory. Existing settings/keys are never regenerated automatically.
4. Create optional Cloudflare records or guide manual DNS; check all seven names before building.
5. Validate Compose, build Core when no Core container exists, start PostgreSQL and inspect actual database state.
6. Bootstrap only if there are no users; take the new password twice with hidden input and pass it through the environment, not argv or a saved password file.
7. Start Core/Caddy and check public readiness with normal certificate verification. HTTPS failure is shown as pending, not success.
8. Offer private authenticator enrollment, display the Control address and give the exact Android build command for your API.

Docker installation follows the [official Ubuntu repository method](https://docs.docker.com/engine/install/ubuntu/), not a remote shell installer. Conflicting container packages or repository settings require manual review; unrelated services are not removed. A matching repository created by an earlier interrupted attempt can be reused. Installing Docker/compiling images requires internet access and can take several minutes.

## Terminal controls

```text
──────────────────────────────────────────────────────
  VYRN  /  Server control
──────────────────────────────────────────────────────
  Ubuntu 24.04 · Self-hosted setup

  1  Install / resume setup

  2  Service status
  3  Check public HTTPS
  4  Authenticator setup

  5  Start existing services
  6  Stop services (keep data)

  7  Client build / addresses

  8  Remove services (keep data)

  0  Exit
──────────────────────────────────────────────────────
```

Each choice opens a fresh screen. Results and errors stay visible until you press Enter to return. Screen clearing is disabled for redirected output or a `TERM=dumb` terminal. English, Persian and Arabic digits are accepted.

Option6 stops services. Option8 opens a removal submenu: choose1, then type `REMOVE` to remove this instance's containers and networks. Accounts, messages, media, certificates, `/etc/vyrn/runtime.env`, Docker, DNS records and source files remain. No volumes or images are deleted. Choosing0 or leaving the confirmation blank cancels removal. Ownership and the local Docker endpoint are checked before removal; another checkout's stack is refused. To restore services afterward, run option1 from the same checkout with the retained settings. Removal causes downtime; it is not a data-erasure operation.

On failure, rerun option1 using the same source checkout and runtime file. The database is queried again, so an already-created account is preserved even if an earlier run was interrupted after bootstrap. The wizard is not a source updater or an automatic migration rollback tool.

If a different VYRN checkout owns existing containers, automatic adoption is refused. Existing standalone Nginx/Caddy on80/443 is preserved; use [manual proxy integration](DEPLOYMENT.md). Remote Docker contexts and environment overrides are not supported. The panel does not change SSH authentication, firewall rules or unrelated containers. You must allow incoming80/443 in your host/provider firewall while preserving SSH access.

Setup and status/start/stop actions check the selected local Docker endpoint before contacting the daemon. A saved remote context or `DOCKER_HOST`/`DOCKER_CONTEXT` override is rejected; switch back to a local engine before continuing. Use your real domain: the example.com/net/org documentation roots and their subdomains are not valid setup targets.

## After setup

Open the displayed Control URL from the allowed IP. Use the administrator email/password and the current six-digit Authenticator code. Create a limited-use invitation and share it privately with the matching APK. See [owner/user instructions](OWNER_GUIDE.fa.md).

The server panel does **not** compile/sign Android or collect a keystore. Run the displayed client command on your Windows build workstation. Final signing, complete Release acceptance, external S3 credentials/backup rehearsal and calls remain separate gates. Calls stay unavailable. No production deployment is triggered merely by adding this script to the repository.

## راهنمای سریع فارسی

دستور شروع را روی سرور Ubuntu24.04 خودتان اجرا کنید، زبان فارسی و گزینهٔ۱ را انتخاب کنید. دامنه، IPv4 عمومی و IP خودتان برای مدیریت را بدهید. اگر Docker نصب نیست، راه‌انداز برای نصب آن از مخزن رسمی اجازه می‌گیرد.

برای DNS می‌توانید Zone ID و توکن محدود Cloudflare را در همان ترمینال بدهید؛ توکن ذخیره نمی‌شود. اگر Provider دیگری دارید، رکوردهای نمایش‌داده‌شده را آنجا بسازید و Enter بزنید تا بررسی شوند. ورودی۸۰/۴۴۳ را در Provider و فایروال مجاز کنید؛ دسترسی SSH را حفظ کنید.

ایمیل و رمز مدیر فقط وقتی حسابی وجود ندارد پرسیده می‌شوند. در مرحلهٔ Authenticator، کلید را خصوصی وارد برنامهٔ احراز هویت کنید؛ آن کلید را در کادر کد۶رقمی پنل ننویسید. رمز یا توکن را داخل چت و عکس عمومی نفرستید.

اگر نصب نیمه‌تمام شد، دوباره همین پنل و گزینهٔ۱ را باز کنید؛ تنظیمات و حساب‌های موجود حفظ می‌شوند. خطای گواهی/DNS با خاموش‌کردن TLS دور زده نمی‌شود. گزینهٔ۶ فقط سرویس را متوقف می‌کند و داده‌ها را حذف نمی‌کند.

با انتخاب هر گزینه صفحه پاک و بخش بعدی باز می‌شود؛ نتیجه تا زدن Enter نمایش داده می‌شود. گزینهٔ۸ منوی حذف سرویس‌ها را باز می‌کند: گزینهٔ۱ و سپس نوشتن `REMOVE` لازم است. فقط کانتینرها و شبکه‌های همین نصب حذف می‌شوند؛ پیام‌ها، حساب‌ها، رسانه، گواهی‌ها، تنظیمات، DNS، Docker و سورس باقی می‌مانند. گزینهٔ۰ یا تأیید خالی حذف را لغو می‌کند. برای راه‌اندازی دوباره، گزینهٔ۱ منوی اصلی را از همان پوشه اجرا کنید.

پس از نصب، وارد آدرس پنل شوید و دعوت محدود بسازید. اپ مخصوص API خودتان را با دستور نمایش‌داده‌شده روی کامپیوتر ویندوز بسازید؛ کلید امضا روی سرور ساخته یا ذخیره نمی‌شود.

## Validation scope

Run `python3 scripts/test-setup-server.py` for offline validation of inputs, secret handling, protected files, ownership, DNS planning, bootstrap guards and orchestration. CI additionally runs `python3 scripts/test-setup-server.py --integration` against a uniquely named disposable Docker project after building Core, checking real migrations/bootstrap, existing-account preservation, health and restart. That check does not install host packages, issue public certificates or edit real Cloudflare DNS. Fresh-host package installation, real DNS/provider reachability and certificate issuance need per-host verification; no blanket full-host acceptance is claimed.
