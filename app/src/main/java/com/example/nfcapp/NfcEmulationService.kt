package com.example.nfcapp

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class NfcEmulationService : HostApduService() {

    companion object {
        @Volatile
        var currentNdefMessage: ByteArray = createTextNdefRecord("")

        fun createTextNdefRecord(text: String): ByteArray {
            if (text.isEmpty()) return byteArrayOf()
            val langBytes = "en".toByteArray(Charsets.US_ASCII)
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + langBytes.size + textBytes.size)

            payload[0] = langBytes.size.toByte()
            System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
            System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)

            val header = 0xD1.toByte()
            val type = "T".toByteArray(Charsets.US_ASCII)

            val ndefRecord = ByteArray(3 + type.size + payload.size)
            ndefRecord[0] = header
            ndefRecord[1] = type.size.toByte()
            ndefRecord[2] = payload.size.toByte()
            System.arraycopy(type, 0, ndefRecord, 3, type.size)
            System.arraycopy(payload, 0, ndefRecord, 3 + type.size, payload.size)

            return ndefRecord
        }

        private val APDU_SELECT_NDEF_APP = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x07.toByte(),
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte()
        )
        private val APDU_SELECT_CC_FILE = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x02.toByte(), 0xE1.toByte(), 0x03.toByte())
        private val APDU_SELECT_NDEF_FILE = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x02.toByte(), 0xE1.toByte(), 0x04.toByte())

        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        private val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06,
            0xE1.toByte(), 0x04.toByte(), 0x0B.toByte(), 0xFE.toByte(), 0x00, 0x00
        )
    }

    private enum class SelectedFile { NONE, CC_FILE, NDEF_FILE }
    private var currentSelectedFile = SelectedFile.NONE

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED

        if (commandApdu.contentEquals(APDU_SELECT_NDEF_APP)) {
            currentSelectedFile = SelectedFile.NONE
            return STATUS_SUCCESS
        }
        if (commandApdu.contentEquals(APDU_SELECT_CC_FILE)) {
            currentSelectedFile = SelectedFile.CC_FILE
            return STATUS_SUCCESS
        }
        if (commandApdu.contentEquals(APDU_SELECT_NDEF_FILE)) {
            currentSelectedFile = SelectedFile.NDEF_FILE
            return STATUS_SUCCESS
        }

        if (commandApdu.size >= 4 && commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xB0.toByte()) {
            val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or (commandApdu[3].toInt() and 0xFF)
            val length = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0

            return when (currentSelectedFile) {
                SelectedFile.CC_FILE -> readBuffer(CAPABILITY_CONTAINER, offset, length)
                SelectedFile.NDEF_FILE -> {
                    val fullNdefFile = buildFullNdefFile(currentNdefMessage)
                    readBuffer(fullNdefFile, offset, length)
                }
                SelectedFile.NONE -> STATUS_FAILED
            }
        }
        return STATUS_FAILED
    }

    override fun onDeactivated(reason: Int) {
        currentSelectedFile = SelectedFile.NONE
    }

    private fun buildFullNdefFile(payload: ByteArray): ByteArray {
        val nlen = payload.size
        val file = ByteArray(2 + nlen)
        file[0] = ((nlen shr 8) and 0xFF).toByte()
        file[1] = (nlen and 0xFF).toByte()
        System.arraycopy(payload, 0, file, 2, nlen)
        return file
    }

    private fun readBuffer(buffer: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset >= buffer.size) return STATUS_FAILED
        var readLen = if (length == 0) buffer.size - offset else length
        if (offset + readLen > buffer.size) readLen = buffer.size - offset

        val result = ByteArray(readLen + 2)
        System.arraycopy(buffer, offset, result, 0, readLen)
        result[readLen] = STATUS_SUCCESS[0]
        result[readLen + 1] = STATUS_SUCCESS[1]
        return result
    }
}
