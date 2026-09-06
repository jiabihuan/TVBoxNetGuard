package com.jiabihuan.tvnetguard.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局设置。所有值都直接读写 SharedPreferences，
 * 保证 UI 进程与 VPN 服务（同一进程）看到的是同一份数据。
 */
object Prefs {

    private const val FILE = "tvnetguard_prefs"

    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_WATCHDOG = "watchdog"
    private const val KEY_GLOBAL_UP = "global_up_kbps"
    private const val KEY_GLOBAL_DOWN = "global_down_kbps"
    private const val KEY_STRICT_GLOBAL = "strict_global"
    private const val KEY_ROOT_MODE = "root_mode"
    private const val KEY_MODE = "engine_mode"
    private const val KEY_LOG = "log_enabled"
    private const val KEY_MTU = "mtu"
    private const val KEY_SHOW_SYSTEM = "show_system"
    private const val KEY_BLOCK_IPV6 = "block_ipv6"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var watchdog: Boolean
        get() = sp.getBoolean(KEY_WATCHDOG, true)
        set(v) = sp.edit().putBoolean(KEY_WATCHDOG, v).apply()

    /** 全局上行限速，KB/s；-1 表示不限，0 表示完全禁止上行 */
    var globalUpKbps: Int
        get() = sp.getInt(KEY_GLOBAL_UP, -1)
        set(v) = sp.edit().putInt(KEY_GLOBAL_UP, v).apply()

    var globalDownKbps: Int
        get() = sp.getInt(KEY_GLOBAL_DOWN, -1)
        set(v) = sp.edit().putInt(KEY_GLOBAL_DOWN, v).apply()

    var strictGlobal: Boolean
        get() = sp.getBoolean(KEY_STRICT_GLOBAL, true)
        set(v) = sp.edit().putBoolean(KEY_STRICT_GLOBAL, v).apply()

    var rootMode: Boolean
        get() = sp.getBoolean(KEY_ROOT_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_ROOT_MODE, v).apply()

    /** 引擎工作模式：0=自动(有 root 用 root，否则 VPN) 1=纯 Root 2=免 Root VPN */
    var mode: Int
        get() = sp.getInt(KEY_MODE, 0)
        set(v) = sp.edit().putInt(KEY_MODE, v).apply()

    /** 是否显示桌面悬浮窗 */
    var floatWindow: Boolean
        get() = sp.getBoolean("float_window", false)
        set(v) = sp.edit().putBoolean("float_window", v).apply()

    /** 悬浮窗锁定显示的应用 uid；-1 表示显示整机总速率 */
    var floatLockUid: Int
        get() = sp.getInt("float_lock_uid", -1)
        set(v) = sp.edit().putInt("float_lock_uid", v).apply()

    /** 悬浮窗位置：0=左上 1=右上 2=左下 3=右下 */
    var floatCorner: Int
        get() = sp.getInt("float_corner", 0)
        set(v) = sp.edit().putInt("float_corner", v).apply()

    var logEnabled: Boolean
        get() = sp.getBoolean(KEY_LOG, false)
        set(v) = sp.edit().putBoolean(KEY_LOG, v).apply()

    var mtu: Int
        get() = sp.getInt(KEY_MTU, 1500)
        set(v) = sp.edit().putInt(KEY_MTU, v.coerceIn(576, 4096)).apply()

    var showSystem: Boolean
        get() = sp.getBoolean(KEY_SHOW_SYSTEM, false)
        set(v) = sp.edit().putBoolean(KEY_SHOW_SYSTEM, v).apply()

    /**
     * 接管 IPv6 并丢弃。关闭它的后果是：应用可以走 IPv6 绕过全部限速。
     * 只有在你的网络强依赖 IPv6 且需要保留时才关。
     */
    var blockIpv6: Boolean
        get() = sp.getBoolean(KEY_BLOCK_IPV6, true)
        set(v) = sp.edit().putBoolean(KEY_BLOCK_IPV6, v).apply()
}
