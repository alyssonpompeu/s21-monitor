package com.alysson.offlineai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PluginManagerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var packs: PluginPackManager
    private lateinit var performance: PerformanceSettings
    private lateinit var imageGenerator: ImageGenerationManager
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var builderButton: TextView
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        packs = PluginPackManager(applicationContext)
        performance = PerformanceSettings(applicationContext)
        imageGenerator = ImageGenerationManager(applicationContext, ResourceGuard(applicationContext))
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹", "Voltar") { setResult(RESULT_OK); finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = "Plugins locais"
            textSize = 21f
            setTextColor(Color.rgb(32, 33, 36))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(action("⚙", "Limites de desempenho") { showPerformanceDialog() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Todos os recursos opcionais ficam aqui, inclusive imagens. Os limites são alvos de segurança: Android não permite impor uma quota exata de GPU por aplicativo."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("+ Importar .iapack") { openPackPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        builderButton = pill("Abrir Builder Studio") {
            startActivity(Intent(this, BuilderStudioActivity::class.java))
        }
        row.addView(builderButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(row)

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        list.removeAllViews()
        val installed = packs.listInstalled()
        builderButton.isEnabled = packs.isInstalled(PluginPackManager.BUILDER_PACK_ID)
        builderButton.alpha = if (builderButton.isEnabled) 1f else 0.45f
        val limits = performance.limits()
        status.text = if (importing) status.text else
            "Limites: CPU ${limits.cpuPercent}% • GPU ${limits.gpuPercent}% • RAM ${limits.ramPercent}% • ${installed.size} .iapack(s)"

        addImagePluginCard()
        installed.forEach { pack -> addPackCard(pack) }
    }

    private fun addImagePluginCard() {
        val installed = imageGenerator.hasModel()
        val subtitle = if (installed) {
            "image-model • Tiny-SD Q4_K • ~739 MiB • ${imageGenerator.backendDescription()}"
        } else {
            "image-model • Tiny-SD Q4_K • não instalado"
        }
        addCard(
            title = "Gerador de imagens Tiny-SD",
            subtitle = subtitle,
            description = "Geração 512×512 local. CPU é o padrão seguro; Vulkan é experimental e pode ser escolhido nas opções.",
            onMenu = { imagePluginMenu() },
        )
    }

    private fun addPackCard(pack: PluginPackManager.InstalledPack) {
        val backend = performance.backend(pack.id).label
        addCard(
            title = pack.name,
            subtitle = "${pack.type} • v${pack.version} • ${formatSize(pack.totalBytes)} • $backend",
            description = pack.description,
            onMenu = { packMenu(pack) },
        )
    }

    private fun addCard(title: String, subtitle: String, description: String, onMenu: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(248, 249, 250))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 15f
            setTextColor(Color.rgb(32, 33, 36))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(action("⋮", "Opções do plugin") { onMenu() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(titleRow)
        if (description.isNotBlank()) card.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(5), 0, 0)
        })
        list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun imagePluginMenu() {
        val options = if (imageGenerator.hasModel()) {
            arrayOf("Detalhes", "Desempenho / backend", "Substituir modelo", "Excluir modelo")
        } else {
            arrayOf("Detalhes", "Desempenho / backend", "Importar modelo")
        }
        AlertDialog.Builder(this)
            .setTitle("Gerador de imagens Tiny-SD")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Detalhes" -> AlertDialog.Builder(this)
                        .setTitle("Tiny-SD local")
                        .setMessage(
                            "Arquivo: ${ImageGenerationManager.MODEL_FILE}\n" +
                                "Instalado: ${if (imageGenerator.hasModel()) "sim" else "não"}\n" +
                                "Vulkan empacotado: ${if (imageGenerator.hasVulkanEngine()) "sim" else "não"}\n" +
                                "Backend: ${imageGenerator.backendDescription()}\n\n" +
                                performance.recommendation(PerformanceSettings.IMAGE_PLUGIN_ID)
                        )
                        .setPositiveButton("OK", null).show()
                    "Desempenho / backend" -> showBackendDialog(PerformanceSettings.IMAGE_PLUGIN_ID, "Gerador de imagens")
                    "Importar modelo", "Substituir modelo" -> openImagePicker()
                    "Excluir modelo" -> confirmDeleteImageModel()
                }
            }.show()
    }

    private fun confirmDeleteImageModel() {
        AlertDialog.Builder(this)
            .setTitle("Excluir Tiny-SD?")
            .setMessage("O modelo de imagens será removido. As imagens já geradas e os demais plugins permanecem.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                if (imageGenerator.deleteModel()) status.text = "Modelo Tiny-SD removido."
                refresh()
            }.show()
    }

    private fun packMenu(pack: PluginPackManager.InstalledPack) {
        AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setItems(arrayOf("Detalhes", "Desempenho / backend", "Excluir pacote")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle(pack.name)
                        .setMessage(
                            "ID: ${pack.id}\nTipo: ${pack.type}\nVersão: ${pack.version}\n" +
                                "Tamanho: ${formatSize(pack.totalBytes)}\nBackend: ${performance.backend(pack.id).label}\n\n" +
                                "${pack.description}\n\n${performance.recommendation(pack.id)}"
                        )
                        .setPositiveButton("OK", null).show()
                    1 -> showBackendDialog(pack.id, pack.name)
                    else -> AlertDialog.Builder(this)
                        .setTitle("Excluir ${pack.name}?")
                        .setMessage("Os arquivos desse plugin serão removidos. Projetos e outros plugins não serão apagados.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Excluir") { _, _ ->
                            packs.remove(pack.id)
                            refresh()
                        }.show()
                }
            }.show()
    }

    private fun showBackendDialog(pluginId: String, name: String) {
        val supported = performance.supportedBackends(pluginId)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PluginManagerActivity, android.R.layout.simple_spinner_dropdown_item, supported.map { it.label })
            val current = performance.backend(pluginId)
            setSelection(supported.indexOf(current).takeIf { it >= 0 } ?: 0)
        }
        box.addView(spinner)
        box.addView(TextView(this).apply {
            text = performance.recommendation(pluginId) +
                "\n\nO valor de GPU nas configurações é um orçamento/política. O driver Mali/Android não oferece ao app uma quota percentual rígida de utilização da GPU."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(10), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Backend • $name")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                performance.setBackend(pluginId, supported[spinner.selectedItemPosition])
                refresh()
            }.show()
    }

    private fun showPerformanceDialog() {
        val current = performance.limits()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }

        val cpuLabel = TextView(this)
        val cpu = seek(PerformanceSettings.MIN_CPU, PerformanceSettings.MAX_CPU, current.cpuPercent) { value ->
            cpuLabel.text = "CPU alvo máximo: $value%"
        }
        cpuLabel.text = "CPU alvo máximo: ${current.cpuPercent}%"
        box.addView(cpuLabel); box.addView(cpu)

        val gpuLabel = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        val gpu = seek(PerformanceSettings.MIN_GPU, PerformanceSettings.MAX_GPU, current.gpuPercent) { value ->
            gpuLabel.text = "GPU / orçamento Vulkan: $value%"
        }
        gpuLabel.text = "GPU / orçamento Vulkan: ${current.gpuPercent}%"
        box.addView(gpuLabel); box.addView(gpu)

        val ramLabel = TextView(this).apply { setPadding(0, dp(10), 0, 0) }
        val ram = seek(PerformanceSettings.MIN_RAM, PerformanceSettings.MAX_RAM, current.ramPercent) { value ->
            ramLabel.text = "RAM máxima do processo: $value% do total"
        }
        ramLabel.text = "RAM máxima do processo: ${current.ramPercent}% do total"
        box.addView(ramLabel); box.addView(ram)

        box.addView(TextView(this).apply {
            text = "Faixas limitadas por segurança: CPU até 90%, GPU até 90% e RAM até 75%. CPU é aproximada por threads/ritmo. RAM e temperatura têm bloqueio adicional. GPU 0% desabilita Vulkan; acima disso não é uma quota rígida do driver."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(0, dp(12), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Limites globais")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                performance.setLimits(
                    cpuPercent = PerformanceSettings.MIN_CPU + cpu.progress,
                    gpuPercent = PerformanceSettings.MIN_GPU + gpu.progress,
                    ramPercent = PerformanceSettings.MIN_RAM + ram.progress,
                )
                refresh()
            }.show()
    }

    private fun seek(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max - min
        progress = value.coerceIn(min, max) - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(min + progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun openPackPicker() {
        if (importing) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_PACK)
    }

    private fun openImagePicker() {
        if (importing) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_IMAGE_MODEL)
    }

    @Deprecated("Deprecated API retained for Storage Access Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        when (requestCode) {
            REQUEST_PACK -> importPack(uri)
            REQUEST_IMAGE_MODEL -> importImageModel(uri)
        }
    }

    private fun importPack(uri: Uri) {
        importing = true
        status.text = "Validando pacote…"
        scope.launch {
            try {
                val installed = packs.install(uri) { p -> runOnUiThread { status.text = p } }
                status.text = "${installed.name} instalado com assinatura e integridade verificadas."
            } catch (t: Throwable) {
                status.text = "Falha ao instalar plugin: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                refresh()
            }
        }
    }

    private fun importImageModel(uri: Uri) {
        importing = true
        status.text = "Validando Tiny-SD…"
        scope.launch {
            try {
                val result = imageGenerator.importModel(uri) { p -> runOnUiThread { status.text = p } }
                status.text = "Tiny-SD instalado e verificado • SHA-256 ${result.sha256.take(12)}…"
            } catch (t: Throwable) {
                status.text = "Falha ao instalar Tiny-SD: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                importing = false
                refresh()
            }
        }
    }

    private fun action(label: String, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 27f; gravity = Gravity.CENTER; contentDescription = description; setOnClickListener { onClick() }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.rgb(26, 115, 232)); setOnClickListener { onClick() }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024 -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
        else -> "${bytes / 1024} KiB"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        setResult(RESULT_OK)
        super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PACK = 7101
        private const val REQUEST_IMAGE_MODEL = 7102
    }
}
