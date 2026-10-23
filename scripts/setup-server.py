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
import shlex
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
OS_RELEASE = Path('/etc/os-release')
COMMAND = Path('/usr/local/bin/vyrn')
SUPPORTED = {
    'ubuntu': {'22.04': 'jammy', '24.04': 'noble', '26.04': 'resolute', '26.10': 'stonking'},
    'debian': {'12': 'bookworm', '13': 'trixie'},
}
HOSTS = ('', 'api.', 'ws.', 'media.', 'push.', 'admin.', 'updates.')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(NoRedirect)


def color(text, code='36'):
    if sys.stdout.isatty() and os.environ.get('TERM') != 'dumb' and 'NO_COLOR' not in os.environ:
        return f'\033[{code}m{text}\033[0m'
    return text


def screen(title):
    if sys.stdout.isatty() and os.environ.get('TERM') != 'dumb':
        print('\033[2J\033[H', end='', flush=True)
    print('\n' + color('─' * 54))
    print(color('  VYRN', '1;36') + '  /  ' + color(title, '1;37'))
    print(color('─' * 54))


def choice_input():
    return input(color('> ', '1;36')).strip()


def ask(label, validate, default=''):
    while True:
        value = input(label + (f' [{default}]' if default else '') + ': ').strip() or default
        try:
            return validate(value)
        except ValueError as error:
            print('Please correct: ' + str(error))


def yes(label):
    return input(label + ' [y/N]: ').strip().lower() in ('y', 'yes')


def hidden(prompt):
    with warnings.catch_warnings():
        warnings.simplefilter('error', getpass.GetPassWarning)
        try:
            return getpass.getpass(prompt)
        except getpass.GetPassWarning:
            raise RuntimeError('Hidden input unavailable; use an interactive SSH terminal.') from None


def domain(value):
    value = value.lower()
    label = r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?'
    if len(value) > 253 or not re.fullmatch(label + r'(?:\.' + label + ')+', value):
        raise ValueError('Use a hostname such as chat.example.com, without https://.')
    reserved = ('example.com', 'example.net', 'example.org')
    if any(value == name or value.endswith('.' + name) for name in reserved):
        raise ValueError('Use your own domain, not the documentation example.')
    if value.rsplit('.', 1)[-1].isdigit():
        raise ValueError('A DNS hostname is required, not an IP address.')
    return value


def public_ip(value):
    address = ipaddress.IPv4Address(value)
    if not address.is_global:
        raise ValueError('Enter the server public IPv4.')
    return str(address)


def cidrs(value):
    networks = [ipaddress.ip_network(part, strict=False) for part in value.split()]
    if not networks or any(n.prefixlen == 0 or n.is_multicast or n.is_unspecified for n in networks):
        raise ValueError('Use your operator IP/VPN CIDR; do not allow the entire internet.')
    return ' '.join(map(str, networks))


def email(value):
    if len(value) > 254 or not re.fullmatch(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+', value):
        raise ValueError('Enter a plain email address.')
    return value.lower()


def zone_id(value):
    if not re.fullmatch('[a-fA-F0-9]{32}', value):
        raise ValueError('32 hexadecimal characters')
    return value


def run(arguments, *, capture=False, env=None, timeout=1800):
    # Do not log argv/environment: bootstrap secrets are passed through env only.
    try:
        result = subprocess.run(arguments, cwd=ROOT, env=env, text=True,
                                stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(f'{arguments[0]} unavailable or timed out; retry this step.') from None
    if result.returncode:
        raise RuntimeError(f'{arguments[0]} failed (exit {result.returncode}). Review the step output; data was not automatically reset.')
    return result.stdout.strip() if capture else ''


def compose(*arguments, **options):
    environment = dict(options.pop('env', None) or os.environ)
    # Ambient shell/Compose settings must not override the protected instance file.
    for key in ('POSTGRES_PASSWORD', 'VYRN_DOMAIN', 'VYRN_ADMIN_TOTP_SECRET',
                'VYRN_ADMIN_ALLOWED_CIDRS', 'COMPOSE_PROFILES', 'COMPOSE_FILE', 'COMPOSE_PROJECT_NAME'):
        environment.pop(key, None)
    files = ['-f', str(ROOT / 'deployment/compose.yaml')]
    if CONFIG.exists() and settings().get('VYRN_PROXY_MODE') == 'existing':
        files += ['-f', str(ROOT / 'deployment/compose.setup-proxy.yaml')]
    return run(['docker', 'compose', '--project-name', 'vyrn', '--env-file', str(CONFIG),
                *files, *arguments], env=environment, **options)


def private_directory():
    CONFIG.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = CONFIG.parent.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise RuntimeError('Configuration directory must be private and owned by this administrator.')


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
        raise RuntimeError('Runtime file must be a private regular file owned by this administrator.')
    result = {}
    for line in CONFIG.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip().strip('\"\x27')
    for key in ('VYRN_DOMAIN', 'POSTGRES_PASSWORD', 'VYRN_ADMIN_TOTP_SECRET', 'VYRN_ADMIN_ALLOWED_CIDRS'):
        if not result.get(key):
            raise RuntimeError(f'Missing {key}; inspect the private config without replacing it.')
    domain(result['VYRN_DOMAIN'])
    cidrs(result['VYRN_ADMIN_ALLOWED_CIDRS'])
    if result.get('VYRN_PROXY_MODE', 'standalone') not in ('standalone', 'existing'):
        raise ValueError('VYRN_PROXY_MODE must be standalone or existing; inspect the private settings.')
    return result


def services(values):
    return ('postgres', 'core') if values.get('VYRN_PROXY_MODE') == 'existing' else ('postgres', 'core', 'caddy')


def install_command():
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise RuntimeError('Register the command with: sudo bash setup.sh')
    marker = '# Managed by VYRN setup\n'
    if COMMAND.is_symlink() or (COMMAND.exists() and marker not in COMMAND.read_text()):
        raise RuntimeError('/usr/local/bin/vyrn already exists and is not managed by this installer; preserved.')
    script = shlex.quote(str(ROOT / 'setup.sh'))
    content = ('#!/bin/sh\n' + marker + f'if [ ! -f {script} ]; then\n'
               '  echo "VYRN checkout moved or was removed. Run sudo bash setup.sh from its new location." >&2\n'
               f'  exit 1\nfi\nif [ "$(id -u)" -ne 0 ]; then\n  exec sudo /bin/bash {script} "$@"\nfi\n'
               f'exec /bin/bash {script} "$@"\n')
    COMMAND.parent.mkdir(parents=True, exist_ok=True)
    if not COMMAND.exists() or COMMAND.read_text() != content:
        COMMAND.write_text(content)
    COMMAND.chmod(0o755)


def host_release():
    if sys.platform != 'linux':
        raise RuntimeError('The server setup panel runs on Ubuntu/Debian, not Windows/macOS.')
    release = dict(line.split('=', 1) for line in OS_RELEASE.read_text().splitlines() if '=' in line)
    distro = release.get('ID', '').strip('\"\x27')
    version = release.get('VERSION_ID', '').strip('\"\x27')
    codename = SUPPORTED.get(distro, {}).get(version)
    if not codename:
        raise RuntimeError('Automatic setup supports Ubuntu 22.04/24.04/26.04/26.10 and Debian 12/13 only.')
    return distro, version, codename


def host_check():
    distro, version, _ = host_release()
    if shutil.disk_usage(ROOT).free < 12 * 1024**3:
        raise RuntimeError('At least12GiB free is needed for builds and the10GiB media reserve.')
    return distro, version


def docker_repository(arch, key):
    distro, _, codename = host_release()
    if arch not in ('amd64', 'arm64'):
        raise RuntimeError('Only amd64/arm64 hosts are supported by this installer.')
    base = 'https://download.docker.com/linux/' + distro
    repository = (f'Types: deb\nURIs: {base}\nSuites: {codename}\n'
                  f'Components: stable\nArchitectures: {arch}\nSigned-By: {key}\n')
    return repository, base + '/gpg'


def docker_ready():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise RuntimeError('Use the local Docker context without DOCKER_HOST/DOCKER_CONTEXT overrides.')
    if not shutil.which('docker'):
        distro, _, _ = host_release()
        if not yes(f'Install Docker Engine from its official {distro} repository?'):
            raise RuntimeError('Docker is required.')
        # Preserve conflicting installations; never uninstall another service automatically.
        for package in ('docker.io', 'podman-docker', 'containerd', 'runc'):
            probe = subprocess.run(['dpkg-query', '-W', '-f=${Status}', package], capture_output=True, text=True)
            if probe.returncode == 0 and probe.stdout.strip() == 'install ok installed':
                raise RuntimeError('Existing container packages need manual review; see Docker install guide.')
        run(['apt-get', 'update'])
        run(['apt-get', 'install', '-y', 'ca-certificates', 'curl'])
        key = Path('/etc/apt/keyrings/docker.asc')
        source = Path('/etc/apt/sources.list.d/docker.sources')
        arch = run(['dpkg', '--print-architecture'], capture=True)
        repository, key_url = docker_repository(arch, key)
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
                     key_url, '--output', str(key)])
                key.chmod(0o644)
                source.write_text(repository)
            except (RuntimeError, OSError):
                key.unlink(missing_ok=True)
                raise
        run(['apt-get', 'update'])
        run(['apt-get', 'install', '-y', 'docker-ce', 'docker-ce-cli', 'containerd.io',
             'docker-buildx-plugin', 'docker-compose-plugin'])
        run(['systemctl', 'enable', '--now', 'docker'])
    check_engine()


def check_engine():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise RuntimeError('Use the local Docker context without DOCKER_HOST/DOCKER_CONTEXT overrides.')
    # Inspect local context metadata before contacting any daemon, including status.
    endpoint = run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], capture=True, timeout=30)
    if not endpoint.startswith('unix://'):
        raise RuntimeError('Only a local Unix-socket Docker engine is supported.')
    run(['docker', 'info', '--format', '{{.ServerVersion}}'], capture=True, timeout=30)
    run(['docker', 'compose', 'version'], capture=True, timeout=30)


def owned_stack():
    check_engine()
    identifiers = run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=vyrn'], capture=True).split()
    if not identifiers:
        return False
    containers = json.loads(run(['docker', 'inspect', *identifiers], capture=True))
    for container in containers:
        directory = container['Config']['Labels'].get('com.docker.compose.project.working_dir', '')
        if not directory or Path(directory).resolve() != ROOT:
            raise RuntimeError('Another VYRN deployment owns these containers. Use its checkout or the manual guide.')
    return any(c['Config']['Labels'].get('com.docker.compose.service') == 'caddy' and
               c['State']['Running'] for c in containers)


def free_ports(ports=(80, 443)):
    for port in ports:
        with socket.socket() as probe:
            try:
                probe.bind(('0.0.0.0', port))
            except OSError:
                raise RuntimeError(f'Port {port} is occupied. Use existing-proxy mode or inspect the listener in Diagnostics; do not stop unrelated services.') from None


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
        print(color('  OK  ', '32') + name + ' → ' + address if ok else color('  WAIT  ', '33') + name + ' → ' + address)
        if not ok:
            missing.append(name)
    return not missing


def cloudflare_dns(values, address):
    if not yes('Use Cloudflare to create missing DNS records automatically?'):
        return
    zone = ask('Cloudflare Zone ID', zone_id)
    token = hidden('Cloudflare API token (hidden, not saved): ')
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
            raise RuntimeError('Cloudflare rejected the request; credentials/response not printed.') from None

    try:
        missing = []
        for prefix in HOSTS:
            name = prefix + values['VYRN_DOMAIN']
            records = api(base + '?' + urllib.parse.urlencode({'name': name}))
            if not records:
                missing.append(name)
            elif len(records) != 1 or records[0].get('type') != 'A' or records[0].get('content') != address or records[0].get('proxied'):
                raise RuntimeError('DNS conflict preserved at ' + name)
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
            print('Existing accounts preserved; initial bootstrap skipped.')
            return False
    owner = ask('New administrator email', email)
    while True:
        password = hidden('New administrator password (12+ characters): ')
        if len(password) < 12 or len(password.encode()) > 1024 or '\x00' in password:
            print('Use12+ characters and at most1024 bytes.')
            continue
        if password == hidden('Repeat password: '):
            break
        print('Passwords differ.')
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


def start_existing(*services):
    # Older Compose supports up --wait but not start --wait. Preserve containers.
    compose('start', *services)
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        identifiers = compose('ps', '-aq', *services, capture=True).split()
        if len(identifiers) != len(services):
            raise RuntimeError('Some services are missing; use Install / resume setup.')
        containers = json.loads(run(['docker', 'inspect', *identifiers], capture=True, timeout=30))
        states = [container['State'] for container in containers]
        if any(state.get('Health', {}).get('Status') == 'unhealthy' for state in states):
            raise RuntimeError('A service is unhealthy; inspect its status. Data is preserved.')
        if all(state.get('Running') and state.get('Health', {}).get('Status', 'healthy') == 'healthy' for state in states):
            print('Services running and health checks passed.')
            return
        time.sleep(2)
    raise RuntimeError('Services did not become healthy in time; data preserved.')


def https_check(values, attempts=1):
    url = 'https://api.' + values['VYRN_DOMAIN'] + '/health/ready'
    for attempt in range(attempts):
        try:
            with HTTP.open(url, timeout=10) as response:
                if response.status == 200 and json.loads(response.read(4096)).get('status') == 'ready':
                    print(color('  OK  ', '32') + url)
                    return True
        except (OSError, ValueError):
            pass
        if attempt + 1 < attempts:
            time.sleep(5)
    print('HTTPS not ready. Check DNS, inbound80/443, certificates and Core; no TLS bypass is used.')
    print('Next: run Diagnostics and DNS / records. In existing-proxy mode configure your host proxy and certificate first (Proxy setup guide).')
    return False


def authenticator(values):
    if yes('Show the authenticator setup key in this private terminal? Never share it.'):
        print('Time-based · SHA1 · 6 digits · 30 seconds')
        print(values['VYRN_ADMIN_TOTP_SECRET'])
        input('Save it in your authenticator, then press Enter. ')


def links(values):
    print('\n' + 'Control: ' + 'https://admin.' + values['VYRN_DOMAIN'])
    print('Client build on your Windows workstation:')
    print('./scripts/build-android.ps1 -Flavor dev -BuildType debug -ApiUrl https://api.' + values['VYRN_DOMAIN'])
    print('Create invitations in Control. Final signing/offsite backups are separate; calls remain unavailable.')


def install():
    host_check()
    docker_ready()
    running_proxy = owned_stack()
    existing_proxy = False
    if CONFIG.exists() or CONFIG.is_symlink():
        values = settings()
        print('Using existing protected settings; secrets will not be replaced.')
    else:
        if not running_proxy:
            try:
                free_ports()
            except RuntimeError as error:
                print(color(str(error), '33'))
                print('Existing-proxy mode builds PostgreSQL/Core only, bound to 127.0.0.1:18080.')
                print('You must configure HTTPS and restricted admin access in your current proxy; it will not be edited automatically.')
                if not yes('Continue using the existing reverse proxy?'):
                    return
                existing_proxy = True
        name = ask('Service domain, e.g. chat.yourdomain.com', domain)
        address = ask('Server public IPv4', public_ip)
        default = os.environ.get('SSH_CLIENT', '').split()
        default = default[0] if default else ''
        allowed = ask('Your administrator IP or VPN CIDRs (space separated)', cidrs, default)
        print(f'\nDomain: {name}\nServer: {address}\nAdmin network: {allowed}\nConfig: {CONFIG}')
        if not yes('Save settings and set up this instance?'):
            return
        values = dict(VYRN_DOMAIN=name, VYRN_PUBLIC_IPV4=address, VYRN_ADMIN_ALLOWED_CIDRS=allowed,
                      VYRN_PROXY_MODE='existing' if existing_proxy else 'standalone',
                      POSTGRES_PASSWORD=secrets.token_hex(32),
                      VYRN_ADMIN_TOTP_SECRET=base64.b32encode(secrets.token_bytes(20)).decode())
        write_config(values)
    if values.get('VYRN_PROXY_MODE') != 'existing' and not running_proxy:
        free_ports()
    address = public_ip(values['VYRN_PUBLIC_IPV4']) if values.get('VYRN_PUBLIC_IPV4') else ask('Server public IPv4 for DNS checks', public_ip)
    cloudflare_dns(values, address)
    print('\nCreate these DNS-only A records; remove broken AAAA records. Allow80/443 at the provider/host.')
    for prefix in HOSTS:
        print(f'  {prefix}{values["VYRN_DOMAIN"]}  →  {address}')
    while True:
        input('After saving DNS records, press Enter to check. ')
        if dns_check(values, address):
            break
        if not yes('DNS is not ready. Retry?'):
            return
    print('\n[1/4] Validate configuration and prepare Core')
    compose('config', '-q')
    (ROOT / 'artifacts/updates').mkdir(parents=True, exist_ok=True)
    existing = compose('ps', '-aq', 'core', capture=True)
    if not existing:
        if values.get('VYRN_PROXY_MODE') == 'existing':
            free_ports((18080,))
        compose('build', 'core')
    print('[2/4] Start database')
    compose('up', '-d', '--wait', 'postgres')
    print('[3/4] Create first administrator only if database is empty')
    bootstrap()
    print('[4/4] Start Core/HTTPS and verify')
    compose('up', '-d', '--no-build', '--wait', *services(values)[1:])
    if values.get('VYRN_PROXY_MODE') == 'existing':
        proxy_help(values)
    ready = https_check(values, attempts=8)
    print('Services started; public HTTPS verified.' if ready else 'Services started; public HTTPS still needs attention.')
    authenticator(values)
    links(values)


def proxy_help(values=None):
    print(color('EXISTING REVERSE PROXY', '1;36'))
    print('1. Keep your existing Nginx/Caddy running on 80/443. Choose Install and accept existing-proxy mode.')
    print('2. Core will listen ONLY at http://127.0.0.1:18080; PostgreSQL stays private.')
    print('3. Route API/WebSocket/media/push hostnames to that backend with WebSocket upgrades.')
    print('4. Public API hostnames must deny /v1/admin and /v1/admin/* (404).')
    print('5. Serve the admin static directory below on the admin hostname; proxy /v1/ to Core.')
    print('   Restrict the entire admin host to your operator IP/VPN. Keep the real client IP in X-Real-IP.')
    print('   Admin files: ' + str(ROOT / 'admin'))
    if values:
        print('   Admin hostname: admin.' + values['VYRN_DOMAIN'])
        print('   Allowed networks: ' + values['VYRN_ADMIN_ALLOWED_CIDRS'])
    print('6. Obtain valid HTTPS certificates for your hostnames. Never disable certificate verification.')
    print('7. Validate your proxy configuration before reload (Nginx: sudo nginx -t). Then run Check public HTTPS.')
    print('Reference: https://github.com/EclipseOfficialDota/VYRN/blob/main/docs/DEPLOYMENT.md#existing-reverse-proxy-and-migration-from-earlier-settings')
    print('Templates in deployment/nginx-*.conf are examples: replace domains, paths and certificate names before use.')


def show_error(error):
    print(color('NEEDS ATTENTION: ', '1;31') + str(error))
    text = str(error).lower()
    if isinstance(error, FileNotFoundError) and not CONFIG.exists():
        solution = 'No instance settings found. Choose Install / resume setup first. Do not copy settings from another server.'
    elif 'port' in text and 'occupied' in text:
        solution = 'Open Diagnostics to identify the listener. For an existing proxy, use the Proxy setup guide and existing-proxy install mode.'
    elif 'docker_host' in text or 'context' in text or 'unix-socket' in text:
        solution = 'Unset DOCKER_HOST and DOCKER_CONTEXT; select a local Unix-socket context with docker context use. Remote engines are refused.'
    elif 'another vyrn' in text:
        solution = 'Use the original checkout identified by the Compose working_dir label; do not adopt or delete another installation.'
    elif 'docker' in text and ('repository' in text or 'key' in text or 'packages' in text):
        solution = 'Review the existing Docker package/repository configuration using the official distro guide. It was preserved; do not blindly remove container packages.'
    elif 'docker' in text:
        solution = 'Run Diagnostics. Check systemctl status docker, local engine access, docker compose version, registry connectivity and free disk. Retry Install after correction.'
    elif 'apt-get' in text:
        solution = 'Check DNS/internet and apt repository errors. Wait for other apt/dpkg processes; never delete lock files. Correct repositories, then retry.'
    elif 'private' in text or 'runtime' in text or 'missing vyrn' in text:
        solution = 'Inspect /etc/vyrn ownership and permissions locally: directory 0700, runtime.env 0600, owned by root. Do not print or regenerate existing secrets.'
    elif 'cloudflare' in text or 'dns conflict' in text:
        solution = 'Check the zone ID, token DNS-edit scope and the displayed hostname. Resolve conflicting A/AAAA/proxy records in your provider; existing records were preserved.'
    elif 'gib' in text or 'space' in text:
        solution = 'Inspect df -h and docker system df. Free reviewed unused files or enlarge the disk; do not delete volumes to make space.'
    elif 'healthy' in text or 'services' in text:
        solution = 'Choose Service status and Diagnostics. Inspect docker compose logs privately; correct database/configuration/disk errors, then resume Install. Never reset volumes as a login fix.'
    else:
        solution = 'Run Diagnostics, correct the reported prerequisite, then retry the same action. Existing passwords and data are not automatically replaced.'
    print(color('NEXT: ', '1;33') + solution)
    print('Help: https://github.com/EclipseOfficialDota/VYRN/blob/main/docs/SETUP_WIZARD.md#troubleshooting')


def diagnostics():
    print('Read-only checks. No packages, services, firewall rules or settings will be changed.\n')
    checks = [('OS and disk', host_check), ('Local Docker engine / Compose', check_engine)]
    for label, check in checks:
        try:
            check()
            print(color('PASS  ', '32') + label)
        except (RuntimeError, OSError, ValueError) as error:
            print(color('FAIL  ', '31') + label)
            show_error(error)
    try:
        listeners = run(['ss', '-lntp'], capture=True, timeout=10)
        print('\nTCP listeners on 80 / 443 / 18080:')
        print('\n'.join(line for line in listeners.splitlines() if re.search(r':(?:80|443|18080)\s', line)) or 'None')
    except (RuntimeError, OSError) as error:
        show_error(error)
    try:
        values = settings()
        print(color('\nPASS  ', '32') + 'Protected settings; mode: ' + values.get('VYRN_PROXY_MODE', 'standalone'))
        owned_stack()
        compose('ps')
        dns_menu(values)
        https_check(values)
    except (RuntimeError, OSError, ValueError) as error:
        show_error(error)
    print('\nFor firewall checks, preserve SSH and review provider inbound TCP 80/443; no firewall is changed here.')
    print('For authenticator failures, use the current six-digit code, not the setup key; check timedatectl status on the server and automatic time on your phone.')


def dns_menu(values=None):
    values = values or settings()
    address = values.get('VYRN_PUBLIC_IPV4') or ask('Server public IPv4', public_ip)
    if not dns_check(values, public_ip(address)):
        print(color('NEXT: ', '33') + 'Point all seven DNS-only A records above to this IP. Remove stale AAAA records only if IPv6 is not configured. Wait for propagation, then retry.')


def remove():
    values = settings()
    screen('Remove services')
    print('Removes this instance’s containers and networks.')
    print('Keeps accounts, messages, media, certificates and private settings.')
    print('DNS, Docker, source files and other applications stay in place.')
    print('\n  1  ' + 'Remove services (keep data)')
    print('  0  ' + 'Back')
    choice = choice_input()
    if choice == '0':
        return
    if choice != '1':
        raise ValueError('Choose 1 or 0.')
    screen('Confirm removal')
    print('Instance: ' + values['VYRN_DOMAIN'])
    if input('Type REMOVE to remove services, or Enter to cancel: ').strip() != 'REMOVE':
        print('Cancelled.')
        return
    # Recheck ownership and the local endpoint immediately before any mutation.
    owned_stack()
    compose('down')
    print('Services removed. Data and settings retained; option 1 reinstalls them.')


def main():
    parser = argparse.ArgumentParser(description='VYRN beginner setup and terminal control panel (Ubuntu/Debian).')
    parser.add_argument('--lang', choices=('en',), help=argparse.SUPPRESS)
    parser.add_argument('--install-command', action='store_true', help='Register the vyrn terminal command')
    parser.add_argument('--check', action='store_true', help='Read-only host/prerequisite report; no installation')
    options = parser.parse_args()
    if options.install_command:
        install_command()
        return
    if options.check:
        distro, version = host_check()
        print(f'OK: {distro} {version} and disk budget; Docker CLI: ' + str(bool(shutil.which('docker'))))
        return
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise RuntimeError('Run on your Ubuntu/Debian server: sudo bash setup.sh')
    if not sys.stdin.isatty():
        raise RuntimeError('Interactive terminal required. Reconnect using ssh -t; do not pipe passwords.')
    actions = {
        '1': 'Install / resume setup',
        '2': 'Service status',
        '3': 'Check public HTTPS',
        '4': 'Authenticator setup',
        '5': 'Start existing services',
        '6': 'Stop services (keep data)',
        '7': 'Client build / addresses',
        '8': 'Remove services (keep data)',
        '9': 'Diagnostics / troubleshoot',
        '10': 'DNS records / check',
        '11': 'Existing proxy setup guide',
    }
    while True:
        screen('Server control')
        print('  ' + 'Ubuntu / Debian · Self-hosted setup')
        for number, title in actions.items():
            if number in ('1', '2', '5', '7', '8'):
                print()
            print('  ' + color(number.rjust(2), '1;31' if number == '8' else '1;36') + '  ' + title)
        print('\n  0  ' + 'Exit')
        print('\n' + '─' * 54)
        choice = choice_input()
        if choice == '0':
            return
        screen(actions.get(choice, 'Choose an option'))
        try:
            if choice == '1':
                install()
            elif choice == '8':
                remove()
            elif choice == '9':
                diagnostics()
            elif choice == '10':
                dns_menu()
            elif choice == '11':
                proxy_help(settings() if CONFIG.exists() else None)
            elif choice in ('2', '3', '4', '5', '6', '7'):
                values = settings()
                if choice in ('2', '5', '6'):
                    owned_stack()
                if choice == '2':
                    compose('ps')
                elif choice == '3':
                    https_check(values)
                elif choice == '4':
                    authenticator(values)
                elif choice == '5':
                    start_existing(*services(values))
                elif choice == '6' and yes('Stop this instance while retaining all volumes?'):
                    compose('stop', *reversed(services(values)))
                elif choice == '7':
                    links(values)
            else:
                print('Choose a number from 0 to 11.')
        except (RuntimeError, OSError, ValueError) as error:
            show_error(error)
        input('\nPress Enter to return to the main menu. ')


if __name__ == '__main__':
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print('\nStopped; existing data/configuration preserved.')
        sys.exit(130)
    except (RuntimeError, OSError) as error:
        show_error(error)
        sys.exit(1)
