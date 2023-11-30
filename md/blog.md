# 前言

`java`对象可以重写`finalize()`方法来监听当前对象没有被引用可以被回收。一般是在这个方法里面做一些释放资源的逻辑。然而它早就被废弃了，因为用它要特别小心，容易出问题，不是本文讨论的重点，有兴趣的同学可以去查一下资料，建议新写的业务不要依赖这个方法，接下来我们探索一下如何替代它。

# 分析

替代条件：`在对象没有被引用的时候调用对象的方法释放资源。`

1. 谁？在什么时候？调用对象的方法释放资源
2. 怎么知道对象没有被引用了
3. 调用对象的什么方法释放资源

最直觉的想法是，开一个定时器，定时检查注册的对象是否可以被释放。

那我们就尝试这样子实现，来看看可以不可以，哈哈，当然是不可以，看看我们会卡在哪一步，程序员还是看代码比较直观。

#### 尝试

为了可读性，演示代码不考虑严格的逻辑，读者不要介意。

第3个问题很好解决，我们用统一的接口就可以了，刚好有现成`java.lang.AutoCloseable`：

```java
public interface AutoCloseable {
    void close() throws Exception;
}
```

来吧，定时器和管理类搞起来

定时器：

```kotlin
private class CloseableTimer(
    // 定时器间隔，毫秒
    private val interval: Long,
    // 定时器运行的block
    private val block: () -> Unit,
) {
    private val _handler = Handler(Looper.getMainLooper())

    private val _loopRunnable = object : Runnable {
        override fun run() {
            try {
                block()
            } finally {
                _handler.postDelayed(this, interval)
            }
        }
    }

    init {
        _handler.postDelayed(_loopRunnable, interval)
    }
}
```

定时器的逻辑比较简单，根据传入的时间间隔`interval`，不断的回调`block`。

管理类：

```kotlin
object CloseableManager {
    /** 弱引用保存 */
    private val _holder = WeakHashMap<AutoCloseable, String>()

    /** 注册对象 */
    fun register(instance: AutoCloseable) {
        _holder[instance] = ""
    }

    // 定时器
    private val _timer = CloseableTimer(10_000) {
        _holder.iterator().run {
            while (hasNext()) {
                val item = next()
                val closeable = item.key
                // 写不下去了，能被遍历到的都是还有被引用的对象
            }
        }
    }
}
```

内部采用`java.util.WeakHashMap`来保存`AutoCloseable`，当你用`key`来保存一个`AutoCloseable`对象，之后如果遍历的时候找不到这个对象了，那说明这个对象已经没有引用指向它了，这个对象就达到我们能调用`close()`方法的条件。

这里就矛盾了，既然没有引用指向这个对象了，又怎么调用到对象的`close()`方法呢？<br>
代码写不下去了，我们开始思考新的方法。

# 探索一

既然要调用到对象的`close()`方法，内部一定要强引用它，等到外部没有引用它，只有内部强引用它的时候，才能`close()`。

怎么知道外部已经没有引用指向这个对象了？

我们可以做一层包装，在外部注册`原始对象`的时候，返回一个`包装对象`给外部使用，此时内部用弱引用保存`包装对象`。

当外部没有引用指向`包装对象`的时候，就说明我们可以调用`原始对象`的`close()`方法释放资源了，
但此时我们用弱引用保存`包装对象`，怎么找到它映射到`原始对象`呢？

既然内部要用弱引用保存`包装对象`，就先看一下弱引用：


```java
public class WeakReference<T> extends Reference<T> {
    public WeakReference(T referent) {
        super(referent);
    }
    public WeakReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }
}
```

第二个构造方法中的第二个参数，类型是`ReferenceQueue`，它的作用是弱引用保存的对象获取不到的时候（即已经没有引用指向保存的对象了），弱引用本身会被添加到这个`ReferenceQueue`中，后续我们可以从这个`ReferenceQueue`中找到弱引用，这里一定要看清楚是找到`弱引用`，而不是`弱引用保存的对象`。

到这里思路已经通了，我们在`ReferenceQueue`中拿到弱引用，再根据弱引用找到映射的`原始对象`，就可以`close()`了，如果看到这里有点不理解的话没关系，我们看代码吧。

#### 实现

模拟一个文件资源接口：

```kotlin
interface FileResource : AutoCloseable {
    fun write(content: String)
}
```

创建包装类：

```kotlin
class Wrapper<T : FileResource>(val instance: T)
```

修改管理类：

```kotlin
// 起一个别名方便阅读
typealias WeakKey = WeakReference<Wrapper<out FileResource>>

object FileResourceManager {
    private val _holder = mutableMapOf<WeakKey, FileResource>()
    private val _refQueue = ReferenceQueue<Wrapper<out FileResource>>()

    fun <T : FileResource> register(instance: T): Wrapper<T> {
        // 1.创建包装对象，包住原始对象instance
        val wrapper = Wrapper(instance)

        // 2.弱引用保存包装对象
        val ref = WeakKey(wrapper, _refQueue)

        // 3.弱引用映射原始对象
        _holder[ref] = instance

        // 4.返回包装对象给外部使用
        return wrapper
    }

    private val _timer = CloseableTimer(10_000) {
        while (true) {
            // 1.拿到弱引用
            val ref = _refQueue.poll() ?: break

            // 2.弱引用获取映射的原始对象
            val instance = _holder[ref]

            // 3.关闭原始对象
            instance?.close()
        }
    }
}
```

看一下`register()`逻辑，先创建一个`包装对象`来包住`原始对象`，并用弱引用保存`包装对象`，这里要注意，创建弱引用使用的是上文提到的第二个构造方法，需要传`ReferenceQueue`参数`_refQueue`，然后再把弱引用和`原始对象`做一个映射，最后返回`包装对象`给外部使用。

看一下定时器逻辑，从`_refQueue`中不断的取弱引用，如果能取到，就说明这个弱引用保存的对象已经没有引用指向它了，也就是注册的时候返回的`Wrapper`对象已经没有引用指向它了，此时就可以调用`原始对象`的`close()`方法关闭了。

#### 测试

文件资源接口的实现类：

```kotlin
class FileResourceImpl : FileResource {
    override fun write(content: String) {
        logMsg { "write $content $this" }
    }

    override fun close() {
        logMsg { "close $this" }
    }
}
```

在`write()`和`close()`方法里面打印了日志。

外部使用：

```kotlin
class MainActivity : AppCompatActivity() {
    // 包装对象
    private val _wrapper = FileResourceManager.register(FileResourceImpl())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 触发write方法
        _wrapper.instance.write("content")

        // 关闭页面
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        logMsg { "onDestroy" }
    }
}
```

在`onCreate()`方法里面调用包装对象的方法，然后就关闭页面，我们运行demo之后页面被关闭，此时我们可以打开`Profiler`手动触发垃圾回收，等待定时器触发，看看`close()`是否会被调用，来看看日志：

```
19:25:17.323 closeable-demo          com.sd.demo.closeable           I  write content com.sd.demo.closeable.FileResourceImpl@25d4406
19:25:18.405 closeable-demo          com.sd.demo.closeable           I  onDestroy
19:25:38.485 closeable-demo          com.sd.demo.closeable           I  close com.sd.demo.closeable.FileResourceImpl@25d4406
```

可以看到`close()`被调用了，因为页面关闭触发垃圾回收之后`Activity`对象被回收了，它持有的`包装对象`，即`_wrapper`也被回收了。

这样子虽然初步实现了，但是很繁琐而且有使用风险，如果外部不注意直接持有了`原始对象`，那`包装对象`就会因为没有被持有，导致被回收了，定时器触发的时候，还是会调用`原始对象`的`close()`方法。

外部直接持有`原始对象`：

```kotlin
private val _instance = FileResourceManager.register(FileResourceImpl()).instance
```

搞了半天，弄了个半成品，我们继续探索。

# 探索二

思路不变，还是采用`包装`的方式，但是有没有办法不对外暴露这个`包装对象`呢？

因为`FileResource`是一个接口，要包装一个接口，那只能想到动态代理了。`java`提供了现成的api可以让我们方便的创建某个接口的代理对象，这个api位于`java.lang.reflect.Proxy`中：

```java
public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces, InvocationHandler h)
```

我们直接来使用这个api：

```kotlin
val clazz = FileResource::class.java

// 创建FileResource接口的代理对象
val wrapper = Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), object : InvocationHandler{
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return null
    }
}) as FileResource

logMsg { 
    wrapper.write("content")
}
```

不了解`java`动态代理的同学可能会有疑问，传一个接口的`Class`就能创建出这个接口的实现类对象？没错确实是这样的，我们称它为代理对象，对应上文中的`包装对象`。

那调用这个代理对象的方法会发生什么？这里是重点，也就是上面api方法中的第三个参数`InvocationHandler`，它是一个接口。当代理对象的方法被调用的时候，`InvocationHandler.invoke()`方法会被回调，具体看一下这个接口：

```java
public interface InvocationHandler {
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
}
```
`proxy`是代理对象，`method`是被调用的方法，`args`是调用这个方法的参数列表。最后返回一个值，这个值会被当作代理对象方法调用的返回值。

到这里思路打通了，我们可以返回一个代理对象给外部使用，对外部来说，拿到的是`FileResource`接口的实现类，就没有`包装`这一个概念。

#### 实现


