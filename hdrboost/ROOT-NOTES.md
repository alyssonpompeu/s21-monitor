# HDR Boost S21 ROOT

- Root-first via Magisk, KernelSU or APatch (`su`).
- Applies Samsung Vivid settings and maximum Android display brightness.
- Probes known Samsung HBM/auto-brightness sysfs controls only when exposed by the kernel.
- Saves and restores original settings/sysfs values on disable.
- Keeps WRITE_SETTINGS as a non-root fallback.
- Does not claim to convert SDR into true HDR; real HDR remains content/compositor/display-pipeline dependent.
