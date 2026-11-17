package io.github.jvhubert.journal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate

/**
 * Widget do Journal (casca 5): as 4 garrafas de água do dia, as tarefas de hoje e o microfone da tarefa por voz.
 * Tocar numa garrafa marca até ela (tocar na última cheia esvazia, como na tela Hábitos) SEM abrir o app: o valor fica
 * guardado aqui como "pendente" e o site o aplica ao abrir (window.JJNativo.widgetAgua). O site, por sua vez, avisa o
 * widget sempre que a água ou as tarefas mudam lá dentro (atualizarWidget).
 */
object WidgetDados {
    private fun p(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
    fun hoje(): String = LocalDate.now().toString()

    fun agua(c: Context): Int = if (p(c).getString("dia", "") == hoje()) p(c).getInt("agua", 0) else 0
    fun tarefas(c: Context): Int = if (p(c).getString("dia", "") == hoje()) p(c).getInt("tarefas", -1) else -1

    /** Toque na garrafa i (1 a 4): marca até ela; tocar na última cheia volta uma. */
    fun tocar(c: Context, i: Int): Int {
        val atual = agua(c)
        val n = if (atual == i) i - 1 else i
        p(c).edit().putString("dia", hoje()).putInt("agua", n).putBoolean("pend", true).putLong("t", System.currentTimeMillis())
            .putInt("tarefas", tarefas(c)).apply()
        return n
    }

    /** O site manda o que está lá dentro. Se há toque pendente do widget para hoje, ele vale até o site aplicar. */
    fun doSite(c: Context, dia: String, agua: Int, tarefas: Int) {
        val e = p(c).edit().putString("dia", dia).putInt("tarefas", tarefas)
        if (!(p(c).getBoolean("pend", false) && p(c).getString("dia", "") == dia)) e.putInt("agua", agua)
        e.apply()
    }

    fun pendente(c: Context): String {
        val q = p(c)
        return if (q.getBoolean("pend", false)) JSONObject().put("dia", q.getString("dia", "")).put("agua", q.getInt("agua", 0)).toString() else ""
    }

    fun aplicado(c: Context) = p(c).edit().putBoolean("pend", false).apply()
}

class JournalWidget : AppWidgetProvider() {
    companion object {
        const val GARRAFA = "io.github.jvhubert.journal.GARRAFA"
        private val IDS = intArrayOf(R.id.g1, R.id.g2, R.id.g3, R.id.g4)

        fun redesenhar(c: Context) {
            val am = AppWidgetManager.getInstance(c)
            val ids = am.getAppWidgetIds(ComponentName(c, JournalWidget::class.java))
            if (ids.isNotEmpty()) am.updateAppWidget(ids, vistas(c))
        }

        private fun abrir(c: Context, hash: String, codigo: Int): PendingIntent {
            val i = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(MainActivity.BASE + hash))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(c, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun vistas(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget)
            val n = WidgetDados.agua(c)
            IDS.forEachIndexed { k, id ->
                val cheia = k < n
                v.setImageViewResource(id, if (cheia) (if (n >= 3) R.drawable.garrafa_meta else R.drawable.garrafa_cheia) else R.drawable.garrafa_vazia)
                v.setContentDescription(id, "${k + 1} garrafa${if (k > 0) "s" else ""}")
                val t = Intent(c, JournalWidget::class.java).setAction(GARRAFA).putExtra("i", k + 1)
                v.setOnClickPendingIntent(id, PendingIntent.getBroadcast(c, 100 + k, t, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            val rotulo = when { n >= 4 -> "meta + bônus"; n >= 3 -> "meta batida"; else -> "$n de 3 · água" }
            v.setTextViewText(R.id.agua_txt, rotulo)
            v.setTextColor(R.id.agua_txt, if (n >= 3) 0xFF6EE29A.toInt() else 0xFFAD9588.toInt())
            val tf = WidgetDados.tarefas(c)
            v.setTextViewText(R.id.tarefas_num, if (tf < 0) "–" else tf.toString())
            v.setTextViewText(R.id.tarefas_txt, if (tf == 1) "tarefa hoje" else "tarefas hoje")
            v.setOnClickPendingIntent(R.id.tarefas, abrir(c, "#tarefas-hoje", 201))
            v.setOnClickPendingIntent(R.id.voz, abrir(c, "#ditar", 202))
            return v
        }
    }

    override fun onUpdate(c: Context, am: AppWidgetManager, ids: IntArray) { am.updateAppWidget(ids, vistas(c)) }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == GARRAFA) {
            WidgetDados.tocar(c, i.getIntExtra("i", 1))
            redesenhar(c)
        }
    }
}
