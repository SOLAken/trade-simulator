#!/usr/bin/env python3
"""统计 -Xlog:safepoint 中与 GC 相关的 STW 停顿（G1 与 ZGC 统一口径）。

用法：python3 deploy/scripts/gc-report.py .run/logs/trade-service-gc.log [起始uptime秒]
"""
import re
import sys

path = sys.argv[1]
since = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
# [12.345s][info][safepoint] Safepoint "G1CollectForAllocation", Time since last: ..., Reaching safepoint: 1 ns, Cleanup: ..., At safepoint: 2 ns, Total: 3 ns
pat = re.compile(r'^\[(\d+\.\d+)s\].*Safepoint "([^"]+)".*Total: (\d+) ns')
gc_ops = ('G1', 'ZMark', 'ZRelocate', 'ZVerify', 'CollectForAllocation', 'GC_HeapInspection', 'Cleanup')
pauses, by_op, gc_cycles = [], {}, 0
for line in open(path, errors='replace'):
    if '[gc]' in line and ('Pause' in line or 'Collection' in line):
        t = re.match(r'^\[(\d+\.\d+)s\]', line)
        if t and float(t.group(1)) >= since:
            gc_cycles += 1
    m = pat.match(line)
    if not m or float(m.group(1)) < since:
        continue
    op, ns = m.group(2), int(m.group(3))
    if not any(k in op for k in gc_ops):
        continue
    ms = ns / 1e6
    pauses.append(ms)
    by_op.setdefault(op, []).append(ms)

if not pauses:
    print('no GC safepoints found')
    sys.exit()
pauses.sort()
pct = lambda p: pauses[min(len(pauses) - 1, int(len(pauses) * p))]
print(f'GC 事件 {gc_cycles}  STW 次数 {len(pauses)}  总停顿 {sum(pauses):.1f}ms  '
      f'p50 {pct(0.5):.3f}ms  p99 {pct(0.99):.3f}ms  max {pauses[-1]:.3f}ms')
for op, v in sorted(by_op.items(), key=lambda kv: -sum(kv[1])):
    print(f'  {op:28s} n={len(v):4d}  avg={sum(v)/len(v):.3f}ms  max={max(v):.3f}ms')
