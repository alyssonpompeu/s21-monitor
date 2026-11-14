#!/usr/bin/env python3
"""S9 (starqltechn, SDM845 v2) - GPU undervolt, etapa 1 (isolada).

Base: klabit87/android_kernel_samsung_sdm845, branch lineage-19.0 (Linux 4.9.186).
Arquivo alterado: drivers/clk/qcom/gpucc-sdm845.c (somente o fixup sdm845v2).

O SDM845 nao tem margem percentual (como o MARGIN_REQ do Exynos). A tensao do
rail GFX (pm8005_s1) e pedida por "corner" RPMh. rate_max[corner] e a maior
frequencia permitida naquele corner; subir esse limite faz a frequencia rodar
um corner abaixo. Segue o formato da curva do S21: agressivo nas frequencias
baixas, topo intacto.

  257 MHz: LOW_SVS -> MIN_SVS
  342 MHz: SVS     -> LOW_SVS
  414 MHz: SVS_L1  -> SVS
  520/596/675/710 MHz: sem alteracao

Isto e um pedido de corner ao firmware; a tensao fisica nao foi medida.
Uso:  python3 apply_s9_gpu_uv_step1.py <raiz-do-kernel> [--revert]
"""
import re
import sys

STOCK = {"VDD_GX_MIN": 180000000, "VDD_GX_LOWER": 257000000, "VDD_GX_LOW": 342000000}
STEP1 = {"VDD_GX_MIN": 257000000, "VDD_GX_LOWER": 342000000, "VDD_GX_LOW": 414000000}
UNTOUCHED = {"VDD_GX_LOW_L1": 414000000, "VDD_GX_NOMINAL": 520000000,
             "VDD_GX_NOMINAL_L1": 596000000, "VDD_GX_HIGH": 675000000,
             "VDD_GX_HIGH_L1": 710000000}
REL = "drivers/clk/qcom/gpucc-sdm845.c"
FUNC = re.compile(r"(static void gpu_cc_gfx_sdm845_fixup_sdm845v2\(void\)\n\{.*?\n\}\n)", re.S)


def values(body):
    return {m.group(1): int(m.group(2)) for m in re.finditer(
        r"gpu_cc_gx_gfx3d_clk_src\.clkr\.hw\.init->rate_max\[(VDD_GX_\w+)\]\s*=\s*(\d+);", body)}


def main():
    root = sys.argv[1]
    revert = "--revert" in sys.argv
    src, dst = (STEP1, STOCK) if revert else (STOCK, STEP1)
    path = root.rstrip("/") + "/" + REL
    text = open(path).read()
    m = FUNC.search(text)
    if not m:
        sys.exit("ERRO: fixup sdm845v2 nao encontrado; arvore diferente da esperada")
    body = m.group(1)
    cur = values(body)
    for k, v in {**src, **UNTOUCHED}.items():
        if cur.get(k) != v:
            sys.exit("ERRO: %s=%s, esperado %s; nada foi alterado" % (k, cur.get(k), v))
    new = body
    for k in src:
        new, n = re.subn(r"(rate_max\[%s\]\s*=\s*)%d;" % (k, src[k]), r"\g<1>%d;" % dst[k], new)
        if n != 1:
            sys.exit("ERRO: substituicao de %s falhou; nada foi alterado" % k)
    after = values(new)
    order = ["VDD_GX_MIN", "VDD_GX_LOWER", "VDD_GX_LOW", "VDD_GX_LOW_L1", "VDD_GX_NOMINAL",
             "VDD_GX_NOMINAL_L1", "VDD_GX_HIGH", "VDD_GX_HIGH_L1"]
    seq = [after[k] for k in order]
    if seq != sorted(seq) or after != {**dst, **UNTOUCHED}:
        sys.exit("ERRO: tabela final invalida; nada foi alterado")
    open(path, "w").write(text.replace(body, new))
    print("OK (%s): " % ("revertido" if revert else "etapa 1 aplicada") +
          ", ".join("%s=%d" % (k, after[k]) for k in order))
    print("Pedido de corner RPMh; tensao fisica do rail GFX nao medida.")


if __name__ == "__main__":
    main()
