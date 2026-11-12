package io.github.jvhubert.journal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * Abre o Journal em tela cheia, sem barra de endereço (Trusted Web Activity). Tudo que o Journal faz
 * (telas, dados, digital, avisos) continua vindo do site; este app é só a moldura.
 * Na primeira abertura pede a permissão de avisos (Android 13 em diante).
 */
class MainActivity : LauncherActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        super.onCreate(savedInstanceState)
    }
}
