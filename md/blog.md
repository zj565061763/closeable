# 前言

`java`中可以重写`finalize()`方法来监听对象即将被回收，在里面做一些释放资源的操作，但是它被废弃了，有兴趣的同学可以查一下资料，我们探索一下有没有方案替代它。

# 分析

一般来说访问硬件或者文件资源的实例，在使用完毕之后需要关闭，如果忘记关闭了，`finalize()`被回调的时候也会关闭。如果不依赖`finalize()`，我们该怎么实现？

模拟一个文件资源接口，以及它的工厂类：

```kotlin
interface FileResource {
    fun write(content: String)
    fun close()
}

object FileResourceFactory {
    fun create(path: String): FileResource {
        // TODO 创建接口实例
    }
}
```

`FileResource`实例在使用完成后需要调用`close()`来释放资源。

假设我们是`FileResource`这个库的开发者，如果外部忘记调用`close()`了，<br>
我们应该在没有引用指向实例的时候调用`close()`，那怎么判断没有引用指向实例了？

可以在外部从工厂获取实例的时候返回一个代理对象给外部使用，具体步骤如下：

1. 创建原始对象，`强`引用保存
2. 创建代理对象，`弱`引用保存
3. 弱引用映射到原始对象
4. 返回代理对象给外部使用

说的比较抽象了，我们继续看下面的实现吧。

# 实现

先写`FileResource`的实现类，通常访问某个资源会有一个`唯一标识`，例如文件资源的路径，代码如下：

```kotlin
class FileResourceImpl(private val path: String) : FileResource {
    override fun write(content: String) {
        logMsg { "write $content $this" }
    }

    override fun close() {
        logMsg { "close $this" }
    }
}
```

再写代理类，就是对原始对象做一层包装，这里利用了`kotlin`语法特性，通过`by`委托给传入的原始对象，实现如下：

```kotlin
class FileResourceProxy(
    private val instance: FileResource
) : FileResource by instance
```

最后写工厂类，负责创建`FileResource`实例：

```kotlin
object FileResourceFactory {
    private val _holder = mutableMapOf<WeakReference<FileResource>, FileResource>()
    private val _refQueue = ReferenceQueue<FileResource>()

    // 创建实例
    fun create(path: String): FileResource {
        // 1.创建原始对象
        val instance = FileResourceImpl(path)

        // 2.创建代理对象
        val proxy = FileResourceProxy(instance)

        // 3.弱引用保存代理对象
        val weak = WeakReference<FileResource>(proxy, _refQueue)

        // 4.弱引用映射原始对象
        _holder[weak] = instance

        // 返回代理对象给外部使用
        return proxy
    }
}
```

`create()`方法返回到是代理对象`proxy`，`proxy`被第3步中创建的`weak`弱引用保存，当外部没有引用指向`proxy`的时候后，`weak`就会被放入`_refQueue`中。

`_refQueue`的类型是`ReferenceQueue`，它的作用就是`WeakReference`保存的对象没有被引用的时候，垃圾回收机制会把`WeakReference`添加到`ReferenceQueue`中。

换句话说，当我们在`ReferenceQueue`中能拿到`WeakReference`的时候，`WeakReference`之前保存的对象已经没有被引用了，刚好符合我们的需求，就是`proxy`对象已经没有被引用了。

接着，我们再定义一个关闭方法来检查`ReferenceQueue`：

```kotlin
fun close() {
    while (true) {
        // 1.从_refQueue中取弱引用
        val weak = _refQueue.poll() ?: break

        // 2.弱引用获取映射的原始对象
        val instance = _holder.remove(weak)

        // 3.关闭原始对象
        instance?.close()
    }
}
```

`close()`方法不断的从`_refQueue`中取弱引用，如果能取到，说明这个弱引用保存的代理对象已经没有被引用了，此时我们可以关闭原始对象了。

来测试一下它能不能正常工作，测试代码：

```kotlin
class MainActivity : AppCompatActivity() {
    // 代理对象
    private var _proxy: FileResource? = FileResourceFactory.create("/sdcard/app.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 触发write方法
        _proxy?.write("content")
    }

    override fun onStart() {
        super.onStart()
        logMsg { "onStart" }
        // 检查关闭
        FileResourceFactory.close()
    }

    override fun onStop() {
        super.onStop()
        logMsg { "onStop" }
        // 引用置为null
        _proxy = null
    }
}
```

```
12:35:30.735 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@6a57e3a
12:35:30.745 closeable-demo          com.sd.demo.closeable           I  onStart
12:35:35.018 closeable-demo          com.sd.demo.closeable           I  onStop
12:35:42.202 closeable-demo          com.sd.demo.closeable           I  onStart
12:35:42.203 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@6a57e3a
```

在`onStop()`里面将引用置为`null`，然后打开`Profiler`手动触发垃圾回收，`onStart()`里面调用`FileResourceFactory.close()`方法，根据日志可以看到对象的`close()`方法被触发了。

# 优化

流程已经走通了，但现在我们是主动调用`FileResourceFactory.close()`来关闭的，怎么做到自动关闭，这个放到最后来处理，先来看看有没有可以优化的地方。

#### 优化一

每一个资源接口都要写一个代理类，这太机械化了，我们可以利用`java`动态代理来创建代理对象，关于`java`动态代理这里不赘述，直接看代码：

```kotlin
// 创建实例
fun create(path: String): FileResource {
    // ...

    // 2.创建代理对象
    val clazz = FileResource::class.java
    val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
        if (args != null) {
            method.invoke(instance, *args)
        } else {
            method.invoke(instance)
        }
    } as FileResource

    //...
    return proxy
}
```

这样子我们就不需要`FileResourceProxy`这个类了。

#### 优化二

在实际的场景中，一般有`唯一标识`的资源要考虑并发问题，`create()`的时候，如果他们传的参数`path`是一样的，那么返回的代理对象应该指向同一个原始对象，这样子我们只要考虑对象内部的线程同步逻辑就可以了。

资源的`唯一标识`可以理解为`key`，每个`key`对应一个实例。

先不考虑多个`key`的场景，只考虑单个实例的场景，单实例的工厂类写好后，我们把`key`和这个类做一个映射就可以了。

看一下单实例工厂类的代码：

```kotlin
class SingletonFileResourceFactory {
    private var _instance: FileResource? = null
    private val _proxyHolder = WeakHashMap<FileResource, String>()

    // 创建实例
    fun create(factory: () -> FileResource): FileResource {
        // 原始对象，如果为null，就调用factory创建
        val instance = _instance ?: factory().also {
            _instance = it
        }

        // 创建代理对象
        val clazz = FileResource::class.java
        val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
            if (args != null) {
                method.invoke(instance, *args)
            } else {
                method.invoke(instance)
            }
        } as FileResource

        // 弱引用保存代理对象
        _proxyHolder[proxy] = ""
        return proxy
    }

    // 代理对象是否为空
    fun isEmpty(): Boolean {
        return _proxyHolder.isEmpty()
    }

    // 关闭
    fun close() {
        if (isEmpty()) {
            // _proxyHolder为空，说明外部的代理对象已经都用完了，可以关闭原始对象了
            _instance?.close()
            _instance = null
        }
    }
}
```

单实例工厂的逻辑比较简单，内部保存一个原始对象，如果为`null`就调用`factory`参数创建保存。

代理对象则换成了`WeakHashMap`保存，当`_proxyHolder`为空的时候说明外部的代理对象都已经用完了，没有引用了，此时可以关闭内部保存的原始对象，即`close()`方法的逻辑。


单实例工厂写好后，多个`key`对应多个实例的工厂就很好写了，看一下代码：

```kotlin
object FileResourceFactory {
    private val _holder = mutableMapOf<String, SingletonFileResourceFactory>()

    // 创建实例
    fun create(path: String): FileResource {
        val singletonFactory = _holder[path] ?: SingletonFileResourceFactory().also {
            _holder[path] = it
        }
        return singletonFactory.create { FileResourceImpl(path) }
    }

    // 关闭空闲的对象
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
```

代码简化了很多，`_holder`是一个`Map`，把`key`和单实例工厂做一个映射，`create()`的时候直接调用单实例工厂的`create()`即可。`close()`方法的逻辑也很简单，只要遍历所有单实例工厂调用`close()`就可以了。

#### 优化三

如果每个资源接口都写这么一套逻辑，还是很繁琐的，可以写一个通用模板，通用模板不关心具体是什么资源接口了，只要它有提供关闭方法就可以了。

刚好有现成的接口`java.lang.AutoCloseable`，我们就用它来写通用模板：

```java
public interface AutoCloseable {
    void close() throws Exception;
}
```

单实例工厂模板：

```kotlin
class SingletonFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private var _instance: T? = null
    private val _proxyHolder = WeakHashMap<T, String>()

    // 创建实例
    fun create(factory: () -> T): T {
        // 原始对象，如果为null，就调用factory创建
        val instance = _instance ?: factory().also {
            _instance = it
        }

        // 创建代理对象
        val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, args ->
            if (args != null) {
                method.invoke(instance, *args)
            } else {
                method.invoke(instance)
            }
        } as T

        // 弱引用保存代理对象
        _proxyHolder[proxy] = ""
        return proxy
    }
}
```

代码和之前的`SingletonFileResourceFactory`差不多，只不过`FileResource`被抽象为模板了，`Class`从构造方法传进来，其他一模一样的代码就没有贴出来了。

多实例工厂模板：

```kotlin
class CloseableFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private val _holder = mutableMapOf<String, SingletonFactory<T>>()

    // 创建key对应的实例
    fun create(key: String, factory: () -> T): T {
        val singletonFactory = _holder[key] ?: SingletonFactory(clazz).also {
            _holder[key] = it
        }
        return singletonFactory.create(factory)
    }
}
```

代码和之前的`FileResourceFactory`差不多，就不赘述了。

#### 重构

模板写好了，我们来重构一下代码，`FileResource`接口要继承`java.lang.AutoCloseable`

```kotlin
interface FileResource : AutoCloseable {
    fun write(content: String)
}
```

重构一下`FileResourceFactory`，内部使用`CloseableFactory`

```kotlin
object FileResourceFactory {
    private val _factory = CloseableFactory(FileResource::class.java)

    // 创建path对应的实例
    fun create(path: String): FileResource {
        return _factory.create(path) { FileResourceImpl(path) }
    }

    // 检查关闭
    fun close() {
        _factory.close()
    }
}
```

重构完了，方法体里面一行代码搞定，我们来测试一下重构之后是否能正常工作


```kotlin
class MainActivity : AppCompatActivity() {
    // 代理对象
    private var _proxy1: FileResource? = FileResourceFactory.create("/sdcard/app.log")
    private var _proxy2: FileResource? = FileResourceFactory.create("/sdcard/app.log")
    private var _proxy3: FileResource? = FileResourceFactory.create("/sdcard/app.log.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 触发write方法
        _proxy1?.write("content")
        _proxy2?.write("content")
        _proxy3?.write("content")
    }

    override fun onStart() {
        super.onStart()
        logMsg { "onStart" }
        // 检查关闭
        FileResourceFactory.close()
    }

    override fun onStop() {
        super.onStop()
        logMsg { "onStop" }
        // 引用置为null
        _proxy1 = null
        _proxy2 = null
        _proxy3 = null
    }
}
```
```
15:09:44.417 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@bc5d31e
15:09:44.417 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@bc5d31e
15:09:44.417 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@c237ff
15:09:44.428 closeable-demo          com.sd.demo.closeable           I  onStart
15:09:50.580 closeable-demo          com.sd.demo.closeable           I  onStop
15:09:54.848 closeable-demo          com.sd.demo.closeable           I  onStart
15:09:54.848 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@bc5d31e
15:09:54.848 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@c237ff
```

一共有3个代理对象，`_proxy1`，`_proxy2`，`_proxy3`，实际上第1个和第2个他们代理的是同一个原始对象，因为他们的`path`一样。

从日志也可以看出一共是2个原始对象，最终`FileResourceFactory.close()`执行的时候也确实`close()`了2个原始对象。

# 自动关闭

这一节我们来实现自动关闭，即自动触发`FileResourceFactory.close()`。

可以用`IdleHandler`实现，关于`IdleHandler`，有专门讲它的文章，这里就不赘述了，简单来说就是当你在主线程注册一个`IdleHandler`后，它会在主线程空闲的时候被执行。

看一下代码：

```kotlin
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
```

这里说明一下构造方法中`block`的返回值`Boolean`代表什么意思：

`true`表示`IdleHandler`监听对象还要继续监听后续的线程空闲事件<br>
`false`表示不再继续监听了，这个`IdleHandler`就会被移除

我们在多实例工厂`CloseableFactory`中使用一下它，看一下它的完整代码：

```kotlin
class CloseableFactory<T : AutoCloseable>(
    private val clazz: Class<T>
) {
    private val _holder = mutableMapOf<String, SingletonFactory<T>>()

    // 创建key对应的实例
    fun create(key: String, factory: () -> T): T {
        val singletonFactory = _holder[key] ?: SingletonFactory(clazz).also {
            _holder[key] = it
        }
        // 注册IdleHandler
        _idleHandler.register()
        return singletonFactory.create(factory)
    }

    private val _idleHandler = SafeIdleHandler {
        close()
        // 返回true，表示继续监听空闲回调；返回false，表示不继续监听了
        _holder.isNotEmpty()
    }

    private fun close() {
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
```

最后我们再来测试一下：

```kotlin
class MainActivity : AppCompatActivity() {
    // 代理对象
    private var _proxy1: FileResource? = FileResourceFactory.create("/sdcard/app.log")
    private var _proxy2: FileResource? = FileResourceFactory.create("/sdcard/app.log")
    private var _proxy3: FileResource? = FileResourceFactory.create("/sdcard/app.log.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 触发write方法
        _proxy1?.write("content")
        _proxy2?.write("content")
        _proxy3?.write("content")
    }

    override fun onStop() {
        super.onStop()
        logMsg { "onStop" }
        // 引用置为null
        _proxy1 = null
        _proxy2 = null
        _proxy3 = null
    }
}
```

```
20:20:51.112 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@bc5d31e
20:20:51.112 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@bc5d31e
20:20:51.112 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@c237ff
20:20:56.600 closeable-demo          com.sd.demo.closeable           I  onStop
20:21:01.313 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@c237ff
20:21:01.313 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@bc5d31e
```

和上面的测试代码差不多，去掉了手动`close()`的代码，可以看到已经可以自动`close()`了。

如果读者有自己的资源接口，想实现自动关闭的功能，只要在你的工厂类中使用`CloseableFactory`就可以了，当然了，要保证它是单例的，因为这样子才可以实现同一个`key`对应的是同一个原始对象。

# 结束

最终完整版的代码放在了这里，有兴趣的同学可以看看：[closeable](https://github.com/zj565061763/closeable)<br>
感谢大家的阅读，如果有遇到问题欢迎和作者交流。

作者邮箱：565061763@qq.com