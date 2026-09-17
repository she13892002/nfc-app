package com.example.nfcapp

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

class NfcEmulationService : HostApduService() {

    companion object {
        @Volatile
        var currentNdefMessage: ByteArray = createTextNdefRecord("NFC App")

        fun createTextNdefRecord(text: String): ByteArray {
            val content = if (text.isEmpty()) "Empty" else text
            val langBytes = "en".toByteArray(Charsets.US_ASCII)
            val textBytes = content.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(1 + langBytes.size + textBytes.size)

            payload[0] = langBytes.size.toByte()
            System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
            System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)

            val header = 0xD1.toByte() // MB=1, ME=1, CF=0, SR=1, IL=0, TNF=1
            val type = "T".toByteArray(Charsets.US_ASCII)

            val ndefRecord = ByteArray(3 + type.size + payload.size)
            ndefRecord[0] = header
            ndefRecord[1] = type.size.toByte()
            ndefRecord[2] = payload.size.toByte()
            System.arraycopy(type, 0, ndefRecord, 3, type.size)
            System.arraycopy(payload, 0, ndefRecord, 3 + type.size, payload.size)

            return ndefRecord
        }

        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // CC File (Capability Container) for Type 4 Tag
        private val CAPABILITY_CONTAINER = byteArrayOf(
            0x00, 0x0F, // CCLEN: 15 bytes
            0x20,       // Mapping Version 2.0
            0x00, 0x3B, // MLe: Max Read 59 bytes
            0x00, 0x34, // MLc: Max Write 52 bytes
            0x04, 0x06, // T=NDEF Control TLV, L=6
            0xE1.toByte(), 0x04.toByte(), // File ID = E104
            0x0B.toByte(), 0xFE.toByte(), // Max NDEF File Size = 3070 bytes
            0x00,       // Read Access (00 = granted)
            0x00        // Write Access (00 = granted)
        )
    }

    private enum class SelectedFile { NONE, CC_FILE, NDEF_FILE }
    private var currentSelectedFile = SelectedFile.NONE

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return STATUS_FAILED

        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        // 1. SELECT 指令處理 (INS = 0xA4)
        if (ins == 0xA4) {
            // 選擇 NDEF Application (AID = D2760000850101)
            if (isNdefAppSelect(commandApdu)) {
                currentSelectedFile = SelectedFile.NONE
                return STATUS_SUCCESS
            }
            // 選擇 CC File (FID = E103)
            if (commandApdu.size >= 7 && commandApdu[5] == 0xE1.toByte() && commandApdu[6] == 0x03.toByte()) {
                currentSelectedFile = SelectedFile.CC_FILE
                return STATUS_SUCCESS
            }
            // 選擇 NDEF File (FID = E104)
            if (commandApdu.size >= 7 && commandApdu[5] == 0xE1.toByte() && commandApdu[6] == 0x04.toByte()) {
                currentSelectedFile = SelectedFile.NDEF_FILE
                return STATUS_SUCCESS
            }
            return STATUS_FAILED
        }

        // 2. READ BINARY 指令處理 (INS = 0xB0)
        if (ins == 0xB0) {
            val offset = (p1 shl 8) or p2
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

    private fun isNdefAppSelect(apdu: ByteArray): Boolean {
        val targetAid = byteArrayOf(0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte(), 0x01.toByte())
        if (apdu.size < 5 + targetAid.size) return false
        for (i in targetAid.indices) {
            if (apdu[5 + i] != targetAid[i]) return false
        }
        return true
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
