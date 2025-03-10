package com.example.myapplication.ultils

import org.json.JSONArray


/**
 * Chuyển đổi FloatArray thành chuỗi JSON để lưu vào cơ sở dữ liệu
 */
fun floatArrayToJson(floatArray: FloatArray): String {
    return JSONArray(floatArray).toString()
}


/**
 * Chuyển đổi chuỗi JSON từ cơ sở dữ liệu về FloatArray
 */
fun jsonToFloatArray(json: String): FloatArray {
    val jsonArray = JSONArray(json)
    val floatArray = FloatArray(jsonArray.length())
    for (i in 0 until jsonArray.length()) {
        floatArray[i] = jsonArray.getDouble(i).toFloat()
    }
    return floatArray
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors must have the same length" }

    var dotProduct = 0f
    var normA = 0f
    var normB = 0f

    for (i in a.indices) {
        dotProduct += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }

    return if (normA == 0f || normB == 0f) 0f else dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
}
