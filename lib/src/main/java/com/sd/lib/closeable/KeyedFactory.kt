package com.sd.lib.closeable

internal class KeyedFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private val _holder = mutableMapOf<String, SingletonFactory<T>>()

    fun create(key: String, factory: () -> T): T {
        val singletonFactory = _holder[key] ?: SingletonFactory(clazz).also {
            _holder[key] = it
        }
        return singletonFactory.create(factory)
    }

    fun close() {
        _holder.iterator().run {
            while (hasNext()) {
                val item = next()
                val factory = item.value
                try {
                    factory.close()
                } finally {
                    if (factory.isEmpty()) {
                        remove()
                    }
                }
            }
        }
    }
}