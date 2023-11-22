package com.sd.demo.closeable

class MyCloseable : AutoCloseable {

    init {
        logMsg { "${this@MyCloseable} init" }
    }

    fun method() {
        logMsg { "${this@MyCloseable} method" }
    }

    protected fun finalize() {
        logMsg { "${this@MyCloseable} finalize" }
    }

    override fun close() {
        logMsg { "${this@MyCloseable} close" }
    }
}