package com.cosmic.livestudio

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.cosmic.livestudio.receiver.ReceiverActivity
import com.cosmic.livestudio.sender.SenderActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSenderMode = findViewById<Button>(R.id.btnSenderMode)
        val btnReceiverMode = findViewById<Button>(R.id.btnReceiverMode)

        btnSenderMode.setOnClickListener {
            startActivity(Intent(this, SenderActivity::class.java))
        }

        btnReceiverMode.setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }
}
