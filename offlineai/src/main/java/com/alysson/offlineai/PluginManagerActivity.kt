package com.alysson.offlineai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PluginManagerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var packs: PluginPackManager
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
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Pacotes .iapack adicionam capacidades sem aumentar o APK principal. A instalação verifica assinatura Ed25519, tamanhos e SHA-256 de cada arquivo."
            textSize = 12f
            setTextColor(Color.rgb(95, 99, 104))
            setPadding(dp(4), 0, dp(4), dp(10))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("+ Importar .iapack") { openPicker() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
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
        status.text = when {
            importing -> status.text
            installed.isEmpty() -> "Nenhum .iapack instalado. O Tiny-SD continua separado e compatível com a tela principal."
            else -> "${installed.size} pacote(s) instalado(s). Coder: ${if (packs.coderModel() != null) "ativo" else "opcional/não instalado"}."
        }
        installed.forEach { pack ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.rgb(248, 249, 250))
            }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(TextView(this).apply {
                text = "${pack.name}\n${pack.type} • v${pack.version} • ${formatSize(pack.totalBytes)}"
                textSize = 15f
                setTextColor(Color.rgb(32, 33, 36))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            titleRow.addView(action("⋮", "Opções do plugin") { packMenu(pack) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            card.addView(titleRow)
            if (pack.description.isNotBlank()) card.addView(TextView(this).apply {
                text = pack.description
                textSize = 12f
                setTextColor(Color.rgb(95, 99, 104))
                setPadding(0, dp(5), 0, 0)
            })
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun packMenu(pack: PluginPackManager.InstalledPack) {
        AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setItems(arrayOf("Detalhes", "Excluir pacote")) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(this)
                        .setTitle(pack.name)
                        .setMessage("ID: ${pack.id}\nTipo: ${pack.type}\nVersão: ${pack.version}\nTamanho: ${formatSize(pack.totalBytes)}\n\n${pack.description}")
                        .setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this)
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

    private fun openPicker() {
        if (importing) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_PACK)
    }

    @Deprecated("Deprecated API retained for Storage Access Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PACK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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

    companion object { private const val REQUEST_PACK = 7101 }
}
