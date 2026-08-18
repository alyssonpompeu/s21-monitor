#!/usr/bin/env python3
from pathlib import Path

p = Path('offlineai/build.gradle')
s = p.read_text(encoding='utf-8')
old = "applicationId 'com.alysson.offlineai.pluginv52'"
new = "applicationId 'com.alysson.unilaw.s21'"
if old not in s:
    raise SystemExit('v8.1 fresh-install applicationId marker missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

manifest = Path('offlineai/src/main/AndroidManifest.xml')
m = manifest.read_text(encoding='utf-8')
# Keep component namespace untouched; applicationId controls install identity.
# The user-visible label may already be Unilaw AI • S21 from the v6 lineage.
manifest.write_text(m, encoding='utf-8')

print('v8.1 fresh install identity: com.alysson.unilaw.s21')
