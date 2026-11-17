package io.github.jvhubert.journal

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Fotos da galeria para a entrevista (casca 6). O site pede a lista do período (dia da entrevista ou início e fim da
 * memória) e carrega as imagens por um endereço interno, que nunca sai do celular:
 *   {BASE}__galeria/mini/<id>   miniatura (lado ~320 px)
 *   {BASE}__galeria/foto/<id>   foto leve para guardar na nota (lado maior 2048 px, JPEG 85)
 */
object Galeria {
    const val PREFIXO = MainActivity.BASE + "__galeria/"

    fun uriDe(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** Fotos tiradas entre de e ate (ms), da mais antiga para a mais nova. */
    fun doPeriodo(c: Context, de: Long, ate: Long): String {
        val col = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        // DATE_TAKEN às vezes vem vazio (prints, fotos recebidas): aí vale a data em que entrou no celular.
        val sel = "((${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR (${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?))"
        val args = arrayOf(de.toString(), ate.toString(), (de / 1000).toString(), (ate / 1000).toString())
        val out = JSONArray()
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, col, sel, args, "${MediaStore.Images.Media.DATE_TAKEN} ASC")?.use { cur ->
            while (cur.moveToNext() && out.length() < 800) {
                val tomada = if (cur.isNull(1)) cur.getLong(2) * 1000 else cur.getLong(1)
                out.put(JSONObject().put("id", cur.getLong(0)).put("quando", tomada).put("w", cur.getInt(3)).put("h", cur.getInt(4))
                    .put("pasta", cur.getString(5) ?: ""))
            }
        }
        return out.toString()
    }

    /** Responde aos endereços internos; null = não é da galeria. */
    fun interceptar(c: Context, url: String): WebResourceResponse? {
        if (!url.startsWith(PREFIXO)) return null
        val partes = url.removePrefix(PREFIXO).substringBefore('?').split('/')
        val id = partes.getOrNull(1)?.toLongOrNull() ?: return vazio(404)
        return try {
            val bytes = when (partes[0]) {
                "mini" -> jpeg(miniatura(c, id), 80)
                "foto" -> jpeg(leve(c, id, 2048), 85)
                else -> return vazio(404)
            }
            WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
        } catch (e: Exception) { vazio(404) }
    }

    private fun vazio(cod: Int) = WebResourceResponse("text/plain", "utf-8", cod, "Nada", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun jpeg(b: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, q, o); o.toByteArray() }

    private fun miniatura(c: Context, id: Long): Bitmap =
        if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(uriDe(id), Size(360, 480), null) else leve(c, id, 480)

    /** Lê a foto já girada do jeito certo e reduzida para o lado maior caber em `lado`. */
    private fun leve(c: Context, id: Long, lado: Int): Bitmap {
        val uri = uriDe(id)
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val k = minOf(1.0, lado.toDouble() / maxOf(w, h))
                if (k < 1.0) dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= lado) s *= 2
        return c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
    }
}
