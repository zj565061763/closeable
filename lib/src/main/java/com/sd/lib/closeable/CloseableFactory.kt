package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 如果[lock]不为null，会在创建和关闭对象的时候加锁，且主线程会在空闲的时候关闭未使用的[AutoCloseable]，如果关闭时发生了异常，会回调[onCloseError]。
 * 如果[lock]为null，则不加锁，也不会自动关闭未使用的[AutoCloseable]。
 */
open class FCloseableFactory<T : AutoCloseable> @JvmOverloads constructor(
    private val clazz: Class<T>,
    private val lock: Any? = Any(),
) {
    private val _holder: MutableMap<String, SingletonFactory<T>> = hashMapOf()

    /**
     * 根据[key]获取[clazz]接口的代理对象，代理对象代理[factory]创建的原始对象
     */
    fun create(key: String, factory: () -> T): T {
        if (lock == null) {
            return createUnlock(key, factory)
        } else {
            synchronized(lock) {
                return createUnlock(key, factory)
            }
        }
    }

    /**
     * 关闭未使用的[AutoCloseable]
     */
    @Throws(Exception::class)
    fun close() {
        if (lock == null) {
            closeUnlock { throw it }
        } else {
            synchronized(lock) {
                closeUnlock { throw it }
            }
        }
    }

    private val _idleHandler = SafeIdleHandler {
        synchronized(checkNotNull(lock)) {
            closeUnlock { onCloseError(it) }
            _holder.isNotEmpty()
        }
    }

    private fun createUnlock(key: String, factory: () -> T): T {
        val singletonFactory = _holder.getOrPut(key) { SingletonFactory(clazz) }
        if (lock != null) _idleHandler.register()
        return singletonFactory.create(factory)
    }

    private inline fun closeUnlock(exceptionHandler: (Exception) -> Unit) {
        val oldSize = _holder.size

        _holder.iterator().run {
            while (hasNext()) {
                next().value.closeable()?.let {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        exceptionHandler(e)
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

    /**
     * 工厂内部已经没有保存的对象了
     */
    protected open fun onEmpty() {}

    /**
     * 如果[lock]不为null，主线程会在空闲的时候关闭未使用的[AutoCloseable]，如果关闭时发生了异常，会回调此方法
     */
    protected open fun onCloseError(e: Exception) {}
}

private class SingletonFactory<T : AutoCloseable>(
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