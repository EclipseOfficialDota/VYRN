"""Offline installer contracts; optional isolated real Docker bootstrap via --integration."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
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

    def test_service_menu_rejects_remote_overrides_before_docker_calls(self):
        for variable in ('DOCKER_HOST', 'DOCKER_CONTEXT'):
            for choice in ('2', '5', '6'):
                with self.subTest(variable=variable, choice=choice), patch.dict(os.environ, {variable: 'remote-engine'}, clear=True), patch.object(sys, 'argv', ['setup-server.py', '--lang', 'en']), patch.object(setup.os, 'geteuid', return_value=0), patch.object(sys.stdin, 'isatty', return_value=True), patch.object(setup, 'settings', return_value=self.values()), patch('builtins.input', side_effect=[choice, '', '0']), patch.object(setup, 'yes', return_value=True), patch.object(setup, 'run', return_value='') as command, contextlib.redirect_stdout(io.StringIO()):
                    setup.main()
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
        self.assertIn('public HTTPS still needs attention', output.getvalue())
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
            self.assertIn('VYRN  /  Test screen', output.getvalue())

    def test_menu_clears_before_action_and_pauses_before_return(self):
        events = []
        def prompt(label):
            events.append(('input', label))
            return next(answers)
        answers = iter(['7', '', '0'])
        with patch.object(sys, 'argv', ['setup-server.py', '--lang', 'en']), patch.object(setup.os, 'geteuid', return_value=0), patch.object(sys.stdin, 'isatty', return_value=True), patch.object(setup, 'settings', return_value=self.values()), patch('builtins.input', side_effect=prompt), patch.object(setup, 'screen', side_effect=lambda title: events.append(('screen', title))), patch.object(setup, 'links', side_effect=lambda values: events.append(('action', 'links'))), contextlib.redirect_stdout(io.StringIO()):
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
        with patch('builtins.input', side_effect=['۱', 'REMOVE']), patch.object(setup, 'owned_stack', side_effect=lambda: events.append('ownership')), patch.object(setup, 'compose', side_effect=lambda *args: events.append(args)), contextlib.redirect_stdout(io.StringIO()):
            setup.remove()
        self.assertEqual(events, ['ownership', ('down',)])
        self.assertEqual(self.config.read_bytes(), before)

    def test_remove_refuses_foreign_or_remote_stack(self):
        with patch.object(setup, 'settings', return_value=self.values()), patch('builtins.input', side_effect=['1', 'REMOVE']), patch.object(setup, 'owned_stack', side_effect=RuntimeError('foreign or remote')), patch.object(setup, 'compose') as command, contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            setup.remove()
        command.assert_not_called()


def integration():
    """Real CLI bootstrap/migrations, restart and preservation; no public DNS/TLS or host install."""
    project = 'vyrn-setup-ci-' + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory() as temp:
        config = Path(temp) / 'runtime.env'
        config.write_text('VYRN_DOMAIN=chat.test-domain.org\nPOSTGRES_PASSWORD=isolated-ci-only\nVYRN_ADMIN_TOTP_SECRET=JBSWY3DPEHPK3PXP\nVYRN_ADMIN_ALLOWED_CIDRS=127.0.0.1/32\n')
        config.chmod(0o600)
        override = Path(temp) / 'compose.yaml'
        override.write_text('services:\n  core:\n    image: vyrn-core:ci\n')
        def compose(*arguments, **options):
            return setup.run(['docker', 'compose', '--project-name', project, '--env-file', str(config),
                              '-f', str(setup.ROOT/'deployment/compose.yaml'), '-f', str(override), *arguments], **options)
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
            finally:
                # Only this disposable uniquely named CI project may lose its volumes.
                subprocess.run(['docker', 'compose', '--project-name', project, '--env-file', str(config),
                                '-f', str(setup.ROOT/'deployment/compose.yaml'), '-f', str(override),
                                'down', '--volumes'], cwd=setup.ROOT, check=True, timeout=120)


if __name__ == '__main__':
    if '--integration' in sys.argv:
        integration()
    else:
        unittest.main()
