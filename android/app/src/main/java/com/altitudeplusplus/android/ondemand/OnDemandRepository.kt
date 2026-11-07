package com.altitudeplusplus.android.ondemand

import com.altitudeplusplus.android.AltitudeConfig
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.api.ViewLiftClient
import com.altitudeplusplus.android.api.array
import com.altitudeplusplus.android.api.double
import com.altitudeplusplus.android.api.get
import com.altitudeplusplus.android.api.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class Team(val title: String, val path: String) {
    AVALANCHE("Avalanche", "/avalanche"),
    NUGGETS("Nuggets", "/nuggets"),
}

data class VideoItem(
    val id: String,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    /** Seconds. */
    val runtime: Int?,
    /** Epoch millis. */
    val published: Long?,
) {
    /** ViewLift's image CDN resizes on request. */
    val thumbnailUrl: String? get() = imageUrl?.substringBefore('?')?.let { "$it?impolicy=resize&w=640" }

    val durationText: String?
        get() {
            val r = runtime ?: return null
            if (r <= 0) return null
            val h = r / 3600; val m = (r % 3600) / 60; val s = r % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

data class ContentRow(val id: String, val title: String, val items: List<VideoItem>, val next: String?)

/** On-demand rows for each team page via the GraphQL `page` query altitudeplus.com uses. */
class OnDemandRepository(private val client: ViewLiftClient) {

    suspend fun rows(team: Team, profile: DeviceProfile, token: String?): List<ContentRow> {
        val result = mutableListOf<ContentRow>()
        for (module in fetchModules(team.path, profile, token, moduleIds = null, next = null)) {
            val row = row(module) ?: continue
            result += if (row.title.isEmpty()) row.copy(title = if (result.isEmpty()) "Latest" else "More") else row
        }
        return result
    }

    /** The next page of one row, or null if there's nothing more. */
    suspend fun more(team: Team, row: ContentRow, profile: DeviceProfile, token: String?): ContentRow? {
        val cursor = row.next ?: return null
        val module = fetchModules(team.path, profile, token, listOf(row.id), cursor).firstOrNull() ?: return null
        val page = row(module, keepEmpty = true) ?: return null
        val known = row.items.mapTo(HashSet()) { it.id }
        val fresh = page.items.filter { it.id !in known }
        // Stop if the server repeats a cursor or returns nothing new.
        val next = if (page.next == cursor || fresh.isEmpty()) null else page.next
        return row.copy(items = row.items + fresh, next = next)
    }

    private suspend fun fetchModules(
        path: String,
        profile: DeviceProfile,
        token: String?,
        moduleIds: List<String>?,
        next: String?,
    ): List<JsonElement> {
        val variables = buildJsonObject {
            put("site", AltitudeConfig.SITE)
            put("path", path)
            put("device", profile.pageDevice)
            put("moduleLimit", if (moduleIds == null) 30 else 1)
            put("moduleOffset", 0)
            put("countryCode", "US")
            put("platform", profile.deviceType)
            if (moduleIds != null) putJsonArray("modules") { moduleIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (next != null) put("next", next)
        }
        return client.graphQL(QUERY, variables, token)["page"]["modules"].array.orEmpty()
    }

    /** Video rows only: games, articles, and talent categories are skipped. */
    private fun row(module: JsonElement, keepEmpty: Boolean = false): ContentRow? {
        val id = module["id"].string ?: return null
        val content = module["contentData"].array ?: return null
        val items = content.mapNotNull(::videoItem)
        if (!keepEmpty && items.isEmpty()) return null
        return ContentRow(id, module["title"].string.orEmpty(), items, module["next"].string)
    }

    private fun videoItem(content: JsonElement): VideoItem? {
        val gist = content["gist"] ?: return null
        if (gist["contentType"].string != "VIDEO") return null
        val id = gist["id"].string ?: content["id"].string ?: return null
        val title = gist["title"].string ?: return null
        return VideoItem(
            id = id,
            title = title,
            summary = gist["description"].string,
            imageUrl = gist["imageGist"]["r16x9"].string,
            runtime = content["runtime"].double?.toInt(),
            published = content["publishDate"].double?.let { if (it > 1e12) it.toLong() else (it * 1000).toLong() },
        )
    }

    private companion object {
        val QUERY = """
            query (${'$'}site: String!, ${'$'}path: String, ${'$'}device: Device!, ${'$'}moduleLimit: Int, ${'$'}moduleOffset: Int, ${'$'}countryCode: String,
                   ${'$'}platform: EntitlementDevice, ${'$'}modules: [String!], ${'$'}next: String) {
              page(site: ${'$'}site, path: ${'$'}path, device: ${'$'}device, includeContent: true, moduleLimit: ${'$'}moduleLimit,
                   moduleOffset: ${'$'}moduleOffset, countryCode: ${'$'}countryCode, platform: ${'$'}platform, modules: ${'$'}modules, next: ${'$'}next) {
                modules {
                  id
                  moduleType
                  ... on CuratedTrayModule { title contentData { ...Item } }
                  ... on GeneratedTrayModule { title next contentData { ...Item } }
                }
              }
            }
            fragment Item on Content {
              id
              gist { id title contentType description imageGist { r16x9 } }
              ... on Video { publishDate runtime }
            }
        """.trimIndent()
    }
}
