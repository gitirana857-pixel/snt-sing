package com.sntsing.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela inicial simples — redireciona para a tela de gravação.
 * No futuro será substituída por login/home.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, RecordingActivity::class.java))
        finish()
    }
}
