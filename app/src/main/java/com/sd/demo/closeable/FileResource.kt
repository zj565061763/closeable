package com.sd.demo.closeable

import com.sd.lib.closeable.FCloseableFactory

interface FileResource : AutoCloseable {
    fun write(content: String)

    companion object {
        private val _factory = FCloseableFactory(FileResource::class.java)

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