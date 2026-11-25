package io.github.jvhubert.journal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Avisos do Journal agendados pelo próprio celular (casca 4, 17/11/2026).
 * A nuvem (jv-ditado) entrega a lista dos próximos avisos (GET /avisos/proximos): lembrete de tarefa na hora, uma por
 * tarefa às 8h, entrevista às 22h, aniversários e os avulsos da fila. O app agenda cada um com um alarme exato e busca
 * a lista de novo a cada 15 min, ao abrir e quando as tarefas mudam no celular. Cada aviso tem uma chave: não repete.
 */
object Avisos {
    private const val PREFS = "avisos"
    private const val CANAL = "avisos"
    private const val JOB = 4040
    private const val MAX_MOSTRADOS = 400

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ligados(c: Context) = prefs(c).getString("url", null) != null

    /** O site passa o endereço e a senha da nuvem (os mesmos que ele já usa). */
    fun ligar(c: Context, url: String, segredo: String): String {
        prefs(c).edit().putString("url", url.trimEnd('/')).putString("segredo", segredo).apply()
        criarCanal(c)
        garantirBusca(c)
        runCatching { pedir(c, "POST", "/avisos/nativo", JSONObject().put("ligado", true).put("aparelho", Build.MODEL).toString()) }
        return atualizar(c)
    }

    fun criarCanal(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Avisos do Journal", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tarefas, entrevista do dia e aniversários"
            })
        }
    }

    /** Busca a lista na nuvem e agenda tudo de novo. Roda fora da tela (chame numa thread). */
    @Synchronized
    fun atualizar(c: Context): String {
        if (!ligados(c)) return "desligados"
        garantirBusca(c)
        val corpo = pedir(c, "GET", "/avisos/proximos?horas=48", null)
        val lista = JSONObject(corpo).getJSONArray("avisos")
        agendarLista(c, lista, true)
        prefs(c).edit().putString("cache", lista.toString()).putLong("buscou", System.currentTimeMillis()).apply()
        return "ok:${lista.length()}"
    }

    /** Agenda a lista. Os já vencidos só aparecem quando ela acabou de vir da nuvem (a guardada pode ter tarefa já feita). */
    private fun agendarLista(c: Context, lista: JSONArray, mostrarVencidos: Boolean) {
        val p = prefs(c)
        val am = c.getSystemService(AlarmManager::class.java)
        // desfaz o que estava agendado e agenda a lista nova
        val antigos = JSONArray(p.getString("agendados", "[]"))
        for (i in 0 until antigos.length()) am.cancel(pendente(c, antigos.getString(i), null))
        val novos = JSONArray()
        val agora = System.currentTimeMillis()
        for (i in 0 until lista.length()) {
            val a = lista.getJSONObject(i)
            val chave = a.getString("chave")
            if (jaMostrado(c, chave)) continue
            val quando = a.getLong("quando")
            if (quando <= agora + 1000) { if (mostrarVencidos) mostrar(c, a); continue }
            val pi = pendente(c, chave, a)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, pi)
            novos.put(chave)
        }
        p.edit().putString("agendados", novos.toString()).apply()
    }

    private fun pendente(c: Context, chave: String, aviso: JSONObject?): PendingIntent {
        val i = Intent(c, AlarmeReceiver::class.java).setAction("aviso").setData(Uri.parse("jj://aviso/" + Uri.encode(chave)))
        if (aviso != null) i.putExtra("aviso", aviso.toString())
        return PendingIntent.getBroadcast(c, chave.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun jaMostrado(c: Context, chave: String) = JSONArray(prefs(c).getString("mostrados", "[]")).let { arr -> (0 until arr.length()).any { arr.getString(it) == chave } }

    private fun marcarMostrado(c: Context, chave: String) {
        val p = prefs(c)
        val arr = JSONArray(p.getString("mostrados", "[]"))
        arr.put(chave)
        val corte = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_MOSTRADOS) until arr.length()) corte.put(arr.getString(i))
        p.edit().putString("mostrados", corte.toString()).apply()
    }

    /** Mostra o aviso: tocar abre o Journal no lugar certo; Concluir e Adiar 1h respondem sem abrir o app. */
    fun mostrar(c: Context, a: JSONObject) {
        val chave = a.optString("chave")
        if (chave.isNotEmpty()) { if (jaMostrado(c, chave)) return; marcarMostrado(c, chave) }
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        criarCanal(c)
        val id = a.optString("tag", chave).hashCode()
        val abrir = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(MainActivity.BASE + a.optString("url", "#").let { if (it.startsWith("#")) it else "#" }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = android.app.Notification.Builder(c, CANAL)
            .setSmallIcon(R.drawable.ic_aviso)
            .setContentTitle(a.optString("titulo", "Journal"))
            .setContentText(a.optString("corpo", ""))
            .setAutoCancel(true)
            .setColor(0xFF6EE29A.toInt())
            .setContentIntent(PendingIntent.getActivity(c, id, abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val acoes = a.optJSONArray("acoes")
        if (acoes != null) for (i in 0 until acoes.length()) {
            val ac = acoes.getJSONObject(i)
            val it = Intent(c, AcaoReceiver::class.java).setAction("acao").setData(Uri.parse(ac.getString("url")))
                .putExtra("notif", id)
            b.addAction(android.app.Notification.Action.Builder(null, ac.optString("titulo"),
                PendingIntent.getBroadcast(c, (ac.getString("url")).hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        c.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    fun testar(c: Context) = mostrar(c, JSONObject().put("titulo", "Avisos ligados").put("corpo", "É assim que os lembretes vão chegar.").put("tag", "teste").put("url", "#"))

    /** Busca a lista a cada 15 min (o Android junta com outras tarefas para economizar bateria). */
    private fun agendarBusca(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB) != null) return
        js.schedule(JobInfo.Builder(JOB, ComponentName(c, BuscaJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build())
    }

    /** A busca automática é um reforço: se o Android recusar, os avisos e o app continuam funcionando. */
    private fun garantirBusca(c: Context) { runCatching { agendarBusca(c) } }

    fun pedir(c: Context, metodo: String, rota: String, corpo: String?): String {
        val p = prefs(c)
        val url = if (rota.startsWith("http")) rota else (p.getString("url", "") + rota)
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = metodo
        con.connectTimeout = 15000; con.readTimeout = 20000
        p.getString("segredo", null)?.let { con.setRequestProperty("x-segredo", it) }
        if (corpo != null) {
            con.doOutput = true
            con.setRequestProperty("content-type", "application/json")
            con.outputStream.use { it.write(corpo.toByteArray()) }
        }
        val cod = con.responseCode
        val txt = (if (cod in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (cod !in 200..299) throw IllegalStateException("erro $cod")
        return txt
    }

    /** Depois de reiniciar o celular: agenda de novo o que estava guardado e busca a lista. */
    @Synchronized
    fun reagendarDoCache(c: Context) {
        if (!ligados(c)) return
        garantirBusca(c)
        // primeiro o que está guardado (funciona sem internet); a lista nova da nuvem vem em seguida
        runCatching { agendarLista(c, JSONArray(prefs(c).getString("cache", "[]")), false) }
    }
}

class AlarmeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val txt = i.getStringExtra("aviso") ?: return
        Avisos.mostrar(c, JSONObject(txt))
    }
}

/** Concluir / Adiar 1h: chama o link assinado da nuvem, tira o aviso e atualiza a lista. */
class AcaoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val url = i.data?.toString() ?: return
        c.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("notif", 0))
        val fim = goAsync()
        Thread {
            try { Avisos.pedir(c, "POST", url, "{}"); Avisos.atualizar(c) } catch (_: Exception) {}
            finally { fim.finish() }
        }.start()
    }
}

class InicioReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val fim = goAsync()
        Thread {
            try {
                Avisos.reagendarDoCache(c)
                // o Android dá poucos segundos ao sinal de ligar: se a rede demorar, a busca automática completa depois
                val busca = Thread { runCatching { Avisos.atualizar(c) } }.apply { start() }
                busca.join(8000)
            } finally { fim.finish() }
        }.start()
    }
}

class BuscaJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            val erro = runCatching { Avisos.atualizar(this) }.isFailure
            jobFinished(p, erro)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}
