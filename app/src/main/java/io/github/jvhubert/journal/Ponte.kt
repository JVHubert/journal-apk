package io.github.jvhubert.journal

import android.content.ContentValues
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.File

/**
 * O que o site pode pedir ao Android (window.JJNativo). Tudo é opcional para o site: fora do app ele segue como antes.
 * Respostas demoradas (digital, salvar) voltam por window.__jjNativo(id, ok, mensagem).
 */
class Ponte(private val a: MainActivity) {

    @JavascriptInterface
    fun casca(): Int = BuildConfig.CASCA

    /** A digital (ou o PIN do celular) está disponível? */
    @JavascriptInterface
    fun temDigital(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return Build.VERSION.SDK_INT >= 28
        val bm = a.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Pede a digital (ou o PIN). Responde ok = true quando destravou. */
    @JavascriptInterface
    fun digital(id: String, titulo: String) {
        if (Build.VERSION.SDK_INT < 28) { a.responder(id, true); return }
        a.runOnUiThread {
            val b = BiometricPrompt.Builder(a).setTitle(titulo.ifBlank { "Journal" })
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            try {
                b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = a.responder(id, true)
                    override fun onAuthenticationError(code: Int, msg: CharSequence) = a.responder(id, false, msg.toString())
                })
            } catch (e: Exception) { a.responder(id, false, e.message ?: "erro") }
        }
    }

    /** Mantém a tela acesa enquanto grava (no WebView não há o "wake lock" do Chrome). */
    @JavascriptInterface
    fun telaAcesa(ligar: Boolean) = a.telaAcesa(ligar)

    @JavascriptInterface
    fun compartilhar(texto: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
        a.runOnUiThread { a.startActivity(Intent.createChooser(i, "Compartilhar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @JavascriptInterface
    fun abrirFora(url: String) = a.runOnUiThread { a.abrirFora(url) }

    /** Salva um arquivo em Downloads (gravação, cópia). Conteúdo em base64. */
    @JavascriptInterface
    fun salvar(id: String, nome: String, tipo: String, base64: String) {
        Thread {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nome)
                        put(MediaStore.Downloads.MIME_TYPE, tipo.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("sem lugar")
                    a.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nome).writeBytes(bytes)
                }
                a.responder(id, true, nome)
            } catch (e: Exception) { a.responder(id, false, e.message ?: "erro") }
        }.start()
    }
}
