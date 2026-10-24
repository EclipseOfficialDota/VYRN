"""Check Linux build orchestration without downloading an SDK or compiling Android."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
script = root / 'scripts/build-android-linux.sh'
subprocess.run(['bash', '-n', str(script)], check=True)
with tempfile.TemporaryDirectory() as directory:
    temp = Path(directory)
    log = temp / 'calls'
    tools = temp / 'bin'
    tools.mkdir()
    sdk = temp / 'sdk/cmdline-tools/latest/bin'
    sdk.mkdir(parents=True)
    for path, body in (
        (tools / 'uname', 'echo x86_64'),
        (tools / 'java', 'exit 0'),
        (tools / 'pwsh', 'printf "pwsh %s\\n" "$*" >> "$CALLS"'),
        (sdk / 'sdkmanager', 'printf "sdk %s\\n" "$*" >> "$CALLS"'),
    ):
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
               JAVA_HOME=str(temp), ANDROID_HOME=str(temp / 'sdk'), CALLS=str(log))
    subprocess.run(['bash', str(script), '-ApiUrl', 'https://api.test-domain.org'], env=env, check=True)
    lines = log.read_text().splitlines()
    assert len(lines) == 4, lines
    assert '--licenses' in lines[0]
    assert 'ndk;27.2.12479018' in lines[1]
    assert 'android/fetch-upstream.ps1' in lines[2]
    assert 'scripts/build-android.ps1 -ApiUrl https://api.test-domain.org' in lines[3]
    (tools / 'uname').write_text('#!/bin/sh\necho aarch64\n')
    result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
    assert result.returncode == 1 and 'x86_64' in result.stderr
    assert log.read_text().splitlines() == lines
print('PASS: Linux syntax, SDK license/package order, pinned source fetch, API forwarding and architecture guard')
