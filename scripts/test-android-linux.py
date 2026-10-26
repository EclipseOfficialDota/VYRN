"""Check Linux build orchestration without downloading an SDK or compiling Android."""
import os
from pathlib import Path
import subprocess
import shutil
import tempfile

root = Path(__file__).resolve().parents[1]
script = root / 'scripts/build-android-linux.sh'
subprocess.run(['bash', '-n', str(script)], check=True)
with tempfile.TemporaryDirectory() as directory:
    temp = Path(directory)
    copied_script = temp / 'checkout/scripts/build-android-linux.sh'
    copied_script.parent.mkdir(parents=True)
    shutil.copyfile(script, copied_script)
    script = copied_script
    log = temp / 'calls'
    tools = temp / 'bin'
    tools.mkdir()
    sdk = temp / 'sdk/cmdline-tools/latest/bin'
    sdk.mkdir(parents=True)
    for path, body in (
        (tools / 'uname', 'echo x86_64'),
        (tools / 'java', 'exit 0'),
        (tools / 'pwsh', 'printf "pwsh %s\\n" "$*" >> "$CALLS"\ncase "$*" in *scripts/build-android.ps1*) if IFS= read -r line; then echo "Unexpected build stdin" >&2; exit 23; fi;; esac'),
        (sdk / 'sdkmanager', 'printf "sdk %s\\n" "$*" >> "$CALLS"'),
    ):
        path.write_text('#!/bin/sh\n' + body + '\n')
        path.chmod(0o755)
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
               JAVA_HOME=str(temp), ANDROID_HOME=str(temp / 'sdk'), CALLS=str(log))
    subprocess.run(['bash', str(script), '-ApiUrl', 'https://api.test-domain.org'], env=env, input='unexpected build input\n', text=True, check=True)
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
    (tools / 'uname').write_text('#!/bin/sh\necho x86_64\n')
    (tools / 'curl').write_text('#!/bin/sh\nprintf "%s\\n" "$*" > "$CALLS.download"\nexit 22\n')
    (tools / 'curl').chmod(0o755)
    env['ANDROID_HOME'] = str(temp / 'missing-sdk')
    result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
    assert result.returncode == 22
    assert 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' in Path(str(log) + '.download').read_text()
    assert not (temp / 'missing-sdk').exists()
    assert log.read_text().splitlines() == lines  # No build after a failed SDK download.
    downloads = Path(str(log) + '.download').read_text()
    archive = temp / 'checkout/.local/android/commandlinetools-linux-13114758_latest.zip'
    archive.parent.mkdir(parents=True)
    archive.write_bytes(b'corrupt archive')
    result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
    assert result.returncode != 0 and 'Using local Android' in result.stdout
    assert archive.read_bytes() == b'corrupt archive'
    assert not (temp / 'missing-sdk').exists()
    assert Path(str(log) + '.download').read_text() == downloads
    assert log.read_text().splitlines() == lines
    # Offline mode uses only the warmed source/SDK/Gradle trees, no sdkmanager/fetch.
    checkout = script.parents[1]
    for required in ('platforms/android-36/android.jar', 'build-tools/36.0.0/aapt2',
                     'ndk/27.2.12479018/source.properties', 'ndk/27.2.12479018/build/cmake/android.toolchain.cmake',
                     'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/clang', 'cmake/3.22.1/bin/cmake'):
        path = temp / 'sdk' / required
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    for required in ('android/upstream/.git', '.local/gradle/caches', '.local/gradle/wrapper/dists'):
        (checkout / required).mkdir(parents=True)
    wrapper = checkout / 'android/upstream/gradle/wrapper'
    wrapper.mkdir(parents=True)
    (wrapper / 'gradle-wrapper.properties').write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip\n')
    distribution = checkout / '.local/gradle/wrapper/dists/gradle-9.4.1-bin/hash'
    distribution.mkdir(parents=True)
    (distribution / 'gradle-9.4.1-bin.zip.ok').touch()
    env['ANDROID_HOME'] = str(temp / 'sdk')
    before = log.read_text().splitlines()
    subprocess.run(['bash', str(script), '--offline', '-ApiUrl', 'https://api.test-domain.org'], env=env, check=True)
    assert log.read_text().splitlines()[len(before):] == [
        'pwsh -NoProfile -File scripts/build-android.ps1 -Offline -ApiUrl https://api.test-domain.org']
    kit = checkout / '.local/offline/vyrn-android-offline.tar.gz'
    kit.parent.mkdir(parents=True)
    kit.touch()
    before = log.read_text().splitlines()
    subprocess.run(['bash', str(script)], env=env, check=True)
    assert log.read_text().splitlines()[len(before):] == ['pwsh -NoProfile -File scripts/build-android.ps1 -Offline']
    (distribution / 'gradle-9.4.1-bin.zip.ok').unlink()
    result = subprocess.run(['bash', str(script), '--offline'], env=env, capture_output=True, text=True)
    assert result.returncode == 1 and 'pinned Gradle distribution' in result.stderr, (result.returncode, result.stdout, result.stderr)
    (distribution / 'gradle-9.4.1-bin.zip.ok').touch()
    (temp / 'sdk/platforms/android-36/android.jar').unlink()
    result = subprocess.run(['bash', str(script), '--offline'], env=env, capture_output=True, text=True)
    assert result.returncode == 1 and 'Offline SDK package missing' in result.stderr
print('PASS: Linux syntax, SDK license/package order, pinned source fetch, API forwarding and architecture guard')
