#!/usr/bin/env python3
from pathlib import Path

p = Path('src/kernel/sched/ems/cpu_select.c')
s = p.read_text()

marker = '/* APPLE FINAL EMS 3x3 */'
if marker in s:
    print('APPLE FINAL patch already present')
    raise SystemExit(0)

anchor = '''/******************************************************************************\n * best cpu selection                                                         *\n ******************************************************************************/\nint find_best_cpu(struct tp_env *env)\n{\n'''
if anchor not in s:
    raise SystemExit('PATCH ERROR: find_best_cpu anchor not found')

helpers = r'''/* APPLE FINAL EMS 3x3
 *
 * Native Exynos2100 task-placement hysteresis and cache-locality layer.
 * This is intentionally conservative: Samsung EMS still computes the primary
 * target; this layer only rejects low-value cross-cluster movement when the
 * previous CPU remains a valid fit candidate.
 *
 * Topology: CPU0-3=A55, CPU4-6=A78, CPU7=X1.
 * Migration matrix is asymmetric by design. Higher values mean that a larger
 * placement benefit is required to leave the current cluster.
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
    return cpu >= 0 && cpu < nr_cpu_ids && cpu_active(cpu) &&
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

    /* Same-cluster cache locality: if the old CPU is not over capacity and
     * its rq is no more congested than the selected sibling, keep the hot
     * task/cache local instead of bouncing between cores. */
    if (src == dst) {
        if (env->cpu_util_with[prev_cpu] <= prev_cap &&
            env->nr_running[prev_cpu] <= env->nr_running[best_cpu] + 1)
            return true;
        return false;
    }

    /* Up-migration hysteresis. The selected faster cluster is accepted only
     * once the task is sufficiently large for the source cluster. */
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

        /* Matrix tie-breaker close to the threshold: a higher migration cost
         * extends the source-cluster residency by at most 10 percentage
         * points; this provides hysteresis without hard-pinning the task. */
        if (pct && cost) {
            unsigned long extra = min_t(unsigned long, cost / 5, 10);
            if (util * 100 < prev_cap * (pct + extra) &&
                env->cpu_util_with[prev_cpu] <= prev_cap)
                return true;
        }
        return false;
    }

    /* Down-migration hysteresis. Do not throw a hot task down a cluster until
     * the target has substantial spare capacity. */
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

s = s.replace(anchor, helpers + anchor, 1)
old = '''\treturn best_cpu;\n}\n'''
# Replace only the final return in find_best_cpu by using the last occurrence.
pos = s.rfind(old)
if pos < 0:
    raise SystemExit('PATCH ERROR: final return anchor not found')
new = '''\tif (apple_final_keep_prev(env, best_cpu))\n\t\tbest_cpu = task_cpu(env->p);\n\n\treturn best_cpu;\n}\n'''
s = s[:pos] + new + s[pos+len(old):]

p.write_text(s)
print('APPLE FINAL EMS 3x3 patch applied:', p)
