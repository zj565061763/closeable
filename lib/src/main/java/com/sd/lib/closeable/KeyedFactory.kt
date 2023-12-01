package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import java.lang.reflect.Proxy
import java.util.WeakHashMap

class FKeyedFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private val _holder: MutableMap<String, SingletonFactory<T>> = hashMapOf()

    fun create(key: String, factory: () -> T): T {
        checkMainThread()
        val singletonFactory = _holder[key] ?: SingletonFactory(clazz).also {
            _holder[key] = it
        }
        _idleHandler.register()
        return singletonFactory.create(factory)
    }

    private val _idleHandler = SafeIdleHandler {
        close()
        _holder.isNotEmpty()
    }

    private fun close() {
        checkMainThread()
        _holder.iterator().run {
            while (hasNext()) {
                val item = next()
                item.value.closeable()?.let { closeable ->
                    try {
                        closeable.close()
                    } finally {
                        remove()
                    }
                }
            }
        }
    }
}

private class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) : AutoCloseable {
    private var _instance: T? = null
    private val _holder = WeakHashMap<T, String>()

    init {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
    }

    fun create(factory: () -> T): T {
        _instance?.let { return it }

        val instance = factory().also { _instance = it }
        val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
            if (args != null) {
                method.invoke(instance, *args)
            } else {
                method.invoke(instance)
            }
        } as T

        return proxy.also {
            _holder[proxy] = ""
        }
    }

    fun closeable(): AutoCloseable? {
        return if (_holder.isEmpty()) this else null
    }

    override fun close() {
        try {
            _instance?.close()
        } finally {
            _instance = null
        }
    }
}

private fun checkMainThread() {
    val mainLooper = Looper.getMainLooper() ?: return
    if (mainLooper !== Looper.myLooper()) error("Not main thread.")
}

internal class SafeIdleHandler(private val block: () -> Boolean) {
    private var _idleHandler: IdleHandler? = null

    fun register() {
        val mainLooper = Looper.getMainLooper() ?: return
        if (mainLooper === Looper.myLooper()) {
            addIdleHandler()
        } else {
            Handler(mainLooper).post { addIdleHandler() }
        }
    }

    private fun addIdleHandler() {
        Looper.myLooper() ?: return
        _idleHandler?.let { return }
        IdleHandler {
            block().also { if (!it) _idleHandler = null }
        }.also {
            _idleHandler = it
            Looper.myQueue().addIdleHandler(it)
        }
    }
}