package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

object FCloseableStore {
    private val _store: MutableMap<Class<out AutoCloseable>, KeyedHolderFactory<out AutoCloseable>> = hashMapOf()
    private val _idleHandler = SafeIdleHandler { close() > 0 }

    /**
     * 根据[key]获取对象，[clazz]必须是接口，因为返回的是[clazz]接口的动态代理对象，代理对象代理[factory]创建的真实对象，
     * 当代理对象没有被强引用持有的时候，主线程会在空闲的时候调用真实对象的[AutoCloseable.close]方法释放资源。
     */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T : AutoCloseable> key(key: String, clazz: Class<T>, factory: () -> T): T {
        require(clazz.isInterface) { "clazz must be an interface" }
        require(clazz != AutoCloseable::class.java) { "clazz must not be:${AutoCloseable::class.java.name}" }
        synchronized(this@FCloseableStore) {
            val keyedHolderFactory = _store[clazz] ?: KeyedHolderFactory<T>().also {
                _store[clazz] = it
            }
            _idleHandler.register()
            val holder = (keyedHolderFactory as KeyedHolderFactory<T>).create(key, factory)
            val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), CloseableInvocationHandler(holder))
            return proxy as T
        }
    }

    private fun close(): Int {
        synchronized(this@FCloseableStore) {
            _store.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    item.value.close { key, closeable ->
                        try {
                            closeable.close()
                            Log.d(FCloseableStore::class.java.simpleName, "${item.key.name} close key:$key")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.let { size ->
                        if (size <= 0) {
                            iterator.remove()
                            Log.d(FCloseableStore::class.java.simpleName, "${item.key.name} removed")
                        }
                    }
                }
            }
            return _store.size
        }
    }

    private class CloseableInvocationHandler(
        private val holder: CloseableHolder<*>
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
            return if (args != null) {
                method?.invoke(holder.instance, *args)
            } else {
                method?.invoke(holder.instance)
            }
        }
    }
}

private class CloseableHolder<T : AutoCloseable>(val instance: T)

private class KeyedHolderFactory<T : AutoCloseable> {
    private val _store: MutableMap<String, HolderFactory<T>> = hashMapOf()

    fun create(key: String, factory: () -> T): CloseableHolder<T> {
        val holderFactory = _store[key] ?: HolderFactory<T>().also {
            _store[key] = it
        }
        return holderFactory.create(factory)
    }

    inline fun close(block: (String, AutoCloseable) -> Unit): Int {
        _store.iterator().let { iterator ->
            while (iterator.hasNext()) {
                val item = iterator.next()
                item.value.closeable()?.let {
                    try {
                        block(item.key, it)
                    } finally {
                        iterator.remove()
                    }
                }
            }
        }
        return _store.size
    }
}

private class HolderFactory<T : AutoCloseable> {
    private var _instance: T? = null
    private val _holders = WeakHashMap<CloseableHolder<T>, String>()

    fun create(factory: () -> T): CloseableHolder<T> {
        val instance = _instance ?: factory().also {
            _instance = it
        }
        return CloseableHolder(instance).also {
            _holders[it] = ""
        }
    }

    /**
     * 如果返回不为null，说明当前[_instance]关联的所有[CloseableHolder]对象已经没有强引用了，
     * 可以调用返回对象的[AutoCloseable.close]方法可以关闭[_instance]
     */
    fun closeable(): AutoCloseable? {
        _instance ?: return null
        return if (_holders.isEmpty()) _closeTask else null
    }

    private val _closeTask = AutoCloseable {
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
            block().also { if (!it) _idleHandler = null }
        }.also {
            _idleHandler = it
            Looper.myQueue().addIdleHandler(it)
        }
    }
}

