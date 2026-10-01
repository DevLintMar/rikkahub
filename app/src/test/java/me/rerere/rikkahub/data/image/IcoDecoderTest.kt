package me.rerere.rikkahub.data.image

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class IcoDecoderTest {

    @Test
    fun `非法或空数据返回 null`() {
        assertNull(IcoDecoder.decodeIcoToBitmap(ByteArray(0)))
        assertNull(IcoDecoder.decodeIcoToBitmap(ByteArray(5)))
        // reserved != 0
        assertNull(IcoDecoder.decodeIcoToBitmap(byteArrayOf(1, 0, 1, 0, 1, 0)))
        // type != 1 && type != 2
        assertNull(IcoDecoder.decodeIcoToBitmap(byteArrayOf(0, 0, 3, 0, 1, 0)))
        // count == 0
        assertNull(IcoDecoder.decodeIcoToBitmap(byteArrayOf(0, 0, 1, 0, 0, 0)))
    }

    @Test
    fun `条目越界返回 null`() {
        val out = ByteArrayOutputStream()
        // ICONDIR: reserved=0, type=1, count=1
        out.write(byteArrayOf(0, 0, 1, 0, 1, 0))
        // ICONDIRENTRY: w=16, h=16, colors=0, res=0, planes=1, bpp=32, bytes=100, offset=500 (超出了文件大小)
        out.write(byteArrayOf(16, 16, 0, 0, 1, 0, 32, 0))
        out.write(byteArrayOf(100, 0, 0, 0)) // 100 bytes
        out.write(byteArrayOf(-12, 1, 0, 0))  // offset 500
        val bytes = out.toByteArray()
        assertNull(IcoDecoder.decodeIcoToBitmap(bytes))
    }

    @Test
    fun `零条目不崩溃`() {
        val bytes = byteArrayOf(0, 0, 1, 0, 0, 0)
        assertNull(IcoDecoder.decodeIcoToBitmap(bytes))
    }
}
