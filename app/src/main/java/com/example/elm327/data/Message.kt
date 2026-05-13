package com.example.elm327.data

import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class Message(
    val id: Long = System.nanoTime(),
    val text: String,
    val timestamp: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
)
