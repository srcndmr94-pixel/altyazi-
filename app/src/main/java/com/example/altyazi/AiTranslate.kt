package com.example.altyazi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiTranslate {
    fun translate(key: String, game: String, history: List<String>, text: String): String? {
        return try {
            val system = "Sen bir video oyunu altyazı çevirmenisin. Oyun: " +
                (if (game.isBlank()) "bilinmiyor" else game) + ". " +
                "Ekrandan okunan İngilizce altyazıyı doğal Türkçeye çevir. OCR hataları olabilir, düzelt. " +
                "Karakter, yer ve oyun terimlerini oyunun bilinen adlarıyla tutarlı kullan. " +
                "Konuşanın adı altyazıda yazıyorsa ya da bağlamdan çok net biliniyorsa ilk satıra " +
                "'Konuşan: İsim', ikinci satıra çeviriyi yaz. Emin değilsen yalnızca çeviriyi yaz, " +
                "konuşan uydurma. Çok kısa ve hızlı cevap ver, başka açıklama ekleme."
            val user = "Önceki satırlar:\n" + history.joinToString("\n") +
                "\n\nÇevrilecek:\n" + text
            val body = JSONObject()
                .put("model", "claude-haiku-5-5")
                .put("max_tokens", 160)
                .put("system", system)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)))
            val c = URL("https://api.anthropic.com/v1/messages")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 7000
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) return null
            val resp = c.inputStream.bufferedReader().readText()
            JSONObject(resp).getJSONArray("content").getJSONObject(0)
                .getString("text").trim()
        } catch (e: Exception) {
            null
        }
    }
}
