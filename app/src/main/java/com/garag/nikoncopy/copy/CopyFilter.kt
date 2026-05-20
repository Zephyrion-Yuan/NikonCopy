package com.garag.nikoncopy.copy

data class CopyFilter(
    val directories: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
) {
    fun accepts(directoryPath: String, extension: String): Boolean {
        val dirOk = directories.isEmpty() || directoryPath in directories
        val extOk = extensions.isEmpty() || extension.lowercase() in extensions
        return dirOk && extOk
    }
}

