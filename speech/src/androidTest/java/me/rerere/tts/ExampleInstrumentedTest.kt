package me.rerere.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 *
 * 断言的包名必须等于模块的 **namespace**（库模块的 test APK 包名就是 `<namespace>.test`）——
 * 这里写的是历史遗留的 `me.rerere.tts.test`，而模块 namespace 早已是 `me.rerere.speech`，
 * 于是这条测试从接通 instrumented CI 起就一直失败（此前没人跑，所以没人知道）。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("me.rerere.speech.test", appContext.packageName)
    }
}
