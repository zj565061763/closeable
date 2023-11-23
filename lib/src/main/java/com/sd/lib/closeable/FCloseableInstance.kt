package com.sd.lib.closeable

import java.util.concurrent.atomic.AtomicInteger

object FCloseableInstance {
    private val _instanceHolder: MutableMap<Class<out AutoCloseable>, MutableMap<Any, Pair<AutoCloseable, AtomicInteger>>> = hashMapOf()

    inline fun <reified T : AutoCloseable> key(key: Any, noinline factory: () -> T): Holder<T> {
        return key(T::class.java, key, factory)
    }

    @JvmStatic
    fun <T : AutoCloseable> key(clazz: Class<T>, key: Any, factory: () -> T): Holder<T> {
        synchronized(this@FCloseableInstance) {
            val map = _instanceHolder[clazz] ?: hashMapOf<Any, Pair<AutoCloseable, AtomicInteger>>().also {
                _instanceHolder[clazz] = it
            }
            val pair = map[key] ?: (factory() to AtomicInteger(0)).also {
                map[key] = it
            }
            val finalize = AutoCloseable { decrementCount(clazz, key) }
            return Holder(instance = pair.first as T, finalize = finalize).also {
                pair.second.incrementAndGet()
            }
        }
    }

    private fun <T : AutoCloseable> decrementCount(clazz: Class<T>, key: Any) {
        synchronized(this@FCloseableInstance) {
            val map = checkNotNull(_instanceHolder[clazz])
            val pair = checkNotNull(map[key])
            val count = pair.second.decrementAndGet()
            if (count > 0) {
                null
            } else {
                map.remove(key)
                if (map.isEmpty()) _instanceHolder.remove(clazz)
                pair.first
            }
        }?.close()
    }

    class Holder<T : AutoCloseable>(
        private val instance: T,
        private val finalize: AutoCloseable,
    ) {
        fun get(): T = instance

        protected fun finalize() = finalize.close()
    }
}