package com.sd.demo.compose.facepreview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
  @Test
  fun application_isRegistered() {
    val application = ApplicationProvider.getApplicationContext<Context>()

    assertThat(application).isInstanceOf(App::class.java)
  }
}
