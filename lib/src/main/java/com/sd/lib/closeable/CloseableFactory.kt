package com.sd.lib.closeable

import android.os.Looper
import android.os.MessageQueue.IdleHandler
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

interface CloseableFactory<T : AutoCloseable> {
    /**
     * 根据[key]返回接口的代理对象，来代理[factory]创建的原始对象，
     * 注意：此方法只能在主线程调用，否则会抛异常
     */
    fun create(key: String, factory: () -> T): T
}

/**
 * 主线程会在空闲的时候关闭未使用的[AutoCloseable]
 */
open class FAutoCloseFactory<T : AutoCloseable>(
    clazz: Class<T>,
) : CloseableFactory<T> {

    private val _factory = CloseableFactoryImpl(clazz)

    private val _idleHandler = SafeIdleHandler {
        _factory.close(
            onException = { onCloseError(it) },
            onEmpty = { onEmpty() },
        ) > 0
    }

    override fun create(key: String, factory: () -> T): T {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Should be called on main thread." }
        _idleHandler.register()
        return _factory.create(key, factory)
    }

    /**
     * 工厂内部已经没有保存的对象了(MainThread)
     */
    protected open fun onEmpty() {}

    /**
     * 主线程会在空闲的时候关闭未使用的[AutoCloseable]，如果关闭时发生了异常，会回调此方法(MainThread)
     */
    protected open fun onCloseError(e: Exception) {}
}

private class CloseableFactoryImpl<T : AutoCloseable>(
    private val clazz: Class<T>,
) : CloseableFactory<T> {

    private val _holder: MutableMap<String, SingletonFactory<T>> = hashMapOf()

    override fun create(key: String, factory: () -> T): T {
        return _holder.getOrPut(key) { SingletonFactory(clazz) }.create(factory)
    }

    /**
     * 关闭未使用的[AutoCloseable]并返回剩余的个数
     */
    inline fun close(
        onException: (Exception) -> Unit,
        onEmpty: () -> Unit,
    ): Int {
        val oldSize = _holder.size

        _holder.iterator().run {
            while (hasNext()) {
                next().value.closeable()?.let {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        onException(e)
                    } finally {
                        remove()
                    }
                }
            }
        }

        if (oldSize > 0 && _holder.isEmpty()) {
            onEmpty()
        }

        return _holder.size
    }
}

/**
 * 单实例工厂
 */
private class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>,
) : AutoCloseable, InvocationHandler {

    private var _instance: T? = null
    private var _proxyRef: WeakReference<T>? = null

    init {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
    }

    fun create(factory: () -> T): T {
        _instance ?: factory().also {
            _instance = it
        }

        return _proxyRef?.get() ?: kotlin.run {
            val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), this)
            @Suppress("UNCHECKED_CAST")
            (proxy as T).also { _proxyRef = WeakReference(it) }
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

private class SafeIdleHandler(
    private val block: () -> Boolean,
) {
    private var _idleHandler: IdleHandler? = null

    fun register() {
        _idleHandler?.let { return }
        IdleHandler {
            block().also { keep ->
                if (keep) {
                    // keep
                } else {
                    _idleHandler = null
                }
            }
        }.also {
            _idleHandler = it
            Looper.myQueue().addIdleHandler(it)
        }
    }
}