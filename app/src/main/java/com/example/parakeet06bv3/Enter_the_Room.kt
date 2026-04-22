package com.example.parakeet06bv3

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.parakeet06bv3.databinding.ActivityEnterTheRoomBinding
import com.example.parakeet06bv3.databinding.ActivityMainBinding

class Enter_the_Room : AppCompatActivity() {

    private val binding by lazy { ActivityEnterTheRoomBinding.inflate(layoutInflater) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets

            }

            // ⭐ 여기 추가
          binding.enter.setOnClickListener {
            val intent = Intent(this, WebRTC::class.java)
            startActivity(intent)
        }


    }
}