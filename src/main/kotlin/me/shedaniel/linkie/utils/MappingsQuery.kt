package me.shedaniel.linkie.utils

import me.shedaniel.linkie.Class
import me.shedaniel.linkie.Field
import me.shedaniel.linkie.MappingsContainer
import me.shedaniel.linkie.MappingsEntry
import me.shedaniel.linkie.MappingsEntryType
import me.shedaniel.linkie.MappingsEntryType.CLASS
import me.shedaniel.linkie.MappingsEntryType.FIELD
import me.shedaniel.linkie.MappingsEntryType.METHOD
import me.shedaniel.linkie.MappingsMember
import me.shedaniel.linkie.MappingsMetadata
import me.shedaniel.linkie.MappingsProvider
import me.shedaniel.linkie.Method
import me.shedaniel.linkie.optimumName

typealias ClassResultSequence = Sequence<ResultHolder<Class>>
typealias FieldResultSequence = Sequence<ResultHolder<Pair<Class, Field>>>
typealias MethodResultSequence = Sequence<ResultHolder<Pair<Class, Method>>>

enum class QueryDefinition(val toDefinition: MappingsEntry.() -> String?, val multiplier: Double) {
    MAPPED({ mappedName }, 1.0),
    INTERMEDIARY({ intermediaryName }, 1.0 - Double.MIN_VALUE),
    OBF_MERGED({ obfName.merged }, 1.0 - Double.MIN_VALUE - Double.MIN_VALUE),
    OBF_CLIENT({ obfName.client }, 1.0 - Double.MIN_VALUE - Double.MIN_VALUE),
    OBF_SERVER({ obfName.server }, 1.0 - Double.MIN_VALUE - Double.MIN_VALUE),
    WILDCARD({ throw IllegalStateException("Cannot get definition of type WILDCARD!") }, 1.0);

    operator fun invoke(entry: MappingsEntry): String? = toDefinition(entry)

    companion object {
        val allProper = listOf(
            MAPPED,
            INTERMEDIARY,
            OBF_MERGED,
            OBF_CLIENT,
            OBF_SERVER,
        )
    }
}

data class MatchAccuracy(val accuracy: Double) {
    companion object {
        val Exact = MatchAccuracy(1.0)
        val Fuzzy = MatchAccuracy(0.5)
    }
}

fun MatchAccuracy.isExact(): Boolean = this == MatchAccuracy.Exact
fun MatchAccuracy.isNotExact(): Boolean = this != MatchAccuracy.Exact

object MappingsQuery {
    private data class MemberResultMore<T : MappingsMember>(
        val parent: Class,
        val field: T,
        val parentDef: QueryDefinition,
        val fieldDef: QueryDefinition,
    ) {
        fun toSimplePair(): Pair<Class, T> = parent to field
    }

    fun errorNoResultsFound(type: MappingsEntryType?, searchKey: String) {
        val onlyClass = searchKey.onlyClass()

        throw when {
            onlyClass.firstOrNull()?.isDigit()?.not() == true && !onlyClass.isValidJavaIdentifier() ->
                NullPointerException("No results found! `$onlyClass` is not a valid java identifier!")
            type != METHOD && (searchKey.startsWith("func_") || searchKey.startsWith("method_")) ->
                NullPointerException("No results found! `$searchKey` looks like a method!")
            type != FIELD && searchKey.startsWith("field_") ->
                NullPointerException("No results found! `$searchKey` looks like a field!")
            type == CLASS && !searchKey.startsWith("class_") && searchKey.firstOrNull()?.isLowerCase() == true ->
                NullPointerException("No results found! `$searchKey` doesn't look like a class!")
            else -> NullPointerException("No results found!")
        }
    }

    fun MappingsEntry.searchDefinition(classKey: String, accuracy: MatchAccuracy): QueryDefinition? {
        return QueryDefinition.allProper.maxOfIgnoreNullSelf { it(this).matchWithSimilarity(classKey, accuracy)?.times(it.multiplier) }
    }

    fun MappingsEntry.searchWithSimilarity(classKey: String, accuracy: MatchAccuracy): Double? {
        return QueryDefinition.allProper.maxOfIgnoreNull { it(this).matchWithSimilarity(classKey, accuracy)?.times(it.multiplier) }
    }

    suspend fun queryClasses(context: QueryContext): QueryResult<MappingsContainer, ClassResultSequence> {
        val searchKey = context.searchKey
        val isSearchKeyWildcard = searchKey == "*"
        val mappings = context.provider.get()

        val results: Sequence<ResultHolder<Class>> = if (isSearchKeyWildcard) {
            mappings.classes.values.asSequence()
                .sortedBy { it.intermediaryName }
                .mapIndexed { index, entry -> entry hold (mappings.classes.size - index + 1) * 100.0 }
        } else {
            mappings.classes.values.asSequence()
                .mapNotNull { c -> c.searchWithSimilarity(searchKey, context.accuracy)?.let { c hold it } }
        }.sortedWith(compareByDescending<ResultHolder<Class>> { it.score }
            .thenBy { it.value.optimumName.onlyClass() })

        return QueryResult(mappings, results)
    }

    suspend fun queryFields(context: QueryContext): QueryResult<MappingsContainer, FieldResultSequence> =
        queryMember(context) { it.fields.asSequence() }

    suspend fun queryMethods(context: QueryContext): QueryResult<MappingsContainer, MethodResultSequence> =
        queryMember(context) { it.methods.asSequence() }

    suspend fun <T>  queryClassFilter(context: QueryContext, classKey: String, action: (Class, QueryDefinition) -> T): Sequence<T> {
        val mappings = context.provider.get()
        val hasClassFilter = classKey.isNotBlank()
        val isClassKeyWildcard = classKey == "*"
        return mappings.classes.values.asSequence().mapNotNull { c ->
            when {
                !hasClassFilter || isClassKeyWildcard -> QueryDefinition.WILDCARD
                else -> c.searchDefinition(classKey, context.accuracy)
            }?.let { action(c, it) }
        }
    }

    suspend fun <T : MappingsMember> queryMember(
        context: QueryContext,
        memberGetter: (Class) -> Sequence<T>,
    ): QueryResult<MappingsContainer, Sequence<ResultHolder<Pair<Class, T>>>> {
        val searchKey = context.searchKey
        val hasClassFilter = searchKey.contains('/')
        val classKey = if (hasClassFilter) searchKey.substringBeforeLast('/') else ""
        val fieldKey = searchKey.onlyClass()
        val isClassKeyWildcard = classKey == "*"
        val isFieldKeyWildcard = fieldKey == "*"
        val mappings = context.provider.get()
        
        val members: Sequence<MemberResultMore<T>> =  queryClassFilter(context, classKey) { c, parentDef ->
            if (isFieldKeyWildcard) {
                memberGetter(c).map { MemberResultMore(c, it, parentDef, QueryDefinition.WILDCARD) }
            } else {
                memberGetter(c).mapNotNull { f -> f.searchDefinition(fieldKey, context.accuracy)?.let { MemberResultMore(c, f, parentDef, it) } }
            }
        }.flatMap { it }.distinctBy { it.field }

        val sortedMembers: Sequence<ResultHolder<Pair<Class, T>>> = when {
            // Class and field both wildcard
            isFieldKeyWildcard && (!hasClassFilter || isClassKeyWildcard) -> {
                members.sortedWith(
                    compareBy<MemberResultMore<T>> { it.parent.optimumName.onlyClass() }
                        .thenBy { it.field.intermediaryName }
                        .reversed()
                ).mapIndexed { index, entry -> entry.toSimplePair() hold (index + 1.0) }
            }
            // Only field wildcard
            isFieldKeyWildcard -> members.map { it.toSimplePair() hold it.parentDef(it.parent).similarityOnNull(classKey) }
            // Has class filter
            hasClassFilter && !isClassKeyWildcard -> members.sortedWith(
                compareByDescending<MemberResultMore<T>> { it.fieldDef(it.field)!!.similarity(fieldKey) }
                    .thenByDescending { it.parentDef(it.parent).similarityOnNull(classKey) }
                    .reversed()
            ).mapIndexed { index, entry -> entry.toSimplePair() hold (index + 1.0) }
            // Simple search
            else -> members.map { it.toSimplePair() hold it.fieldDef(it.field)!!.similarity(fieldKey) }
        }.sortedWith(compareByDescending<ResultHolder<Pair<Class, T>>> { it.score }
            .thenBy { it.value.first.optimumName.onlyClass() }
            .thenBy { it.value.second.intermediaryName })

        return QueryResult(mappings, sortedMembers)
    }
}

data class ResultHolder<T>(
    val value: T,
    val score: Double,
)

infix fun <T> T.hold(score: Double): ResultHolder<T> = ResultHolder(this, score)

data class QueryContext(
    val provider: MappingsProvider,
    val searchKey: String,
    val accuracy: MatchAccuracy = MatchAccuracy.Exact,
)

fun <T> QueryResult<MappingsContainer, T>.toSimpleMappingsMetadata(): QueryResult<MappingsMetadata, T> =
    mapKey { it.toSimpleMappingsMetadata() }

data class QueryResult<A : MappingsMetadata, T>(
    val mappings: A,
    val value: T,
)

inline fun <A : MappingsMetadata, T, B : MappingsMetadata> QueryResult<A, T>.mapKey(transformer: (A) -> B): QueryResult<B, T> {
    return QueryResult(transformer(mappings), value)
}

inline fun <A : MappingsMetadata, T, V> QueryResult<A, T>.map(transformer: (T) -> V): QueryResult<A, V> {
    return QueryResult(mappings, transformer(value))
}
