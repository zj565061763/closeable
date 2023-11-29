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
            val holder1 = FCloseableStore.key("key") { MyCloseable() }
            holder1.instance.method()

            val holder2 = FCloseableStore.key("key") { MyCloseable() }
            holder2.instance.method()
            check(holder1.instance === holder2.instance)
        }
    }
}

inline fun logMsg(block: () -> Any) {
    Log.i("closeable-demo", block().toString())
}