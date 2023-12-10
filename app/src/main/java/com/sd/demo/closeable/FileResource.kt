package com.sd.demo.closeable

import com.sd.lib.closeable.FCloseableFactory

interface FileResource : AutoCloseable {
    fun write(content: String)

    companion object {
        private val _factory = object : FCloseableFactory<FileResource>(FileResource::class.java) {
            override fun onEmpty() {
                logMsg { "factory empty" }
            }

            override fun onCloseError(e: Exception) {
                logMsg { "factory close error:$e" }
            }
        }

        fun create(path: String): FileResource {
            return _factory.create(path) { FileResourceImpl(path) }
        }
    }
}

private class FileResourceImpl(private val path: String) : FileResource {
    override fun write(content: String) {
        logMsg { "write $content $this" }
    }

    override fun close() {
        logMsg { "close $this" }
    }
}