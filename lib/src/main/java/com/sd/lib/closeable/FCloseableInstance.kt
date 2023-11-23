package com.sd.lib.closeable

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class Holder<T : AutoCloseable>(val instance: T)

object FCloseableInstance {
    private val _store: MutableMap<Class<out AutoCloseable>, KeyedHolderFactory<out AutoCloseable>> = hashMapOf()
    private val _timer = IntervalTimer(5 * 1000) {
        Log.i(FCloseableInstance::class.java.simpleName, "timer")
        close()
    }

    inline fun <reified T : AutoCloseable> key(key: Any, noinline factory: () -> T): Holder<T> {
        return key(T::class.java, key, factory)
    }

    @JvmStatic
    fun <T : AutoCloseable> key(clazz: Class<T>, key: Any, factory: () -> T): Holder<T> {
        synchronized(this@FCloseableInstance) {
            val keyedHolderFactory = _store[clazz] ?: KeyedHolderFactory<T>().also {
                _store[clazz] = it
            }
            return (keyedHolderFactory as KeyedHolderFactory<T>).create(key, factory).also {
                _timer.start()
            }
        }
    }

    @JvmStatic
    private fun close(errorHandler: (Exception) -> Unit = { it.printStackTrace() }) {
        synchronized(this@FCloseableInstance) {
            _store.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    item.value.close {
                        try {
                            it.close()
                        } catch (e: Exception) {
                            errorHandler(e)
                        }
                    }
                    if (item.value.isEmpty()) {
                        iterator.remove()
                    }
                }
            }

            if (_store.isEmpty()) {
                _timer.stop()
            }
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

private class IntervalTimer(
    private val interval: Long,
    private val task: Runnable,
) {
    private val _started = AtomicBoolean(false)
    private val _handler = Handler(Looper.getMainLooper())

    fun start() {
        if (_started.compareAndSet(false, true)) {
            _handler.postDelayed(_loopRunnable, interval)
        }
    }

    fun stop() {
        if (_started.compareAndSet(true, false)) {
            _handler.removeCallbacks(_loopRunnable)
        }
    }

    private val _loopRunnable = object : Runnable {
        override fun run() {
            if (_started.get()) {
                try {
                    task.run()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _handler.postDelayed(this, interval)
                }
            }
        }
    }
}