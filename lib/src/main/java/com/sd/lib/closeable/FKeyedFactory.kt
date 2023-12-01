package com.sd.lib.closeable

import java.lang.reflect.Proxy
import java.util.WeakHashMap

class FKeyedFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private val _holder = mutableMapOf<String, SingletonFactory<T>>()

    fun create(key: String, factory: () -> T): T {
        val singletonFactory = _holder[key] ?: SingletonFactory(clazz).also {
            _holder[key] = it
        }
        return singletonFactory.create(factory)
    }

    fun close() {
        _holder.iterator().run {
            while (hasNext()) {
                val item = next()
                val factory = item.value
                try {
                    factory.close()
                } finally {
                    if (factory.isEmpty()) {
                        remove()
                    }
                }
            }
        }
    }
}

private class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private var _instance: T? = null
    private val _proxyHolder = WeakHashMap<T, String>()

    init {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
    }

    fun isEmpty(): Boolean = _proxyHolder.isEmpty()

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
            _proxyHolder[proxy] = ""
        }
    }

    fun close(): Boolean {
        return isEmpty().also {
            if (it) {
                _instance?.close()
                _instance = null
            }
        }
    }
}