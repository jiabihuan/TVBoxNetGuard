package com.jiabihuan.tvnetguard.util

import kotlin.math.abs

/** 字节 / 速率的人性化显示 */
object Format {

    fun bytes(n: Long): String {
        val v = abs(n)
        return when {
            v >= 1024L * 1024 * 1024 -> "%.2f GB".format(n / (1024.0 * 1024 * 1024))
            v >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
            v >= 1024L -> "%.0f KB".format(n / 1024.0)
            else -> "$n B"
        }
    }

    /** 字节/秒 -> "123 KB/s" */
    fun speed(bytesPerSec: Long): String {
        val v = abs(bytesPerSec)
        return when {
            v >= 1024L * 1024 -> "%.2f MB/s".format(bytesPerSec / (1024.0 * 1024))
            v >= 1024L -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    /** 直接给 KB/s 数值，用于速率栏（避免二次换算的精度抖动） */
    fun kbps(bytesPerSec: Long): String = "%.1f KB/s".format(bytesPerSec / 1024.0)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}小时${m}分" else if (m > 0) "${m}分钟" else "${s}秒"
    }
}
