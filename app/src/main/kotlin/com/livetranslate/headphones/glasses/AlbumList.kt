package com.livetranslate.headphones.glasses

/**
 * Names from a HeyCyan album listing. One name per line, or the last token on the line.
 * Timestamp names sort in capture order.
 */
internal object AlbumList {
    fun fileNames(list: String, accept: (String) -> Boolean): List<String> =
        list.lineSequence().mapNotNull { line ->
            val token = line.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            token.takeIf { it.isNotEmpty() && accept(it) }
        }.distinct().toList()

    fun isJpegName(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)

    fun isVideoName(name: String): Boolean =
        name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".mov", ignoreCase = true)

    fun newestVideoName(list: String): String? = fileNames(list, ::isVideoName).maxOrNull()

    fun hasReachableAlbum(list: String): Boolean =
        list.contains(".jpg", ignoreCase = true) ||
            list.contains(".jpeg", ignoreCase = true) ||
            list.contains(".mp4", ignoreCase = true) ||
            list.contains(".mov", ignoreCase = true)
}
