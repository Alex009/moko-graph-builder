import com.gitlab.mvysny.konsumexml.konsumeXml
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private val json: Json = Json { ignoreUnknownKeys = true }

suspend fun main() = coroutineScope {
    // TODO раскомментить чтобы считать актуальные gradle metadata с мавена
//    val metadata: List<GradleMetadata> = fetchGradleMetadata()
//    saveGradleMetadata(metadata)

    val metadata: List<GradleMetadata> = readGradleMetadata()

    val graph: List<GraphNode> = buildGraph(metadata)

    val filtered: List<GraphNode> = graph.filter { graphNode ->
        graphNode.isDependsOn(graph, "resources")
    }

    File("output").mkdirs()
    writeGraph("output/full.txt", graph)
    writeGraph("output/filtered.txt", filtered)

    File("output/deps.txt").writer().use { writer ->
        graph.forEach { node ->
            writer.appendLine(node.path)
            node.transitiveDependencies(graph)
                .sortedBy { it.path }
                .forEach { dep ->
                    writer.appendLine("  - ${dep.path}")
                }
        }
    }
}

private fun GraphNode.isDependsOn(nodes: List<GraphNode>, module: String): Boolean {
    if (this.dependencies.contains(module)) return true
    return this.dependencies.map { id ->
        nodes.single { it.id == id }
    }.any { it.isDependsOn(nodes, module) }
}

private fun GraphNode.transitiveDependencies(nodes: List<GraphNode>): List<GraphNode> {
    return this.dependencies.map { id ->
        nodes.single { it.id == id }
    }.flatMap { node ->
        listOf(node) + node.transitiveDependencies(nodes)
    }.distinctBy { it.id }
}

private suspend fun fetchGradleMetadata(): List<GradleMetadata> {
    val repoRoot = "https://repo1.maven.org/maven2/dev/icerock/moko/"

    val httpClient = HttpClient(OkHttp) {
        engine {
            threadsCount = 8
        }
    }
    val response: HttpResponse = httpClient.get(repoRoot)
    val body: String = response.body()

    val regex = Regex("<a href=\"(.*)\" title=\"(.*)\">")
    val links: Map<String, String> = regex.findAll(body)
        .map { result ->
            result.groups[2]!!.value to result.groups[1]!!.value
        }
        .toMap()
        .mapValues { (_, value) ->
            repoRoot + value
        }
        .mapKeys { (key, _) ->
            key.dropLast(1)
        }

    val result: List<GradleMetadata> = withContext(Dispatchers.IO) {
        links.map { (title, link) ->
            async {
                println("start load $title")
                val mavenMetadata: MavenMetadata = httpClient.getMavenMetadata(link)
                val lastRelease: String = mavenMetadata.versioning.latest
                val gradleMetadata: GradleMetadata = httpClient.getGradleMetadata(
                    group = mavenMetadata.groupId,
                    artifact = mavenMetadata.artifactId,
                    version = lastRelease
                ) ?: return@async null
                println("load $title complete")
                gradleMetadata
            }
        }.awaitAll().filterNotNull()
    }
    httpClient.close()

    return result
}

private fun saveGradleMetadata(list: List<GradleMetadata>) {
    val metadataDir = File("metadata")
    metadataDir.mkdirs()

    val group: Map<String, List<GradleMetadata>> = list.groupBy { it.component.path }

    group.forEach { (path, list) ->
        val metadata = GradleMetadata(
            component = list.first().component,
            createdBy = list.first().createdBy,
            variants = list.flatMap { it.variants }.distinctBy { it.name }
        )
        File(metadataDir, "$path.json").writer().use { writer ->
            writer.append(json.encodeToString(GradleMetadata.serializer(), metadata))
        }
    }
}

private fun readGradleMetadata(): List<GradleMetadata> {
    val metadataDir = File("metadata")
    return metadataDir.listFiles().orEmpty().map { file ->
        val text: String = file.readText()
        json.decodeFromString(GradleMetadata.serializer(), text)
    }
}

private fun buildGraph(libs: List<GradleMetadata>): List<GraphNode> {
    return libs.filter { gradleMetadata ->
        gradleMetadata.variants.any { it.platformType == "common" }
    }.map { gradleMetadata ->
        GraphNode(
            id = gradleMetadata.component.module,
            path = gradleMetadata.component.path,
            platforms = gradleMetadata.variants.mapNotNull { variant ->
                variant.kotlinNativeTarget ?: variant.platformType
            }.distinct(),
            dependencies = gradleMetadata.variants
                .flatMap { it.dependencies.orEmpty() }
                .filter { it.group == "dev.icerock.moko" }
                .map { it.module }
        )
    }.sortedBy { it.id }
}

private fun writeGraph(filename: String, graph: List<GraphNode>) {
    File(filename).writer().use { writer ->
        writer.appendLine("digraph MOKO {")
        graph.forEach { node ->
            writer.appendLine("  " + node.declaration)
        }
        writer.appendLine()
        graph.flatMap { it.deps }.forEach { dependency ->
            writer.appendLine("  $dependency")
        }
        writer.appendLine("}")
    }
}

private suspend fun HttpClient.getMavenMetadata(
    artifactRoot: String
): MavenMetadata {
    val metadataUrl = artifactRoot + "maven-metadata.xml"

    val body: String = get(metadataUrl).body()

    return with(body.konsumeXml()) {
        child("metadata") {
            val groupId: String = childText("groupId")
            val artifactId: String = childText("artifactId")

            val versioning: MavenMetadata.Versioning = child("versioning") {
                val latest: String = childText("latest")
                val release: String = childText("release")
                val versions: List<String> = child("versions") {
                    childrenText("version")
                }
                val lastUpdated: String = childText("lastUpdated")
                MavenMetadata.Versioning(
                    latest = latest,
                    release = release,
                    lastUpdated = lastUpdated.toLong(),
                    versions = versions
                )
            }

            MavenMetadata(
                groupId = groupId,
                artifactId = artifactId,
                versioning = versioning
            )
        }
    }
}

private suspend fun HttpClient.getGradleMetadata(
    group: String,
    artifact: String,
    version: String
): GradleMetadata? {
    val groupPath: String = group.replace('.', '/')
    val mavenBase = "https://repo1.maven.org/maven2/$groupPath/$artifact/$version"
    val gradleMetadataUrl = "$mavenBase/$artifact-$version.module"

    val response: HttpResponse = this.get(gradleMetadataUrl)
    if (response.status != HttpStatusCode.OK) return null

    val body: String = response.body()

    return json.decodeFromString(GradleMetadata.serializer(), body)
}

data class GraphNode(
    val id: String,
    val path: String,
    val platforms: List<String>,
    val dependencies: List<String>
) {
    val declaration: String get() = "${id.camelCase()} [label=\"$path (${platforms.joinToString()})\"];"
    val deps: List<String> get() = dependencies.map { "${id.camelCase()} -> ${it.camelCase()}" }.distinct()

    fun String.camelCase(): String {
        var result = this;
        do {
            val delim: Int = result.indexOf("-")
            if (delim == -1) return result

            result = result.substring(startIndex = 0, endIndex = delim) +
                    result[delim + 1].uppercase() +
                    result.substring(startIndex = delim + 2)
        } while (true)
    }
}
