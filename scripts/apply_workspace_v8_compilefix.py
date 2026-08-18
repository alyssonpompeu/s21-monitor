#!/usr/bin/env python3
from pathlib import Path

# Small deterministic post-patch for issues found by the first physical v8 CI build.
# It runs after the v8 feature patches so it fixes generated Kotlin, not the historical base files.

main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')
legacy_refs = main.count('InteractionMode.TEXT')
main = main.replace('InteractionMode.TEXT', 'InteractionMode.AUTO')
if legacy_refs == 0:
    print('v8 compilefix: no legacy InteractionMode.TEXT references remained')
else:
    print(f'v8 compilefix: replaced {legacy_refs} legacy InteractionMode.TEXT reference(s)')
main_path.write_text(main, encoding='utf-8')

router_path = Path('offlineai/src/main/java/com/alysson/offlineai/SmartCapabilityRouter.kt')
router = router_path.read_text(encoding='utf-8')
# Kotlin ordinary strings do not accept \.; [.] is equivalent regex syntax and needs no escape.
if '|\\.exe)' in router:
    router = router.replace('|\\.exe)', '|[.]exe)')
elif '|[.]exe)' not in router:
    raise SystemExit('v8 compilefix: expected .exe router pattern not found')
router_path.write_text(router, encoding='utf-8')

# Guard against regressions before Gradle spends minutes compiling the native library.
main_check = main_path.read_text(encoding='utf-8')
router_check = router_path.read_text(encoding='utf-8')
if 'InteractionMode.TEXT' in main_check:
    raise SystemExit('v8 compilefix failed: InteractionMode.TEXT remains')
if '|\\.exe)' in router_check:
    raise SystemExit('v8 compilefix failed: unsupported Kotlin escape remains')
if '|[.]exe)' not in router_check:
    raise SystemExit('v8 compilefix failed: safe .exe regex missing')

print('v8 compilefix applied successfully')
