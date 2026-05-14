package com.example.elm327.viewmodel

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.elm327.bluetooth.BluetoothManager
import com.example.elm327.bluetooth.BluetoothManager.ResponseType
import com.example.elm327.data.Message
import com.example.elm327.data.MessageType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Estado de conexão
// ─────────────────────────────────────────────────────────────────────────────

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val attempt: Int = 1) : ConnectionState()
    data class Connected(val deviceName: String, val macAddress: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class MainViewModel : ViewModel() {

    private val bluetoothManager = BluetoothManager()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    /** Último comando enviado pelo usuário — usado para re-envio em caso de STOPPED. */
    private var lastUserCommand: String? = null

    /** Job do loop de leitura contínua. */
    private var readingJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Conexão
    // ─────────────────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice, bluetoothAdapter: BluetoothAdapter) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting(attempt = 1)
            addMessage("Conectando a ${device.name ?: device.address}…", MessageType.SYSTEM)
            addMessage("(Tentativas: padrão seguro → inseguro → canal RFCOMM 1)", MessageType.SYSTEM)

            val result = bluetoothManager.connect(device, bluetoothAdapter)

            if (result.isSuccess) {
                @Suppress("MissingPermission")
                _connectionState.value = ConnectionState.Connected(
                    deviceName = device.name ?: "Desconhecido",
                    macAddress = device.address
                )
                addMessage("Conectado! Aguardando estabilização…", MessageType.SYSTEM)

                // Inicia leitura ANTES de enviar comandos — assim nenhuma
                // resposta é perdida (padrão AndrOBD).
                startReadingLoop()

                // Pequena janela para o coroutine de leitura ser agendado
                // no Dispatcher.IO antes de começarmos a enviar.
                delay(200)

                sendInitialCommands()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Falha desconhecida"
                _connectionState.value = ConnectionState.Error(msg)
                addMessage("Falha na conexão: $msg", MessageType.ERROR)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sequência de inicialização AT
    // Ordem e timing baseados em análise de AndrOBD, ESP32-OBD2-Gauge e SwiftOBD2
    // ─────────────────────────────────────────────────────────────────────────

    private data class AtCommand(
        val cmd: String,
        val delayMs: Long,
        val description: String
    )

    private suspend fun sendInitialCommands() {
        val commands = listOf(
            AtCommand("ATZ",   1000L, "Reset completo"),
            AtCommand("ATE0",   200L, "Echo off"),
            AtCommand("ATL0",   200L, "Line feeds off"),
            AtCommand("ATS0",   200L, "Spaces off"),
            AtCommand("ATH0",   200L, "Headers off"),
            AtCommand("ATAT2",  200L, "Adaptive timing modo 2 (agressivo)"),
            AtCommand("ATSP0",  200L, "Protocolo automático"),
            AtCommand("0100",  2500L, "Detectar protocolo OBD-II")
        )

        addMessage("──── Inicializando ELM327 ────", MessageType.SYSTEM)

        for (atCmd in commands) {
            if (!bluetoothManager.isConnected) break
            addMessage(">> ${atCmd.cmd}", MessageType.SENT)
            bluetoothManager.sendCommand(atCmd.cmd)
            delay(atCmd.delayMs)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loop de leitura e classificação de respostas
    // ─────────────────────────────────────────────────────────────────────────

    private fun startReadingLoop() {
        readingJob?.cancel()
        readingJob = viewModelScope.launch {
            bluetoothManager.startReading().collect { line ->
                processReceivedLine(line)
            }

            // O Flow encerrou — conexão foi perdida
            if (_connectionState.value is ConnectionState.Connected) {
                addMessage("Conexão perdida inesperadamente", MessageType.ERROR)
                _connectionState.value = ConnectionState.Error("Conexão perdida")
            }
        }
    }

    /**
     * Classifica cada linha recebida e dispara recuperação automática quando
     * necessário.
     *
     * Hierarquia de recuperação (baseada em AndrOBD ElmProt.java):
     *   Nível 1 — DATA ERROR / RX ERROR / BUFFER FULL → ATWS (warm start)
     *   Nível 2 — STOPPED → re-enviar último comando
     *   Nível 3 — UNABLE TO CONNECT / NO CONN → ATPC + ATSP0 (re-init protocolo)
     *   Nível 4 — IOException no InputStream → notificar UI
     */
    private suspend fun processReceivedLine(line: String) {
        val type = bluetoothManager.classifyResponse(line)

        val msgType = when (type) {
            ResponseType.OK        -> MessageType.OK
            ResponseType.SEARCHING -> MessageType.INFO
            ResponseType.NO_DATA   -> MessageType.WARNING
            ResponseType.STOPPED   -> MessageType.WARNING
            ResponseType.DATA_ERROR,
            ResponseType.BUFFER_FULL,
            ResponseType.RX_ERROR,
            ResponseType.CAN_ERROR,
            ResponseType.BUS_BUSY,
            ResponseType.BUS_ERROR,
            ResponseType.NO_CONNECTION,
            ResponseType.ERROR     -> MessageType.ERROR
            ResponseType.DATA      -> MessageType.RECEIVED
        }

        addMessage(line, msgType)

        // Reação automática a erros
        when (type) {
            // Nível 1: erros de dados → warm start (ATWS)
            // ATWS reinicializa o protocolo sem reset físico (mais rápido que ATZ)
            ResponseType.DATA_ERROR,
            ResponseType.BUFFER_FULL,
            ResponseType.RX_ERROR -> {
                delay(100)
                addMessage("[Auto] Warm start (ATWS)…", MessageType.SYSTEM)
                bluetoothManager.sendCommand("ATWS")
            }

            // Nível 2: STOPPED → re-enviar o último comando do usuário
            ResponseType.STOPPED -> {
                lastUserCommand?.let { cmd ->
                    delay(300)
                    addMessage("[Auto] Re-enviando: $cmd", MessageType.SYSTEM)
                    bluetoothManager.sendCommand(cmd)
                }
            }

            // Nível 3: sem conexão com ECU → fechar protocolo e re-detectar
            ResponseType.NO_CONNECTION -> {
                delay(500)
                addMessage("[Auto] Fechando protocolo e re-detectando…", MessageType.SYSTEM)
                bluetoothManager.sendCommand("ATPC")
                delay(300)
                bluetoothManager.sendCommand("ATSP0")
            }

            else -> { /* sem ação automática */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    fun sendCommand(command: String) {
        lastUserCommand = command
        viewModelScope.launch {
            addMessage(">> $command", MessageType.SENT)
            bluetoothManager.sendCommand(command)
        }
    }

    fun disconnect() {
        readingJob?.cancel()
        bluetoothManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
        addMessage("Desconectado", MessageType.SYSTEM)
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers internos
    // ─────────────────────────────────────────────────────────────────────────

    private fun addMessage(text: String, type: MessageType = MessageType.RECEIVED) {
        val message = Message(text = text, type = type)
        _messages.value = _messages.value + message
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothManager.disconnect()
    }
}
