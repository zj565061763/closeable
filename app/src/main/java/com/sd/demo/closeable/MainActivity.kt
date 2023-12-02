package com.sd.demo.closeable

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    // 代理对象
    private var _proxy1: FileResource? = FileResource.create("/sdcard/app.log")
    private var _proxy2: FileResource? = FileResource.create("/sdcard/app.log")
    private var _proxy3: FileResource? = FileResource.create("/sdcard/app.log.log")

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

inline fun logMsg(block: () -> Any) {
    Log.i("closeable-demo", block().toString())
}