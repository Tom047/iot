package com.example.iot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothHelper(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null

    /**
     * Check if device supports Bluetooth
     */
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Get the list of paired devices (for Classic Bluetooth).
     * For BLE, scanning procedure is different.
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    /**
     * Connect to the chosen BluetoothDevice using the first UUID we find.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            // If your STM32 uses a well-known UUID, you can place it here:
            // val myUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            // Otherwise, you can attempt the first available UUID in device.uuids
            val uuid = device.uuids?.firstOrNull()?.uuid
                ?: UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP default

            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothSocket?.connect()
            return@withContext true
        } catch (e: IOException) {
            e.printStackTrace()
            bluetoothSocket?.close()
            bluetoothSocket = null
            return@withContext false
        }
    }

    /**
     * Send data to the STM32 board once connected
     */
    suspend fun sendData(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (bluetoothSocket == null) {
            return@withContext false
        }
        return@withContext try {
            bluetoothSocket?.outputStream?.write(data)
            bluetoothSocket?.outputStream?.flush()
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Close the socket
     */
    fun closeConnection() {
        bluetoothSocket?.close()
        bluetoothSocket = null
    }
}