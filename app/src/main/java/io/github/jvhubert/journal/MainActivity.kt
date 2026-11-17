package io.github.jvhubert.journal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import java.io.File

/**
 * O Journal num app de verdade (casca 3, 17/11/2026). As telas continuam vindo do site e se atualizam sozinhas;
 * o app dá ao site o que o Chrome não dava a um WebView: microfone, câmera, digital do Android, tela acesa,
 * compartilhar, salvar arquivos e, nas próximas cascas, avisos, widget e a galeria de fotos (ponte JJNativo).
 */
class MainActivity : Activity() {
    companion object {
        const val BASE = "https://jvhubert.github.io/johnnys-journal/"
        private const val PEDIDO_MICROFONE = 10
        private const val PEDIDO_AVISOS = 11
        private const val ESCOLHER_ARQUIVO = 20
    }

    lateinit var web: WebView
        private set
    private var permissaoPendente: PermissionRequest? = null
    private var arquivoPendente: ValueCallback<Array<Uri>>? = null
    private var fotoDaCamera: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0D0806")
        window.navigationBarColor = Color.parseColor("#0D0806")

        val raiz = FrameLayout(this)
        raiz.setBackgroundColor(Color.parseColor("#0D0806"))
        web = WebView(this)
        raiz.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        // Barras do sistema e teclado: o site fica sempre na área livre (o Android 15 desenha por baixo das barras).
        raiz.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString JournalApp/${BuildConfig.CASCA}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.setBackgroundColor(Color.parseColor("#120B09"))
        web.addJavascriptInterface(Ponte(this), "JJNativo")
        web.webViewClient = Cliente()
        web.webChromeClient = Cromo()

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_AVISOS)
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(enderecoDe(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // Link do Telegram, atalho do ícone ou "Compartilhar" com o app já aberto.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val alvo = enderecoDe(intent, nova = true) ?: return
        web.loadUrl(alvo)
    }

    private fun enderecoDe(i: Intent?): String = enderecoDe(i, nova = false) ?: "${BASE}?casca=${BuildConfig.CASCA}"

    private fun enderecoDe(i: Intent?, nova: Boolean): String? {
        if (i == null) return null
        if (i.action == Intent.ACTION_SEND) {
            val titulo = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            val texto = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            return Uri.parse(BASE).buildUpon()
                .appendQueryParameter("casca", BuildConfig.CASCA.toString())
                .appendQueryParameter("title", titulo)
                .appendQueryParameter("text", texto)
                .build().toString()
        }
        val dado = i.data?.toString()
        if (dado != null && dado.startsWith(BASE)) return dado
        return null
    }

    @Deprecated("Botão Voltar do Android: volta no histórico do site (fecha folhas e telas) antes de sair.")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Fora do Journal (notícias, Google Maps, YouTube...) abre no app certo ou no navegador padrão (o Firefox do JV). */
    fun abrirFora(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            val volta = if (url.startsWith("intent:")) runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull() else null
            if (volta != null) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(volta))) }
        }
    }

    inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url.toString()
            if (url.startsWith(BASE) || url == BASE.removeSuffix("/")) return false
            if (!req.isForMainFrame && url.startsWith("https://")) return false   // janelas internas (boletim do JV11)
            abrirFora(url)
            return true
        }
    }

    inner class Cromo : WebChromeClient() {
        // Microfone (entrevista, ditado): pede a permissão do Android uma vez e libera para o site.
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val querAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!querAudio) { request.deny(); return@runOnUiThread }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    permissaoPendente = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PEDIDO_MICROFONE)
                }
            }
        }

        // "Pôr foto", "Câmera", "Juntar a gravação", arquivo de mudança: o seletor do Android, ou a câmera direto.
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            arquivoPendente?.onReceiveValue(null)
            arquivoPendente = callback
            val aceita = params.acceptTypes.joinToString(",")
            val intent = if (params.isCaptureEnabled && aceita.contains("image")) {
                val arq = File(File(cacheDir, "camera").apply { mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")
                fotoDaCamera = FileProvider.getUriForFile(this@MainActivity, "$packageName.arquivos", arq)
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, fotoDaCamera)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } else {
                fotoDaCamera = null
                params.createIntent()
            }
            return try { startActivityForResult(intent, ESCOLHER_ARQUIVO); true }
            catch (e: ActivityNotFoundException) { arquivoPendente = null; false }
        }
    }

    @Deprecated("Resposta do seletor de arquivos e da câmera.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ESCOLHER_ARQUIVO) return
        val cb = arquivoPendente ?: return
        arquivoPendente = null
        if (resultCode != RESULT_OK) { cb.onReceiveValue(null); return }
        val camera = fotoDaCamera
        if (camera != null) { cb.onReceiveValue(arrayOf(camera)); return }
        val varios = data?.clipData
        val lista = if (varios != null) Array(varios.itemCount) { varios.getItemAt(it).uri } else data?.data?.let { arrayOf(it) }
        cb.onReceiveValue(lista)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PEDIDO_MICROFONE) {
            val p = permissaoPendente ?: return
            permissaoPendente = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) p.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else p.deny()
        }
    }

    /** Responde a uma chamada assíncrona da ponte (digital, salvar arquivo): o site espera pela promessa. */
    fun responder(id: String, ok: Boolean, msg: String = "") {
        val js = "window.__jjNativo && window.__jjNativo(${aspas(id)}, $ok, ${aspas(msg)})"
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    private fun aspas(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    fun telaAcesa(ligar: Boolean) = runOnUiThread {
        if (ligar) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
