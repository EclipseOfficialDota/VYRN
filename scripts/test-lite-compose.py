"""Validate Compose merging without starting Docker or contacting a registry."""
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
environment = {key: value for key, value in os.environ.items() if not key.startswith('COMPOSE_')}
environment.update(VYRN_DOMAIN='chat.test-domain.org', VYRN_IMAGE='vyrn-core:lite-ci',
                   POSTGRES_PASSWORD='isolated-config-check-only', VYRN_ADMIN_TOTP_SECRET='JBSWY3DPEHPK3PXP',
                   VYRN_ADMIN_ALLOWED_CIDRS='192.0.2.10/32')
command = ['docker', 'compose', '-f', str(root/'deployment/compose.yaml')]
def config(arguments):
    return json.loads(subprocess.run(arguments+['config', '--format', 'json'], env=environment, check=True, capture_output=True, text=True).stdout)
original = config(command)
lite = config(command+['-f', str(root/'deployment/compose.lite.yaml')])
assert 'build' in original['services']['core']
assert 'build' not in lite['services']['core']
assert original['name'] == 'vyrn' and lite['name'] == 'vyrn-lite'
assert original['volumes']['postgres']['name'] != lite['volumes']['postgres']['name']
assert original['networks']['edge']['ipam']['config'][0]['subnet'] != lite['networks']['edge']['ipam']['config'][0]['subnet']
assert lite['services']['core']['environment']['VYRN_TRUSTED_PROXY'] == lite['networks']['edge']['ipam']['config'][0]['subnet']
assert 'pool_max_conns=4' in lite['services']['core']['environment']['DATABASE_URL']
assert int(lite['services']['core']['mem_limit']) == 256*1024**2
assert int(lite['services']['postgres']['mem_limit']) == 192*1024**2
assert not lite['services']['postgres'].get('ports')
assert not lite['services']['core'].get('ports')
print('PASS: original source build retained; Lite image-only, separate project/volumes/subnet, bounded resources, private backend/database')
