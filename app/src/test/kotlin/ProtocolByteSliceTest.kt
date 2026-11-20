import com.google.common.truth.Truth.assertWithMessage
import com.mob.lee.fastair.io.ProtocolByte
import com.mob.lee.fastair.io.ProtocolType
import org.junit.Test

class ProtocolByteSliceTest {

    // The received file was corrupted because the writer copied from array()[0],
    // which is the type byte, not the first byte of content. The correct write
    // starts at arrayOffset() + position().
    @Test
    fun headerPrecedesBodyInBackingArray() {
        val content = "9527".toByteArray()
        val slice = ProtocolByte.wrap(content, ProtocolType.B).bytes()
        val writeStart = slice.arrayOffset() + slice.position()

        assertWithMessage("write start").that(writeStart).isEqualTo(ProtocolByte.HEAD_LENGTH)
        assertWithMessage("array()[0] is the type byte, not content")
            .that(slice.array()[0]).isEqualTo(ProtocolType.B)
        assertWithMessage("array()[0] must differ from the first content byte")
            .that(slice.array()[0]).isNotEqualTo(content[0])
        assertWithMessage("content actually begins at write start")
            .that(slice.array()[writeStart]).isEqualTo(content[0])
    }

    @Test
    fun bodyMatchesSentContentForEveryPacketType() {
        val longValue = ByteArray(8).also { it[6] = 0x25; it[7] = 0x37 } // 9527 big-endian
        val blob = ByteArray(8192) { it.toByte() }

        val cases = linkedMapOf(
            "long" to (ProtocolByte.long(9527) to longValue),
            "string" to (ProtocolByte.string("photo.jpg") to "photo.jpg".toByteArray()),
            "bytes" to (ProtocolByte.bytes(blob) to blob)
        )

        cases.forEach { (label, pair) ->
            val (packet, expected) = pair
            val slice = packet.bytes()
            // Snapshot before reading: a relative get() advances position, so the
            // write start is only meaningful at the moment of the write.
            val writeStart = slice.arrayOffset() + slice.position()
            val written = ByteArray(slice.remaining())
            slice.get(written)

            assertWithMessage("$label: write start").that(writeStart).isEqualTo(ProtocolByte.HEAD_LENGTH)
            assertWithMessage("$label: body").that(written).isEqualTo(expected)
        }
    }

    // getString() reads through bytes(); ignoring the offset would glue header
    // bytes to the front of the file name.
    @Test
    fun stringContentStartsAfterHeader() {
        val packet = ProtocolByte.string("report.pdf")

        assertWithMessage("type").that(packet.getType()).isEqualTo(ProtocolType.W)
        assertWithMessage("name").that(packet.getString()).isEqualTo("report.pdf")
        val slice = packet.bytes()
        assertWithMessage("write start")
            .that(slice.arrayOffset() + slice.position()).isEqualTo(ProtocolByte.HEAD_LENGTH)
    }

    // A short tail packet must yield exactly its own bytes.
    @Test
    fun partialBodyYieldsOnlyItsOwnBytes() {
        val partial = ByteArray(200) { (it % 251).toByte() }.copyOf(100)
        val slice = ProtocolByte.wrap(partial, ProtocolType.B).bytes()
        val written = ByteArray(slice.remaining())
        slice.get(written)

        assertWithMessage("size").that(written.size).isEqualTo(100)
        assertWithMessage("body").that(written).isEqualTo(partial)
    }
}
