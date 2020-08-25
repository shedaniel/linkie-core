package me.shedaniel.linkie

import kotlinx.serialization.Serializable
import me.shedaniel.linkie.utils.info

@Serializable
data class MappingsContainer(
        val version: String,
        val classes: MutableList<Class> = mutableListOf(),
        val name: String,
        var mappingSource: MappingSource? = null,
        var namespace: String? = null
) {
    fun getClass(intermediaryName: String): Class? =
            classes.firstOrNull { it.intermediaryName == intermediaryName }

    fun getOrCreateClass(intermediaryName: String): Class =
            getClass(intermediaryName) ?: Class(intermediaryName).also { classes.add(it) }

    fun prettyPrint() {
        buildString {
            classes.forEach {
                it.apply {
                    append("$intermediaryName: $mappedName\n")
                    methods.forEach {
                        it.apply {
                            append("  $intermediaryName $intermediaryDesc: $mappedName $mappedDesc\n")
                        }
                    }
                    fields.forEach {
                        it.apply {
                            append("  $intermediaryName $intermediaryDesc: $mappedName $mappedDesc\n")
                        }
                    }
                    append("\n")
                }
            }
        }.also { info(it) }
    }

    @Serializable
    @Suppress("unused")
    enum class MappingSource {
        MCP_SRG,
        MCP_TSRG,
        MOJANG,
        YARN_V1,
        YARN_V2,
        SPIGOT,
        ENGIMA;

        override fun toString(): String = name.toLowerCase().split("_").joinToString(" ") { it.capitalize() }
    }
}

fun MappingsContainer.getClassByObfName(obf: String): Class? {
    classes.forEach {
        if (it.obfName.isMerged()) {
            if (it.obfName.merged.equals(obf, ignoreCase = false))
                return it
        } else if (it.obfName.client.equals(obf, ignoreCase = false))
            return it
        else if (it.obfName.server.equals(obf, ignoreCase = false))
            return it
    }
    return null
}

fun Class.getMethodByObfName(obf: String): Method? {
    methods.forEach {
        if (it.obfName.isMerged()) {
            if (it.obfName.merged.equals(obf, ignoreCase = false))
                return it
        } else if (it.obfName.client.equals(obf, ignoreCase = false))
            return it
        else if (it.obfName.server.equals(obf, ignoreCase = false))
            return it
    }
    return null
}

fun Class.getMethodByObfNameAndDesc(obf: String, desc: String): Method? {
    methods.filter {
        if (it.obfName.isMerged()) {
            if (it.obfName.merged.equals(obf, ignoreCase = false))
                return@filter true
        } else if (it.obfName.client.equals(obf, ignoreCase = false))
            return@filter true
        else if (it.obfName.server.equals(obf, ignoreCase = false))
            return@filter true
        return@filter false
    }.forEach {
        if (it.obfDesc.isMerged()) {
            if (it.obfDesc.merged.equals(desc, ignoreCase = false))
                return it
        } else if (it.obfDesc.client.equals(desc, ignoreCase = false))
            return it
        else if (it.obfDesc.server.equals(desc, ignoreCase = false))
            return it
    }
    return null
}

fun Class.getFieldByObfName(obf: String): Field? {
    fields.forEach {
        if (it.obfName.isMerged()) {
            if (it.obfName.merged.equals(obf, ignoreCase = false))
                return it
        } else if (it.obfName.client.equals(obf, ignoreCase = false))
            return it
        else if (it.obfName.server.equals(obf, ignoreCase = false))
            return it
    }
    return null
}

@Serializable
data class Class(
        val intermediaryName: String,
        val obfName: Obf = Obf(),
        var mappedName: String? = null,
        val methods: MutableList<Method> = mutableListOf(),
        val fields: MutableList<Field> = mutableListOf()
) {
    fun getMethod(intermediaryName: String): Method? =
            methods.firstOrNull { it.intermediaryName == intermediaryName }

    fun getOrCreateMethod(intermediaryName: String, intermediaryDesc: String): Method =
            getMethod(intermediaryName) ?: Method(intermediaryName, intermediaryDesc).also { methods.add(it) }

    fun getField(intermediaryName: String): Field? =
            fields.firstOrNull { it.intermediaryName == intermediaryName }

    fun getOrCreateField(intermediaryName: String, intermediaryDesc: String): Field =
            getField(intermediaryName) ?: Field(intermediaryName, intermediaryDesc).also { fields.add(it) }
}

@Serializable
data class Method(
        val intermediaryName: String,
        val intermediaryDesc: String,
        val obfName: Obf = Obf(),
        val obfDesc: Obf = Obf(),
        var mappedName: String? = null,
        var mappedDesc: String? = null
)

@Serializable
data class Field(
        val intermediaryName: String,
        val intermediaryDesc: String,
        val obfName: Obf = Obf(),
        val obfDesc: Obf = Obf(),
        var mappedName: String? = null,
        var mappedDesc: String? = null
)

@Serializable
data class Obf(
        var client: String? = null,
        var server: String? = null,
        var merged: String? = null
) {
    fun list(): List<String> {
        val list = mutableListOf<String>()
        if (client != null) list.add(client!!)
        if (server != null) list.add(server!!)
        if (merged != null) list.add(merged!!)
        return list
    }

    fun isMerged(): Boolean = merged != null
    fun isEmpty(): Boolean = client == null && server == null && merged == null
}

@Serializable
data class YarnBuild(
        val gameVersion: String,
        val separator: String,
        val build: Int,
        val maven: String,
        val version: String,
        val stable: Boolean
)