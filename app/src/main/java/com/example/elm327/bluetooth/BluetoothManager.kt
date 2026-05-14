package com.example.elm327.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Gerenciador de conexão Bluetooth com ELM327 via SPP/RFCOMM.
 *
 * Melhorias implementadas com base em análise de projetos de referência
 * (AndrOBD, SwiftOBD2, ESP32-OBD2-Gauge, ELM327-emulator):
 *
 *  1. cancelDiscovery() antes de connect() — discovery consome banda BT e
 *     causa falhas silenciosas de conexão
 *  2. Três tentativas de socket (padrão seguro → inseguro → reflexão canal 1)
 *     — adaptadores clone frequentemente falham no SDP e aceitam apenas canal 1
 *  3. 500ms de estabilização após socket.connect() — padrão AndrOBD
 *  4. Leitura byte a byte — garante detecção correta do delimitador '>'
 *  5. Classificação de respostas — permite reação automática a erros específicos
 */
class BluetoothManager {

    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Palavras-chave de erro reconhecidas pelo ELM327
        const val RESP_OK           = "OK"
        const val RESP_ERROR        = "ERROR"
        const val RESP_NODATA       = "NODATA"
        const val RESP_SEARCHING    = "SEARCHING"
        const val RESP_STOPPED      = "STOPPED"
        const val RESP_DATA_ERROR   = "DATA ERROR"
        const val RESP_BUFFER_FULL  = "BUFFER FULL"
        const val RESP_RX_ERROR     = "RX ERROR"
        const val RESP_CAN_ERROR    = "CAN ERROR"
        const val RESP_BUS_BUSY     = "BUSBUSY"
        const val RESP_BUS_ERROR    = "BUSERR"
        const val RESP_NOCONN       = "UNABLE TO CONNECT"
        const val RESP_UNABLE       = "UNABLE TO"
        const val RESP_NO_CONN2     = "NO CONN"
    }

    enum class ResponseType {
        OK, DATA, SEARCHING,
        NO_DATA, STOPPED,
        DATA_ERROR, BUFFER_FULL, RX_ERROR,
        CAN_ERROR, BUS_BUSY, BUS_ERROR,
        NO_CONNECTION, ERROR
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    // ─────────────────────────────────────────────────────────────────────────
    // Conexão com 3 tentativas de fallback
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun connect(
        device: BluetoothDevice,
        bluetoothAdapter: BluetoothAdapter
    ): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()

        // CRÍTICO: cancelar discovery antes de conectar.
        // Discovery consome largura de banda BT e frequentemente causa
        // IOException silenciosa no socket.connect().
        @Suppress("MissingPermission")
        bluetoothAdapter.cancelDiscovery()

        // Tentativa 1: socket seguro via SDP (API padrão)
        tryConnect(device, attempt = 1)?.let { return@withContext it }

        // Tentativa 2: socket inseguro via SDP
        tryConnect(device, attempt = 2)?.let { return@withContext it }

        // Tentativa 3: reflexão — canal RFCOMM fixo 1
        // Muitos adaptadores clone ignoram o SDP mas aceitam conexão direta
        // no canal 1 (canal SPP padrão). Padrão comprovado em AndrOBD.
        tryConnect(device, attempt = 3)?.let { return@withContext it }

        Result.failure(IOException("Todas as tentativas de conexão falharam"))
    }

    private suspend fun tryConnect(device: BluetoothDevice, attempt: Int): Result<Unit>? {
        return try {
            val sock = when (attempt) {
                1 -> {
                    @Suppress("MissingPermission")
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
                2 -> {
                    @Suppress("MissingPermission")
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
                else -> {
                    // Reflexão: createRfcommSocket(int channel)
                    val method = device.javaClass.getMethod(
                        "createRfcommSocket", Int::class.java
                    )
                    @Suppress("MissingPermission")
                    method.invoke(device, 1) as BluetoothSocket
                }
            }
            socket = sock
            sock.connect()
            inputStream  = sock.inputStream
            outputStream = sock.outputStream

            // 500ms de estabilização — padrão AndrOBD.
            // ELM327s mais lentos precisam deste tempo após a negociação
            // RFCOMM antes de aceitar comandos AT.
            delay(500)

            Result.success(Unit)
        } catch (e: Exception) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
            null // retorna null para indicar que deve tentar próxima alternativa
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envio de comando
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envia um comando ao ELM327 com o terminador '\r' obrigatório.
     * Usa ISO_8859_1 porque alguns adaptadores clone não aceitam UTF-8.
     */
    suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        try {
            outputStream?.write("$command\r".toByteArray(Charsets.ISO_8859_1))
            outputStream?.flush()
        } catch (_: IOException) {
            // A IOException será detectada e reportada pelo loop de leitura
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loop de leitura contínua
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Flow que lê continuamente o InputStream do ELM327 e emite linhas completas.
     *
     * Decisões de design baseadas em análise dos projetos de referência:
     *  - Leitura byte a byte: garante detecção imediata do '>' sem bloqueio
     *  - Três delimitadores: '>' (prompt ELM), '\r', '\n' — cobre todos os firmwares
     *  - Espaços: mantidos dentro de linha (ATS0 não é 100% respeitado em clones)
     *  - Fechar socket em disconnect() interrompe read() com IOException
     */
    fun startReading(): Flow<String> = flow {
        val stream = inputStream ?: return@flow
        val lineBuffer = StringBuilder()

        try {
            while (true) {
                val byte = stream.read()
                if (byte == -1) break // stream encerrado graciosamente

                when (val char = byte.toChar()) {
                    '>' -> {
                        // Prompt do ELM327 — fim de resposta completa
                        val line = lineBuffer.toString().trim()
                        if (line.isNotEmpty()) emit(line)
                        lineBuffer.clear()
                    }
                    '\r', '\n' -> {
                        val line = lineBuffer.toString().trim()
                        if (line.isNotEmpty()) emit(line)
                        lineBuffer.clear()
                    }
                    else -> lineBuffer.append(char)
                }
            }
        } catch (_: IOException) {
            // Conexão encerrada (socket fechado por disconnect())
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // Classificação de respostas ELM327
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classifica uma linha recebida do ELM327 para permitir reação automática
     * a erros (warm start, re-envio, reinicialização de protocolo, etc.).
     */
    fun classifyResponse(response: String): ResponseType {
        val upper = response.uppercase().trim()
        return when {
            upper.contains(RESP_NOCONN)       -> ResponseType.NO_CONNECTION
            upper.contains(RESP_NO_CONN2)     -> ResponseType.NO_CONNECTION
            upper.contains(RESP_UNABLE)       -> ResponseType.NO_CONNECTION
            upper.contains(RESP_DATA_ERROR)   -> ResponseType.DATA_ERROR
            upper.contains(RESP_BUFFER_FULL)  -> ResponseType.BUFFER_FULL
            upper.contains(RESP_RX_ERROR)     -> ResponseType.RX_ERROR
            upper.contains(RESP_CAN_ERROR)    -> ResponseType.CAN_ERROR
            upper.contains(RESP_BUS_BUSY)     -> ResponseType.BUS_BUSY
            upper.contains(RESP_BUS_ERROR)    -> ResponseType.BUS_ERROR
            upper.contains(RESP_NODATA)       -> ResponseType.NO_DATA
            upper.contains(RESP_STOPPED)      -> ResponseType.STOPPED
            upper.contains(RESP_SEARCHING)    -> ResponseType.SEARCHING
            upper.contains(RESP_ERROR)        -> ResponseType.ERROR
            upper == RESP_OK                  -> ResponseType.OK
            else                              -> ResponseType.DATA
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Desconexão
    // ─────────────────────────────────────────────────────────────────────────

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) {
            // Ignora erros ao fechar — o importante é limpar as referências
        } finally {
            socket       = null
            inputStream  = null
            outputStream = null
        }
    }
}
