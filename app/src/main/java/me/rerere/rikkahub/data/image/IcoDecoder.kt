package me.rerere.rikkahub.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil 3 的 Windows ICO 图标解码器。
 *
 * Android 原生 [BitmapFactory] 与 Coil 官方组件均不支持 .ico 格式。
 * 本解码器解析 ICO 容器头 (ICONDIR)，提取出最佳分辨率的图像帧：
 * - 针对现代内嵌 PNG 帧：直接解码 PNG 字节流；
 * - 针对传统 DIB 帧：修正 ICO 专有的双倍高度掩码，构造标准位图头交由 [BitmapFactory]
 *   解码，并在必要时兜底直接提取 32 位 ARGB 像素阵列。
 */
class IcoDecoder(
    private val source: ImageSource,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.IO) {
        val bytes = source.source().readByteArray()
        val bitmap = decodeIcoToBitmap(bytes) ?: return@withContext null
        DecodeResult(
            image = bitmap.asImage(),
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val mimeType = result.mimeType
            if (mimeType != null && (
                    mimeType.equals("image/x-icon", ignoreCase = true) ||
                    mimeType.equals("image/vnd.microsoft.icon", ignoreCase = true)
                )
            ) {
                return IcoDecoder(result.source)
            }

            val isIco = runCatching {
                val source = result.source.source()
                if (source.request(4)) {
                    val buffer = source.buffer
                    buffer[0] == 0.toByte() && buffer[1] == 0.toByte() &&
                        buffer[2] == 1.toByte() && buffer[3] == 0.toByte()
                } else false
            }.getOrDefault(false)

            return if (isIco) IcoDecoder(result.source) else null
        }
    }

    companion object {
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        /**
         * 将 ICO 文件的原始二进制字节解析为最佳质量的 [Bitmap]。
         * 纯静态逻辑，供解码器与单测复用。
         */
        fun decodeIcoToBitmap(bytes: ByteArray): Bitmap? {
            if (bytes.size < 6) return null
            val reserved = readShortLE(bytes, 0)
            val type = readShortLE(bytes, 2)
            val count = readShortLE(bytes, 4)
            if (reserved != 0 || (type != 1 && type != 2) || count <= 0) return null

            var bestOffset = -1
            var bestLength = -1
            var bestScore = -1

            for (i in 0 until count) {
                val entryBase = 6 + i * 16
                if (entryBase + 16 > bytes.size) break

                val rawW = bytes[entryBase].toInt() and 0xFF
                val rawH = bytes[entryBase + 1].toInt() and 0xFF
                val w = if (rawW == 0) 256 else rawW
                val h = if (rawH == 0) 256 else rawH
                val bpp = readShortLE(bytes, entryBase + 6) and 0xFFFF
                val bytesInRes = readIntLE(bytes, entryBase + 8)
                val offset = readIntLE(bytes, entryBase + 12)

                // 评分策略：优先高分辨率，同分辨率优先高色深
                val score = w * h * 100 + bpp
                if (score > bestScore && offset in 0 until bytes.size && bytesInRes > 0) {
                    bestScore = score
                    bestOffset = offset
                    bestLength = bytesInRes
                }
            }

            if (bestOffset < 0 || bestLength <= 0 || bestOffset + bestLength > bytes.size) {
                return null
            }

            val entryBytes = bytes.copyOfRange(bestOffset, bestOffset + bestLength)
            return decodeEntry(entryBytes)
        }

        private fun decodeEntry(entryBytes: ByteArray): Bitmap? {
            if (entryBytes.size < 4) return null

            // 1. 内嵌 PNG 检测
            if (entryBytes.size >= 8 && entryBytes.copyOfRange(0, 8).contentEquals(PNG_HEADER)) {
                return BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.size)
            }

            // 2. 传统 DIB (BITMAPINFOHEADER)
            val headerSize = readIntLE(entryBytes, 0)
            if (headerSize >= 40 && entryBytes.size >= headerSize) {
                val width = readIntLE(entryBytes, 4)
                val rawHeight = readIntLE(entryBytes, 8)
                val realHeight = rawHeight / 2 // ICO 中高度为双倍（含掩码）
                val bpp = readShortLE(entryBytes, 14) and 0xFFFF

                if (width > 0 && realHeight > 0) {
                    // 先尝试构造标准 BMP 文件头
                    val correctedDib = entryBytes.copyOf()
                    writeIntLE(correctedDib, 8, realHeight)

                    val paletteColors = if (bpp <= 8) {
                        val declared = readIntLE(entryBytes, 32)
                        if (declared == 0) 1 shl bpp else declared
                    } else 0
                    val offsetBits = 14 + headerSize + paletteColors * 4

                    val bmpFile = ByteArray(14 + correctedDib.size)
                    bmpFile[0] = 0x42.toByte() // B
                    bmpFile[1] = 0x4D.toByte() // M
                    writeIntLE(bmpFile, 2, bmpFile.size)
                    writeIntLE(bmpFile, 10, offsetBits)
                    System.arraycopy(correctedDib, 0, bmpFile, 14, correctedDib.size)

                    val bmp = BitmapFactory.decodeByteArray(bmpFile, 0, bmpFile.size)
                    if (bmp != null) return bmp

                    // 兜底：32 位 ARGB DIB 像素手动重组（自底向上扫描）
                    if (bpp == 32 && entryBytes.size >= headerSize + width * realHeight * 4) {
                        return decode32BitBgraPixels(entryBytes, headerSize, width, realHeight)
                    }
                }
            }

            // 3. 通用尝试
            return BitmapFactory.decodeByteArray(entryBytes, 0, entryBytes.size)
        }

        private fun decode32BitBgraPixels(
            dib: ByteArray,
            offset: Int,
            width: Int,
            height: Int,
        ): Bitmap? = runCatching {
            val pixels = IntArray(width * height)
            var srcIndex = offset
            // BMP 默认自底向上存储
            for (row in height - 1 downTo 0) {
                val rowStart = row * width
                for (col in 0 until width) {
                    if (srcIndex + 4 > dib.size) break
                    val b = dib[srcIndex].toInt() and 0xFF
                    val g = dib[srcIndex + 1].toInt() and 0xFF
                    val r = dib[srcIndex + 2].toInt() and 0xFF
                    val a = dib[srcIndex + 3].toInt() and 0xFF
                    pixels[rowStart + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    srcIndex += 4
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()

        private fun readShortLE(bytes: ByteArray, offset: Int): Int {
            if (offset + 1 >= bytes.size) return 0
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            return (b1 shl 8) or b0
        }

        private fun readIntLE(bytes: ByteArray, offset: Int): Int {
            if (offset + 3 >= bytes.size) return 0
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            val b2 = bytes[offset + 2].toInt() and 0xFF
            val b3 = bytes[offset + 3].toInt() and 0xFF
            return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }

        private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
            if (offset + 3 >= bytes.size) return
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
