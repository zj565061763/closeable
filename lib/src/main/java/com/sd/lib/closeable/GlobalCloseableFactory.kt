package com.sd.lib.closeable

object FGlobalCloseableFactory {
    private val _holder: MutableMap<Class<out AutoCloseable>, FCloseableFactory<out AutoCloseable>> = hashMapOf()

    @JvmStatic
    fun <T : AutoCloseable> create(clazz: Class<T>, key: String, factory: () -> T): T {
        return factoryOf(clazz).create(key, factory)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : AutoCloseable> factoryOf(clazz: Class<T>): FCloseableFactory<T> {
        synchronized(FGlobalCloseableFactory) {
            val value = _holder.getOrPut(clazz) {
                object : FCloseableFactory<T>(clazz) {
                    override fun onEmpty() {
                        super.onEmpty()
                        synchronized(FGlobalCloseableFactory) {
                            _holder.remove(clazz)
                        }
                    }
                }
            }
            return value as FCloseableFactory<T>
        }
    }
}
