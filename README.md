# About

关于本库，请看[这里](https://juejin.cn/post/7307469456359473152)

# Gradle

[![](https://jitpack.io/v/zj565061763/closeable.svg)](https://jitpack.io/#zj565061763/closeable)

# Sample

1. 模拟文件资源接口

```kotlin
/**
 * 文件资源接口
 */
interface FileResource : AutoCloseable {
    fun write(content: String)
}
```

2. 文件资源接口实现类

```kotlin
/**
 * 文件资源接口实现类
 */
private class FileResourceImpl(private val path: String) : FileResource {
    override fun write(content: String) = Unit
    override fun close() = Unit
}
```

3. 文件资源工厂

```kotlin
/**
 * 文件资源接口工厂
 */
object FileResourceFactory {
    private val _factory = FAutoCloseFactory(FileResource::class.java)

    /**
     * 创建[path]对应的文件资源
     */
    fun create(path: String): FileResource {
        return _factory.create(path) { FileResourceImpl(path) }
    }
}
```

4. 使用资源接口

```kotlin
class MainActivity : AppCompatActivity() {
    // 获取某个路径对应的文件资源
    private var _fileResource: FileResource? = FileResourceFactory.create("/sdcard/app.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 模拟操作资源
        _fileResource?.write("hello world")

        // 置为null，等待主线程空闲的时候，自动关闭资源
        _fileResource = null
    }
}
```