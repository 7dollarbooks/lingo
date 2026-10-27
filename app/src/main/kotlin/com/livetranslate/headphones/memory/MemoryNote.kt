package com.livetranslate.headphones.memory

data class MemoryNote(
    val id: String,
    val text: String,
    val photoFileName: String,
    val latitude: Double?,
    val longitude: Double?,
    val heartBpm: Int?,
    val systolic: Int?,
    val diastolic: Int?,
    val steps: Int?,
    val calories: Int?,
    val createdAtMs: Long,
    val keep: Boolean,
)

object MemoryRetention {
    const val DAYS = 7
    const val MS: Long = DAYS * 24L * 60L * 60L * 1000L

    fun isExpired(
        createdAtMs: Long,
        nowMs: Long,
        keep: Boolean,
        retentionMs: Long = MS,
    ): Boolean = !keep && nowMs - createdAtMs >= retentionMs
}
