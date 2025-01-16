package com.example.iot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class BluetoothHelper(private val context: Context) {
    companion object {
        private val MY_SERVICE_UUID = UUID.fromString("51311102-030e-485f-b122-f8f381aa84ed")
        private val MY_CHARACTERISTIC_UUID = UUID.fromString("485f4145-52b9-4644-af1f-7a6b9322490f")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val devices = mutableListOf<BluetoothDevice>()

    // StateFlow that emits the current list of devices
    private val _scannedDevicesFlow = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevicesFlow: StateFlow<List<BluetoothDevice>> get() = _scannedDevicesFlow

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> get() = _isConnected

    // Store reference to the characteristic where you’ll write data
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

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
     * Old function
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    fun startScanning() {
        try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

            // Optionally: stop previous scanning if you want to restart clean
            // scanner.stopScan(scanCallback)

            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("BLEManager", "Security exception while scanning", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device != null && !devices.contains(device)) {
                devices.add(device)
                // We found a new device => update the StateFlow list
                _scannedDevicesFlow.value =
                    devices.toList().distinctBy { it.address }.sortedBy { it.address }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEManager", "Scan failed with error code: $errorCode")
        }
    }

    fun getScannedDevices(): List<BluetoothDevice> {
        return devices
            .distinctBy { it.address }
            .sortedBy { it.address }
    }

    // GATT Connect method (asynchronous!)
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // Connect as a GATT client. `false` => autoConnect = false (recommended).
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    // Our GATT Callback to handle connection changes, service discovery, reads/writes, etc.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Connected to GATT server. Discovering services...")
                _isConnected.value = true
                try {
                    gatt.discoverServices()  // async => triggers onServicesDiscovered
                } catch (e: SecurityException) {
                    Log.e("BLEManager", "Security exception while scanning", e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Disconnected from GATT server.")
                _isConnected.value = false
                closeConnection() // Cleanup
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Services discovered.")
                // TODO: Find your service and characteristic here
                val myService = gatt.getService(MY_SERVICE_UUID)
                if (myService != null) {
                    writeCharacteristic = myService.getCharacteristic(MY_CHARACTERISTIC_UUID)
                    if (writeCharacteristic == null) {
                        Log.e("BLE", "Characteristic not found in this service!")
                    } else {
                        Log.d("BLE", "Characteristic found! Ready to write data.")
                    }
                } else {
                    Log.e("BLE", "Service not found on this device.")
                }
            } else {
                Log.e("BLE", "onServicesDiscovered received error status: $status")
            }
        }

        // Called when a characteristic write completes
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Characteristic write successful!")
            } else {
                Log.e("BLE", "Characteristic write failed, status: $status")
            }
        }

        // If you need to handle reads or notifications/indications, override
        // onCharacteristicRead, onCharacteristicChanged, etc.
    }

    /**
     * Send data by writing it into the discovered characteristic.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendData(data: ByteArray) = withContext(Dispatchers.IO) {
        val gatt = bluetoothGatt ?: run {
            Log.e("BLE", "BluetoothGatt is null")
            return@withContext false
        }
        val characteristic = writeCharacteristic ?: run {
            Log.e("BLE", "Write characteristic is null")
            return@withContext false
        }

        Log.d("BLE", "Sending data: ${data.joinToString(", ") { it.toString() }}")

        val writeResult = gatt.writeCharacteristic(
            characteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

        Log.d("BLE", "Write operation result: $writeResult")

        return@withContext writeResult
    }

    fun closeConnection() {
        try {
            bluetoothGatt?.close()
            bluetoothGatt = null
            _isConnected.value = false
        } catch (e: SecurityException) {
            Log.e("BLEManager", "Security exception while scanning", e)
        }
    }
}