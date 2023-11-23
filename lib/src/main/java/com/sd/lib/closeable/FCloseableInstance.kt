package com.sd.lib.closeable

import android.util.Log
import java.util.WeakHashMap

private const val TAG = "FCloseableInstance"

object FCloseableInstance {
    private val _store: MutableMap<Class<out AutoCloseable>, KeyedHolderFactory<out AutoCloseable>> = hashMapOf()

    inline fun <reified T : AutoCloseable> key(key: Any, noinline factory: () -> T): Holder<T> {
        return key(T::class.java, key, factory)
    }

    @JvmStatic
    fun <T : AutoCloseable> key(clazz: Class<T>, key: Any, factory: () -> T): Holder<T> {
        synchronized(this@FCloseableInstance) {
            val keyedHolderFactory = _store[clazz] ?: KeyedHolderFactory<T>().also {
                _store[clazz] = it
            }
            return (keyedHolderFactory as KeyedHolderFactory<T>).create(key, factory) as Holder<T>
        }
    }

    class Holder<T : AutoCloseable>(val instance: T) {
        protected fun finalize() {
            Log.i(TAG, "finalize $this")
        }
    }

    private class KeyedHolderFactory<T : AutoCloseable> {
        private val _store: MutableMap<Any, HolderFactory<T>> = hashMapOf()

        fun create(key: Any, factory: () -> T): Holder<T> {
            val holderFactory = _store[key] ?: HolderFactory<T>().also {
                _store[key] = it
            }
            return holderFactory.create(factory)
        }

        fun isEmpty(): Boolean {
            return _store.iterator().let {
                while (it.hasNext()) {
                    val item = it.next()
                    if (item.value.isEmpty()) {
                        it.remove()
                    }
                }
                _store.isEmpty()
            }
        }
    }

    private class HolderFactory<T : AutoCloseable> {
        private var _instance: T? = null
        private val _holders = WeakHashMap<Holder<T>, String>()

        fun create(factory: () -> T): Holder<T> {
            val instance = _instance ?: factory().also {
                _instance = it
            }
            return Holder(instance).also {
                _holders[it] = ""
            }
        }

        fun isEmpty(): Boolean {
            return _holders.isEmpty().also {
                if (it) {
                    try {
                        _instance?.close()
                    } finally {
                        _instance = null
                    }
                }
            }
        }
    }
}