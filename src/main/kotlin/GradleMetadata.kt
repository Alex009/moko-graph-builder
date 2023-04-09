import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GradleMetadata(
    val component: Component,
    val createdBy: Map<String, Creator>,
    val variants: List<Variant>
) {
    @Serializable
    data class Component(
        val group: String,
        val module: String,
        val version: String,
        val attributes: Map<String, JsonElement>
    ) {
        val path: String get() = "$group:$module"
    }

    @Serializable
    data class Creator(
        val version: String,
        val buildId: String? = null
    )

    @Serializable
    data class Variant(
        val name: String,
        val attributes: Map<String, JsonElement>,
        val dependencies: List<Dependency>? = null,
        val files: List<File>? = null,
        @SerialName("available-at")
        val availableAt: Location? = null
    ) {
        val platformType: String? get() = attributes[Attributes.KOTLIN_PLATFORM_TYPE.key]?.jsonPrimitive?.content
        val kotlinNativeTarget: String? get() = attributes[Attributes.KOTLIN_NATIVE_TARGET.key]?.jsonPrimitive?.content
    }

    @Serializable
    data class Dependency(
        val group: String,
        val module: String,
        val version: Version? = null
    ) {
        val path: String get() = "$group:$module"

        @Serializable
        data class Version(
            val requires: String? = null,
            val strictly: String? = null,
            val prefers: String? = null
        ) {
            val resolved: String? = strictly ?: requires ?: prefers
        }
    }

    @Serializable
    data class File(
        val name: String,
        val url: String
    )

    @Serializable
    data class Location(
        val url: String,
        val group: String,
        val module: String,
        val version: String
    )

    enum class Attributes(val key: String) {
        USAGE("org.gradle.usage"),
        STATUS("org.gradle.status"),
        KOTLIN_PLATFORM_TYPE("org.jetbrains.kotlin.platform.type"),
        KOTLIN_NATIVE_TARGET("org.jetbrains.kotlin.native.target"),
        ARTIFACT_TYPE("artifactType")
    }
}
