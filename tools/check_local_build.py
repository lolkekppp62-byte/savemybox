"""Проверка с уже установленной JDK; системные переменные не изменяются."""
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
env = os.environ.copy()
env['JAVA_HOME'] = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.1.12-hotspot'
env['PATH'] = str(pathlib.Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env['PATH']
sys.exit(subprocess.run([sys.executable, str(root / 'tools/build.py'), '--check'], cwd=root, env=env).returncode)
