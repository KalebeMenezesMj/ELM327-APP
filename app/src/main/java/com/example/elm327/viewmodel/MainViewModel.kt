package com.example.elm327.viewmodel

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.elm327.bluetooth.BluetoothManager
import com.example.elm327.data.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val macAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class MainViewModel : ViewModel() {

    private val bluetoothManager = BluetoothManager()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    fun connect(device: BluetoothDevice) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            val result = bluetoothManager.connect(device)
            if (result.isSuccess) {
                _connectionState.value = ConnectionState.Connected(
                    deviceName = device.name ?: "Desconhecido",
                    macAddress = device.address
                )
                // Inicia leitura em coroutine paralela antes de enviar comandos
                launch {
                    bluetoothManager.startReading().collect { line ->
                        addMessage(line)
                    }
                    // Flow encerrou — conexão foi perdida
                    if (_connectionState.value is ConnectionState.Connected) {
                        _connectionState.value = ConnectionState.Error("Conexão perdida")
                    }
                }
                delay(200) // Aguarda leitura iniciar
                sendInitialCommands()
            } else {
                _connectionState.value = ConnectionState.Error(
                    result.exceptionOrNull()?.message ?: "Falha na conexão"
                )
            }
        }
    }

    private suspend fun sendInitialCommands() {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "0100")
        for (command in commands) {
            addMessage(">> $command")
            bluetoothManager.sendCommand(command)
            // ATZ precisa de mais tempo pois reseta o módulo
            val waitMs = if (command == "ATZ") 1500L else 300L
            delay(waitMs)
        }
    }

    fun sendCommand(command: String) {
        viewModelScope.launch {
            addMessage(">> $command")
            bluetoothManager.sendCommand(command)
        }
    }

    fun disconnect() {
        bluetoothManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun addMessage(text: String) {
        val message = Message(text = text)
        _messages.value = _messages.value + message
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothManager.disconnect()
    }
}
