#!/usr/bin/env python3
from pathlib import Path
import re

p = Path('src/kernel/sched/ems/cpu_select.c')
s = p.read_text()

marker = '/* APPLE FINAL EMS 3x3 */'
if marker in s:
    print('APPLE FINAL patch already present')
else:
    fn_re = re.compile(r'\bint\s+find_best_cpu\s*\(\s*struct\s+tp_env\s*\*\s*env\s*\)\s*\{')
    m = fn_re.search(s)
    if not m:
        raise SystemExit('PATCH ERROR: find_best_cpu(struct tp_env *env) not found')

    helpers = r'''/* APPLE FINAL EMS 3x3
 *
 * Native Exynos2100 task-placement hysteresis and cache-locality layer.
 * Samsung EMS remains the primary placement/energy engine. This layer only
 * rejects low-value movement when the previous CPU remains a legal, fit CPU.
 *
 * Topology: CPU0-3=A55, CPU4-6=A78, CPU7=X1.
 * Matrix values are asymmetric migration costs; they are not MHz or voltage.
 */
static const unsigned int apple_final_migration_cost[3][3] = {
    /* to: A55, A78, X1 */
    {  0, 18, 45 }, /* from A55 */
    { 28,  0, 25 }, /* from A78 */
    { 55, 35,  0 }, /* from X1  */
};

static inline int apple_final_cluster(int cpu)
{
    if (cpu <= 3)
        return 0;
    if (cpu <= 6)
        return 1;
    return 2;
}

static inline bool apple_final_cpu_valid(struct tp_env *env, int cpu)
{
    return cpu >= 0 && cpu < nr_cpu_ids &&
           cpumask_test_cpu(cpu, cpu_active_mask) &&
           cpumask_test_cpu(cpu, &env->cpus_allowed) &&
           cpumask_test_cpu(cpu, &env->fit_cpus);
}

static bool apple_final_keep_prev(struct tp_env *env, int best_cpu)
{
    struct task_struct *p = env->p;
    int prev_cpu = task_cpu(p);
    int src, dst;
    unsigned long util, prev_cap, dst_cap;
    unsigned int cost;

    if (best_cpu < 0 || best_cpu == prev_cpu)
        return false;
    if (!apple_final_cpu_valid(env, prev_cpu))
        return false;

    src = apple_final_cluster(prev_cpu);
    dst = apple_final_cluster(best_cpu);
    util = env->task_util;
    prev_cap = capacity_cpu(prev_cpu);
    dst_cap = capacity_cpu(best_cpu);
    cost = apple_final_migration_cost[src][dst];

    /* Same-cluster locality: keep the task on its hot CPU when that CPU still
     * fits and is not materially more congested than the selected sibling.
     * This avoids needless L1/L2 refills and same-cluster ping-pong. */
    if (src == dst) {
        if (env->cpu_util_with[prev_cpu] <= prev_cap &&
            env->nr_running[prev_cpu] <= env->nr_running[best_cpu] + 1)
            return true;
        return false;
    }

    /* Up-migration hysteresis. X1 remains a sprint CPU, while A78 is the
     * preferred sustained performance cluster when it can meet demand. */
    if (dst > src) {
        unsigned long pct = 0;

        if (src == 0 && dst == 1)
            pct = 55;
        else if (src == 0 && dst == 2)
            pct = 85;
        else if (src == 1 && dst == 2)
            pct = 85;

        if (pct && util * 100 < prev_cap * pct)
            return true;

        /* Near the boundary, use the 3x3 matrix as a bounded extra residency
         * cost. Samsung's EMS energy winner still wins once benefit is clear. */
        if (pct && cost) {
            unsigned long extra = min_t(unsigned long, cost / 5, 10);
            if (util * 100 < prev_cap * (pct + extra) &&
                env->cpu_util_with[prev_cpu] <= prev_cap)
                return true;
        }
        return false;
    }

    /* Down-migration hysteresis: avoid immediately dropping a hot task to a
     * smaller cluster after a brief utilization dip. */
    if (src == 2 && dst == 1) {
        if (util * 100 > dst_cap * 65)
            return true;
    } else if (src == 1 && dst == 0) {
        if (util * 100 > dst_cap * 35)
            return true;
    } else if (src == 2 && dst == 0) {
        if (util * 100 > dst_cap * 25)
            return true;
    }

    return false;
}

'''

    # Insert immediately before the actual function, independent of Samsung's
    # surrounding comment/banner formatting in that historical revision.
    insert_at = m.start()
    s = s[:insert_at] + helpers + s[insert_at:]

    # Re-find the function after insertion and brace-scan only its body. This avoids
    # accidentally modifying a similarly named return in another EMS function.
    m = fn_re.search(s, insert_at + len(helpers))
    if not m:
        raise SystemExit('PATCH ERROR: find_best_cpu disappeared after insertion')
    brace_open = s.find('{', m.start(), m.end() + 1)
    if brace_open < 0:
        raise SystemExit('PATCH ERROR: function opening brace not found')

    depth = 0
    brace_close = None
    for i in range(brace_open, len(s)):
        c = s[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                brace_close = i
                break
    if brace_close is None:
        raise SystemExit('PATCH ERROR: function closing brace not found')

    body = s[brace_open:brace_close + 1]
    returns = list(re.finditer(r'(?m)^(\s*)return\s+best_cpu\s*;\s*$', body))
    if not returns:
        raise SystemExit('PATCH ERROR: return best_cpu not found inside find_best_cpu')
    r = returns[-1]
    indent = r.group(1)
    replacement = (
        f'{indent}if (apple_final_keep_prev(env, best_cpu))\n'
        f'{indent}\tbest_cpu = task_cpu(env->p);\n\n'
        f'{indent}return best_cpu;'
    )
    body = body[:r.start()] + replacement + body[r.end():]
    s = s[:brace_open] + body + s[brace_close + 1:]

    p.write_text(s)
    print('APPLE FINAL EMS 3x3 patch applied:', p)

# Samsung's cache.S has ENTRY(flush_cache_all) immediately followed by an
# explicit flush_cache_all: label. ENTRY() already emits that symbol label;
# LLVM's integrated assembler rejects the second definition. Keep the ENTRY()
# annotation and remove only the redundant label.
cache = Path('src/drivers/soc/samsung/debug/cache.S')
cs = cache.read_text()
dup = 'ENTRY(flush_cache_all)\nflush_cache_all:'
if dup in cs:
    cs = cs.replace(dup, 'ENTRY(flush_cache_all)', 1)
    cache.write_text(cs)
    print('APPLE FINAL asm compatibility patch applied:', cache)
elif 'ENTRY(flush_cache_all)' not in cs:
    raise SystemExit('PATCH ERROR: flush_cache_all ENTRY not found in Samsung cache.S')
