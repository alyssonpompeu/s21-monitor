#!/usr/bin/env python3
"""S9 (SDM845) - telemetria de tensao da CPU. SOMENTE LEITURA.

O driver clk-cpu-osm le do hardware, para cada frequencia, o corner virtual e a
tensao open-loop gravados pelo firmware, mas so imprime com pr_debug (invisivel).
Este patch troca esse pr_debug por pr_info: a tabela passa a aparecer no dmesg
no boot. Nao altera frequencia nem tensao.
Uso: python3 apply_s9_cpu_volt_telemetry.py <raiz-do-kernel>
"""
import sys
REL = "drivers/clk/qcom/clk-cpu-osm.c"
OLD = 'pr_debug("index=%d freq=%ld virtual_corner=%d open_loop_voltage=%u\\n",'
NEW = 'pr_info("s9_osm_lut: index=%d freq=%ld virtual_corner=%d open_loop_voltage=%u\\n",'
path = sys.argv[1].rstrip("/") + "/" + REL
text = open(path).read()
if text.count(NEW) == 1 and OLD not in text:
    sys.exit("ERRO: telemetria ja aplicada; nada foi alterado")
if text.count(OLD) != 1:
    sys.exit("ERRO: linha esperada nao encontrada (%d ocorrencias); nada foi alterado" % text.count(OLD))
open(path, "w").write(text.replace(OLD, NEW))
print("OK: telemetria OSM (somente leitura) aplicada em " + REL)
