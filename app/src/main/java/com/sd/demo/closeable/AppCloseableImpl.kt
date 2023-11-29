package com.sd.demo.closeable

class AppCloseableImpl : AppCloseable {

    init {
        logMsg { "${this@AppCloseableImpl} init" }
    }

    override fun method(msg: String): String {
        logMsg { "${this@AppCloseableImpl} method" }
        return msg
    }

    protected fun finalize() {
        logMsg { "${this@AppCloseableImpl} finalize" }
    }

    override fun close() {
        logMsg { "${this@AppCloseableImpl} close" }
    }
}