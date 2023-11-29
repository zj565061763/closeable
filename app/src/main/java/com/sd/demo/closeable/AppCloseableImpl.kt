package com.sd.demo.closeable

class AppCloseableImpl : AppCloseable {

    init {
        logMsg { "${this@AppCloseableImpl} init" }
    }

    override fun method(value: Int): Int {
        logMsg { "${this@AppCloseableImpl} method" }
        return 1
    }

    protected fun finalize() {
        logMsg { "${this@AppCloseableImpl} finalize" }
    }

    override fun close() {
        logMsg { "${this@AppCloseableImpl} close" }
    }
}