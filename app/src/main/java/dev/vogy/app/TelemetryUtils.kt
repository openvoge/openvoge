package dev.vogy.app

fun Byte.toHexByte(): String = "%02X".format(this.toInt() and 0xFF)
fun ByteArray.toHexString(): String = joinToString(" ") { it.toHexByte() }
