```kotlin
package com.example.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray

object AiTranslate {

    // Buraya kendi Claude API anahtarınızı girin
    private const val API_KEY = "YOUR_CLAUDE_API_KEY"
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    suspend fun translateWithClaude(text: String, gameName: String = "PS5 Game"): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        if (API_KEY == "YOUR_CLAUDE_API_KEY" || API_KEY.isBlank()) {
            return@withContext "Hata: API Key girilmedi."
        }

        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("x-api-key", API_KEY)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val systemPrompt = "Sen profesyonel bir oyun altyazı çevirmenisin. " +
                    "Oyun adı: $gameName. " +
                    "Verilen İngilizce altyazıyı doğal, akıcı ve bağlama uygun Türkçe altyazı olarak çevir. " +
                    "Sadece Türkçe çeviriyi yaz, ekstra açıklama ekleme. Karakter isimlerini koru."

            val jsonBody = JSONObject().apply {
                put("model", "claude-3-haiku-20240307")
                put("max_tokens", 150)
                put("system", systemPrompt)
                
                val messagesArray = JSONArray().apply {
                    val messageObj = JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    }
                    put(messageObj)
                }
                put("messages", messagesArray)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val contentArray = jsonResponse.getJSONArray("content")
                if (contentArray.length() > 0) {
                    return@withContext contentArray.getJSONObject(0).getString("text").trim()
                }
            } else {
                return@withContext "Çeviri hatası ($responseCode)"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Bağlantı hatası"
        }

        return@withContext ""
    }
}
```
