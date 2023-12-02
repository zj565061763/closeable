package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 此类不支持多线程并发，如果有多线程的应用场景，外部可以根据需求加锁，
 * 如果[autoClose]设置为true，则[close]方法会在主线程空闲的时候触发
 */
open class FCloseableFactory<T : AutoCloseable> @JvmOverloads constructor(
    private val clazz: Class<T>,
    private val autoClose: Boolean = true,
) {
    private val _holder: MutableMap<String, SingletonFactory<T>> = hashMapOf()

    /**
     * 根据[key]获取[clazz]接口的代理对象，代理对象代理[factory]创建的原始对象
     */
    fun create(key: String, factory: () -> T): T {
        val singletonFactory = _holder.getOrPut(key) { SingletonFactory(clazz) }
        if (autoClose) _idleHandler.register()
        return singletonFactory.create(factory)
    }

    private val _idleHandler = SafeIdleHandler {
        close()
        _holder.isNotEmpty()
    }

    /**
     * 关闭未使用的[AutoCloseable]
     */
    fun close() {
        val oldSize = _holder.size
        _holder.iterator().run {
            while (hasNext()) {
                val item = next()
                item.value.closeable()?.let {
                    try {
                        it.close()
                    } finally {
                        remove()
                    }
                }
            }
        }
        if (oldSize > 0 && _holder.isEmpty()) {
            onEmpty()
        }
    }

    protected open fun onEmpty() {}
}

private class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) : AutoCloseable, InvocationHandler {
    private var _instance: T? = null
    private val _holder = WeakHashMap<T, String>()

    init {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
    }

    @Suppress("UNCHECKED_CAST")
    fun create(factory: () -> T): T {
        _instance ?: factory().also {
            _instance = it
        }
        return (Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), this) as T).also {
            _holder[it] = ""
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

private class SafeIdleHandler(private val block: () -> Boolean) {
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
            block().also {
                if (!it) _idleHandler = null
            }
        }.also {
            _idleHandler = it
            Looper.myQueue().addIdleHandler(it)
        }
    }
}