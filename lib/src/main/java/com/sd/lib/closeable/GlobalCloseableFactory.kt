package com.sd.lib.closeable

object FGlobalCloseableFactory {
    private val _holder: MutableMap<Class<out AutoCloseable>, FCloseableFactory<out AutoCloseable>> = hashMapOf()

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T : AutoCloseable> factoryOf(clazz: Class<T>): FCloseableFactory<T> {
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

fun <T : AutoCloseable> fCloseableFactoryOf(clazz: Class<T>): FCloseableFactory<T> {
    return FGlobalCloseableFactory.factoryOf(clazz)
}
