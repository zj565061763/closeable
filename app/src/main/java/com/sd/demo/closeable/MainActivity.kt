package com.sd.demo.closeable

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.sd.demo.closeable.databinding.ActivityMainBinding
import com.sd.lib.closeable.FCloseableStore

class MainActivity : AppCompatActivity() {
    private val _binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(_binding.root)
        _binding.btn.setOnClickListener {
            val c1 = FCloseableStore.key("key", AppCloseable::class.java) { AppCloseableImpl() }
            logMsg { "c1 result:${c1.method(1)}" }

            val c2 = FCloseableStore.key("key", AppCloseable::class.java) { AppCloseableImpl() }
            logMsg { "c2 result:${c1.method(2)}" }

            check(c1 !== c2)
        }
    }
}

inline fun logMsg(block: () -> Any) {
    Log.i("closeable-demo", block().toString())
}