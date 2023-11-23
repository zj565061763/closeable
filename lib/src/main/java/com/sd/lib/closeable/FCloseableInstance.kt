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

    @JvmStatic
    fun close() {
        synchronized(this@FCloseableInstance) {
            _store.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    try {
                        item.value.close { it.close() }
                    } finally {
                        if (item.value.isEmpty()) {
                            iterator.remove()
                        }
                    }
                }
            }
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

        fun isEmpty() = _store.isEmpty()
    }

    private class HolderFactory<T : AutoCloseable> : AutoCloseable {
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
}