package com.altitudeplusplus.android.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Small helpers for walking untyped ViewLift responses, like the Swift `JSON` type. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.array: List<JsonElement>? get() = this as? JsonArray

/** Depth-first search for the first value stored under [key]. */
fun JsonElement?.first(key: String): JsonElement? = when (this) {
    is JsonObject -> this[key]?.takeUnless { it is JsonNull } ?: values.firstNotNullOfOrNull { it.first(key) }
    is JsonArray -> firstNotNullOfOrNull { it.first(key) }
    else -> null
}
