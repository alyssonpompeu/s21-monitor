package com.alysson.kernelbench;

import java.util.ArrayList;
import java.util.List;

final class HistoricalData {
    private HistoricalData() {}

    static List<RunRecord> all() {
        ArrayList<RunRecord> a = new ArrayList<>();

        a.add(full("hist-v233", "v2.3.3 Stable Burst", "2026-08-14 02:03:29",
                "S21Lab_FULL_20260814_020345.txt",
                263201,247582,447128,98732,23012,
                1106.881,5284.386,3193.768,12656.00,11.267,
                696.12,5056.83,8193.282,5293.328,
                "Baseline elétrico/CPU comprovado."));

        a.add(full("hist-stage1", "KernelLab Stage 1", "2026-08-14",
                "MASTER CONTEXT / S21Lab_FULL_20260814_045329.txt",
                268077,242671,466024,98433,25803,
                1109.453,5100.722,3328.744,12565.33,11.640,
                0,0,6375.688,7219.252,
                "DTB/GPU Interactive; storage raw individual não consta no contexto mestre."));

        a.add(full("hist-v24", "v2.4", "2026-08-14 02:44:17",
                "S21Lab_FULL_20260814_024500.txt",
                262838,244464,448857,96507,29187,
                1089.381,5229.270,3206.119,12389.33,10.868,
                733.73,6563.06,8169.116,5301.741,
                "Resultado medido do S21Lab."));

        a.add(full("hist-v241", "v2.4.1", "2026-08-14 03:08:40",
                "S21Lab_FULL_20260814_030901.txt",
                264315,234245,465040,95895,26937,
                1104.903,4814.434,3321.714,12304.00,10.852,
                775.31,5958.89,7941.875,5407.622,
                "Resultado medido do S21Lab."));

        a.add(full("hist-h103", "Hybrid v1.0.3", "2026-08-14 11:24:10",
                "S21Lab_FULL_20260814_112459.txt",
                260872,244166,440396,103136,26108,
                1100.843,5181.790,3145.684,13173.33,12.137,
                615.17,5911.84,7948.556,7404.396,
                "WORKING STYLE."));

        a.add(full("hist-h11", "Hybrid v1.1 SmartControl", "2026-08-14 12:30:46",
                "S21Lab_FULL_20260814_123118.txt",
                263303,249468,442442,103638,23367,
                1113.712,5329.788,3160.302,13162.67,12.777,
                589.63,5252.14,7979.142,8379.945,
                "Melhor baseline sustained do contexto mestre."));

        a.add(full("hist-h12", "Hybrid v1.2 Burst", "2026-08-14 14:08:40",
                "S21Lab_FULL_20260814_140922.txt",
                285380,233840,523067,103083,27381,
                1100.441,4814.283,3736.194,13061.33,12.948,
                683.94,6161.19,7576.586,7468.177,
                "Melhor burst do contexto mestre."));

        a.add(full("hist-apple11", "Apple G991B v1.1", "2026-08-14 23:24:36",
                "S21Lab_FULL_20260814_232546.txt",
                260212,249573,435026,100368,23828,
                1114.412,5331.302,3107.330,12816.00,11.840,
                760.38,5196.72,8139.170,6643.794,
                "Mapeamento confirmado pelo README Apple G991B v1.2."));

        a.add(full("hist-2348", "S21Lab 23:48", "2026-08-14 23:48:32",
                "S21Lab_FULL_20260814_234839.txt",
                264716,242751,452692,104032,26045,
                1112.067,5095.172,3233.512,13173.33,13.132,
                662.91,5848.34,7680.041,9047.533,
                "Kernel/overlay não atribuído no arquivo; mantido sem inventar versão."));

        a.add(full("hist-0000", "S21Lab 00:00", "2026-08-15 00:00:58",
                "S21Lab_FULL_20260815_000128.txt",
                331687,400683,484308,100632,26495,
                1748.052,8691.382,3459.341,12864.00,11.760,
                675.06,5948.73,7460.054,8365.467,
                "Resultado real preservado; CPU burst muito acima da série anterior, portanto tratado como run histórico e não como novo baseline automático."));

        a.add(full("hist-apple12tel", "Apple control 1.2 telemetry", "2026-08-15 00:52:50",
                "S21Lab_FULL_20260815_005418.txt",
                360003,240371,742364,93033,24589,
                1109.967,5016.928,5302.599,12181.33,8.627,
                277.88,5869.46,7656.452,7710.216,
                "Arquivo declara apple_control=1.2-telemetry."));

        return a;
    }

    private static RunRecord full(String id, String label, String timestamp, String source,
                                  double total, double cpu, double gpu, double mem, double storage,
                                  double single, double multi, double gpuBurst,
                                  double ramCopy, double ramLatency,
                                  double storageWrite, double storageRead,
                                  double cpuSoak, double gpuSoak, String note) {
        RunRecord r = new RunRecord();
        r.id = id;
        r.label = label;
        r.timestamp = timestamp;
        r.source = source;
        r.kind = "FULL";
        r.historical = true;
        r.total = total;
        r.cpu = cpu;
        r.gpu = gpu;
        r.mem = mem;
        r.storage = storage;
        r.single = single;
        r.multi = multi;
        r.gpuBurst = gpuBurst;
        r.ramCopy = ramCopy;
        r.ramLatency = ramLatency;
        r.storageWrite = storageWrite;
        r.storageRead = storageRead;
        r.cpuSoak = cpuSoak;
        r.gpuSoak = gpuSoak;
        r.note = note;
        return r;
    }

    static RunRecord findById(String id) {
        for (RunRecord r : all()) if (r.id.equals(id)) return r;
        return null;
    }
}
