#!/usr/bin/env python3
import os
import shutil
import subprocess
from pathlib import Path


def prepare_vulkan_headers_for_android_cross_compile() -> None:
    """Make SPIR-V and Vulkan C/C++ headers visible inside the Android NDK sysroot."""
    if os.environ.get("GITHUB_ACTIONS", "").lower() != "true":
        return

    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        raise SystemExit("v4: ANDROID_HOME ausente no GitHub Actions")

    subprocess.run(["sudo", "apt-get", "install", "-y", "spirv-headers", "libvulkan-dev"], check=True)

    sysroot_usr = (
        Path(android_home)
        / "ndk"
        / "29.0.13113456"
        / "toolchains"
        / "llvm"
        / "prebuilt"
        / "linux-x86_64"
        / "sysroot"
        / "usr"
    )
    source_spirv = Path("/usr/include/spirv")
    source_spirv_cmake = Path("/usr/share/cmake/SPIRV-Headers")
    source_vulkan = Path("/usr/include/vulkan")
    if not source_spirv.is_dir() or not source_spirv_cmake.is_dir():
        raise SystemExit("v4: spirv-headers instalado, mas arquivos esperados não foram encontrados")
    if not source_vulkan.is_dir() or not (source_vulkan / "vulkan.hpp").is_file():
        raise SystemExit("v4: libvulkan-dev instalado, mas vulkan/vulkan.hpp não foi encontrado")

    shutil.copytree(source_spirv, sysroot_usr / "include" / "spirv", dirs_exist_ok=True)
    shutil.copytree(source_spirv_cmake, sysroot_usr / "share" / "cmake" / "SPIRV-Headers", dirs_exist_ok=True)
    shutil.copytree(source_vulkan, sysroot_usr / "include" / "vulkan", dirs_exist_ok=True)
    print(f"SPIRV-Headers e Vulkan-Hpp preparados no sysroot Android: {sysroot_usr}")


prepare_vulkan_headers_for_android_cross_compile()

main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
text = main_path.read_text(encoding='utf-8')

# Tiny-SD is now managed exclusively from Plugins locais, not the project drawer.
old = '''        val imagePack = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(60, 64, 67))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { showImagePackDialog() }
        }
        imagePack.tag = "imagePackStatus"
        drawer.addView(imagePack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

'''
if old not in text:
    raise SystemExit('v4: image drawer block not found')
text = text.replace(old, '', 1)

old = '''        val imagePack = drawer.findViewWithTag<TextView>("imagePackStatus")
        imagePack?.text = "Gerador de imagens: ${imageGenerator.modelDescription()}\\nToque para importar/validar o pacote local."
'''
if old not in text:
    raise SystemExit('v4: image drawer refresh block not found')
text = text.replace(old, '', 1)

# Missing image model routes to the unified local plugin screen.
old = '''        if (!imageGenerator.hasModel()) {
            showImagePackDialog()
            return
        }
'''
new = '''        if (!imageGenerator.hasModel()) {
            activateResultMode()
            answer.text = "Instale o Tiny-SD em ◇ Plugins locais antes de criar imagens."
            openPluginManager()
            return
        }
'''
if old not in text:
    raise SystemExit('v4: image missing-model block not found')
text = text.replace(old, new, 1)

# Main-chat CPU budget is an average target: keep max native workers bounded and pace token delivery.
old = '''                val generated = StringBuilder()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    answer.append(token)
                    resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
                }
'''
new = '''                val generated = StringBuilder()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                engine.sendUserPrompt(prompt, predictionBudget(settings)).collect { token ->
                    val liveGuard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    if (!liveGuard.safe) throw IllegalStateException(liveGuard.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    answer.append(token)
                    resultScroll.post { resultScroll.fullScroll(View.FOCUS_DOWN) }
                    if (pacingDelayMs > 0L) delay(pacingDelayMs)
                }
'''
if old not in text:
    raise SystemExit('v4: main generation loop not found')
text = text.replace(old, new, 1)

# Add performance access inside the existing Settings/Personalization dialog.
old = '''        box.addView(specific)

        box.addView(TextView(this).apply {
            text = "Avançado, Intermediário e Rápido são perfis de execução do Qwen local. Eles não são GPT-5.6, GPT-5.5 nem o3 e não usam a internet."
'''
new = '''        box.addView(specific)

        val perf = PerformanceSettings(applicationContext).limits()
        box.addView(TextView(this).apply {
            text = "Desempenho e limites  ›\\nCPU ${perf.cpuPercent}% • GPU ${perf.gpuPercent}% • RAM ${perf.ramPercent}%"
            textSize = 14f
            setTextColor(Color.rgb(26, 115, 232))
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { showPerformanceLimitsDialog() }
        })

        box.addView(TextView(this).apply {
            text = "Avançado, Intermediário e Rápido são perfis de execução do Qwen local. Eles não são GPT-5.6, GPT-5.5 nem o3 e não usam a internet."
'''
if old not in text:
    raise SystemExit('v4: personalization insertion point not found')
text = text.replace(old, new, 1)

needle = '''    private fun showImagePackDialog() {
'''
method = '''    private fun showPerformanceLimitsDialog() {
        val performance = PerformanceSettings(applicationContext)
        val current = performance.limits()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }

        fun addLimit(label: String, min: Int, max: Int, value: Int): Pair<TextView, android.widget.SeekBar> {
            val title = TextView(this).apply { textSize = 13f; text = "$label: $value%" }
            val seek = android.widget.SeekBar(this).apply {
                this.max = max - min
                progress = value.coerceIn(min, max) - min
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { title.text = "$label: ${min + p}%" }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) = Unit
                })
            }
            box.addView(title)
            box.addView(seek)
            return title to seek
        }

        val cpu = addLimit("CPU alvo máximo", PerformanceSettings.MIN_CPU, PerformanceSettings.MAX_CPU, current.cpuPercent).second
        val gpu = addLimit("GPU / orçamento Vulkan", PerformanceSettings.MIN_GPU, PerformanceSettings.MAX_GPU, current.gpuPercent).second
        val ram = addLimit("RAM máxima do processo", PerformanceSettings.MIN_RAM, PerformanceSettings.MAX_RAM, current.ramPercent).second

        box.addView(TextView(this).apply {
            text = "São limites de segurança, não quotas rígidas do kernel. CPU é aproximada por workers e pausas; GPU 0% desliga Vulkan, mas o Android/Mali não expõe controle percentual exato; RAM e temperatura também são verificadas durante tarefas pesadas."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(10), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Desempenho e limites")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                performance.setLimits(
                    PerformanceSettings.MIN_CPU + cpu.progress,
                    PerformanceSettings.MIN_GPU + gpu.progress,
                    PerformanceSettings.MIN_RAM + ram.progress,
                )
                safetyStatus.text = resourceGuard.shortStatus()
            }
            .show()
    }

'''
if needle not in text:
    raise SystemExit('v4: performance method insertion point not found')
text = text.replace(needle, method + needle, 1)

main_path.write_text(text, encoding='utf-8')

# Builder Studio uses Coder-specific guard and CPU pacing to lower average load on constrained profiles.
builder_path = Path('offlineai/src/main/java/com/alysson/offlineai/BuilderStudioActivity.kt')
builder = builder_path.read_text(encoding='utf-8')
builder = builder.replace('guard.state(ResourceGuard.TaskKind.CHAT)', 'guard.state(ResourceGuard.TaskKind.CODER)')
old = '''                val generated = StringBuilder()
                status.text = "Gerando localmente • ${modelStatusText()}"
                engine.sendUserPrompt(prompt, budget).collect { token ->
                    val live = guard.state(ResourceGuard.TaskKind.CODER)
                    if (!live.safe) throw IllegalStateException(live.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    sourceField.append(token)
                }
'''
new = '''                val generated = StringBuilder()
                val pacingDelayMs = PerformanceSettings(applicationContext).tokenPacingDelayMs()
                status.text = "Gerando localmente • ${modelStatusText()}"
                engine.sendUserPrompt(prompt, budget).collect { token ->
                    val live = guard.state(ResourceGuard.TaskKind.CODER)
                    if (!live.safe) throw IllegalStateException(live.reason ?: "Geração interrompida para proteger o aparelho.")
                    generated.append(token)
                    sourceField.append(token)
                    if (pacingDelayMs > 0L) kotlinx.coroutines.delay(pacingDelayMs)
                }
'''
if old not in builder:
    raise SystemExit('v4: builder generation loop not found')
builder = builder.replace(old, new, 1)
builder_path.write_text(builder, encoding='utf-8')

print('Workspace v4 unified plugins/performance patch applied')