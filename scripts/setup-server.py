"""Interactive single-host setup. Standard library only; secrets stay outside Git."""
import argparse
import base64
import getpass
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
import urllib.parse
import warnings

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path('/etc/vyrn/runtime.env')
HOSTS = ('', 'api.', 'ws.', 'media.', 'push.', 'admin.', 'updates.')
LANG = 'en'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(NoRedirect)


def tr(en, fa):
    return fa if LANG == 'fa' else en


def ask(label, validate, default=''):
    while True:
        value = input(label + (f' [{default}]' if default else '') + ': ').strip() or default
        try:
            return validate(value)
        except ValueError as error:
            print(tr('Please correct: ', 'لطفاً اصلاح کنید: ') + str(error))


def yes(label):
    return input(label + ' [y/N]: ').strip().lower() in ('y', 'yes', 'بله')


def hidden(prompt):
    with warnings.catch_warnings():
        warnings.simplefilter('error', getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning:
            raise RuntimeError(tr('Hidden input unavailable; use an interactive SSH terminal.', 'ورودی مخفی در دسترس نیست؛ با ترمینال تعاملی SSH وصل شوید.')) from None


def domain(value):
    value = value.lower()
    label = r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?'
    if len(value) > 253 or not re.fullmatch(label + r'(?:\.' + label + ')+', value):
        raise ValueError(tr('Use a hostname such as chat.example.com, without https://.', 'دامنه مثل chat.example.com؛ بدون https://.'))
    if value.endswith(('.example.com', '.example.net', '.example.org')):
        raise ValueError(tr('Use your own domain, not the documentation example.', 'دامنهٔ واقعی خودتان را بدهید؛ نه نمونهٔ راهنما.'))
    if value.rsplit('.', 1)[-1].isdigit():
        raise ValueError(tr('A DNS hostname is required, not an IP address.', 'نام دامنه لازم است؛ نه آدرس IP.'))
    return value


def public_ip(value):
    address = ipaddress.IPv4Address(value)
    if not address.is_global:
        raise ValueError(tr('Enter the server public IPv4.', 'IPv4 عمومی سرور را وارد کنید.'))
    return str(address)


def cidrs(value):
    networks = [ipaddress.ip_network(part, strict=False) for part in value.split()]
    if not networks or any(n.prefixlen == 0 or n.is_multicast or n.is_unspecified for n in networks):
        raise ValueError(tr('Use your operator IP/VPN CIDR; do not allow the entire internet.', 'IP خودتان یا محدودهٔ VPN؛ کل اینترنت را مجاز نکنید.'))
    return ' '.join(map(str, networks))


def email(value):
    if len(value) > 254 or not re.fullmatch(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+', value):
        raise ValueError(tr('Enter a plain email address.', 'یک ایمیل ساده و معتبر وارد کنید.'))
    return value.lower()


def language(value):
    if value not in ('en', 'fa'):
        raise ValueError('en / fa')
    return value


def zone_id(value):
    if not re.fullmatch('[a-fA-F0-9]{32}', value):
        raise ValueError('32 hexadecimal characters / ۳۲ کاراکتر هگز')
    return value


def run(arguments, *, capture=False, env=None, timeout=1800):
    # Do not log argv/environment: bootstrap secrets are passed through env only.
    try:
        result = subprocess.run(arguments, cwd=ROOT, env=env, text=True,
                                stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(tr('Command unavailable or timed out; retry this step.', 'دستور در دسترس نیست یا زمان آن تمام شد؛ این مرحله را دوباره اجرا کنید.')) from None
    if result.returncode:
        raise RuntimeError(tr('Step failed. Existing data is preserved; fix the prerequisite and retry.', 'مرحله ناموفق بود. داده‌ها حفظ شدند؛ پیش‌نیاز را اصلاح و دوباره تلاش کنید.'))
    return result.stdout.strip() if capture else ''


def compose(*arguments, **options):
    environment = dict(options.pop('env', None) or os.environ)
    # Ambient shell/Compose settings must not override the protected instance file.
    for key in ('POSTGRES_PASSWORD', 'VYRN_DOMAIN', 'VYRN_ADMIN_TOTP_SECRET',
                'VYRN_ADMIN_ALLOWED_CIDRS', 'COMPOSE_PROFILES', 'COMPOSE_FILE', 'COMPOSE_PROJECT_NAME'):
        environment.pop(key, None)
    return run(['docker', 'compose', '--project-name', 'vyrn', '--env-file', str(CONFIG),
                '-f', str(ROOT / 'deployment/compose.yaml'), *arguments], env=environment, **options)


def private_directory():
    CONFIG.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = CONFIG.parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise RuntimeError(tr('Configuration directory must be private and owned by this administrator.', 'پوشهٔ تنظیمات باید خصوصی و متعلق به مدیر اجراکننده باشد.'))


def write_config(values):
    private_directory()
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(CONFIG, flags, 0o600), 'w', encoding='utf-8') as target:
        target.write(''.join(f'{key}={value}\n' for key, value in values.items()))
        target.flush()
        os.fsync(target.fileno())


def settings():
    info = CONFIG.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise RuntimeError(tr('Runtime file must be a private regular file owned by this administrator.', 'فایل تنظیمات باید عادی، خصوصی و متعلق به مدیر اجراکننده باشد.'))
    result = {}
    for line in CONFIG.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip().strip('\"\x27')
    for key in ('VYRN_DOMAIN', 'POSTGRES_PASSWORD', 'VYRN_ADMIN_TOTP_SECRET', 'VYRN_ADMIN_ALLOWED_CIDRS'):
        if not result.get(key):
            raise RuntimeError(tr(f'Missing {key}; inspect the private config without replacing it.', f'{key} موجود نیست؛ فایل خصوصی را بررسی کنید و جایگزین نکنید.'))
    domain(result['VYRN_DOMAIN'])
    cidrs(result['VYRN_ADMIN_ALLOWED_CIDRS'])
    return result


def host_check():
    if sys.platform != 'linux':
        raise RuntimeError('The server setup panel runs on Ubuntu24.04, not Windows/macOS.')
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    if release.get('ID', '').strip('\"') != 'ubuntu' or release.get('VERSION_ID', '').strip('\"') != '24.04':
        raise RuntimeError('Automatic setup currently supports Ubuntu24.04 only.')
    if shutil.disk_usage(ROOT).free < 12 * 1024**3:
        raise RuntimeError(tr('At least12GiB free is needed for builds and the10GiB media reserve.', 'حداقل۱۲گیگابایت فضای آزاد برای ساخت و ذخیرهٔ امن رسانه لازم است.'))


def docker_ready():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise RuntimeError('Use the local Docker context without DOCKER_HOST/DOCKER_CONTEXT overrides.')
    if not shutil.which('docker'):
        if not yes(tr('Install Docker Engine from the official Ubuntu repository?', 'Docker از مخزن رسمی اوبونتو نصب شود؟')):
            raise RuntimeError(tr('Docker is required.', 'Docker لازم است.'))
        # Preserve conflicting installations; never uninstall another service automatically.
        for package in ('docker.io', 'podman-docker', 'containerd', 'runc'):
            probe = subprocess.run(['dpkg-query', '-W', '-f=${Status}', package], capture_output=True, text=True)
            if probe.returncode == 0 and probe.stdout.strip() == 'install ok installed':
                raise RuntimeError(tr('Existing container packages need manual review; see Docker install guide.', 'بسته‌های کانتینری موجود نیاز به بررسی دستی دارند؛ راهنمای Docker را ببینید.'))
        run(['apt-get', 'update'])
        run(['apt-get', 'install', '-y', 'ca-certificates', 'curl'])
        key = Path('/etc/apt/keyrings/docker.asc')
        source = Path('/etc/apt/sources.list.d/docker.sources')
        arch = run(['dpkg', '--print-architecture'], capture=True)
        if arch not in ('amd64', 'arm64'):
            raise RuntimeError('Only amd64/arm64 hosts are supported by this installer.')
        repository = ('Types: deb\nURIs: https://download.docker.com/linux/ubuntu\nSuites: noble\n'
                      f'Components: stable\nArchitectures: {arch}\nSigned-By: {key}\n')
        if Path('/etc/apt/sources.list.d/docker.list').exists():
            raise RuntimeError('Existing Docker repository needs manual review; preserved.')
        if source.exists():
            if source.read_text() != repository or not key.is_file() or key.stat().st_size < 128:
                raise RuntimeError('Existing Docker repository differs; preserved for manual review.')
        else:
            if key.exists():
                raise RuntimeError('Existing Docker key preserved; inspect it using the manual guide.')
            key.parent.mkdir(mode=0o755, exist_ok=True)
            try:
                run(['curl', '--fail', '--silent', '--show-error', '--proto', '=https', '--tlsv1.2',
                     'https://download.docker.com/linux/ubuntu/gpg', '--output', str(key)])
                key.chmod(0o644)
                source.write_text(repository)
            except (RuntimeError, OSError):
                key.unlink(missing_ok=True)
                raise
        run(['apt-get', 'update'])
        run(['apt-get', 'install', '-y', 'docker-ce', 'docker-ce-cli', 'containerd.io',
             'docker-buildx-plugin', 'docker-compose-plugin'])
        run(['systemctl', 'enable', '--now', 'docker'])
    run(['docker', 'info', '--format', '{{.ServerVersion}}'], capture=True, timeout=30)
    run(['docker', 'compose', 'version'], capture=True, timeout=30)
    endpoint = run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], capture=True)
    if not endpoint.startswith('unix://'):
        raise RuntimeError('Only a local Unix-socket Docker engine is supported.')


def owned_stack():
    identifiers = run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=vyrn'], capture=True).split()
    if not identifiers:
        return False
    containers = json.loads(run(['docker', 'inspect', *identifiers], capture=True))
    for container in containers:
        directory = container['Config']['Labels'].get('com.docker.compose.project.working_dir', '')
        if not directory or Path(directory).resolve() != ROOT:
            raise RuntimeError(tr('Another VYRN deployment owns these containers. Use its checkout or the manual guide.', 'این کانتینرها متعلق به نصب دیگری هستند؛ از پوشهٔ همان نصب یا راهنمای دستی استفاده کنید.'))
    return any(c['Config']['Labels'].get('com.docker.compose.service') == 'caddy' and
               c['State']['Running'] for c in containers)


def free_ports():
    for port in (80, 443):
        with socket.socket() as probe:
            try:
                probe.bind(('0.0.0.0', port))
            except OSError:
                raise RuntimeError(tr(f'Port{port} is occupied. Preserve the existing proxy; use the manual integration guide.', f'پورت{port} اشغال است؛ پراکسی موجود را حفظ و راهنمای اتصال دستی را استفاده کنید.')) from None


def dns_check(values, address):
    missing = []
    for prefix in HOSTS:
        name = prefix + values['VYRN_DOMAIN']
        try:
            output = run(['getent', 'ahostsv4', name], capture=True, timeout=10)
            addresses = {line.split()[0] for line in output.splitlines() if line.split()}
        except RuntimeError:
            addresses = set()
        ok = address in addresses
        print(('  OK  ' if ok else '  --  ') + name + ' → ' + address)
        if not ok:
            missing.append(name)
    return not missing


def cloudflare_dns(values, address):
    if not yes(tr('Use Cloudflare to create missing DNS records automatically?', 'رکوردهای DNS ناموجود با Cloudflare خودکار ساخته شوند؟')):
        return
    zone = ask('Cloudflare Zone ID', zone_id)
    token = hidden(tr('Cloudflare API token (hidden, not saved): ', 'توکن Cloudflare (مخفی؛ ذخیره نمی‌شود): '))
    if not token or any(char.isspace() for char in token):
        raise RuntimeError('Cloudflare token is empty or contains whitespace.')
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
    base = 'https://api.cloudflare.com/client/v4/zones/' + zone + '/dns_records'

    def api(url, body=None):
        request = urllib.request.Request(url, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            with HTTP.open(request, timeout=20) as response:
                result = json.load(response)
            if not result.get('success'):
                raise ValueError()
            return result['result']
        except (OSError, ValueError, KeyError):
            raise RuntimeError(tr('Cloudflare rejected the request; credentials/response not printed.', 'درخواست Cloudflare رد شد؛ اطلاعات محرمانه و پاسخ چاپ نشدند.')) from None

    try:
        missing = []
        for prefix in HOSTS:
            name = prefix + values['VYRN_DOMAIN']
            records = api(base + '?' + urllib.parse.urlencode({'name': name}))
            if not records:
                missing.append(name)
            elif len(records) != 1 or records[0].get('type') != 'A' or records[0].get('content') != address or records[0].get('proxied'):
                raise RuntimeError(tr('DNS conflict preserved at ', 'رکورد متعارض حفظ شد: ') + name)
        for name in missing:
            api(base, {'type': 'A', 'name': name, 'content': address, 'ttl': 300, 'proxied': False})
            print('  OK  ' + name)
    finally:
        headers.clear()
        token = ''


def bootstrap():
    table = compose('exec', '-T', 'postgres', 'psql', '-U', 'vyrn', '-d', 'vyrn', '-At', '-v',
                    'ON_ERROR_STOP=1', '-c', "SELECT to_regclass('public.users');", capture=True)
    if table:
        count = compose('exec', '-T', 'postgres', 'psql', '-U', 'vyrn', '-d', 'vyrn', '-At', '-v',
                        'ON_ERROR_STOP=1', '-c', 'SELECT count(*) FROM users;', capture=True)
        if not count.isdecimal():
            raise RuntimeError('Cannot establish database state; bootstrap refused.')
        if int(count):
            print(tr('Existing accounts preserved; initial bootstrap skipped.', 'حساب‌های موجود حفظ شدند؛ مدیر اولیه دوباره ساخته نمی‌شود.'))
            return False
    owner = ask(tr('New administrator email', 'ایمیل مدیر جدید'), email)
    while True:
        password = hidden(tr('New administrator password (12+ characters): ', 'رمز مدیر جدید (حداقل۱۲کاراکتر): '))
        if len(password) < 12 or len(password.encode()) > 1024 or '\x00' in password:
            print(tr('Use12+ characters and at most1024 bytes.', 'حداقل۱۲کاراکتر و حداکثر۱۰۲۴بایت.'))
            continue
        if password == hidden(tr('Repeat password: ', 'تکرار رمز: ')):
            break
        print(tr('Passwords differ.', 'رمزها یکسان نیستند.'))
    environment = os.environ.copy()
    environment.update(VYRN_BOOTSTRAP_EMAIL=owner, VYRN_BOOTSTRAP_PASSWORD=password,
                       VYRN_BOOTSTRAP_INVITE=secrets.token_hex(24))
    try:
        compose('run', '--rm', '-T', '-e', 'VYRN_BOOTSTRAP_EMAIL', '-e', 'VYRN_BOOTSTRAP_PASSWORD',
                '-e', 'VYRN_BOOTSTRAP_INVITE', 'core', 'bootstrap', env=environment)
    finally:
        for key in ('VYRN_BOOTSTRAP_EMAIL', 'VYRN_BOOTSTRAP_PASSWORD', 'VYRN_BOOTSTRAP_INVITE'):
            environment.pop(key, None)
        password = ''
    return True


def https_check(values, attempts=1):
    url = 'https://api.' + values['VYRN_DOMAIN'] + '/health/ready'
    for attempt in range(attempts):
        try:
            with HTTP.open(url, timeout=10) as response:
                if response.status == 200 and json.loads(response.read(4096)).get('status') == 'ready':
                    print('  OK  ' + url)
                    return True
        except (OSError, ValueError):
            pass
        if attempt + 1 < attempts:
            time.sleep(5)
    print(tr('HTTPS not ready. Check DNS, inbound80/443, certificates and Core; no TLS bypass is used.', 'HTTPS آماده نیست؛ DNS، ورودی۸۰/۴۴۳، گواهی و Core را بررسی کنید. اعتبارسنجی TLS خاموش نمی‌شود.'))
    return False


def authenticator(values):
    if yes(tr('Show the authenticator setup key in this private terminal? Never share it.', 'کلید Authenticator در این ترمینال خصوصی نمایش داده شود؟ آن را به کسی ندهید.')):
        print(tr('Time-based · SHA1 · 6 digits · 30 seconds', 'زمان‌محور · SHA1 · ۶رقمی · ۳۰ثانیه'))
        print(values['VYRN_ADMIN_TOTP_SECRET'])
        input(tr('Save it in your authenticator, then press Enter. ', 'در Authenticator ذخیره کنید؛ سپس Enter بزنید. '))


def links(values):
    print('\n' + tr('Control: ', 'پنل: ') + 'https://admin.' + values['VYRN_DOMAIN'])
    print(tr('Client build on your Windows workstation:', 'ساخت اپ روی کامپیوتر ویندوز خودتان:'))
    print('./scripts/build-android.ps1 -Flavor dev -BuildType debug -ApiUrl https://api.' + values['VYRN_DOMAIN'])
    print(tr('Create invitations in Control. Final signing/offsite backups are separate; calls remain unavailable.', 'دعوت‌ها را در پنل بسازید. امضای نهایی و بکاپ خارجی جدا هستند؛ تماس‌ها غیرفعال‌اند.'))


def install():
    host_check()
    docker_ready()
    if not owned_stack():
        free_ports()
    if CONFIG.exists() or CONFIG.is_symlink():
        values = settings()
        print(tr('Using existing protected settings; secrets will not be replaced.', 'تنظیمات خصوصی موجود استفاده می‌شود؛ رمزها جایگزین نمی‌شوند.'))
    else:
        name = ask(tr('Service domain, e.g. chat.yourdomain.com', 'دامنهٔ سرویس؛ مثل chat.yourdomain.com'), domain)
        address = ask(tr('Server public IPv4', 'IPv4 عمومی سرور'), public_ip)
        default = os.environ.get('SSH_CLIENT', '').split()
        default = default[0] if default else ''
        allowed = ask(tr('Your administrator IP or VPN CIDRs (space separated)', 'IP خودتان یا CIDRهای VPN، با فاصله'), cidrs, default)
        print(f'\nDomain: {name}\nServer: {address}\nAdmin network: {allowed}\nConfig: {CONFIG}')
        if not yes(tr('Save settings and set up this instance?', 'تنظیمات ذخیره و این نصب راه‌اندازی شود؟')):
            return
        values = dict(VYRN_DOMAIN=name, VYRN_PUBLIC_IPV4=address, VYRN_ADMIN_ALLOWED_CIDRS=allowed,
                      POSTGRES_PASSWORD=secrets.token_hex(32),
                      VYRN_ADMIN_TOTP_SECRET=base64.b32encode(secrets.token_bytes(20)).decode())
        write_config(values)
    address = public_ip(values['VYRN_PUBLIC_IPV4']) if values.get('VYRN_PUBLIC_IPV4') else ask(tr('Server public IPv4 for DNS checks', 'IPv4 عمومی سرور برای بررسی DNS'), public_ip)
    cloudflare_dns(values, address)
    print(tr('\nCreate these DNS-only A records; remove broken AAAA records. Allow80/443 at the provider/host.', '\nرکوردهای A زیر را DNS-only بسازید؛ AAAA خراب را حذف کنید. ورودی۸۰/۴۴۳ را در میزبان و Provider مجاز کنید.'))
    for prefix in HOSTS:
        print(f'  {prefix}{values["VYRN_DOMAIN"]}  →  {address}')
    while True:
        input(tr('After saving DNS records, press Enter to check. ', 'پس از ذخیرهٔ DNS، برای بررسی Enter بزنید. '))
        if dns_check(values, address):
            break
        if not yes(tr('DNS is not ready. Retry?', 'DNS هنوز آماده نیست. دوباره بررسی شود؟')):
            return
    print(tr('\n[1/4] Validate configuration and prepare Core', '\n[۱/۴] بررسی تنظیمات و آماده‌کردن Core'))
    compose('config', '-q')
    (ROOT / 'artifacts/updates').mkdir(parents=True, exist_ok=True)
    existing = compose('ps', '-aq', 'core', capture=True)
    if not existing:
        compose('build', 'core')
    print(tr('[2/4] Start database', '[۲/۴] راه‌اندازی دیتابیس'))
    compose('up', '-d', '--wait', 'postgres')
    print(tr('[3/4] Create first administrator only if database is empty', '[۳/۴] ساخت مدیر اولیه فقط در دیتابیس خالی'))
    bootstrap()
    print(tr('[4/4] Start Core/HTTPS and verify', '[۴/۴] اجرای Core و HTTPS و بررسی'))
    compose('up', '-d', '--no-build', '--wait', 'core', 'caddy')
    ready = https_check(values, attempts=8)
    print(tr('Services started; public HTTPS verified.' if ready else 'Services started; public HTTPS still needs attention.',
             'سرویس‌ها اجرا و HTTPS عمومی تأیید شد.' if ready else 'سرویس‌ها اجرا شدند؛ HTTPS عمومی هنوز نیاز به بررسی دارد.'))
    authenticator(values)
    links(values)


def main():
    global LANG
    parser = argparse.ArgumentParser(description='VYRN beginner setup and terminal control panel (Ubuntu24.04).')
    parser.add_argument('--lang', choices=('en', 'fa'))
    parser.add_argument('--check', action='store_true', help='Read-only host/prerequisite report; no installation')
    options = parser.parse_args()
    if options.check:
        host_check()
        print('OK: Ubuntu24.04 and disk budget; Docker CLI: ' + str(bool(shutil.which('docker'))))
        return
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise RuntimeError('Run on your Ubuntu server: sudo bash setup.sh')
    if not sys.stdin.isatty():
        raise RuntimeError('Interactive terminal required. Reconnect using ssh -t; do not pipe passwords.')
    LANG = options.lang or ask('Language / زبان: en or fa', language, 'en')
    while True:
        print('\n╭── VYRN SETUP ─────────────────────╮')
        print(tr('  1  Install / resume setup\n  2  Service status\n  3  Check public HTTPS\n  4  Authenticator setup\n  5  Start existing services\n  6  Stop services (keep data)\n  7  Client build / addresses\n  0  Exit',
                 '  ۱  نصب / ادامهٔ نصب\n  ۲  وضعیت سرویس‌ها\n  ۳  بررسی HTTPS عمومی\n  ۴  تنظیم Authenticator\n  ۵  روشن‌کردن سرویس‌های موجود\n  ۶  توقف سرویس‌ها؛ حفظ داده‌ها\n  ۷  ساخت اپ / آدرس‌ها\n  ۰  خروج'))
        print('╰───────────────────────────────────╯')
        choice = input('> ').strip().translate(str.maketrans('۰۱۲۳۴۵۶۷۸۹', '0123456789'))
        try:
            if choice == '0':
                return
            if choice == '1':
                install()
            elif choice in ('2', '3', '4', '5', '6', '7'):
                values = settings()
                if choice in ('2', '5', '6'):
                    run(['docker', 'info', '--format', '{{.ServerVersion}}'], capture=True, timeout=30)
                    owned_stack()
                if choice == '2':
                    compose('ps')
                elif choice == '3':
                    https_check(values)
                elif choice == '4':
                    authenticator(values)
                elif choice == '5':
                    compose('start', '--wait', 'postgres', 'core', 'caddy')
                elif choice == '6' and yes(tr('Stop this instance while retaining all volumes?', 'این نصب متوقف و همهٔ داده‌ها حفظ شوند؟')):
                    compose('stop', 'caddy', 'core', 'postgres')
                elif choice == '7':
                    links(values)
        except (RuntimeError, OSError, ValueError) as error:
            print(tr('Needs attention: ', 'نیاز به بررسی: ') + str(error))
            print('https://github.com/EclipseOfficialDota/VYRN/blob/main/docs/SETUP_WIZARD.md')


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nStopped; existing data/configuration preserved.')
        sys.exit(130)
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
