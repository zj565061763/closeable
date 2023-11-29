package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue.IdleHandler
import android.util.Log
import java.util.WeakHashMap
import kotlin.reflect.KProperty

object FCloseableStore {
    private val _store: MutableMap<Class<out AutoCloseable>, KeyedHolderFactory<out AutoCloseable>> = hashMapOf()
    private val _idleHandler = SafeIdleHandler {
        close().let { size ->
            Log.d(FCloseableStore::class.java.simpleName, "close size:$size")
            size > 0
        }
    }

    inline fun <reified T : AutoCloseable> key(key: Any, noinline factory: () -> T): Holder<T> {
        return key(T::class.java, key, factory)
    }

    @JvmStatic
    fun <T : AutoCloseable> key(clazz: Class<T>, key: Any, factory: () -> T): Holder<T> {
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
                    item.value.close {
                        try {
                            it.close()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (item.value.isEmpty()) {
                        iterator.remove()
                    }
                }
            }
            return _store.size
        }
    }

    class Holder<T : AutoCloseable> internal constructor(val instance: T)
}

private class KeyedHolderFactory<T : AutoCloseable> {
    private val _store: MutableMap<Any, HolderFactory<T>> = hashMapOf()

    fun isEmpty() = _store.isEmpty()

    fun create(key: Any, factory: () -> T): FCloseableStore.Holder<T> {
        val holderFactory = _store[key] ?: HolderFactory<T>().also {
            _store[key] = it
        }
        return holderFactory.create(factory)
    }

    inline fun close(block: (AutoCloseable) -> Unit) {
        return _store.iterator().let { iterator ->
            while (iterator.hasNext()) {
                val item = iterator.next()
                item.value.closeable()?.let {
                    try {
                        block(it)
                    } finally {
                        iterator.remove()
                    }
                }
            }
        }
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