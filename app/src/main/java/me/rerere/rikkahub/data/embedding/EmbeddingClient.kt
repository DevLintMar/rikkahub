package me.rerere.rikkahub.data.embedding

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "EmbeddingClient"

@Serializable
private data class EmbeddingRequest(
    val input: List<String>,
    val model: String,
)

class EmbeddingClient(private val okHttpClient: OkHttpClient) {

    /**
     * Calls `POST {baseUrl}/embeddings` (OpenAI-compatible) and returns one embedding
     * vector per input text. Items that fail to parse are returned as null; the whole
     * call returns a list of nulls on error and never throws.
     */
    suspend fun computeEmbeddings(
        texts: List<String>,
        model: String,
        baseUrl: String,
        apiKey: String,
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        try {
            val body = json.encodeToString(EmbeddingRequest(input = texts, model = model))
            val request = Request.Builder()
                .url("$baseUrl/embeddings")
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "computeEmbeddings failed: HTTP ${it.code} from $baseUrl")
                    return@withContext texts.map { null }
                }
                val responseBody = it.body?.string() ?: return@withContext texts.map { null }
                parseEmbeddingResponse(responseBody, texts.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "computeEmbeddings failed", e)
            texts.map { null }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pure parser for an OpenAI-compatible `/embeddings` response body.
         * Returns a list of exactly [count] items; missing/extra data entries are
         * padded with null / truncated respectively.
         */
        fun parseEmbeddingResponse(body: String, count: Int): List<FloatArray?> {
            return try {
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?: return List(count) { null }
                data.map { item ->
                    val arr = item.jsonObject["embedding"]?.jsonArray ?: return@map null
                    FloatArray(arr.size) { i -> arr[i].jsonPrimitive.float }
                }.let { list -> (list + List((count - list.size).coerceAtLeast(0)) { null }).take(count) }
            } catch (e: Exception) {
                List(count) { null }
            }
        }
    }
}
