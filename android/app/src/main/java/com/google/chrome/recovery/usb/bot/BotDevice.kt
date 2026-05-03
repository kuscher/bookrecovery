package com.google.chrome.recovery.usb.bot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BotDevice(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    private var tag = 0

    fun writeSectors(lba: Int, data: ByteArray): Boolean {
        val sectors = data.size / 512
        if (data.size % 512 != 0) return false

        // SCSI WRITE(10)
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // Signature "USBC"
        cbw.putInt(++tag)
        cbw.putInt(data.size) // DataTransferLength
        cbw.put(0x00.toByte()) // Flags (0 = out to device)
        cbw.put(0x00.toByte()) // LUN
        cbw.put(10.toByte()) // Command Length
        
        // SCSI payload
        cbw.put(0x2A.toByte()) // WRITE(10) opcode
        cbw.put(0x00.toByte())
        cbw.putInt(Integer.reverseBytes(lba)) // LBA is big endian in SCSI
        cbw.put(0x00.toByte())
        cbw.putShort(java.lang.Short.reverseBytes(sectors.toShort())) // Transfer length big endian
        cbw.put(0x00.toByte()) // Control
        
        // Pad to 31 bytes
        while (cbw.position() < 31) cbw.put(0x00.toByte())

        // Send CBW
        var result = connection.bulkTransfer(endpointOut, cbw.array(), 31, 5000)
        if (result != 31) return false

        // Send Data in 16KB chunks to avoid Android bulkTransfer limits
        val chunkSize = 16384
        var offset = 0
        while (offset < data.size) {
            val toWrite = Math.min(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + toWrite)
            result = connection.bulkTransfer(endpointOut, chunk, toWrite, 5000)
            if (result != toWrite) return false
            offset += toWrite
        }

        // Read CSW
        val csw = ByteArray(13)
        var cswRead = 0
        while (cswRead < 13) {
            val res = connection.bulkTransfer(endpointIn, csw, 13 - cswRead, 10000) // 10s timeout for flash writes
            if (res < 0) return false
            // Shift data if it was a short read, though CSW is usually one packet
            if (cswRead > 0) System.arraycopy(csw, 0, csw, cswRead, res)
            cswRead += res
        }
        return cswRead == 13 && csw[12] == 0.toByte() // Status == 0 (Passed)
    }

    fun readSectors(lba: Int, numSectors: Int): ByteArray? {
        val dataSize = numSectors * 512
        
        // SCSI READ(10)
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // Signature "USBC"
        cbw.putInt(++tag)
        cbw.putInt(dataSize) // DataTransferLength
        cbw.put(0x80.toByte()) // Flags (1 = in from device)
        cbw.put(0x00.toByte()) // LUN
        cbw.put(10.toByte()) // Command Length
        
        // SCSI payload
        cbw.put(0x28.toByte()) // READ(10) opcode
        cbw.put(0x00.toByte())
        cbw.putInt(Integer.reverseBytes(lba)) // LBA big endian
        cbw.put(0x00.toByte())
        cbw.putShort(java.lang.Short.reverseBytes(numSectors.toShort())) // length big endian
        cbw.put(0x00.toByte()) // Control
        
        while (cbw.position() < 31) cbw.put(0x00.toByte())

        if (connection.bulkTransfer(endpointOut, cbw.array(), 31, 5000) != 31) return null

        val data = ByteArray(dataSize)
        val chunkSize = 16384
        var offset = 0
        while (offset < dataSize) {
            val toRead = Math.min(chunkSize, dataSize - offset)
            val chunk = ByteArray(toRead)
            val result = connection.bulkTransfer(endpointIn, chunk, toRead, 5000)
            if (result != toRead) return null
            System.arraycopy(chunk, 0, data, offset, toRead)
            offset += toRead
        }

        val csw = ByteArray(13)
        var cswRead = 0
        while (cswRead < 13) {
            val res = connection.bulkTransfer(endpointIn, csw, 13 - cswRead, 5000)
            if (res < 0) return null
            if (cswRead > 0) System.arraycopy(csw, 0, csw, cswRead, res)
            cswRead += res
        }
        if (cswRead != 13 || csw[12] != 0.toByte()) return null

        return data
    }
}
