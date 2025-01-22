package com.example.iot

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.iot.models.Note
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(bluetoothHelper: BluetoothHelper) {
    val scope = rememberCoroutineScope()

    val isBluetoothSupported by remember { mutableStateOf(bluetoothHelper.isBluetoothSupported()) }
    val isBluetoothEnabled by remember { mutableStateOf(bluetoothHelper.isBluetoothEnabled()) }

    // Collect the list of scanned devices from the Flow. This will update in real-time!
    val scannedDevices by bluetoothHelper.scannedDevicesFlow.collectAsState()

    // For storing the currently selected device and connection state
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    //var isConnected by remember { mutableStateOf(false) }
    val isConnected by bluetoothHelper.isConnected.collectAsState()

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
                items(scannedDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            // Attempt connection
                            scope.launch {
                                selectedDevice = device
                                bluetoothHelper.connect(device)
                            }
                        }
                    ) {
                        Text(
                            text = device.address,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            if (selectedDevice != null) {
                Text("Selected Device: ${selectedDevice!!.address}")
                Text("Connection Status: ${if (isConnected) "Connected" else "Disconnected"}")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display notes as clickable buttons
            Text("Available Notes:")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())  // Enable horizontal scrolling
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