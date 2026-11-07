package com.altitudeplusplus.android.guide

import android.util.Xml
import com.altitudeplusplus.android.AltitudeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class Program(
    val id: String,
    val title: String,
    val subtitle: String?,
    val summary: String?,
    val category: String?,
    /** Gracenote program type, e.g. "Sports event" for games. */
    val kind: String?,
    /** Epoch millis. */
    val start: Long,
    val end: Long,
    val imageUrl: String?,
) {
    fun isOn(at: Long) = at in start until end
    val isGame get() = kind == "Sports event"
}

/** Now/next listings for the 24/7 channel, from the public XMLTV feed. */
class GuideRepository(private val http: OkHttpClient) {

    suspend fun load(): List<Program> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(AltitudeConfig.EPG_URL).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Guide request failed (HTTP ${response.code})")
            val cutoff = System.currentTimeMillis() - 3_600_000
            parse(response.body!!.byteStream()).filter { it.end > cutoff }.sortedBy { it.start }
        }
    }

    private fun parse(input: InputStream): List<Program> {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val programs = mutableListOf<Program>()
        var current: MutableMap<String, String>? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "programme" -> current = mutableMapOf(
                        "id" to (parser.getAttributeValue(null, "id") ?: ""),
                        "start" to (parser.getAttributeValue(null, "start") ?: ""),
                        "stop" to (parser.getAttributeValue(null, "stop") ?: ""),
                    )
                    "icon" -> current?.put("icon", parser.getAttributeValue(null, "src") ?: "")
                    "title", "sub-title", "sub-type", "desc", "category" -> current?.let { map ->
                        val text = parser.nextText().trim()
                        if (text.isNotEmpty() && name !in map) map[name] = text
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    val map = current ?: continue
                    val start = runCatching { format.parse(map["start"]!!)!!.time }.getOrNull()
                    val end = runCatching { format.parse(map["stop"]!!)!!.time }.getOrNull()
                    val title = map["title"]
                    if (start != null && end != null && title != null) {
                        programs += Program(
                            id = map["id"] + map["start"],
                            title = title,
                            subtitle = map["sub-title"],
                            summary = map["desc"],
                            category = map["category"],
                            kind = map["sub-type"],
                            start = start,
                            end = end,
                            imageUrl = map["icon"]?.takeIf { it.isNotBlank() },
                        )
                    }
                    current = null
                }
            }
        }
        return programs
    }
}
