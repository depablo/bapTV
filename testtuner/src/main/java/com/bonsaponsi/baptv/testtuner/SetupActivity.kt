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
        // Test scripts: `am start -n …/.SetupActivity --ez clear true` removes the channels (a clean start).
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            val removed = contentResolver.delete(android.media.tv.TvContract.buildChannelsUriForInput(inputId), null, null)
            android.util.Log.i("TestTuner", "removed $removed channels of $inputId")
            setResult(RESULT_OK)
            finish()
            return
        }
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

    private companion object {
        const val EXTRA_CLEAR = "clear"
    }
}
