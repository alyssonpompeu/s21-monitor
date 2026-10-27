#!/usr/bin/env python3
import sys
from pathlib import Path

if len(sys.argv) != 4:
    raise SystemExit('usage: check_kmi.py HZA6_KMI_CRITICAL.txt Module.symvers report.txt')

baseline_p = Path(sys.argv[1])
symvers_p = Path(sys.argv[2])
report_p = Path(sys.argv[3])

expected = {}
sections = {}
section = 'unknown'
for raw in baseline_p.read_text(errors='replace').splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if line.startswith('[') and line.endswith(']'):
        section = line[1:-1]
        continue
    parts = line.split()
    if len(parts) < 2 or not parts[0].startswith('0x'):
        continue
    crc, sym = parts[0].lower(), parts[1]
    expected.setdefault(sym, set()).add(crc)
    sections.setdefault(sym, set()).add(section)

built = {}
for raw in symvers_p.read_text(errors='replace').splitlines():
    parts = raw.split()
    if len(parts) < 2:
        continue
    crc, sym = parts[0].lower(), parts[1]
    if not crc.startswith('0x'):
        crc = '0x' + crc
    built.setdefault(sym, set()).add(crc)

common = sorted(set(expected) & set(built))
missing = sorted(set(expected) - set(built))
mismatch = []
for sym in common:
    if expected[sym].isdisjoint(built[sym]):
        mismatch.append(sym)

# These are especially important because they cover base module ABI, CFI,
# memory primitives and the exact Mali<->CAL interface used by HZA6.
must_match = [
    'module_layout', '__cfi_slowpath', '__stack_chk_guard', '__stack_chk_fail',
    'memcpy', 'memset', 'cal_dfs_get_rate', 'cal_dfs_set_rate',
    'thermal_zone_get_temp', 'exynos_pm_qos_update_request',
]

lines = []
lines.append('APPLE FINAL HZA6 KMI CRC GATE')
lines.append(f'expected_unique_symbols={len(expected)}')
lines.append(f'built_unique_symbols={len(built)}')
lines.append(f'common_symbols={len(common)}')
lines.append(f'missing_from_built_symvers={len(missing)}')
lines.append(f'crc_mismatches={len(mismatch)}')
lines.append('')

if mismatch:
    lines.append('MISMATCHES:')
    for sym in mismatch:
        lines.append(f'{sym}: HZA6={sorted(expected[sym])} built={sorted(built[sym])} sections={sorted(sections.get(sym, []))}')
    lines.append('')

lines.append('MUST_MATCH:')
for sym in must_match:
    if sym not in expected:
        state = 'BASELINE_MISSING'
    elif sym not in built:
        state = 'BUILT_MISSING'
    elif expected[sym].isdisjoint(built[sym]):
        state = f'MISMATCH expected={sorted(expected[sym])} built={sorted(built[sym])}'
    else:
        state = f'PASS crc={sorted(expected[sym] & built[sym])}'
    lines.append(f'{sym}: {state}')

lines.append('')
lines.append('MISSING_NOTE: symbols missing from Module.symvers are not automatically CRC failures;')
lines.append('some may be supplied by other vendor modules. Any symbol present on both sides MUST match.')

# Gate policy: no conflicting CRC is tolerated. Also require a meaningful
# overlap and the core ABI symbols that are present in the baseline.
fatal = False
reasons = []
if mismatch:
    fatal = True
    reasons.append(f'{len(mismatch)} CRC mismatch(es)')
if len(common) < 100:
    fatal = True
    reasons.append(f'only {len(common)} common symbols (<100)')
for sym in must_match:
    if sym in expected:
        if sym not in built:
            # cal_dfs symbols may come from a module disabled in source config;
            # for FINAL that is still unacceptable because our active Mali imports them.
            fatal = True
            reasons.append(f'mandatory symbol missing: {sym}')
        elif expected[sym].isdisjoint(built[sym]):
            fatal = True
            reasons.append(f'mandatory CRC mismatch: {sym}')

lines.append('')
lines.append('gate=' + ('FAIL' if fatal else 'PASS'))
if reasons:
    lines.append('reasons=' + '; '.join(reasons))

report_p.write_text('\n'.join(lines) + '\n')
print(report_p.read_text())
raise SystemExit(1 if fatal else 0)
