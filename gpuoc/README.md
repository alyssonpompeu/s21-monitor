# APPLE FINAL / GPUOC (EXPERIMENTAL)

The source is on a separate branch from the known-good Apple Final. No new GPU frequency is active on boot.

## Candidate table (stock=858 MHz)

| MHz | Change vs stock | PLL formula (26 MHz, P=2, S=0) |
|---:|---:|---:|
| 858 | 0.00% | M=66 |
| 910 | +6.06% | M=70 |
| 936 | +9.09% | M=72 |
| 975 | +13.64% | M=75 |
| 1001 | +16.67% | M=77 |

The code compiles a manual one-second PLL request for each experimental candidate. The request is rejected if hardware readback differs and the clock is restored to the known-stock 858 MHz after the probe.

DO NOT FLASH unless unified Image+Mali+CMUPMUCAL+Module.symvers pass exact KMI checks and TWRP repacking is produced. A passing GitHub compile is NOT proof that 910/936/975/1001 MHz actually works on silicon. A change in GPU frequency alone does not measure performance uplift. Voltage stability, thermal safety, and frame-time performance require testing on the real SM-G991B.

The apple-final-gpuoc.yml workflow emits a developer build tarball only. Rollback must preserve verified current boot and vendor_boot backups.
