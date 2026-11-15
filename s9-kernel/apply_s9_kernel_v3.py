#!/usr/bin/env python3
"""S9 (starqltechn, SDM845 v2.1) - kernel v3. Aplica sobre o fonte PRISTINO.

Base: klabit87/android_kernel_samsung_sdm845, lineage-19.0, commit 66ad1f12.

1. CPU (clk-cpu-osm.c): frequencias novas SEM undervolt. Cada linha da LUT do
   OSM mantem a tensao de fabrica; so o multiplicador muda (A75 ate 2995,2 MHz,
   A55 ate 1996,8 MHz). So regrava se a LUT lida for a de fabrica medida; le de
   volta e guarda o resultado em /sys/module/clk_cpu_osm/parameters/s9_osm_result.
   L3 nao e tocado.
2. GPU (gpucc-sdm845.c + .dts planos): 257/322/404/507/636/798/900 MHz.
3. Memoria (.dts planos, llccbw): voto extra de 7980 MBps (igual ao v2).
4. Governadores: adreno_tz 3/30 ms e migracao 1 ms (igual ao v2).
5. Audio (max98512.c): alto-falante 15 dB -> 18 dB.
6. Toque (sec_ts.c): modo de sensibilidade (luva) ligado por padrao.
7. Reforco ao tocar (cpu-boost.c): A55 1267,2 / A75 1420,8 MHz por 64 ms,
   valores fixos no kernel (parametros somente leitura).

Uso: python3 apply_s9_kernel_v3.py <raiz-do-kernel> <tables_v3.inc>
"""
import re
import sys

ROOT = sys.argv[1].rstrip("/")
TABLES = open(sys.argv[2]).read()
done = []


def edit(rel, fn):
    path = ROOT + "/" + rel
    text = open(path).read()
    new = fn(text)
    if new == text:
        sys.exit("ERRO: nenhuma mudanca em " + rel)
    open(path, "w").write(new)
    done.append(rel)


def once(text, old, new, what):
    if text.count(old) != 1:
        sys.exit("ERRO: '%s' esperado 1 vez, achei %d" % (what, text.count(old)))
    return text.replace(old, new)


# ---------------------------------------------------------------- 1. CPU
OSM_FUNC = r'''
/* ---- S9 kernel v3: overclock na tensao de fabrica (LUT do OSM) ---- */
struct s9_oc {
	u32 khz;
	u16 mv;
	u16 new_lval;
};

''' + TABLES + r'''
static char s9_osm_result[320] = "nao executado";
module_param_string(s9_osm_result, s9_osm_result, sizeof(s9_osm_result), 0444);

static void s9_osm_note(const char *msg)
{
	size_t len = strlen(s9_osm_result);

	if (!strcmp(s9_osm_result, "nao executado"))
		len = 0;
	scnprintf(s9_osm_result + len, sizeof(s9_osm_result) - len, "%s%s",
		  len ? " | " : "", msg);
}

static void s9_osm_rewrite_lut(struct clk_osm *c, const char *name,
			       const struct s9_oc *t, int n)
{
	u32 i, k, fdata, vdata, src, lval, khz, mv, rb;
	int match = 0, ok = 0, refused = 0, same = 0;
	char msg[96];

	/* Passo 1: so mexe se a LUT inteira for a de fabrica que foi medida. */
	for (i = 0; i < OSM_TABLE_SIZE; i++) {
		fdata = clk_osm_read_reg(c, FREQ_REG + i * OSM_REG_SIZE);
		vdata = clk_osm_read_reg(c, VOLT_REG + i * OSM_REG_SIZE);
		src = (fdata & GENMASK(31, 30)) >> 30;
		lval = fdata & GENMASK(7, 0);
		khz = src ? (u32)(XO_RATE / 1000) * lval :
			    (u32)(OSM_INIT_RATE / 1000);
		mv = vdata & GENMASK(11, 0);
		for (k = 0; k < n; k++)
			if (t[k].khz == khz && t[k].mv == mv)
				break;
		if (k < n)
			match++;
	}
	if (match != OSM_TABLE_SIZE) {
		scnprintf(msg, sizeof(msg), "%s: LUT diferente da medida (%d/%d); nada regravado",
			  name, match, OSM_TABLE_SIZE);
		pr_info("s9_osm_oc: %s\n", msg);
		s9_osm_note(msg);
		return;
	}

	/* Passo 2: troca so o multiplicador (bits 7:0); tensao fica a de fabrica. */
	for (i = 0; i < OSM_TABLE_SIZE; i++) {
		fdata = clk_osm_read_reg(c, FREQ_REG + i * OSM_REG_SIZE);
		vdata = clk_osm_read_reg(c, VOLT_REG + i * OSM_REG_SIZE);
		src = (fdata & GENMASK(31, 30)) >> 30;
		lval = fdata & GENMASK(7, 0);
		khz = src ? (u32)(XO_RATE / 1000) * lval :
			    (u32)(OSM_INIT_RATE / 1000);
		mv = vdata & GENMASK(11, 0);
		for (k = 0; k < n; k++)
			if (t[k].khz == khz && t[k].mv == mv)
				break;
		if (k == n || !src || !t[k].new_lval || t[k].new_lval == lval) {
			same++;
			continue;
		}
		clk_osm_write_reg(c, (fdata & ~GENMASK(7, 0)) | t[k].new_lval,
				  FREQ_REG + i * OSM_REG_SIZE);
		mb();
		rb = clk_osm_read_reg(c, FREQ_REG + i * OSM_REG_SIZE);
		if ((rb & GENMASK(7, 0)) == t[k].new_lval)
			ok++;
		else
			refused++;
	}
	scnprintf(msg, sizeof(msg), "%s: frequencia aceita em %d linhas, recusada em %d, inalteradas %d",
		  name, ok, refused, same);
	pr_info("s9_osm_oc: %s\n", msg);
	s9_osm_note(msg);
}

static int clk_osm_read_lut(struct platform_device *pdev, struct clk_osm *c)
'''


def cpu(text):
    text = once(text,
                'pr_debug("index=%d freq=%ld virtual_corner=%d open_loop_voltage=%u\\n",',
                'pr_info("s9_osm_lut: index=%d freq=%ld virtual_corner=%d open_loop_voltage=%u\\n",',
                "telemetria OSM")
    text = once(text,
                "\nstatic int clk_osm_read_lut(struct platform_device *pdev, struct clk_osm *c)\n",
                OSM_FUNC, "definicao de clk_osm_read_lut")
    text = once(text,
                "\trc = clk_osm_read_lut(pdev, &pwrcl_clk);\n",
                "\ts9_osm_rewrite_lut(&pwrcl_clk, \"A55\", s9_oc_pwrcl,\n"
                "\t\t\t   ARRAY_SIZE(s9_oc_pwrcl));\n"
                "\ts9_osm_rewrite_lut(&perfcl_clk, \"A75\", s9_oc_perfcl,\n"
                "\t\t\t   ARRAY_SIZE(s9_oc_perfcl));\n"
                "\n\trc = clk_osm_read_lut(pdev, &pwrcl_clk);\n",
                "chamada read_lut pwrcl")
    return text


edit("drivers/clk/qcom/clk-cpu-osm.c", cpu)

# ---------------------------------------------------------------- 2. GPU
GPU_OLD = [180, 257, 342, 414, 520, 596, 675, 710]
GPU_NEW = [180, 257, 322, 404, 507, 636, 798, 900]
RATE_MAX_OLD = [("VDD_GX_MIN", 180), ("VDD_GX_LOWER", 257), ("VDD_GX_LOW", 342),
                ("VDD_GX_LOW_L1", 414), ("VDD_GX_NOMINAL", 520),
                ("VDD_GX_NOMINAL_L1", 596), ("VDD_GX_HIGH", 675), ("VDD_GX_HIGH_L1", 710)]
RATE_MAX_NEW = {"VDD_GX_MIN": 257, "VDD_GX_LOWER": 322, "VDD_GX_LOW": 404,
                "VDD_GX_LOW_L1": 507, "VDD_GX_NOMINAL": 507,
                "VDD_GX_NOMINAL_L1": 636, "VDD_GX_HIGH": 636, "VDD_GX_HIGH_L1": 900}


def gpucc(text):
    old_tbl = ("static const struct freq_tbl  ftbl_gpu_cc_gx_gfx3d_clk_src_sdm845_v2[] = {\n"
               + "".join("\tF(%d000000, P_CRC_DIV,  1, 0, 0),\n" % f for f in GPU_OLD) + "\t{ }\n};\n")
    new_tbl = ("static const struct freq_tbl  ftbl_gpu_cc_gx_gfx3d_clk_src_sdm845_v2[] = {\n"
               + "".join("\tF(%d000000, P_CRC_DIV,  1, 0, 0),\n" % f for f in GPU_NEW) + "\t{ }\n};\n")
    text = once(text, old_tbl, new_tbl, "tabela de frequencias da GPU v2")
    m = re.search(r"(static void gpu_cc_gfx_sdm845_fixup_sdm845v2\(void\)\n\{.*?\n\}\n)", text, re.S)
    if not m:
        sys.exit("ERRO: fixup sdm845v2 nao encontrado")
    body = m.group(1)
    new = body
    for name, val in RATE_MAX_OLD:
        new, cnt = re.subn(r"(rate_max\[%s\]\s*=\s*)%d000000;" % (name, val),
                           r"\g<1>%d000000;" % RATE_MAX_NEW[name], new)
        if cnt != 1:
            sys.exit("ERRO: rate_max[%s] nao encontrado com o valor de fabrica" % name)
    vals = [RATE_MAX_NEW[n] for n, _ in RATE_MAX_OLD]
    if vals != sorted(vals):
        sys.exit("ERRO: rate_max nao monotono")
    return text.replace(body, new)


edit("drivers/clk/qcom/gpucc-sdm845.c", gpucc)

# Os device trees do S9 neste fonte sao arquivos .dts "planos" (ja expandidos,
# valores em hexadecimal), um por revisao de placa. Os .dtsi da Qualcomm NAO
# entram no build (erro do build 43607363051: editar o .dtsi nao mudava nada).
import glob
import os

DT_FILES = sorted(glob.glob(ROOT + "/arch/arm64/boot/dts/samsung/sdm845-sec-starqlte-chn-r*.dts"))
if len(DT_FILES) != 15:
    sys.exit("ERRO: esperava 15 .dts do starqlte-chn, achei %d" % len(DT_FILES))
DT_GPU = [(710, 900), (675, 798), (596, 636), (520, 507), (414, 404), (342, 322)]
GPU_DT_FINAL = ["0x%x" % (f * 1000000) for f in (900, 798, 636, 507, 404, 322, 257)] + ["0x0"]
BW_OLD = "0x2fa 0x478 0x6b8 0x826 0xa25 0xb71 0xf27 0x134f 0x172b 0x1ae1"
gpu_files = 0


def dt_flat(text):
    global gpu_files
    # memoria: so o no llccbw recebe o voto extra (0x1f2c = 7980 MBps = 2092 MHz)
    pat = re.compile(r"(\n\t\tqcom,llccbw \{\n(?:(?!\n\t\t\};).)*?qcom,bw-tbl = <" + re.escape(BW_OLD) + r")>;", re.S)
    text, cnt = pat.subn(r"\g<1> 0x1f2c>;", text, count=1)
    if cnt != 1:
        sys.exit("ERRO: tabela do llccbw nao encontrada")
    if text.count(BW_OLD + ">;") != 3 or text.count(BW_OLD + " 0x1f2c>;") != 1:
        sys.exit("ERRO: tabelas de memoria fora do esperado")
    # GPU: so os arquivos de chips v2/v2.1 (os que tem o nivel de 710 MHz)
    if "qcom,gpu-freq = <0x%x>;" % 710000000 in text:
        for old, nw in DT_GPU:
            text = once(text, "qcom,gpu-freq = <0x%x>;" % (old * 1000000),
                        "qcom,gpu-freq = <0x%x>;" % (nw * 1000000), "nivel %d MHz no DT" % old)
        if re.findall(r"qcom,gpu-freq = <(0x[0-9a-f]+)>;", text) != GPU_DT_FINAL:
            sys.exit("ERRO: niveis de GPU do DT inesperados")
        gpu_files += 1
    return text


for f in DT_FILES:
    edit(os.path.relpath(f, ROOT), dt_flat)
if gpu_files != 9:
    sys.exit("ERRO: esperava 9 .dts com a tabela de GPU v2, alterei %d" % gpu_files)
if not any(f.endswith("chn-r14_v2.1.dts") for f in DT_FILES):
    sys.exit("ERRO: .dts do Rev14 v2.1 ausente")


# ---------------------------------------------------------------- 4. governadores
def tz(text):
    text = once(text, "#define FLOOR\t\t        5000\n", "#define FLOOR\t\t        3000\n", "FLOOR do adreno_tz")
    text = once(text, "#define CEILING\t\t\t50000\n", "#define CEILING\t\t\t30000\n", "CEILING do adreno_tz")
    return text


edit("drivers/devfreq/governor_msm_adreno_tz.c", tz)
edit("kernel/sched/fair.c",
     lambda t: once(t, "const_debug unsigned int sysctl_sched_migration_cost = 500000UL;",
                    "const_debug unsigned int sysctl_sched_migration_cost = 1000000UL;",
                    "custo de migracao"))

# ---------------------------------------------------------------- 5. audio
def audio(text):
    for side in ("left", "right"):
        old = '\tmsg_maxim("set spk_gain_%s [%%d] for %s channel", sel);\n' % (side, side)
        new = (old + "\n\t/* S9 v3: alto-falante em musica/midia: 15 dB (5) -> 18 dB (6) */\n"
               "\tif (sel == 5)\n\t\tsel = 6;\n")
        text = once(text, old, new, "ganho do alto-falante (%s)" % side)
    return text


edit("techpack/audio/asoc/codecs/max98512.c", audio)


# ---------------------------------------------------------------- 6. toque
edit("drivers/input/touchscreen/sec_ts/sec_ts.c",
     lambda t: once(t, "\tts->touch_functions |= SEC_TS_DEFAULT_ENABLE_BIT_SETFUNC;\n",
                    "\t/* S9 v3: sensibilidade de toque (modo luva) ligada por padrao */\n"
                    "\tts->touch_functions |= SEC_TS_DEFAULT_ENABLE_BIT_SETFUNC | SEC_TS_BIT_SETFUNC_GLOVE;\n",
                    "funcoes de toque no probe"))


# ---------------------------------------------------------------- 7. reforco ao tocar
def boost(text):
    text = once(text, "static unsigned int input_boost_ms = 40;\nmodule_param(input_boost_ms, uint, 0644);",
                "static unsigned int input_boost_ms = 64;\nmodule_param(input_boost_ms, uint, 0444);",
                "input_boost_ms")
    text = once(text, "module_param_cb(input_boost_freq, &param_ops_input_boost_freq, NULL, 0644);",
                "module_param_cb(input_boost_freq, &param_ops_input_boost_freq, NULL, 0444);",
                "input_boost_freq")
    text = once(text, "\t\ts = &per_cpu(sync_info, cpu);\n\t\ts->cpu = cpu;\n\t}\n",
                "\t\ts = &per_cpu(sync_info, cpu);\n\t\ts->cpu = cpu;\n"
                "\t\t/* S9 v3: reforco ao tocar - A55 1267,2 MHz, A75 1420,8 MHz */\n"
                "\t\ts->input_boost_freq = cpu < 4 ? 1267200 : 1420800;\n\t}\n"
                "\tinput_boost_enabled = true;\n",
                "valores padrao do reforco")
    return text


edit("drivers/cpufreq/cpu-boost.c", boost)

print("OK kernel v3: %d arquivos alterados (%d .dts, %d com GPU)" % (len(done), len(DT_FILES), gpu_files))
for d in sorted(done):
    if not d.endswith(".dts"):
        print("  " + d)
