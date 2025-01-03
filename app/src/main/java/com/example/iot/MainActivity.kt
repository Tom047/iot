package com.example.iot

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.iot.models.Note
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothHelper: BluetoothHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothHelper = BluetoothHelper(this)

        setContent {
            MaterialTheme {
                MainScreen(bluetoothHelper)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(bluetoothHelper: BluetoothHelper) {
    val scope = rememberCoroutineScope()
    val isBluetoothSupported by remember { mutableStateOf(bluetoothHelper.isBluetoothSupported()) }
    val isBluetoothEnabled by remember { mutableStateOf(bluetoothHelper.isBluetoothEnabled()) }

    // For storing the list of paired devices (classic BT).
    var pairedDevices by remember { mutableStateOf(listOf<BluetoothDevice>()) }

    // For storing the currently selected device and connection state
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var isConnected by remember { mutableStateOf(false) }

    // Request multiple permissions if needed (e.g. for scanning, location, connect).
    val neededPermissions = mutableListOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).filter {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || // For newer versions
                ContextCompat.checkSelfPermission(
                    LocalContext.current, it
                ) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsGranted ->
        // Once user responds to the permissions, proceed if granted
        if (permissionsGranted.values.all { it }) {
            // Reload the paired devices after permissions are granted
            pairedDevices = bluetoothHelper.getPairedDevices().toList()
        }
    }

    // On first composition, request permissions if needed
    LaunchedEffect(key1 = "permissions") {
        if (neededPermissions.isNotEmpty()) {
            permissionsLauncher.launch(neededPermissions)
        } else {
            // If no permissions needed, load the paired devices
            pairedDevices = bluetoothHelper.getPairedDevices().toList()
        }
    }

    // A list of notes for demonstration
    val availableNotes = listOf(
        Note("C", 60),
        Note("D", 62),
        Note("E", 64),
        Note("F", 65),
        Note("G", 67),
        Note("A", 69),
        Note("B", 71),
        Note("C (high)", 72)
    )

    // A sequence of notes to send
    var noteSequence by remember { mutableStateOf(listOf<Note>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "STM32 Music App") }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isBluetoothSupported) {
                Text("Bluetooth not supported on this device.")
                return@Column
            }
            if (!isBluetoothEnabled) {
                Text("Please enable Bluetooth first.")
            }

            Text("Paired devices:")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(pairedDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            // Attempt connection
                            scope.launch {
                                selectedDevice = device
                                val success = bluetoothHelper.connect(device)
                                isConnected = success
                            }
                        }
                    ) {
                        Text(
                            text = "${device.name} - ${device.address}",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            if (selectedDevice != null) {
                Text("Selected Device: ${selectedDevice!!.name}")
                Text("Connection Status: ${if (isConnected) "Connected" else "Disconnected"}")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display notes as clickable buttons
            Text("Available Notes:")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                availableNotes.forEach { note ->
                    Button(onClick = {
                        // Add note to the sequence
                        noteSequence = noteSequence + note
                    }) {
                        Text(note.name)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display the note sequence
            if (noteSequence.isNotEmpty()) {
                Text("Note Sequence:")
                Text(noteSequence.joinToString { it.name })

                Spacer(modifier = Modifier.height(8.dp))

                // Button to send note sequence
                Button(
                    onClick = {
                        if (isConnected) {
                            scope.launch {
                                // Convert note pitches to bytes
                                val data = noteSequence.map { it.pitch }.toByteArray()
                                // Send the data to the STM32
                                bluetoothHelper.sendData(data)
                            }
                        }
                    }
                ) {
                    Text("Send Sequence to STM32")
                }
            }

            // Optionally, button to clear the sequence
            if (noteSequence.isNotEmpty()) {
                Button(
                    onClick = {
                        noteSequence = emptyList()
                    }
                ) {
                    Text("Clear Sequence")
                }
            }

            // Optionally, close the connection
            if (isConnected) {
                Button(
                    onClick = {
                        bluetoothHelper.closeConnection()
                        isConnected = false
                        selectedDevice = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}