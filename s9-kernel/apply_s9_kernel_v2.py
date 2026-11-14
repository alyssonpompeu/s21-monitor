#!/usr/bin/env python3
"""S9 (starqltechn, SDM845 v2.1) - kernel v2. Aplica sobre o fonte PRISTINO.

Base: klabit87/android_kernel_samsung_sdm845, lineage-19.0, commit 66ad1f12.

1. CPU (clk-cpu-osm.c)
   - telemetria: imprime a LUT (s9_osm_lut) no boot;
   - undervolt: regrava o campo de tensao (mV) das linhas da LUT do A55 e do
     A75 conforme tabelas embutidas (780 mV / 980 mV no topo);
   - overclock de teste: troca o multiplicador da ultima linha
     (A75 146 -> 151 = 2899,2 MHz; A55 92 -> 96 = 1843,2 MHz);
   - so regrava se TODAS as linhas lidas baterem com a tabela de fabrica
     medida no aparelho; depois le de volta e registra o resultado.
   - L3 nao e tocado.
2. GPU (gpucc-sdm845.c + .dts planos do starqlte-chn): tabela B, 7 niveis,
   257 / 322 / 404 / 507 / 636 / 798 / 1000 MHz.
3. Memoria (.dts planos, no llccbw): voto extra de 2092 MHz (7980 MBps), especulativo.
4. Governadores: janela do governor da GPU (5 -> 3 ms, teto 50 -> 30 ms) e
   custo de migracao do escalonador (0,5 -> 1,0 ms).

Uso: python3 apply_s9_kernel_v2.py <raiz-do-kernel> <tables.inc>
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
/* ---- S9 kernel v2: undervolt + overclock de teste na LUT do OSM ---- */
struct s9_uv {
	u32 khz;
	u16 old_mv;
	u16 new_mv;
};

''' + TABLES + r'''
static void s9_osm_rewrite_lut(struct clk_osm *c, const char *name,
			       const struct s9_uv *t, int n,
			       u32 top_lval, u32 new_lval)
{
	u32 i, k, fdata, vdata, src, lval, khz, mv, rb;
	int match = 0, wrote = 0, kept = 0, oc = 0;

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
			if (t[k].khz == khz && t[k].old_mv == mv)
				break;
		if (k < n)
			match++;
	}
	if (match != OSM_TABLE_SIZE) {
		pr_info("s9_osm_uv: %s LUT diferente da medida (%d/%d linhas); nada regravado\n",
			name, match, OSM_TABLE_SIZE);
		return;
	}

	/* Passo 2: regrava tensao (bits 11:0) e, no topo, o multiplicador. */
	for (i = 0; i < OSM_TABLE_SIZE; i++) {
		fdata = clk_osm_read_reg(c, FREQ_REG + i * OSM_REG_SIZE);
		vdata = clk_osm_read_reg(c, VOLT_REG + i * OSM_REG_SIZE);
		src = (fdata & GENMASK(31, 30)) >> 30;
		lval = fdata & GENMASK(7, 0);
		khz = src ? (u32)(XO_RATE / 1000) * lval :
			    (u32)(OSM_INIT_RATE / 1000);
		mv = vdata & GENMASK(11, 0);
		for (k = 0; k < n; k++)
			if (t[k].khz == khz && t[k].old_mv == mv)
				break;
		if (k == n)
			continue;
		clk_osm_write_reg(c, (vdata & ~GENMASK(11, 0)) | t[k].new_mv,
				  VOLT_REG + i * OSM_REG_SIZE);
		if (src && lval == top_lval)
			clk_osm_write_reg(c, (fdata & ~GENMASK(7, 0)) | new_lval,
					  FREQ_REG + i * OSM_REG_SIZE);
		mb();
		rb = clk_osm_read_reg(c, VOLT_REG + i * OSM_REG_SIZE);
		if ((rb & GENMASK(11, 0)) == t[k].new_mv)
			wrote++;
		else
			kept++;
		if (src && lval == top_lval) {
			rb = clk_osm_read_reg(c, FREQ_REG + i * OSM_REG_SIZE);
			if ((rb & GENMASK(7, 0)) == new_lval)
				oc++;
		}
	}
	pr_info("s9_osm_uv: %s tensao aceita em %d linhas, recusada em %d; multiplicador %u->%u aceito em %d linhas\n",
		name, wrote, kept, top_lval, new_lval, oc);
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
                "\ts9_osm_rewrite_lut(&pwrcl_clk, \"A55\", s9_uv_pwrcl,\n"
                "\t\t\t   ARRAY_SIZE(s9_uv_pwrcl), 92, 96);\n"
                "\ts9_osm_rewrite_lut(&perfcl_clk, \"A75\", s9_uv_perfcl,\n"
                "\t\t\t   ARRAY_SIZE(s9_uv_perfcl), 146, 151);\n"
                "\n\trc = clk_osm_read_lut(pdev, &pwrcl_clk);\n",
                "chamada read_lut pwrcl")
    return text


edit("drivers/clk/qcom/clk-cpu-osm.c", cpu)

# ---------------------------------------------------------------- 2. GPU
GPU_OLD = [180, 257, 342, 414, 520, 596, 675, 710]
GPU_NEW = [180, 257, 322, 404, 507, 636, 798, 1000]
RATE_MAX_OLD = [("VDD_GX_MIN", 180), ("VDD_GX_LOWER", 257), ("VDD_GX_LOW", 342),
                ("VDD_GX_LOW_L1", 414), ("VDD_GX_NOMINAL", 520),
                ("VDD_GX_NOMINAL_L1", 596), ("VDD_GX_HIGH", 675), ("VDD_GX_HIGH_L1", 710)]
RATE_MAX_NEW = {"VDD_GX_MIN": 257, "VDD_GX_LOWER": 322, "VDD_GX_LOW": 404,
                "VDD_GX_LOW_L1": 507, "VDD_GX_NOMINAL": 507,
                "VDD_GX_NOMINAL_L1": 636, "VDD_GX_HIGH": 636, "VDD_GX_HIGH_L1": 1000}


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
DT_GPU = [(710, 1000), (675, 798), (596, 636), (520, 507), (414, 404), (342, 322)]
GPU_DT_FINAL = ["0x%x" % (f * 1000000) for f in (1000, 798, 636, 507, 404, 322, 257)] + ["0x0"]
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

print("OK kernel v2: %d arquivos alterados (%d .dts, %d com GPU)" % (len(done), len(DT_FILES), gpu_files))
for d in sorted(done):
    if not d.endswith(".dts"):
        print("  " + d)
