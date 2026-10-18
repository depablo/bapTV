package com.bonsaponsi.baptv.testtuner

import android.app.Activity
import android.media.tv.TvInputInfo
import android.os.Bundle
import android.widget.TextView

/** Input setup: immediately "finds" the test channels and finishes with RESULT_OK. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputId = intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
            ?: android.media.tv.TvContract.buildInputId(android.content.ComponentName(this, TestTunerService::class.java))
        val result = runCatching { TestChannels.install(this, inputId) }
        result.exceptionOrNull()?.let { android.util.Log.e("TestTuner", "saving channels failed", it) }
        setContentView(TextView(this).apply {
            text = result.fold({ "Test tuner: channels saved: $it" }, { "Test tuner: error saving channels: ${it.message}" })
            textSize = 22f
            setPadding(48, 48, 48, 48)
        })
        setResult(if (result.isSuccess) RESULT_OK else RESULT_CANCELED)
        window.decorView.postDelayed({ finish() }, 1500)
    }
}
