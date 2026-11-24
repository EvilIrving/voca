package com.parley.speaker.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exportSchema 打开之后，当前版本必须真的有一份导出躺在 app/schemas 里。
 */
class ExportedSchemaTest {

    @Test
    fun `导出的表结构覆盖当前版本`() {
        val schema = schemaFile()
        assertTrue("先跑一次编译让 Room 导出表结构，期望落在 $schema", schema.isFile)

        val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject

        assertEquals(9, database.getValue("version").jsonPrimitive.int)
        val tables = database.getValue("entities").jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("turn", "vocab", "session", "model_catalog", "memory"), tables)
    }

    private fun schemaFile(): File {
        val relative = "schemas/com.parley.speaker.data.db.ParleyDatabase/9.json"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile } ?: File(relative)
    }
}
