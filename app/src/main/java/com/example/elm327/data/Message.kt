package com.example.elm327.data

import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class MessageType {
    SENT,       // Comando enviado pelo app  (>> ATZ)
    RECEIVED,   // Dado normal recebido      (41 00 BE 3F A8 13)
    OK,         // Resposta "OK" do ELM      (OK)
    ERROR,      // Erros do ELM              (ERROR, CAN ERROR, BUS ERROR)
    WARNING,    // Avisos                    (NODATA, STOPPED)
    INFO,       // Informativos              (SEARCHING..., ELM327 v1.5)
    SYSTEM      // Mensagens internas do app ([Sistema] Warm start...)
}

data class Message(
    val id: Long = System.nanoTime(),
    val text: String,
    val type: MessageType = MessageType.RECEIVED,
    val timestamp: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
)
