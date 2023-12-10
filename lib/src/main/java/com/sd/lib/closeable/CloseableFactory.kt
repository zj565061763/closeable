package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler

interface CloseableFactory<T> {
    /**
     * 根据[key]获取接口的代理对象，来代理[factory]创建的原始对象
     */
    fun create(key: String, factory: () -> T): T
}

/**
 * 主线程会在空闲的时候关闭未使用的[AutoCloseable]，如果关闭时发生了异常，会回调[onCloseError]方法
 */
open class FAutoCloseableFactory<T : AutoCloseable>(
    private val clazz: Class<T>,
    private val lock: Any = Any(),
) : CloseableFactory<T> {

    private val _factory = object : CloseableFactoryImpl<T>(clazz) {
        override fun onEmpty() {
            this@FAutoCloseableFactory.onEmpty()
        }
    }

    private val _idleHandler = SafeIdleHandler {
        synchronized(lock) {
            _factory.close { onCloseError(it) }
            _factory.size > 0
        }
    }

    override fun create(key: String, factory: () -> T): T {
        synchronized(lock) {
            _idleHandler.register()
            return _factory.create(key, factory)
        }
    }

    /**
     * 工厂内部已经没有保存的对象了
     */
    protected open fun onEmpty() {}

    /**
     * 主线程会在空闲的时候关闭未使用的[AutoCloseable]，如果关闭时发生了异常，会回调此方法
     */
    protected open fun onCloseError(e: Exception) {}
}

private open class CloseableFactoryImpl<T : AutoCloseable>(
    private val clazz: Class<T>,
) : CloseableFactory<T> {

    private val _holder: MutableMap<String, SingletonFactory<T>> = hashMapOf()

    val size: Int get() = _holder.size

    override fun create(key: String, factory: () -> T): T {
        return _holder.getOrPut(key) { SingletonFactory(clazz) }.create(factory)
    }

    fun close(exceptionHandler: (Exception) -> Unit) {
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