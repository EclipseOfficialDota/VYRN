"""Exercise actual packaging with tiny stand-in dependencies, no SDK/native build."""
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

source = Path(__file__).with_name('prepare-android-offline.sh')
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / 'scripts').mkdir()
    shutil.copyfile(source, root / 'scripts/prepare-android-offline.sh')
    shutil.copyfile(source.with_name('package-android-offline.sh'), root / 'scripts/package-android-offline.sh')
    (root / 'scripts/build-android-linux.sh').write_text('#!/bin/sh\nexit 0\n')
    for path in ('.tools/android-sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake',
                 '.local/gradle/caches/dependency.jar', '.local/gradle/wrapper/gradle.zip',
                 'android/upstream/TMessagesProj/jni/libvpx/build/make/configure.sh',
                 'android/upstream/TMessagesProj/build/generated.bin'):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('fixture')
    tools = root / 'bin'
    tools.mkdir()
    for name, content in {
        'uname': 'case "$1" in -m) echo x86_64;; -s) echo Linux;; esac',
        'unshare': 'exit 0', 'sudo': 'exit 0', 'git': 'printf "%040d\\n" 0',
    }.items():
        executable = tools / name
        executable.write_text('#!/bin/sh\n' + content + '\n')
        executable.chmod(0o755)
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'], JAVA_HOME='/fixture')
    subprocess.run(['bash', str(root / 'scripts/prepare-android-offline.sh')], env=env, check=True)
    with tarfile.open(root / '.local/offline/vyrn-android-offline.tar.gz') as archive:
        names = set(archive.getnames())
        assert '.tools/android-sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake' in names
        assert 'android/upstream/TMessagesProj/jni/libvpx/build/make/configure.sh' in names
        assert 'android/upstream/TMessagesProj/build/generated.bin' not in names
    assert (root / '.local/offline/vyrn-android-offline.tar.gz.sha256').is_file()
print('PASS: actual kit packaging retains NDK and vendor build source, excludes generated app outputs')
