"""Offline installer contracts; optional isolated real Docker bootstrap via --integration."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import uuid

spec = importlib.util.spec_from_file_location('setup', Path(__file__).with_name('setup-server.py'))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Path(self.temp.name) / 'private/runtime.env'
        self.config.parent.mkdir(mode=0o700)
        self.scope = patch.object(setup, 'CONFIG', self.config)
        self.scope.start()
        self.addCleanup(self.scope.stop)

    def values(self):
        return dict(VYRN_DOMAIN='chat.test-domain.org', POSTGRES_PASSWORD='local-test-only',
                    VYRN_ADMIN_TOTP_SECRET='JBSWY3DPEHPK3PXP', VYRN_ADMIN_ALLOWED_CIDRS='192.0.2.10/32')

    def test_input_boundaries(self):
        self.assertEqual(setup.domain('Chat.Test-Domain.org'), 'chat.test-domain.org')
        self.assertEqual(setup.domain('myexample.com'), 'myexample.com')
        for value in ('https://chat.test.org', 'chat;id.test.org', '-a.test.org', '8.8.8.8', 'chat.example.com', 'x..org', 'example.com', 'example.net', 'example.org'):
            with self.assertRaises(ValueError):
                setup.domain(value)
        self.assertEqual(setup.cidrs('192.0.2.10 2001:db8::1/128'), '192.0.2.10/32 2001:db8::1/128')
        for value in ('0.0.0.0/0', '::/0', '', 'hello'):
            with self.assertRaises(ValueError):
                setup.cidrs(value)
        self.assertEqual(setup.public_ip('8.8.8.8'), '8.8.8.8')
        for value in ('127.0.0.1', '192.168.1.1', '::1'):
            with self.assertRaises(ValueError):
                setup.public_ip(value)
        self.assertEqual(setup.email('Owner@Test-Domain.org'), 'owner@test-domain.org')
        with self.assertRaises(ValueError):
            setup.email('Owner <owner@test.org>')

    def test_release_and_docker_repository_matrix(self):
        release = Path(self.temp.name) / 'os-release'
        expected = {'ubuntu': {'22.04': 'jammy', '24.04': 'noble', '26.04': 'resolute', '26.10': 'stonking'},
                    'debian': {'12': 'bookworm', '13': 'trixie'}}
        for distro, versions in expected.items():
            for version, codename in versions.items():
                for arch in ('amd64', 'arm64'):
                    with self.subTest(distro=distro, version=version, arch=arch), patch.object(setup, 'OS_RELEASE', release):
                        release.write_text(f'ID="{distro}"\nVERSION_ID="{version}"\nVERSION_CODENAME=untrusted-wrong-suite\n')
                        self.assertEqual(setup.host_release(), (distro, version, codename))
                        repository, key_url = setup.docker_repository(arch, '/etc/apt/keyrings/docker.asc')
                        self.assertIn(f'URIs: https://download.docker.com/linux/{distro}\n', repository)
                        self.assertIn(f'Suites: {codename}\n', repository)
                        self.assertIn(f'Architectures: {arch}\n', repository)
                        self.assertEqual(key_url, f'https://download.docker.com/linux/{distro}/gpg')
                        with self.assertRaises(RuntimeError):
                            setup.docker_repository('armhf', '/unused')

    def test_unsupported_releases_rejected_by_python_and_shell_bootstrap(self):
        release = Path(self.temp.name) / 'os-release'
        shell = (setup.ROOT / 'setup.sh').read_text()
        guard = re.search(r'    case .*?    esac', shell, re.S).group(0)
        releases = [(distro, version, True) for distro, versions in setup.SUPPORTED.items() for version in versions]
        releases += [('ubuntu', '20.04', False), ('ubuntu', '25.04', False), ('debian', '11', False), ('debian', '14', False), ('linuxmint', '22', False), ('', '', False)]
        for distro, version, supported in releases:
            with self.subTest(distro=distro, version=version), patch.object(setup, 'OS_RELEASE', release):
                release.write_text(f'ID={distro}\nVERSION_ID={version}\nID_LIKE=ubuntu\n')
                if supported:
                    setup.host_release()
                else:
                    with self.assertRaises(RuntimeError):
                        setup.host_release()
                result = subprocess.run(['bash', '-c', guard], env=dict(os.environ, ID=distro, VERSION_ID=version), capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, supported)

    def test_docker_install_uses_detected_repository_and_key(self):
        release = Path(self.temp.name) / 'os-release'
        for distro, versions in setup.SUPPORTED.items():
            for version, codename in versions.items():
                with self.subTest(distro=distro, version=version), tempfile.TemporaryDirectory() as temp:
                    release.write_text(f'ID={distro}\nVERSION_ID={version}\n')
                    def path(value):
                        return Path(temp) / value.lstrip('/')
                    def command(args, **options):
                        if args[:2] == ['dpkg', '--print-architecture']:
                            return 'amd64'
                        if args[0] == 'curl':
                            Path(args[-1]).write_text('public-test-key-' * 20)
                        return ''
                    path('/etc/apt/sources.list.d').mkdir(parents=True)
                    with patch.object(setup, 'OS_RELEASE', release), patch.object(setup, 'Path', side_effect=path), patch.object(setup.shutil, 'which', return_value=None), patch.object(setup, 'yes', return_value=True), patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', '')), patch.object(setup, 'run', side_effect=command) as run, patch.object(setup, 'check_engine') as check:
                        setup.docker_ready()
                    source = path('/etc/apt/sources.list.d/docker.sources').read_text()
                    self.assertIn(f'Suites: {codename}\n', source)
                    self.assertIn(f'URIs: https://download.docker.com/linux/{distro}\n', source)
                    curl = next(call.args[0] for call in run.call_args_list if call.args[0][0] == 'curl')
                    self.assertIn(f'https://download.docker.com/linux/{distro}/gpg', curl)
                    check.assert_called_once()

    def test_secret_prompt_never_falls_back_to_echo(self):
        with patch.object(setup.getpass, 'getpass', side_effect=setup.getpass.GetPassWarning('echo disabled unavailable')):
            with self.assertRaises(RuntimeError):
                setup.hidden('Private: ')

    def test_private_config_never_replaced(self):
        setup.write_config(self.values())
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertEqual(setup.settings(), self.values())
        before = self.config.read_bytes()
        with self.assertRaises(FileExistsError):
            setup.write_config(self.values())
        self.assertEqual(self.config.read_bytes(), before)
        self.config.chmod(0o644)
        with self.assertRaises(RuntimeError):
            setup.settings()

    def test_config_symlink_refused(self):
        other = Path(self.temp.name) / 'unrelated'
        other.write_text('preserve')
        self.config.symlink_to(other)
        with self.assertRaises(RuntimeError):
            setup.settings()
        with self.assertRaises(FileExistsError):
            setup.write_config(self.values())
        self.assertEqual(other.read_text(), 'preserve')

    def test_shell_environment_cannot_override_instance(self):
        with patch.dict(os.environ, POSTGRES_PASSWORD='ambient', COMPOSE_PROFILES='calls'), patch.object(setup, 'run') as run:
            setup.compose('ps')
        self.assertNotIn('POSTGRES_PASSWORD', run.call_args.kwargs['env'])
        self.assertNotIn('COMPOSE_PROFILES', run.call_args.kwargs['env'])
        self.assertEqual(run.call_args.args[0][:5], ['docker', 'compose', '--project-name', 'vyrn', '--env-file'])

    def test_existing_users_skip_password_prompt(self):
        with patch.object(setup, 'compose', side_effect=['users', '1']) as command, patch.object(setup.getpass, 'getpass') as secret:
            self.assertFalse(setup.bootstrap())
        secret.assert_not_called()
        self.assertEqual(command.call_count, 2)

    def test_unknown_database_state_refuses_bootstrap(self):
        with patch.object(setup, 'compose', side_effect=['users', '?']), self.assertRaises(RuntimeError):
            setup.bootstrap()

    def test_new_bootstrap_secret_only_in_environment(self):
        recorded = []
        def command(*arguments, **options):
            recorded.append((arguments, dict(options.get('env', {}))))
            return ''
        with patch.object(setup, 'compose', side_effect=command), patch('builtins.input', return_value='owner@test-domain.org'), patch.object(setup.getpass, 'getpass', side_effect=['test-password-only-123', 'test-password-only-123']):
            self.assertTrue(setup.bootstrap())
        arguments, environment = recorded[-1]
        self.assertEqual(environment['VYRN_BOOTSTRAP_PASSWORD'], 'test-password-only-123')
        self.assertNotIn('test-password-only-123', arguments)
        self.assertNotIn('VYRN_BOOTSTRAP_PASSWORD', os.environ)

    def test_foreign_stack_is_not_adopted(self):
        foreign = [{'Config': {'Labels': {'com.docker.compose.project.working_dir': '/another/deployment'}}}]
        with patch.object(setup, 'check_engine'), patch.object(setup, 'run', side_effect=['abc123', json.dumps(foreign)]), self.assertRaises(RuntimeError):
            setup.owned_stack()

    def test_compose_working_directories_belong_to_this_checkout(self):
        for directory in (setup.ROOT, setup.ROOT / 'deployment'):
            for running in (False, True):
                containers = [{'Config': {'Labels': {
                    'com.docker.compose.project.working_dir': str(directory),
                    'com.docker.compose.service': 'caddy',
                }}, 'State': {'Running': running}}]
                with self.subTest(directory=directory, running=running), patch.object(setup, 'check_engine'), patch.object(setup, 'run', side_effect=['abc123', json.dumps(containers)]):
                    self.assertEqual(setup.owned_stack(), running)

    def test_service_menu_rejects_remote_overrides_before_docker_calls(self):
        for variable in ('DOCKER_HOST', 'DOCKER_CONTEXT'):
            for choice in ('1', '2', '3'):
                with self.subTest(variable=variable, choice=choice), patch.dict(os.environ, {variable: 'remote-engine'}, clear=True), patch('builtins.input', return_value=choice), patch.object(setup, 'yes', return_value=True), patch.object(setup, 'run', return_value='') as command, contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaises(RuntimeError):
                        setup.service_controls(self.values())
                    command.assert_not_called()

    def test_saved_remote_context_is_rejected_before_contacting_engine(self):
        def command(arguments, **options):
            if arguments[:3] == ['docker', 'context', 'inspect']:
                return 'ssh://remote-engine'
            if arguments[:3] == ['docker', 'ps', '-aq']:
                return ''
            self.fail('Unexpected daemon call before checking its endpoint')
        with patch.dict(os.environ, {}, clear=True), patch.object(setup, 'run', side_effect=command), self.assertRaises(RuntimeError):
            setup.owned_stack()

    def test_local_engine_still_reaches_owned_stack_check(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(setup, 'run', side_effect=['unix:///var/run/docker.sock', '27.0', 'Compose v2', '']) as command:
            self.assertFalse(setup.owned_stack())
        self.assertEqual(command.call_args_list[0].args[0][:3], ['docker', 'context', 'inspect'])
        self.assertEqual(command.call_args_list[-1].args[0][:3], ['docker', 'ps', '-aq'])

    def test_occupied_proxy_port_is_not_stopped(self):
        with patch.object(setup.socket, 'socket') as socket:
            socket.return_value.__enter__.return_value.bind.side_effect = OSError()
            with self.assertRaises(RuntimeError):
                setup.free_ports()

    def test_dns_requires_all_names(self):
        with patch.object(setup, 'run', return_value='8.8.8.8 STREAM name') as command:
            self.assertTrue(setup.dns_check(self.values(), '8.8.8.8'))
        self.assertEqual(command.call_count, 7)
        with patch.object(setup, 'run', return_value='1.1.1.1 STREAM other'):
            self.assertFalse(setup.dns_check(self.values(), '8.8.8.8'))

    def test_cloudflare_checks_all_conflicts_before_writing(self):
        def response(body):
            return contextlib.closing(io.StringIO(json.dumps({'success': True, 'result': body})))
        records = [response([]) for _ in range(6)] + [response([{'type': 'A', 'content': '1.1.1.1', 'proxied': False}])]
        with patch.object(setup, 'yes', return_value=True), patch('builtins.input', return_value='a'*32), patch.object(setup.getpass, 'getpass', return_value='offline-test-only'), patch.object(setup.HTTP, 'open', side_effect=records) as http:
            with self.assertRaises(RuntimeError):
                setup.cloudflare_dns(self.values(), '8.8.8.8')
        self.assertEqual(http.call_count, 7)
        self.assertTrue(all(call.args[0].get_method() == 'GET' for call in http.call_args_list))

    def test_cloudflare_missing_records_create_dns_only(self):
        calls = []
        def request(req, **options):
            calls.append(req)
            body = [] if req.get_method() == 'GET' else {'id': 'test-only'}
            return contextlib.closing(io.StringIO(json.dumps({'success': True, 'result': body})))
        with patch.object(setup, 'yes', return_value=True), patch('builtins.input', return_value='a'*32), patch.object(setup.getpass, 'getpass', return_value='offline-test-only'), patch.object(setup.HTTP, 'open', side_effect=request):
            setup.cloudflare_dns(self.values(), '8.8.8.8')
        self.assertEqual(len(calls), 14)
        for req in calls[7:]:
            body = json.loads(req.data)
            self.assertFalse(body['proxied'])
            self.assertEqual(body['content'], '8.8.8.8')

    def test_https_failure_never_reports_success(self):
        with patch.object(setup.HTTP, 'open', side_effect=urllib.error.URLError('certificate rejected')):
            self.assertFalse(setup.https_check(self.values()))
        self.assertIsNone(setup.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://unsafe/'))

    def test_authenticator_not_disclosed_without_choice(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(setup, 'yes', return_value=False):
            setup.authenticator(self.values())
        self.assertNotIn(self.values()['VYRN_ADMIN_TOTP_SECRET'], output.getvalue())

    def test_pending_dns_preserves_config_and_never_starts_database(self):
        setup.write_config(dict(self.values(), VYRN_PUBLIC_IPV4='8.8.8.8'))
        before = self.config.read_bytes()
        with patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=True), patch.object(setup, 'cloudflare_dns'), patch.object(setup, 'dns_check', return_value=False), patch('builtins.input', return_value=''), patch.object(setup, 'yes', return_value=False), patch.object(setup, 'compose') as command:
            setup.install()
        command.assert_not_called()
        self.assertEqual(self.config.read_bytes(), before)

    def test_fresh_wizard_order_and_no_secret_in_summary(self):
        output = io.StringIO()
        events = []
        def command(*args, **options):
            events.append(args)
            return ''
        with contextlib.redirect_stdout(output), patch.object(setup, 'ROOT', Path(self.temp.name)), patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=False), patch.object(setup, 'free_ports'), patch.object(setup, 'ask', side_effect=['chat.test-domain.org', '8.8.8.8', '192.0.2.10/32']), patch.object(setup, 'yes', return_value=True), patch.object(setup, 'cloudflare_dns'), patch.object(setup, 'dns_check', return_value=True), patch('builtins.input', return_value=''), patch.object(setup, 'compose', side_effect=command), patch.object(setup, 'bootstrap', side_effect=lambda: events.append(('bootstrap',))), patch.object(setup, 'https_check', return_value=False), patch.object(setup, 'authenticator'):
            setup.install()
        self.assertLess(events.index(('up', '-d', '--wait', 'postgres')), events.index(('bootstrap',)))
        self.assertLess(events.index(('bootstrap',)), events.index(('up', '-d', '--no-build', '--wait', 'core', 'caddy')))
        self.assertIn('Public HTTPS still needs attention', output.getvalue())
        values = setup.settings()
        self.assertNotIn(values['POSTGRES_PASSWORD'], output.getvalue())
        self.assertNotIn(values['VYRN_ADMIN_TOTP_SECRET'], output.getvalue())

    def test_start_preserves_containers_and_waits_for_health(self):
        starting = json.dumps([{'State': {'Running': True, 'Health': {'Status': 'starting'}}}])
        healthy = json.dumps([{'State': {'Running': True, 'Health': {'Status': 'healthy'}}}])
        with patch.object(setup, 'compose', side_effect=['', 'abc123', 'abc123']) as command, patch.object(setup, 'run', side_effect=[starting, healthy]) as inspect, patch.object(setup.time, 'sleep'):
            setup.start_existing('core')
        self.assertEqual(command.call_args_list[0].args, ('start', 'core'))
        self.assertEqual(inspect.call_count, 2)

    def test_start_missing_service_does_not_create_or_reset_it(self):
        with patch.object(setup, 'compose', side_effect=['', '']) as command, self.assertRaises(RuntimeError):
            setup.start_existing('core')
        self.assertEqual(command.call_count, 2)

    def test_screen_clears_only_interactive_capable_terminals(self):
        for interactive, terminal, clears in ((True, 'xterm', True), (True, 'dumb', False), (False, 'xterm', False)):
            output = io.StringIO()
            with self.subTest(interactive=interactive, terminal=terminal), patch.object(output, 'isatty', return_value=interactive), patch.dict(os.environ, TERM=terminal), contextlib.redirect_stdout(output):
                setup.screen('Test screen')
            self.assertEqual('\033[2J\033[H' in output.getvalue(), clears)
            self.assertIn('VYRN  /  Test screen', re.sub(r'\033\[[0-9;]*m', '', output.getvalue()))

    def test_menu_clears_before_action_and_pauses_before_return(self):
        events = []
        def prompt(label):
            events.append(('input', label))
            return next(answers)
        answers = iter(['1', '', '0'])
        state = dict(kind='installed', values=self.values(), core='Healthy', database='Healthy', https='Last verified', stage='', has_services=True, error='')
        with patch.object(sys, 'argv', ['setup-server.py', '--lang', 'en']), patch.object(setup.os, 'geteuid', return_value=0), patch.object(sys.stdin, 'isatty', return_value=True), patch.object(setup, 'instance_state', return_value=state), patch('builtins.input', side_effect=prompt), patch.object(setup, 'screen', side_effect=lambda title: events.append(('screen', title))), patch.object(setup, 'links', side_effect=lambda values: events.append(('action', 'links'))), contextlib.redirect_stdout(io.StringIO()):
            setup.main()
        self.assertEqual([event[0] for event in events], ['screen', 'input', 'screen', 'action', 'input', 'screen', 'input'])
        self.assertIn('return to the main menu', events[4][1])

    def test_remove_requires_exact_confirmation_and_supports_back(self):
        for answers in (['0'], ['1', ''], ['1', 'remove']):
            with self.subTest(answers=answers), patch.object(setup, 'settings', return_value=self.values()), patch('builtins.input', side_effect=answers), patch.object(setup, 'owned_stack') as ownership, patch.object(setup, 'compose') as command, contextlib.redirect_stdout(io.StringIO()):
                setup.remove()
                ownership.assert_not_called()
                command.assert_not_called()

    def test_remove_keeps_data_and_config_and_checks_ownership_first(self):
        setup.write_config(self.values())
        before = self.config.read_bytes()
        events = []
        with patch('builtins.input', side_effect=['1', 'REMOVE']), patch.object(setup, 'owned_stack', side_effect=lambda: events.append('ownership')), patch.object(setup, 'compose', side_effect=lambda *args: events.append(args)), contextlib.redirect_stdout(io.StringIO()):
            setup.remove()
        self.assertEqual(events, ['ownership', ('down',)])
        self.assertEqual(self.config.read_bytes(), before)

    def test_remove_refuses_foreign_or_remote_stack(self):
        with patch.object(setup, 'settings', return_value=self.values()), patch('builtins.input', side_effect=['1', 'REMOVE']), patch.object(setup, 'owned_stack', side_effect=RuntimeError('foreign or remote')), patch.object(setup, 'compose') as command, contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            setup.remove()
        command.assert_not_called()

    def test_command_registration_quotes_paths_and_preserves_foreign_commands(self):
        command = Path(self.temp.name) / 'bin/vyrn'
        checkout = Path(self.temp.name) / "repo with ' quote"
        with patch.object(setup, 'COMMAND', command), patch.object(setup, 'ROOT', checkout), patch.object(setup.os, 'geteuid', return_value=0):
            setup.install_command()
            first = command.read_bytes()
            self.assertEqual(command.stat().st_mode & 0o777, 0o755)
            self.assertIn(b'exec sudo /bin/bash', first)
            subprocess.run(['bash', '-n', str(command)], check=True)
            checkout.mkdir()
            (checkout / 'setup.sh').write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
            fake_bin = Path(self.temp.name) / 'fake-bin'
            fake_bin.mkdir()
            (fake_bin / 'id').write_text('#!/bin/sh\necho 0\n')
            (fake_bin / 'id').chmod(0o755)
            result = subprocess.run([str(command), '--check', 'two words'], cwd=self.temp.name,
                                    env=dict(os.environ, PATH=str(fake_bin) + ':' + os.environ['PATH']),
                                    check=True, capture_output=True, text=True)
            self.assertEqual(result.stdout.splitlines(), ['--check', 'two words'])
            setup.install_command()
            self.assertEqual(first, command.read_bytes())
            command.write_text('#!/bin/sh\necho unrelated\n')
            with self.assertRaises(RuntimeError):
                setup.install_command()
            self.assertIn('unrelated', command.read_text())
            command.unlink()
            command.symlink_to(self.config)
            with self.assertRaises(RuntimeError):
                setup.install_command()

    def test_no_color_and_english_only_menu(self):
        output = io.StringIO()
        with patch.object(output, 'isatty', return_value=True), patch.dict(os.environ, {'TERM': 'xterm', 'NO_COLOR': ''}), contextlib.redirect_stdout(output):
            self.assertEqual(setup.color('test'), 'test')
        source = Path(setup.__file__).read_text()
        self.assertIsNone(re.search('[\u0600-\u06ff]', source))
        self.assertNotIn('Language /', source)

    def test_existing_proxy_compose_and_services_exclude_caddy(self):
        values = dict(self.values(), VYRN_PROXY_MODE='existing')
        setup.write_config(values)
        with patch.object(setup, 'run', return_value='') as command:
            setup.compose('config', '-q')
        args = command.call_args.args[0]
        self.assertIn(str(setup.ROOT / 'deployment/compose.setup-proxy.yaml'), args)
        self.assertEqual(setup.services(values), ('postgres', 'core'))
        self.assertNotIn('COMPOSE_PROFILES', command.call_args.kwargs['env'])

    def test_occupied_ports_offer_existing_proxy_install_without_caddy(self):
        events = []
        def command(*args, **options):
            events.append(args)
            return ''
        with patch.object(setup, 'ROOT', Path(self.temp.name)), patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=False), patch.object(setup, 'free_ports', side_effect=[RuntimeError('Port 80 is occupied'), None]), patch.object(setup, 'ask', side_effect=['chat.test-domain.org', '8.8.8.8', '192.0.2.10/32']), patch.object(setup, 'yes', return_value=True), patch.object(setup, 'cloudflare_dns'), patch.object(setup, 'dns_check', return_value=True), patch('builtins.input', return_value=''), patch.object(setup, 'compose', side_effect=command), patch.object(setup, 'bootstrap'), patch.object(setup, 'https_check', return_value=False), patch.object(setup, 'authenticator'), contextlib.redirect_stdout(io.StringIO()):
            setup.install()
        self.assertEqual(setup.settings()['VYRN_PROXY_MODE'], 'existing')
        self.assertIn(('up', '-d', '--no-build', '--wait', 'core'), events)
        self.assertFalse(any('caddy' in args for args in events))

    def test_declining_existing_proxy_keeps_host_unchanged(self):
        with patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=False), patch.object(setup, 'free_ports', side_effect=RuntimeError('Port 80 is occupied')), patch.object(setup, 'yes', return_value=False), patch.object(setup, 'compose') as command, contextlib.redirect_stdout(io.StringIO()):
            setup.install()
        command.assert_not_called()
        self.assertFalse(self.config.exists())

    def test_error_guidance_and_diagnostics_continue_without_configuration(self):
        output = io.StringIO()
        with patch.object(setup, 'host_check', side_effect=RuntimeError('12GiB free needed')), patch.object(setup, 'check_engine', side_effect=RuntimeError('docker failed')), patch.object(setup, 'run', return_value='LISTEN 0 100 0.0.0.0:80 0.0.0.0:* nginx'), contextlib.redirect_stdout(output):
            setup.diagnostics()
        text = output.getvalue()
        self.assertIn('enlarge the disk', text)
        self.assertIn('systemctl status docker', text)
        self.assertIn('Install / resume setup first', text)
        self.assertIn('nginx', text)
        self.assertFalse(self.config.exists())

    def test_nginx_preflight_preserves_existing_sites(self):
        site = Path(self.temp.name) / 'vyrn.conf'
        values = dict(self.values(), VYRN_PROXY_MODE='existing')
        for host in ('api.chat.test-domain.org', '*.test-domain.org', '.test-domain.org', '~^api'):
            with self.subTest(host=host), patch.object(setup, 'NGINX_SITE', site), patch.object(setup.shutil, 'which', return_value='/usr/sbin/nginx'), patch.object(setup, 'run', side_effect=['active', 'server { server_name ' + host + '; }']), self.assertRaises(RuntimeError):
                setup.nginx_preflight(values)
            self.assertFalse(site.exists())
        site.write_text('# unrelated site\n')
        with patch.object(setup, 'NGINX_SITE', site), patch.object(setup.shutil, 'which', return_value='/usr/sbin/nginx'), patch.object(setup, 'run', return_value='active'), self.assertRaises(RuntimeError):
            setup.nginx_preflight(values)
        self.assertEqual(site.read_text(), '# unrelated site\n')

    def test_nginx_repair_restores_site_on_failures_and_interrupt(self):
        values = dict(self.values(), VYRN_PROXY_MODE='existing', VYRN_PUBLIC_IPV4='8.8.8.8')
        setup.write_config(values)
        for failure in ('none', 'certbot', 'validate-tls', 'https', 'interrupt', 'timer'):
            for had_site in (False, True):
                with self.subTest(failure=failure, had_site=had_site), tempfile.TemporaryDirectory() as temp:
                    site = Path(temp) / 'vyrn.conf'
                    before = b'# Managed by VYRN setup\n# previous config\n'
                    if had_site:
                        site.write_bytes(before)
                        site.chmod(0o640)
                    calls = []
                    def command(args, **options):
                        calls.append(args)
                        if args == ['systemctl', 'is-active', 'nginx']:
                            return 'active'
                        if args == ['nginx', '-T']:
                            return '# configuration file /etc/nginx/nginx.conf:\n' + ('# configuration file ' + str(site) + ':\n' + site.read_text() if site.exists() else '')
                        if args[0] == 'certbot' and failure in ('certbot', 'interrupt'):
                            raise KeyboardInterrupt() if failure == 'interrupt' else RuntimeError('certbot failed')
                        if args == ['nginx', '-t'] and failure == 'validate-tls' and site.exists() and 'listen 443 ssl' in site.read_text():
                            raise RuntimeError('nginx validation failed')
                        if 'certbot.timer' in args and failure == 'timer':
                            raise RuntimeError('timer failed')
                        return ''
                    def http(url, **options):
                        body = b'{"status":"ready"}' if '/health/ready' in url else url.rsplit('/', 1)[-1].encode()
                        response = io.BytesIO(body)
                        response.status = 200
                        return response
                    with patch.object(setup, 'NGINX_SITE', site), patch.object(setup, 'WEB_ROOT', Path(temp)/'acme'), patch.object(setup, 'ADMIN_ROOT', Path(temp)/'admin'), patch.object(setup, 'host_check'), patch.object(setup, 'owned_stack'), patch.object(setup, 'dns_check', return_value=True), patch.object(setup, 'yes', return_value=True), patch.object(setup, 'ask', return_value='owner@test-domain.org'), patch.object(setup, 'ensure_backend') as backend, patch.object(setup.shutil, 'which', return_value='/usr/bin/tool'), patch.object(setup, 'run', side_effect=command), patch.object(setup.HTTP, 'open', side_effect=http), patch.object(setup, 'https_check', return_value=failure != 'https'), contextlib.redirect_stdout(io.StringIO()):
                        if failure == 'none':
                            setup.repair_nginx()
                            text = site.read_text()
                            self.assertIn('listen 443 ssl;', text)
                            self.assertIn('allow 192.0.2.10/32;', text)
                            self.assertIn('deny all;', text)
                            self.assertIn('location ^~ /v1/admin/ { return 404; }', text)
                            self.assertIn('nginx -t && systemctl reload nginx', next(args for args in calls if args[0] == 'certbot'))
                        else:
                            with self.assertRaises(KeyboardInterrupt if failure == 'interrupt' else RuntimeError):
                                setup.repair_nginx()
                            self.assertEqual(site.read_bytes() if site.exists() else None, before if had_site else None)
                            if had_site:
                                self.assertEqual(site.stat().st_mode & 0o777, 0o640)
                            self.assertEqual(calls[-2:], [['nginx', '-t'], ['systemctl', 'reload', 'nginx']])
                        backend.assert_called_once_with(values)
                        self.assertFalse(any('stop' in args or 'disable' in args or 'restart' in args for args in calls))
                        self.assertEqual(setup.settings(), values)

    @unittest.skipUnless(setup.shutil.which('nginx') and setup.shutil.which('openssl'), 'nginx/openssl required for actual syntax validation')
    def test_generated_nginx_config_passes_real_nginx_validation(self):
        temp = Path(self.temp.name)
        name = self.values()['VYRN_DOMAIN']
        cert = temp / ('vyrn-setup-' + name)
        cert.mkdir()
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-nodes', '-days', '1', '-subj', '/CN=' + name,
                        '-keyout', str(cert/'privkey.pem'), '-out', str(cert/'fullchain.pem')], check=True, capture_output=True)
        config = temp / 'nginx.conf'
        # Validate the generated directives without binding privileged/public ports.
        with setup.socket.socket() as http, setup.socket.socket() as https:
            http.bind(('127.0.0.1', 0))
            https.bind(('127.0.0.1', 0))
            http_port, https_port = http.getsockname()[1], https.getsockname()[1]
        with patch.object(setup, 'CERT_ROOT', temp), patch.object(setup, 'WEB_ROOT', temp/'acme'), patch.object(setup, 'ADMIN_ROOT', temp/'admin'):
            for tls in (False, True):
                site = setup.nginx_config(self.values(), tls=tls).replace('listen 80;', f'listen 127.0.0.1:{http_port};').replace('listen 443 ssl;', f'listen 127.0.0.1:{https_port} ssl;')
                config.write_text('error_log stderr;\npid ' + str(temp/'nginx.pid') + ';\nevents {}\nhttp {\naccess_log off;\nclient_body_temp_path ' + str(temp/'body') + ';\nproxy_temp_path ' + str(temp/'proxy') + ';\n' + site + '\n}\n')
                result = subprocess.run(['nginx', '-t', '-p', str(temp) + '/', '-c', str(config)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_dashboard_changes_with_verified_runtime_state(self):
        fresh = setup.instance_state()
        self.assertEqual(fresh['kind'], 'new')
        self.assertEqual(list(setup.dashboard_actions(fresh)), ['1', '2', '3'])
        setup.write_config(self.values())
        setup.save_progress('Setup completed', complete=True)
        containers = [dict(Config={'Labels': {'com.docker.compose.service': name}}, State={'Running': True, 'Health': {'Status': 'healthy'}}) for name in ('core', 'postgres', 'caddy')]
        with patch.object(setup, 'owned_stack'), patch.object(setup, 'compose', return_value='core-id db-id'), patch.object(setup, 'run', return_value=json.dumps(containers)):
            ready = setup.instance_state()
            self.assertEqual(ready['kind'], 'installed')
            self.assertEqual(setup.dashboard_actions(ready)['1'][0], 'Connection details')
            self.assertIn('5', setup.dashboard_actions(ready))
            containers[0]['State']['Running'] = False
            with patch.object(setup, 'run', return_value=json.dumps(containers)):
                stopped = setup.instance_state()
            self.assertEqual(stopped['kind'], 'incomplete')
            self.assertEqual(stopped['core'], 'Stopped')
            self.assertNotIn('5', setup.dashboard_actions(stopped))
            containers[0]['State']['Running'] = True
            containers[2]['State']['Running'] = False
            with patch.object(setup, 'run', return_value=json.dumps(containers)):
                proxy_down = setup.instance_state()
            self.assertEqual(proxy_down['kind'], 'incomplete')
            self.assertEqual(proxy_down['proxy'], 'Stopped')
        progress = self.config.parent / 'setup-state.json'
        self.assertEqual(progress.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(self.values()['POSTGRES_PASSWORD'], progress.read_text())
        self.assertNotIn(self.values()['VYRN_ADMIN_TOTP_SECRET'], progress.read_text())

    def test_step_rechecks_after_real_repair_and_never_skips_failure(self):
        events = []
        def verify():
            events.append('verify')
            if events.count('verify') == 1:
                raise RuntimeError('docker is stopped')
            return 'verified'
        with patch('builtins.input', return_value='3'), patch.object(setup, 'screen'), contextlib.redirect_stdout(io.StringIO()):
            result = setup.setup_step('Docker', verify, lambda: events.append('repair'), 'Start engine')
        self.assertEqual(result, 'verified')
        self.assertEqual(events, ['verify', 'repair', 'verify'])

    def test_failed_repair_can_retry_same_stage_or_pause(self):
        with patch('builtins.input', side_effect=['3', '1']), patch.object(setup, 'screen'), patch.object(setup, 'show_error') as error:
            action = unittest.mock.Mock(side_effect=[RuntimeError('failed check'), 'ready'])
            repair = unittest.mock.Mock(side_effect=RuntimeError('failed repair'))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(setup.setup_step('Test', action, repair, 'Fix'), 'ready')
            self.assertEqual(action.call_count, 2)
            self.assertEqual(error.call_count, 2)
        with patch('builtins.input', return_value='0'), patch.object(setup, 'screen'), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(setup.SetupPaused):
            setup.setup_step('Test', lambda: (_ for _ in ()).throw(RuntimeError('failed')))

    def test_docker_repair_never_starts_a_service_for_remote_context(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(setup, 'run', return_value='ssh://remote') as command, self.assertRaises(RuntimeError):
            setup.start_docker()
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[0][:3], ['docker', 'context', 'inspect'])
        with patch.dict(os.environ, {}, clear=True), patch.object(setup, 'run', side_effect=['unix:///var/run/docker.sock', '']) as command, patch.object(setup, 'check_engine') as check:
            setup.start_docker()
        self.assertEqual(command.call_args.args[0], ['systemctl', 'start', 'docker'])
        check.assert_called_once()

    def test_successful_wizard_marks_complete_only_after_https(self):
        setup.write_config(dict(self.values(), VYRN_PUBLIC_IPV4='8.8.8.8'))
        with patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=True), patch.object(setup, 'dns_check', return_value=True), patch.object(setup, 'ensure_backend'), patch.object(setup, 'https_check', return_value=True), patch.object(setup, 'authenticator'), patch.object(setup, 'links'), contextlib.redirect_stdout(io.StringIO()):
            setup.install()
        state = json.loads((self.config.parent/'setup-state.json').read_text())
        self.assertTrue(state['complete'])
        self.assertEqual(state['stage'], 'Setup completed')

    def test_first_setup_repairs_failed_https_then_verifies_before_completion(self):
        setup.write_config(dict(self.values(), VYRN_PUBLIC_IPV4='8.8.8.8', VYRN_PROXY_MODE='existing'))
        with patch.object(setup, 'host_check'), patch.object(setup, 'docker_ready'), patch.object(setup, 'owned_stack', return_value=False), patch.object(setup, 'dns_check', return_value=True), patch.object(setup, 'ensure_backend'), patch.object(setup, 'https_check', side_effect=[False, True]) as verify, patch.object(setup, 'repair_nginx') as repair, patch('builtins.input', return_value='3'), patch.object(setup, 'authenticator'), patch.object(setup, 'links'), contextlib.redirect_stdout(io.StringIO()):
            setup.install()
        repair.assert_called_once()
        self.assertEqual(verify.call_count, 2)
        self.assertTrue(json.loads((self.config.parent/'setup-state.json').read_text())['complete'])


def integration(lite=False):
    """Real CLI bootstrap/migrations, restart and preservation; no public DNS/TLS or host install."""
    project = 'vyrn-setup-ci-' + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory() as temp:
        config = Path(temp) / 'runtime.env'
        image = 'vyrn-core:lite-ci' if lite else 'vyrn-core:ci'
        config.write_text(f'VYRN_IMAGE={image}\nVYRN_DOMAIN=chat.test-domain.org\nPOSTGRES_PASSWORD=isolated-ci-only\nVYRN_ADMIN_TOTP_SECRET=JBSWY3DPEHPK3PXP\nVYRN_ADMIN_ALLOWED_CIDRS=127.0.0.1/32\n')
        config.chmod(0o600)
        override = Path(temp) / 'compose.yaml'
        override.write_text(f'services:\n  core:\n    image: {image}\n')
        files = ['-f', str(setup.ROOT/'deployment/compose.yaml')]
        if lite:
            files += ['-f', str(setup.ROOT/'deployment/compose.lite.yaml')]
        files += ['-f', str(override)]
        def compose(*arguments, **options):
            return setup.run(['docker', 'compose', '--project-name', project, '--env-file', str(config),
                              *files, *arguments], **options)
        with patch.object(setup, 'compose', side_effect=compose):
            try:
                compose('up', '-d', '--wait', 'postgres')
                with patch('builtins.input', return_value='owner@setup-ci.test'), patch.object(setup.getpass, 'getpass', return_value='isolated-ci-password-123'):
                    assert setup.bootstrap()
                compose('up', '-d', '--no-build', '--wait', 'core')
                with patch.object(setup.getpass, 'getpass', side_effect=AssertionError('Existing account must not prompt again')):
                    assert setup.bootstrap() is False
                count = compose('exec', '-T', 'postgres', 'psql', '-U', 'vyrn', '-d', 'vyrn', '-At', '-c', 'SELECT count(*) FROM users;', capture=True)
                assert count == '1'
                compose('stop', 'core')
                setup.start_existing('core')
                print('PASS: real bootstrap, migrations, Core health, existing-account preservation and restart')
                if lite:
                    identifiers = compose('ps', '-q', 'core', 'postgres', capture=True).split()
                    samples = []
                    for _ in range(3):
                        setup.time.sleep(10)
                        output = setup.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *identifiers], capture=True)
                        samples.append([json.loads(line) for line in output.splitlines()])
                    destination = setup.ROOT / 'artifacts/lite-runtime.json'
                    destination.parent.mkdir(exist_ok=True)
                    destination.write_text(json.dumps({'scope': 'Core/PostgreSQL after bootstrap and restart; idle samples, not a capacity benchmark; no Caddy or host overhead', 'samples': samples}, indent=2))
                    print('PASS: Lite bootstrap/health/restart under container limits; idle samples recorded')
            finally:
                # Only this disposable uniquely named CI project may lose its volumes.
                subprocess.run(['docker', 'compose', '--project-name', project, '--env-file', str(config),
                                *files,
                                'down', '--volumes'], cwd=setup.ROOT, check=True, timeout=120)


if __name__ == '__main__':
    if '--integration' in sys.argv:
        integration(lite='--lite' in sys.argv)
    else:
        unittest.main()
