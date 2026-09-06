package com.jiabihuan.tvnetguard

/**
 * 引擎运行状态（UI 与后台服务共享，同进程）。
 * 用来区分当前到底是哪个引擎在跑：免 root 的 VPN，还是纯内核的 root。
 */
object EngineState {

    const val NONE = 0
    const val VPN = 1
    const val ROOT = 2

    @Volatile
    var running = false
        private set

    /** 当前运行引擎类型：NONE / VPN / ROOT */
    @Volatile
    var kind = NONE
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var sessionCount = 0
        private set

    fun setRunning(kind: Int, sessions: Int = 0) {
        running = true
        this.kind = kind
        this.sessionCount = sessions
        lastError = null
    }

    fun setStopped() {
        running = false
        kind = NONE
        sessionCount = 0
    }

    fun note(kind: Int, error: String?) {
        if (error != null) lastError = error
    }

    fun setSessions(n: Int) {
        sessionCount = n
    }
}
