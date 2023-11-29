package com.sd.demo.closeable

interface AppCloseable : AutoCloseable {
    fun method(value: Int): Int
}