package com.hrv.biofeedback.domain.ecg

object Ecg24Bit {
    const val BYTE_COUNT = 3
    const val MIN_VALUE = -8_388_608
    const val MAX_VALUE = 8_388_607

    fun encodeLittleEndian(value: Int): ByteArray {
        require(value in MIN_VALUE..MAX_VALUE) {
            "ECG sample $value is outside signed 24-bit range"
        }
        return byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte()
        )
    }

    fun decodeLittleEndian(bytes: ByteArray, offset: Int = 0): Int {
        require(offset >= 0) { "Byte offset cannot be negative" }
        require(bytes.size - offset >= BYTE_COUNT) {
            "Three bytes are required to decode an ECG sample"
        }

        val unsigned = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)
        return if (unsigned and 0x80_0000 != 0) {
            unsigned - 0x100_0000
        } else {
            unsigned
        }
    }
}
