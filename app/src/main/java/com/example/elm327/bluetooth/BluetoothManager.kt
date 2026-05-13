package com.example.elm327.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothManager {

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = sock
            sock.connect()
            inputStream = sock.inputStream
            outputStream = sock.outputStream
            Result.success(Unit)
        } catch (e: IOException) {
            socket = null
            inputStream = null
            outputStream = null
            Result.failure(e)
        }
    }

    suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        try {
            outputStream?.write("$command\r".toByteArray(Charsets.ISO_8859_1))
            outputStream?.flush()
        } catch (e: IOException) {
            // Será detectado pelo loop de leitura
        }
    }

    fun startReading(): Flow<String> = flow {
        val stream = inputStream ?: return@flow
        val buffer = ByteArray(1024)
        val lineBuffer = StringBuilder()

        try {
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) break

                val chunk = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                for (char in chunk) {
                    when (char) {
                        '\r', '\n' -> {
                            val line = lineBuffer.toString().trim()
                            if (line.isNotEmpty()) emit(line)
                            lineBuffer.clear()
                        }
                        '>' -> {
                            val line = lineBuffer.toString().trim()
                            if (line.isNotEmpty()) emit(line)
                            lineBuffer.clear()
                        }
                        else -> lineBuffer.append(char)
                    }
                }
            }
        } catch (e: IOException) {
            // Conexão encerrada — o flow termina naturalmente
        }
    }.flowOn(Dispatchers.IO)

    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            // Ignora erros ao fechar
        } finally {
            socket = null
            inputStream = null
            outputStream = null
        }
    }
}
