data class MavenMetadata(
    val groupId: String,
    val artifactId: String,
    val versioning: Versioning
) {
    data class Versioning(
        val latest: String,
        val release: String,
        val lastUpdated: Long,
        val versions: List<String>
    )
}
