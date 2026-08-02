package me.rerere.rikkahub.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingIndexerTest {
    @Test
    fun `float array round trips through bytes`() {
        val floats = floatArrayOf(0.1f, -0.5f, 1.0f, 0.0f)
        val out = EmbeddingIndexer.bytesToFloats(EmbeddingIndexer.floatsToBytes(floats))
        assertEquals(floats.size, out.size)
        for (i in floats.indices) assertEquals(floats[i], out[i], 1e-6f)
    }

    @Test
    fun `identical vectors have cosine 1`() {
        val v = floatArrayOf(1f, 0f, 2f)
        assertEquals(1f, EmbeddingIndexer.cosineSimilarity(v, v), 1e-6f)
    }

    @Test
    fun `orthogonal vectors have cosine 0`() {
        assertEquals(0f, EmbeddingIndexer.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `zero vector yields cosine 0`() {
        assertEquals(0f, EmbeddingIndexer.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 1e-6f)
    }
}
