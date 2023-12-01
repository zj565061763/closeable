package com.sd.demo.closeable

import com.sd.lib.closeable.FCloseableFactory

object FileResourceFactory {
    private val _factory = FCloseableFactory(FileResource::class.java)

    fun create(path: String): FileResource {
        return _factory.create(path) { FileResourceImpl(path) }
    }
}

interface FileResource : AutoCloseable {
    fun write(content: String)
}

private class FileResourceImpl(private val path: String) : FileResource {
    override fun write(content: String) {
        logMsg { "write $content $this" }
    }

    override fun close() {
        logMsg { "close $this" }
    }
}