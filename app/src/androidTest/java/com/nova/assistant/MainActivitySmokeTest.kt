package com.nova.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Runs on a fresh emulator install, before any microphone permission is granted. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivitySmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aLaunchWithoutMicrophonePermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                assertTrue(texts(activity.window.decorView).contains("Mikrofona dokunarak konuş."))
                assertNotNull(listenButton(activity.window.decorView))
            }
        }
    }

    @Test fun bDenyMicrophonePermissionKeepsAppUsable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> listenButton(activity.window.decorView)!!.performClick() }
            val device = UiDevice.getInstance(instrumentation)
            val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 60_000)
            assertNotNull("The microphone permission dialog should appear", deny)
            deny!!.click()
            assertTrue(
                "The permission callback should display the microphone denial message",
                device.wait(Until.hasObject(By.text("Konuşmak için mikrofon izni gerekli.")), 30_000)
            )
            scenario.onActivity { activity ->
                assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                assertTrue(texts(activity.window.decorView).contains("Konuşmak için mikrofon izni gerekli."))
                assertTrue(listenButton(activity.window.decorView)!!.isEnabled)
            }
        }
    }

    @Test fun cMissingSpeechProviderShowsUsefulMessage() {
        val context = instrumentation.targetContext
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assumeTrue("This check requires an emulator without a speech recognition activity", intent.resolveActivity(context.packageManager) == null)
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listenButton(activity.window.decorView)!!.performClick()
                assertTrue(texts(activity.window.decorView).contains("Ses tanıma hizmeti bulunamadı. Google ses hizmetlerini kontrol et."))
            }
        }
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }

    private fun listenButton(view: View): Button? {
        if (view is Button && view.text.contains("Nova ile konuş")) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            listenButton(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
