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
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import warnings

ROOT = Path(__file__).resolve().parents[1]
CONFIG = Path('/etc/vyrn/runtime.env')
OS_RELEASE = Path('/etc/os-release')
COMMAND = Path('/usr/local/bin/vyrn')
NGINX_SITE = Path('/etc/nginx/conf.d/vyrn-setup.conf')
WEB_ROOT = Path('/var/lib/vyrn/acme')
ADMIN_ROOT = Path('/var/lib/vyrn/admin')
CERT_ROOT = Path('/etc/letsencrypt/live')
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
    print('Next: use Domain and HTTPS or the failed-stage repair action. Host Nginx can use Diagnose and repair > Repair Core and host Nginx HTTPS.')
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
    try:
        setup_step('1/6  Check this server', host_check)
        setup_step('2/6  Prepare Docker', docker_ready, start_docker, 'Start the local Docker engine')
        values = setup_step('3/6  Configure this instance', configure_instance)
        address = public_ip(values['VYRN_PUBLIC_IPV4']) if values.get('VYRN_PUBLIC_IPV4') else ask('Server public IPv4', public_ip)
        def verify_dns():
            if not dns_check(values, address):
                raise RuntimeError('DNS is not ready. Point every displayed hostname to this server and check stale AAAA records.')
        setup_step('4/6  Connect your domain', verify_dns,
                   lambda: cloudflare_dns(values, address), 'Create missing DNS records with Cloudflare')
        setup_step('5/6  Build and start your backend', lambda: ensure_backend(values))
        def verify_https():
            if not https_check(values):
                raise RuntimeError('Public HTTPS still needs attention. Backend startup alone does not configure an existing proxy.')
        repair = repair_nginx if values.get('VYRN_PROXY_MODE') == 'existing' else lambda: compose('up', '-d', '--no-build', '--wait', 'core', 'caddy')
        label = 'Configure / repair host Nginx and HTTPS' if values.get('VYRN_PROXY_MODE') == 'existing' else 'Start / reconcile Core and Caddy'
        setup_step('6/6  Verify public HTTPS', verify_https, repair, label)
        save_progress('Setup completed', complete=True)
        screen('Setup complete')
        print(color('READY  Backend and public HTTPS verified.', '1;32'))
        authenticator(values)
        links(values)
    except SetupPaused:
        print(color('Setup paused. Correct the prerequisite and choose Resume setup; existing data and secrets remain.', '33'))


class SetupPaused(Exception):
    pass


def save_progress(stage, *, complete=False):
    if not CONFIG.exists():
        return
    private_directory()
    # Store only stage/status metadata, never exception text, inputs or secrets.
    with tempfile.NamedTemporaryFile(mode='w', dir=CONFIG.parent, delete=False, encoding='utf-8') as target:
        json.dump({'stage': stage, 'complete': complete, 'checked_at': time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}, target)
        name = target.name
    os.replace(name, CONFIG.parent / 'setup-state.json')


def setup_step(title, action, repair=None, repair_label=''):
    while True:
        screen('Easy setup  /  ' + title)
        try:
            save_progress(title)
            result = action()
            print(color('PASS  ' + title, '32'), flush=True)
            return result
        except (RuntimeError, OSError, ValueError) as error:
            show_error(error)
        while True:
            print('\n  1  Retry this step\n  2  Run read-only diagnostics')
            if repair:
                print('  3  ' + repair_label)
            print('  0  Pause setup and return')
            choice = choice_input()
            if choice in ('', '0'):
                raise SetupPaused()
            if choice == '1':
                break
            if choice not in ('2', '3') or (choice == '3' and repair is None):
                continue
            try:
                if choice == '2':
                    diagnostics()
                else:
                    repair()
                    break  # Verify the original condition again; a repair is not proof.
            except (RuntimeError, OSError, ValueError) as error:
                show_error(error)


def start_docker():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise RuntimeError('Unset DOCKER_HOST/DOCKER_CONTEXT before local Docker repair.')
    endpoint = run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], capture=True, timeout=15)
    if not endpoint.startswith('unix://'):
        raise RuntimeError('Select a local Unix-socket Docker context before repair.')
    run(['systemctl', 'start', 'docker'], timeout=60)
    check_engine()


def configure_instance():
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
                    raise SetupPaused()
                existing_proxy = True
        name = ask('Service domain, e.g. chat.yourdomain.com', domain)
        address = ask('Server public IPv4', public_ip)
        default = os.environ.get('SSH_CLIENT', '').split()
        default = default[0] if default else ''
        allowed = ask('Your administrator IP or VPN CIDRs (space separated)', cidrs, default)
        print(f'\nDomain: {name}\nServer: {address}\nAdmin network: {allowed}\nConfig: {CONFIG}')
        if not yes('Save settings and set up this instance?'):
            raise SetupPaused()
        values = dict(VYRN_DOMAIN=name, VYRN_PUBLIC_IPV4=address, VYRN_ADMIN_ALLOWED_CIDRS=allowed,
                      VYRN_PROXY_MODE='existing' if existing_proxy else 'standalone',
                      POSTGRES_PASSWORD=secrets.token_hex(32),
                      VYRN_ADMIN_TOTP_SECRET=base64.b32encode(secrets.token_bytes(20)).decode())
        write_config(values)
    if values.get('VYRN_PROXY_MODE') != 'existing' and not running_proxy:
        free_ports()
    return values


def ensure_backend(values):
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


def nginx_config(values, *, tls=False):
    name = domain(values['VYRN_DOMAIN'])
    hosts = ' '.join(prefix + name for prefix in HOSTS)
    challenge = f'location ^~ /.well-known/acme-challenge/ {{ root {WEB_ROOT}; }}'
    config = ('# Managed by VYRN setup\nserver {\n    listen 80;\n'
              f'    server_name {hosts};\n    {challenge}\n'
              '    location / { return 301 https://$host$request_uri; }\n}\n')
    if not tls:
        return config
    certificate = CERT_ROOT / ('vyrn-setup-' + name)
    ssl = (f'    ssl_certificate {certificate}/fullchain.pem;\n'
           f'    ssl_certificate_key {certificate}/privkey.pem;\n'
           '    ssl_protocols TLSv1.2 TLSv1.3;\n'
           '    add_header X-Content-Type-Options nosniff always;\n'
           '    add_header Referrer-Policy no-referrer always;\n'
           '    client_max_body_size 251m;\n')
    proxy = ('        proxy_pass http://127.0.0.1:18080;\n'
             '        proxy_http_version 1.1;\n'
             '        proxy_set_header Host $host;\n'
             '        proxy_set_header X-Real-IP $remote_addr;\n'
             '        proxy_set_header X-Forwarded-For $remote_addr;\n'
             '        proxy_set_header X-Forwarded-Proto https;\n'
             '        proxy_set_header Upgrade $http_upgrade;\n'
             '        proxy_set_header Connection "upgrade";\n'
             '        proxy_read_timeout 75s;\n')
    config += ('server {\n    listen 443 ssl;\n    server_name ' + ' '.join(p + name for p in ('api.', 'ws.', 'media.', 'push.')) + ';\n' + ssl +
               '    location = /metrics { return 404; }\n'
               '    location = /v1/admin { return 404; }\n'
               '    location ^~ /v1/admin/ { return 404; }\n'
               '    location / {\n' + proxy + '    }\n}\n')
    config += ('server {\n    listen 443 ssl;\n    server_name admin.' + name + ';\n' + ssl +
               ''.join('    allow ' + network + ';\n' for network in cidrs(values['VYRN_ADMIN_ALLOWED_CIDRS']).split()) +
               '    deny all;\n' + f'    root {ADMIN_ROOT};\n'
               '    location /v1/ {\n' + proxy + '    }\n'
               '    location / { try_files $uri $uri/ /index.html; }\n}\n')
    return config + ('server {\n    listen 443 ssl;\n    server_name ' + name + ' updates.' + name + ';\n' + ssl +
                     '    location / { return 503 "VYRN: service unavailable.\\n"; }\n}\n')


def nginx_preflight(values):
    if values.get('VYRN_PROXY_MODE') != 'existing':
        raise RuntimeError('Automatic Nginx repair requires existing-proxy mode. Use Install for a standalone instance.')
    if not shutil.which('nginx'):
        raise RuntimeError('Automatic repair supports host Nginx only. Use the proxy guide for Caddy/Apache/container proxies.')
    if run(['systemctl', 'is-active', 'nginx'], capture=True, timeout=15) != 'active':
        raise RuntimeError('Host Nginx is not active. Inspect systemctl status nginx before repair.')
    if NGINX_SITE.is_symlink() or (NGINX_SITE.exists() and not NGINX_SITE.read_text().startswith('# Managed by VYRN setup\n')):
        raise RuntimeError('Foreign Nginx configuration at ' + str(NGINX_SITE) + '; preserved.')
    dump = run(['nginx', '-T'], capture=True, timeout=30)
    # Exclude only this installer-owned file, not unrelated server blocks.
    for section in re.split(r'# configuration file ', dump):
        if section.startswith(str(NGINX_SITE) + ':'):
            continue
        for names in re.findall(r'\bserver_name\s+([^;]+);', re.sub(r'#.*', '', section)):
            for host in names.split():
                for prefix in HOSTS:
                    target = prefix + values['VYRN_DOMAIN']
                    if host.startswith('~') or host == target or (host.startswith('*.') and target.endswith(host[1:])) or (host.startswith('.') and (target == host[1:] or target.endswith(host))):
                        raise RuntimeError('An existing Nginx server_name conflicts with the VYRN hostnames; preserved. Review that site before automatic repair.')


def repair_nginx():
    values = settings()
    host_check()
    owned_stack()
    nginx_preflight(values)
    address = public_ip(values.get('VYRN_PUBLIC_IPV4', ''))
    if not dns_check(values, address):
        raise RuntimeError('DNS is not ready. Correct all seven records before automatic HTTPS repair.')
    print('Repair will build/start this instance if needed, install Certbot if missing, and configure its own Nginx site.')
    print('Other sites remain running. Only a validated reload is used; no port-owning process is killed or disabled.')
    print('On failure or Ctrl+C, the previous VYRN Nginx file is restored. Existing accounts/secrets are retained.')
    print('Certificate issuance requires acceptance of https://letsencrypt.org/repository/ terms.')
    if not yes('Repair ' + values['VYRN_DOMAIN'] + ' and accept the certificate terms?'):
        return
    contact = ask('Certificate renewal contact email', email)
    ensure_backend(values)
    with HTTP.open('http://127.0.0.1:18080/health/ready', timeout=10) as response:
        if response.status != 200 or json.loads(response.read(4096)).get('status') != 'ready':
            raise RuntimeError('Local Core is not ready; Nginx settings were not changed.')
    if not shutil.which('certbot'):
        run(['apt-get', 'update'])
        run(['apt-get', 'install', '-y', 'certbot'])
    for directory in (WEB_ROOT, ADMIN_ROOT):
        if any(parent.is_symlink() for parent in (directory, *directory.parents)):
            raise RuntimeError('Symlink in Nginx content directory; preserved.')
        directory.mkdir(parents=True, exist_ok=True)
        if directory.stat().st_uid != os.geteuid() or directory.stat().st_mode & 0o022:
            raise RuntimeError('Nginx content directory must be administrator-owned and not writable by other users.')
        directory.chmod(0o755)
    # Copy only the three public admin assets; the checkout may live under /root.
    for name in ('index.html', 'app.js', 'style.css'):
        target = ADMIN_ROOT / name
        if target.is_symlink():
            raise RuntimeError('Symlink in admin assets; preserved.')
        shutil.copyfile(ROOT / 'admin' / name, target)
        target.chmod(0o644)
    before = NGINX_SITE.read_bytes() if NGINX_SITE.exists() else None
    mode = NGINX_SITE.stat().st_mode & 0o777 if before is not None else 0o644
    try:
        # Retain existing HTTPS during renewal; an initial install needs HTTP only.
        if before is None:
            NGINX_SITE.write_text(nginx_config(values))
            NGINX_SITE.chmod(0o644)
        run(['nginx', '-t'], timeout=30)
        loaded = run(['nginx', '-T'], capture=True, timeout=30)
        if '# configuration file ' + str(NGINX_SITE) + ':' not in loaded:
            raise RuntimeError('Nginx does not include the installer site directory; restore completed. Use the proxy guide.')
        run(['systemctl', 'reload', 'nginx'], timeout=30)
        challenge = WEB_ROOT / '.well-known/acme-challenge'
        if challenge.is_symlink() or challenge.parent.is_symlink():
            raise RuntimeError('Symlink in certificate challenge directory; preserved.')
        challenge.mkdir(parents=True, exist_ok=True)
        token = secrets.token_hex(16)
        probe = challenge / token
        probe.write_text(token)
        try:
            for prefix in HOSTS:
                host = prefix + values['VYRN_DOMAIN']
                print('Checking public HTTP challenge: ' + host, flush=True)
                with HTTP.open('http://' + host + '/.well-known/acme-challenge/' + token, timeout=10) as response:
                    if response.status != 200 or response.read(128).decode() != token:
                        raise RuntimeError('HTTP certificate challenge is unreachable; inspect DNS, IPv6 and provider port 80.')
        finally:
            probe.unlink(missing_ok=True)
        args = ['certbot', 'certonly', '--webroot', '-w', str(WEB_ROOT), '--non-interactive', '--agree-tos',
                '--email', contact, '--cert-name', 'vyrn-setup-' + values['VYRN_DOMAIN'], '--keep-until-expiring',
                '--deploy-hook', 'nginx -t && systemctl reload nginx']
        for prefix in HOSTS:
            args += ['-d', prefix + values['VYRN_DOMAIN']]
        run(args, timeout=600)
        NGINX_SITE.write_text(nginx_config(values, tls=True))
        NGINX_SITE.chmod(0o644)
        run(['nginx', '-t'], timeout=30)
        run(['systemctl', 'reload', 'nginx'], timeout=30)
        if not https_check(values, attempts=3):
            raise RuntimeError('Public HTTPS verification failed after repair; inspect DNS, IPv6, certificates and inbound port 443.')
        run(['systemctl', 'enable', '--now', 'certbot.timer'], timeout=30)
    except BaseException:
        if before is None:
            NGINX_SITE.unlink(missing_ok=True)
        else:
            NGINX_SITE.write_bytes(before)
            NGINX_SITE.chmod(mode)
        try:
            run(['nginx', '-t'], timeout=30)
            run(['systemctl', 'reload', 'nginx'], timeout=30)
        except (RuntimeError, OSError):
            print(color('RESTORE NEEDS ATTENTION: previous site file is restored, but Nginx validation/reload failed. Run nginx -t and systemctl status nginx.', '1;31'))
        raise
    save_progress('Nginx repair completed', complete=True)
    print(color('REPAIRED: Core ready, public HTTPS verified and certificate renewal timer enabled.', '1;32'))
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
    elif 'nginx' in text and ('foreign' in text or 'conflict' in text):
        solution = 'An existing site/configuration overlaps these hostnames. It was not replaced. Inspect nginx -T privately and migrate that site before retrying Nginx repair.'
    elif 'nginx' in text or 'certbot' in text or 'certificate' in text or 'https' in text:
        solution = 'Use the failed-stage repair action or Diagnose and repair > Repair Core and host Nginx HTTPS. Check Core readiness, DNS/AAAA and inbound TCP 80/443. Failed repairs restore the previous VYRN Nginx file; certificates/data are retained.'
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
    elif 'cloudflare' in text or 'dns' in text:
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


def instance_state():
    state = {'kind': 'new', 'values': None, 'core': 'Not installed', 'database': 'Not installed',
             'proxy': 'Not installed', 'https': 'Not verified', 'stage': '', 'has_services': False, 'error': ''}
    if not CONFIG.exists() and not CONFIG.is_symlink():
        return state
    state['kind'] = 'incomplete'
    try:
        state['values'] = settings()
        expected = set(services(state['values']))
        if state['values'].get('VYRN_PROXY_MODE') == 'existing':
            state['proxy'] = 'Host proxy (use HTTPS check)'
        journal = CONFIG.parent / 'setup-state.json'
        progress = json.loads(journal.read_text()) if journal.is_file() and not journal.is_symlink() else {}
        state['stage'] = str(progress.get('stage', 'Verify the existing installation'))
        if progress.get('complete'):
            state['https'] = 'Last verified: ' + str(progress.get('checked_at', 'unknown'))
        owned_stack()  # Local endpoint and checkout ownership before reading services.
        identifiers = compose('ps', '-aq', *services(state['values']), capture=True).split()
        state['has_services'] = bool(identifiers)
        containers = json.loads(run(['docker', 'inspect', *identifiers], capture=True, timeout=15)) if identifiers else []
        healthy = set()
        for container in containers:
            service = container['Config']['Labels'].get('com.docker.compose.service')
            if service not in expected:
                continue
            status = container['State']
            text = status.get('Health', {}).get('Status', 'running') if status.get('Running') else 'stopped'
            state[{'postgres': 'database', 'core': 'core', 'caddy': 'proxy'}[service]] = text.capitalize()
            if status.get('Running') and status.get('Health', {}).get('Status', 'healthy' if service == 'caddy' else 'unknown') == 'healthy':
                healthy.add(service)
        if healthy == expected and progress.get('complete'):
            state['kind'] = 'installed'
    except (RuntimeError, OSError, ValueError, KeyError, TypeError) as error:
        state['error'] = str(error)
    return state


def section(title, actions):
    screen(title)
    for key, (label, _) in actions.items():
        print('  ' + color(key, '1;36') + '  ' + label)
    print('  0  Back')
    choice = choice_input()
    if choice in actions:
        label, action = actions[choice]
        screen(label)
        action()
    elif choice not in ('', '0'):
        print('Choose one of the displayed options.')


def service_controls(values):
    def control(verb):
        owned_stack()
        if verb == 'start':
            start_existing(*services(values))
        elif yes('Stop this instance? Accounts, messages and files will be retained.'):
            compose('stop', *reversed(services(values)))
    section('Services', {'1': ('Show service status', lambda: (owned_stack(), compose('ps'))),
                         '2': ('Start retained services', lambda: control('start')),
                         '3': ('Stop services, keep data', lambda: control('stop'))})


def repair_menu():
    section('Diagnose and repair', {'1': ('Read-only diagnostics', diagnostics),
                                   '2': ('Resume guided setup / retry failed stages', install),
                                   '3': ('Repair Core and host Nginx HTTPS', repair_nginx),
                                   '4': ('Start local Docker engine', start_docker)})


def domain_controls(values):
    section('Domain and HTTPS', {'1': ('Check DNS and display required records', lambda: dns_menu(values)),
                                '2': ('Create missing DNS records with Cloudflare', lambda: cloudflare_dns(values, public_ip(values.get('VYRN_PUBLIC_IPV4', '')))),
                                '3': ('Verify public HTTPS', lambda: https_check(values)),
                                '4': ('Repair host Nginx HTTPS', repair_nginx),
                                '5': ('Manual proxy integration guide', lambda: proxy_help(values))})


def admin_access(values):
    def details():
        print('Use your own administrator email/password created during initial setup. There is no default password.')
        print('Enter the current six-digit authenticator code on the verification screen, not the setup key.')
        links(values)
    section('Administrator access', {'1': ('Control URL and client connection details', details),
                                     '2': ('Privately enroll the authenticator', lambda: authenticator(values))})


def help_panel():
    print('Easy install checks this server, prepares Docker, saves private settings, connects DNS, starts the backend and verifies HTTPS.')
    print('A failed stage offers retry, diagnostics and a relevant repair where available. Returning later rechecks conditions; existing accounts/secrets are kept.')
    print('A repair is always rechecked before the wizard advances. DNS/provider/firewall faults can require operator action.')
    print('Backups/restore and source upgrades remain documented workflows; they are not shown as unimplemented menu buttons.')
    print('Guide: https://github.com/EclipseOfficialDota/VYRN/blob/main/docs/SETUP_WIZARD.md')


def dashboard_actions(state):
    if state['kind'] == 'new':
        return {'1': ('Easy install', install), '2': ('Check this server', diagnostics), '3': ('Setup help', help_panel)}
    values = state['values']
    actions = {'1': ('Connection details' if state['kind'] == 'installed' else 'Resume setup / finish verification',
                     (lambda: links(values)) if state['kind'] == 'installed' else install),
               '2': ('Diagnose and repair', repair_menu)}
    if values:
        actions['4'] = ('Domain and HTTPS', lambda: domain_controls(values))
        if state['has_services']:
            actions['3'] = ('Service controls', lambda: service_controls(values))
            actions['8'] = ('Remove services (keep data)', remove)
        if state['core'] in ('Healthy', 'Running'):
            actions['5'] = ('Administrator access', lambda: admin_access(values))
        if state['kind'] == 'installed':
            actions['6'] = ('Run guided setup checks again', install)
    actions['9'] = ('Help and limitations', help_panel)
    return dict(sorted(actions.items(), key=lambda pair: int(pair[0])))


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
    while True:
        state = instance_state()
        actions = dashboard_actions(state)
        screen('Welcome' if state['kind'] == 'new' else 'Your server')
        print(color({'new': 'NOT INSTALLED', 'incomplete': 'SETUP NEEDS ATTENTION', 'installed': 'INSTALLED'}[state['kind']], '1;32' if state['kind'] == 'installed' else '1;33'))
        print(f"Core: {state['core']}  |  Database: {state['database']}\nProxy: {state.get('proxy', 'Check HTTPS')}\nHTTPS: {state['https']}")
        if state['kind'] == 'incomplete' and state['stage']:
            print('Resume: ' + state['stage'])
        if state['error']:
            print(color('Check needed: ', '33') + state['error'])
        print()
        for number, (title, _) in actions.items():
            print('  ' + color(number.rjust(2), '1;31' if number == '8' else '1;36') + '  ' + title)
        print('\n   0  Exit')
        choice = choice_input()
        if choice == '0':
            return
        try:
            if choice in actions:
                label, action = actions[choice]
                screen(label)
                action()
            else:
                print('Choose one of the displayed options.')
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
