package com.example.iot.models

data class Note(
    val name: String,
    val pitch: Byte // or whatever data you need to send to STM32
)
