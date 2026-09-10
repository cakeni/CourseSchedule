package com.courseschedule.ui.importdata

import android.content.Context
import android.icu.text.Transliterator
import android.os.Build
import com.google.gson.Gson
import java.util.Locale

internal enum class AcademicDirectorySupport {
    VERIFIED,
    COMPATIBLE,
    EXPERIMENTAL,
    ADAPTER_REQUIRED;

    companion object {
        fun parse(value: String?, schemaVersion: Int): AcademicDirectorySupport? = when (value) {
            "verified" -> VERIFIED
            "compatible" -> COMPATIBLE
            "experimental" -> EXPERIMENTAL
            "adapter_required" -> ADAPTER_REQUIRED
            null -> if (schemaVersion == 1) COMPATIBLE else null
            else -> null
        }
    }
}

internal enum class AcademicDirectoryCategory {
    UNDERGRADUATE,
    GRADUATE,
    COMMON;

    companion object {
        fun parse(value: String?, schemaVersion: Int): AcademicDirectoryCategory? = when (value) {
            "undergraduate" -> UNDERGRADUATE
            "graduate" -> GRADUATE
            "common" -> COMMON
            null -> if (schemaVersion == 1) UNDERGRADUATE else null
            else -> null
        }
    }
}

internal data class AcademicSchoolDirectoryEntry(
    val id: String,
    val name: String,
    val profileId: String,
    val url: String,
    val aliases: List<String> = emptyList(),
    val builtInSchoolId: String? = null,
    val support: AcademicDirectorySupport = AcademicDirectorySupport.COMPATIBLE,
    val category: AcademicDirectoryCategory = AcademicDirectoryCategory.UNDERGRADUATE,
    val sourceType: String = "",
    val adapterId: String = "",
    val allowCleartext: Boolean = false,
    val loginUrls: List<String> = emptyList(),
    val authenticationUrls: List<String> = emptyList(),
    val timetableUrls: List<String> = emptyList(),
    /** WakeUp's recorded entry address; shown as guidance only, never loaded. */
    val referenceUrl: String = ""
) {
    val profile: GenericAcademicProfile? get() = GenericAcademicImport.profile(profileId)
    val host: String get() = academicWebUri(url, allowCleartext, allowNonDefaultPort = true)?.host.orEmpty()
    val canImport: Boolean get() = support != AcademicDirectorySupport.ADAPTER_REQUIRED && profile != null && host.isNotEmpty()
    val needsUserUrl: Boolean get() = support == AcademicDirectorySupport.ADAPTER_REQUIRED && profile != null
    val verified: Boolean get() = support == AcademicDirectorySupport.VERIFIED

    /** WakeUp used its remote parser for this row; reviewed rows may also have a local fallback. */
    val isCloudOnly: Boolean get() = WakeUpImportCatalog.isCloudOnly(sourceType)

    /** School- or type-specific usage note adapted from WakeUp's import steps. */
    val importHint: String? get() = WakeUpImportCatalog.hintFor(name, sourceType)

    fun toAcademicSchool(): AcademicSchool? = builtInSchoolId?.let(AcademicSchools::find)
        ?: profile?.takeIf { canImport }?.let {
            runCatching {
                GenericAcademicImport.createCatalog(
                    it, url, allowCleartext, authenticationUrls, timetableUrls, adapterId, loginUrls
                ).copy(name = name)
            }.getOrNull()
        }

    fun createUserConfiguredSchool(address: String): AcademicSchool {
        val definition = profile?.takeIf { needsUserUrl }
            ?: throw ImportFormatException("该条目没有可复用的本地解析器")
        return GenericAcademicImport.createCatalog(
            profile = definition,
            address = address,
            allowCleartext = address.trim().startsWith("http://", ignoreCase = true),
            adapterId = adapterId
        ).copy(name = name)
    }

    fun matches(query: String): Boolean {
        val needle = normalizeSearch(query)
        if (needle.isEmpty()) return true
        return sequenceOf(name, host, profile?.label.orEmpty(), sourceType)
            .plus(aliases.asSequence())
            .any { normalizeSearch(it).contains(needle) }
    }
}

/** Bundled, reviewable directory. Entries are candidates; parsing success is still verified in preview. */
internal object AcademicSchoolDirectory {
    private const val ASSET_NAME = "academic_school_directory.json"
    private const val MAX_ASSET_CHARS = 1_500_000
    private const val MAX_ENTRIES = 5_000
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    // Han-Latin rules are only guaranteed from API 29; below that, alias
    // romanization silently stays disabled (same outcome as the runCatching).
    private val romanizer: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()") else null
    }

    @Volatile private var cachedEntries: List<AcademicSchoolDirectoryEntry>? = null

    fun entries(context: Context): List<AcademicSchoolDirectoryEntry> = cachedEntries
        ?: synchronized(this) {
            cachedEntries ?: load(context).also { cachedEntries = it }
        }

    fun find(context: Context, id: String?): AcademicSchoolDirectoryEntry? =
        id?.let { wanted -> entries(context).firstOrNull { it.id == wanted } }

    fun search(entries: List<AcademicSchoolDirectoryEntry>, query: String): List<AcademicSchoolDirectoryEntry> =
        if (query.isBlank()) entries else entries.filter { it.matches(query) }

    internal fun parse(json: String): List<AcademicSchoolDirectoryEntry> {
        if (json.length > MAX_ASSET_CHARS) return emptyList()
        val file = runCatching { Gson().fromJson(json, DirectoryFile::class.java) }.getOrNull()
            ?: return emptyList()
        if (file.schemaVersion !in 1..3 || file.entries.size > MAX_ENTRIES) return emptyList()
        val seenIds = HashSet<String>()
        return file.entries.mapNotNull { raw ->
            val id = raw.id?.trim().orEmpty()
            val name = raw.name?.trim().orEmpty()
            val sourceType = raw.sourceType?.trim().orEmpty().take(48)
            val profile = GenericAcademicImport.profile(raw.profile?.trim())
                ?: sourceType.takeIf(String::isNotBlank)?.let(WakeUpImportCatalog::profileFor)
            val sourceUrl = raw.url?.trim().orEmpty()
            val adapterId = raw.adapterId?.trim().orEmpty().ifBlank {
                AcademicAdapterRegistry.adapterIdFor(sourceType, profile?.id)
            }
            val declaredSupport = AcademicDirectorySupport.parse(raw.support, file.schemaVersion)
                ?: return@mapNotNull null
            val support = declaredSupport
            val category = AcademicDirectoryCategory.parse(raw.category, file.schemaVersion) ?: return@mapNotNull null
            val allowCleartext = raw.cleartext == true
            if (!idPattern.matches(id) || !seenIds.add(id) || name.length !in 2..80 ||
                name.any(Char::isISOControl) || !AcademicAdapterRegistry.isKnownAdapterId(adapterId) ||
                profile != null && !AcademicAdapterRegistry.isCompatible(adapterId, profile.system)) {
                return@mapNotNull null
            }
            val school = if (support == AcademicDirectorySupport.ADAPTER_REQUIRED) null else {
                val usableProfile = profile ?: return@mapNotNull null
                runCatching {
                    GenericAcademicImport.createCatalog(
                        usableProfile, sourceUrl, allowCleartext, raw.authenticationUrls.orEmpty(),
                        raw.timetableUrls.orEmpty(), adapterId, raw.loginUrls.orEmpty()
                    )
                }.getOrNull()
                    ?: return@mapNotNull null
            }
            AcademicSchoolDirectoryEntry(
                id = id,
                name = name,
                profileId = profile?.id.orEmpty(),
                url = school?.loginUrl.orEmpty(),
                aliases = raw.aliases.orEmpty().asSequence()
                    .map(String::trim)
                    .filter { it.length in 1..40 && it.none(Char::isISOControl) }
                    .distinct()
                    .take(8)
                    .toList(),
                support = support,
                category = category,
                sourceType = sourceType,
                adapterId = adapterId,
                allowCleartext = allowCleartext && school?.allowCleartext == true,
                loginUrls = school?.loginPrefixes.orEmpty(),
                authenticationUrls = school?.authenticationPrefixes.orEmpty(),
                timetableUrls = school?.timetablePrefixes.orEmpty(),
                referenceUrl = raw.referenceUrl?.trim().orEmpty()
                    .replace(Regex(";jsessionid=[^/?#;]*", RegexOption.IGNORE_CASE), "")
                    .substringBefore('?').substringBefore('#')
                    .filterNot(Char::isISOControl).take(240)
            )
        }
    }

    private fun load(context: Context): List<AcademicSchoolDirectoryEntry> {
        val builtIns = AcademicSchools.all.map { school ->
            AcademicSchoolDirectoryEntry(
                id = school.id,
                name = school.name,
                profileId = GenericAcademicImport.profiles.first { it.system == school.system }.id,
                url = school.loginUrl,
                builtInSchoolId = school.id,
                adapterId = school.adapterId,
                support = if (school.verified) AcademicDirectorySupport.VERIFIED
                    else AcademicDirectorySupport.COMPATIBLE
            ).withRomanizedName()
        }
        val bundled = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyList())
        val builtInNames = builtIns.mapTo(HashSet()) { it.name }
        return builtIns + bundled.filterNot { it.name in builtInNames }
            .map { it.withRomanizedName() }
            .sortedBy { normalizeSearch(it.aliases.lastOrNull().orEmpty()) }
    }

    private fun AcademicSchoolDirectoryEntry.withRomanizedName(): AcademicSchoolDirectoryEntry {
        val romanizer = romanizer ?: return this
        val romanized = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) romanizer.transliterate(name) else ""
        }.getOrDefault("").trim()
        return if (romanized.isBlank()) this else copy(aliases = (aliases + romanized).distinct())
    }

    private data class DirectoryFile(
        val schemaVersion: Int = 0,
        val entries: List<RawEntry> = emptyList()
    )

    private data class RawEntry(
        val id: String? = null,
        val name: String? = null,
        val profile: String? = null,
        val url: String? = null,
        val aliases: List<String>? = null,
        val support: String? = null,
        val category: String? = null,
        val sourceType: String? = null,
        val adapterId: String? = null,
        val cleartext: Boolean? = null,
        val loginUrls: List<String>? = null,
        val authenticationUrls: List<String>? = null,
        val timetableUrls: List<String>? = null,
        val referenceUrl: String? = null
    )
}

private fun normalizeSearch(value: String): String = value.lowercase(Locale.ROOT)
    .filterNot { it.isWhitespace() || it == '-' || it == '_' }
