package com.sd.lib.closeable

import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 单实例工厂
 */
internal class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>,
) : AutoCloseable, InvocationHandler {

    private var _instance: T? = null
    private var _proxyRef: WeakReference<T>? = null

    init {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
    }

    @Suppress("UNCHECKED_CAST")
    fun create(factory: () -> T): T {
        _instance ?: factory().also {
            _instance = it
        }

        return _proxyRef?.get() ?: kotlin.run {
            (Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), this) as T).also {
                _proxyRef = WeakReference(it)
            }
        }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val instance = checkNotNull(_instance)
        return if (args != null) {
            method.invoke(instance, *args)
        } else {
            method.invoke(instance)
        }
    }

    fun closeable(): AutoCloseable? {
        return if (_proxyRef?.get() == null) this else null
    }

    override fun close() {
        try {
            _instance?.close()
        } finally {
            _instance = null
        }
    }
}