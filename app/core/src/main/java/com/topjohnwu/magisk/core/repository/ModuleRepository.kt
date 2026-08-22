package com.topjohnwu.magisk.core.repository

import com.topjohnwu.magisk.core.model.module.OnlineModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

data class RepositoryModule(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val versionCode: Int,
    val zipUrl: String,
    val notesUrl: String,
) {
    fun asOnlineModule() = OnlineModule(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        zipUrl = zipUrl,
        changelog = notesUrl,
    )
}

data class RepositoryCandidate(
    val id: String,
    val name: String = id,
    val author: String = "",
    val description: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val zipUrl: String = "",
    val notesUrl: String = "",
    val propUrl: String = "",
)

/** Reads both common modules.json indexes and direct GitHub module repositories. */
class ModuleRepository(private val network: NetworkService) {

    suspend fun loadSources(rawSources: String): List<RepositoryCandidate> = coroutineScope {
        rawSources.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()
            .take(MAX_SOURCES)
            .map { source -> async { runCatching { loadSource(source) }.getOrDefault(emptyList()) } }
            .toList()
            .awaitAll()
            .flatten()
            .filter { it.id.isNotBlank() && (it.zipUrl.isNotBlank() || it.propUrl.isNotBlank()) }
            .distinctBy { it.id.lowercase() to it.zipUrl }
    }

    suspend fun resolve(candidates: List<RepositoryCandidate>): List<RepositoryModule> =
        coroutineScope {
            candidates.take(MAX_RESULTS).map { candidate ->
                async { runCatching { resolve(candidate) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }

    private suspend fun loadSource(source: String): List<RepositoryCandidate> {
        githubRepository(source)?.let { github ->
            val (owner, repository, requestedBranch) = github
            val branches = listOfNotNull(requestedBranch, "main", "master").distinct()
            for (branch in branches) {
                val propUrl = "https://raw.githubusercontent.com/$owner/$repository/$branch/module.prop"
                val prop = runCatching { network.fetchString(propUrl) }.getOrNull() ?: continue
                val values = parseProperties(prop)
                val id = values["id"].orEmpty().ifBlank { repository }
                return listOf(
                    candidateFromProperties(
                        id = id,
                        values = values,
                        zipUrl = "https://github.com/$owner/$repository/archive/refs/heads/$branch.zip",
                        notesUrl = "https://raw.githubusercontent.com/$owner/$repository/$branch/README.md",
                        propUrl = propUrl,
                    )
                )
            }
            return emptyList()
        }

        val normalized = githubBlobToRaw(source)
        val body = network.fetchString(normalized)
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") -> parseJsonObject(JSONObject(trimmed))
            trimmed.startsWith("[") -> parseJsonArray(JSONArray(trimmed))
            normalized.endsWith("module.prop", ignoreCase = true) -> {
                val values = parseProperties(body)
                val id = values["id"].orEmpty()
                val zipUrl = values["zipUrl"].orEmpty().ifBlank {
                    values["zip_url"].orEmpty()
                }
                listOf(candidateFromProperties(id, values, zipUrl, "", normalized))
            }
            else -> emptyList()
        }
    }

    private fun parseJsonObject(root: JSONObject): List<RepositoryCandidate> {
        val modules = root.optJSONArray("modules") ?: root.optJSONArray("data")
        if (modules != null) return parseJsonArray(modules)
        if (root.opt("id") is String) return listOfNotNull(parseEntry(root))
        return buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val module = root.optJSONObject(id) ?: continue
                if (!module.has("id")) module.put("id", id)
                parseEntry(module)?.let(::add)
            }
        }
    }

    private fun parseJsonArray(array: JSONArray): List<RepositoryCandidate> = buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::parseEntry)?.let(::add)
        }
    }

    private fun parseEntry(entry: JSONObject): RepositoryCandidate? {
        val metadata = entry.optJSONObject("metadata")
        val version = newestVersion(entry)
        fun string(vararg keys: String): String {
            for (key in keys) {
                entry.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                metadata?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
                version?.optString(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        fun int(vararg keys: String): Int {
            for (key in keys) {
                if (entry.has(key)) return entry.optInt(key)
                if (metadata?.has(key) == true) return metadata.optInt(key)
                if (version?.has(key) == true) return version.optInt(key)
            }
            return 0
        }

        val id = string("id", "moduleId", "module_id")
        if (id.isBlank()) return null
        return RepositoryCandidate(
            id = id,
            name = string("name").ifBlank { id },
            author = string("author"),
            description = string("description", "desc"),
            version = string("version"),
            versionCode = int("versionCode", "version_code"),
            zipUrl = string("zipUrl", "zip_url", "download"),
            notesUrl = string("notesUrl", "notes_url", "changelog"),
            propUrl = string("propUrl", "prop_url"),
        )
    }

    private fun newestVersion(entry: JSONObject): JSONObject? {
        entry.optJSONObject("latest")?.let { return it }
        val versions = entry.optJSONArray("versions") ?: return null
        var newest: JSONObject? = null
        var newestCode = Int.MIN_VALUE
        for (index in 0 until versions.length()) {
            val item = versions.optJSONObject(index) ?: continue
            val code = item.optInt("versionCode", item.optInt("version_code", index))
            if (code >= newestCode) {
                newest = item
                newestCode = code
            }
        }
        return newest
    }

    private suspend fun resolve(candidate: RepositoryCandidate): RepositoryModule? {
        val values = if (candidate.propUrl.isNotBlank()) {
            runCatching { parseProperties(network.fetchString(candidate.propUrl)) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val id = values["id"].orEmpty().ifBlank { candidate.id }
        val zipUrl = candidate.zipUrl.ifBlank {
            values["zipUrl"].orEmpty().ifBlank { values["zip_url"].orEmpty() }
        }
        if (id.isBlank() || zipUrl.isBlank()) return null
        return RepositoryModule(
            id = id,
            name = values["name"].orEmpty().ifBlank { candidate.name.ifBlank { id } },
            author = values["author"].orEmpty().ifBlank { candidate.author },
            description = values["description"].orEmpty().ifBlank { candidate.description },
            version = values["version"].orEmpty().ifBlank { candidate.version.ifBlank { "unknown" } },
            versionCode = values["versionCode"]?.toIntOrNull()
                ?: candidate.versionCode,
            zipUrl = zipUrl,
            notesUrl = candidate.notesUrl.ifBlank {
                values["changelog"].orEmpty()
            },
        )
    }

    private fun candidateFromProperties(
        id: String,
        values: Map<String, String>,
        zipUrl: String,
        notesUrl: String,
        propUrl: String,
    ) = RepositoryCandidate(
        id = id,
        name = values["name"].orEmpty().ifBlank { id },
        author = values["author"].orEmpty(),
        description = values["description"].orEmpty(),
        version = values["version"].orEmpty(),
        versionCode = values["versionCode"]?.toIntOrNull() ?: 0,
        zipUrl = zipUrl,
        notesUrl = notesUrl,
        propUrl = propUrl,
    )

    private fun parseProperties(body: String): Map<String, String> = buildMap {
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@forEach
            put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim())
        }
    }

    private fun githubBlobToRaw(url: String): String {
        val match = Regex(
            "https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.*)",
            RegexOption.IGNORE_CASE,
        ).matchEntire(url) ?: return url
        val (owner, repository, branch, path) = match.destructured
        return "https://raw.githubusercontent.com/$owner/$repository/$branch/$path"
    }

    private fun githubRepository(url: String): Triple<String, String, String?>? {
        val match = Regex(
            "https?://github\\.com/([^/]+)/([^/#?]+)(?:/tree/([^/#?]+))?/?(?:[?#].*)?",
            RegexOption.IGNORE_CASE,
        ).matchEntire(url) ?: return null
        val (owner, rawRepository, branch) = match.destructured
        return Triple(owner, rawRepository.removeSuffix(".git"), branch.ifBlank { null })
    }

    companion object {
        private const val MAX_SOURCES = 20
        private const val MAX_RESULTS = 50
    }
}
