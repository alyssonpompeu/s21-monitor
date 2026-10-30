# Apple Final V5 — tabela MIF geométrica candidata (13 OPPs)

**Estado:** modelo matemático calculado; não inserido no boot/kernel operacional, não compilado ou validado como overclock físico.

Dados de entrada: 13 pares de frequência/tensão CAL/ASV extraídos no Galaxy S21 SM-G991B, Exynos 2100. A tabela CAL/ASV original termina em 3.172 MHz/768.750 µV.
Progressão geométrica de 421.000 a 3.672.000 kHz: `f(i) = 421000 * (3672000/421000)^(i/12)`, arredondada para 1.000 kHz. Razão `r = 1.197802039`.
Tensões estimadas por interpolação linear entre os OPPs originais; 3.672 MHz por extrapolação `V=768750*(f/3172000)^0.2203166016`; teto múltiplo de 6.250 µV apenas para modelagem.

| Nivel | MHz | CAL/ASV estimado µV | CAL/ASV arredondado µV | Fonte |
|--:|--:|--:|--:|--|
| 0 | 3672 | 793945 | 800000 | EXTRAPOLADO |
| 1 | 3066 | 762755 | 768750 | INTERPOLADO |
| 2 | 2559 | 738269 | 743750 | INTERPOLADO |
| 3 | 2137 | 697981 | 700000 | INTERPOLADO |
| 4 | 1784 | 653285 | 656250 | INTERPOLADO |
| 5 | 1489 | 613737 | 618750 | INTERPOLADO |
| 6 | 1243 | 581860 | 587500 | INTERPOLADO |
| 7 | 1038 | 547744 | 550000 | INTERPOLADO |
| 8 | 867 | 532877 | 537500 | INTERPOLADO |
| 9 | 723 | 526738 | 531250 | INTERPOLADO |
| 10 | 604 | 518077 | 518750 | INTERPOLADO |
| 11 | 504 | 510400 | 512500 | INTERPOLADO |
| 12 | 421 | 506250 | 506250 | OBSERVADO |

## Condições de engenharia antes de ativar

- A V4 adicionou uma 14ª entrada sintética ao Linux. A V5 propõe 13 níveis geométricos, mas **12/13 níveis desta lista ainda não aparecem na tabela CAL/ASV original** (somente 421 MHz coincide exatamente).
- Um valor `opp_list[i].volt` não impõe necessariamente tensão à RAM. A alimentação da DRAM, a tabela FVMAP em SRAM, o ACPM/firmware, PLL, DMC/PHY e timings são caminhos distintos.
- Substituir todos os 13 níveis ECT pode deixar de fora frequência original de boot, retomada ou reboot; a função `exynos_devfreq_get_opp_idx()` poderia falhar na inicialização. É necessário preservar/compatibilizar os âncoras originais antes de qualquer flash.
- A interface PM QoS já expõe o teto de 3.672 MHz no V4; a ausência de transições não é resolvida trocando apenas valores de tabela.
- Acrescentar níveis intermediários não força o hardware a percorrê-los. Uma *rampa* requer política explícita, com transições sequenciais limitadas e confirmação ACPM, sem suprimir emergências térmicas.
- A variação da tensão CAL/ASV **não representa** uma leitura de VDDQ/VDD2. Não use o arredondamento como parâmetro elétrico pronto para aplicação.
- O modelo é um instrumento de projeto. Não existe, nesta ramificação, kernel V5 pronto para instalação.

## Referências do driver

- https://github.com/FlopKernel-Series/flop_exynos2100_kernel/blob/4553117a5014928dff55595f46627e0d77bcf133/drivers/devfreq/exynos/exynos-devfreq.c
- https://github.com/FlopKernel-Series/flop_exynos2100_kernel/blob/4553117a5014928dff55595f46627e0d77bcf133/drivers/soc/samsung/cal-if/fvmap.c
- https://github.com/FlopKernel-Series/flop_exynos2100_kernel/blob/4553117a5014928dff55595f46627e0d77bcf133/drivers/soc/samsung/cal-if/acpm_dvfs.c
