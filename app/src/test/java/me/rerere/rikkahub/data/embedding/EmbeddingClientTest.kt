package me.rerere.rikkahub.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingClientTest {
    @Test
    fun `parses embeddings from openai response`() {
        val body = """{"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}"""
        val out = EmbeddingClient.parseEmbeddingResponse(body, 2)
        assertEquals(2, out.size)
        assertEquals(0.1f, out[0]!![0], 1e-6f)
        assertEquals(0.4f, out[1]!![1], 1e-6f)
    }

    @Test
    fun `malformed response yields nulls`() {
        val out = EmbeddingClient.parseEmbeddingResponse("not json", 1)
        assertEquals(1, out.size)
        assertNull(out[0])
    }
}
