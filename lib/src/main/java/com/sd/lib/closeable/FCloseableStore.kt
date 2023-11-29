package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import android.util.Log
import java.util.WeakHashMap
import kotlin.reflect.KProperty

object FCloseableStore {
    private val _store: MutableMap<Class<out AutoCloseable>, KeyedHolderFactory<out AutoCloseable>> = hashMapOf()
    private val _idleHandler = SafeIdleHandler { close() > 0 }

    inline fun <reified T : AutoCloseable> key(key: String, noinline factory: () -> T): Holder<T> {
        return key(T::class.java, key, factory)
    }

    /**
     * 创建[key]关联的[Holder]对象，外部应该强引用持有[Holder]对象，并通过[Holder.instance]方法实时获取目标对象。
     * 当[key]关联的所有[Holder]对象都没有被强引用的时候，主线程会在空闲的时候，触发[key]绑定目标对象的[AutoCloseable.close]方法。
     */
    @JvmStatic
    fun <T : AutoCloseable> key(clazz: Class<T>, key: String, factory: () -> T): Holder<T> {
        synchronized(this@FCloseableStore) {
            val keyedHolderFactory = _store[clazz] ?: KeyedHolderFactory<T>().also {
                _store[clazz] = it
            }
            _idleHandler.registerMain()
            return (keyedHolderFactory as KeyedHolderFactory<T>).create(key, factory)
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

    class Holder<T : AutoCloseable> internal constructor(val instance: T)
}

private class KeyedHolderFactory<T : AutoCloseable> {
    private val _store: MutableMap<String, HolderFactory<T>> = hashMapOf()

    fun create(key: String, factory: () -> T): FCloseableStore.Holder<T> {
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

private class HolderFactory<T : AutoCloseable> : AutoCloseable {
    private var _instance: T? = null
    private val _holders = WeakHashMap<FCloseableStore.Holder<T>, String>()

    fun create(factory: () -> T): FCloseableStore.Holder<T> {
        val instance = _instance ?: factory().also {
            _instance = it
        }
        return FCloseableStore.Holder(instance).also {
            _holders[it] = ""
        }
    }

    /**
     * 如果返回不为null，说明当前[_instance]关联的[FCloseableStore.Holder]对象已经全部被回收，
     * 此时调用返回对象的[AutoCloseable.close]方法可以关闭[_instance]
     */
    fun closeable(): AutoCloseable? {
        return if (_holders.isEmpty()) this else null
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

    fun registerMain() {
        val mainLooper = Looper.getMainLooper() ?: return
        if (mainLooper === Looper.myLooper()) {
            register()
        } else {
            Handler(mainLooper).post { register() }
        }
    }

    fun register(): Boolean {
        Looper.myLooper() ?: return false
        synchronized(this@SafeIdleHandler) {
            _idleHandler?.let { return true }
            IdleHandler {
                block().also { sticky ->
                    synchronized(this@SafeIdleHandler) {
                        if (!sticky) {
                            _idleHandler = null
                        }
                    }
                }
            }.also {
                _idleHandler = it
                Looper.myQueue().addIdleHandler(it)
            }
            return true
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T : AutoCloseable> FCloseableStore.Holder<T>.getValue(thisObj: Any?, property: KProperty<*>): T = instance