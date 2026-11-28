package app.familygem.geni

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Developer tool, not a test: calls the Geni sandbox API with the login stored by the installed app,
 * to set up and inspect cases (conflicts, merged and deleted profiles) for checks on a device.
 * Skipped unless started with arguments, and only against sandbox.geni.com. Responses go to logcat (tag GeniTool).
 *
 * Run without uninstalling the app (connectedAndroidTest would remove it and its login):
 * `gradle :app:installDebug :app:installDebugAndroidTest`, then
 * `adb shell am instrument -w -e class app.familygem.geni.GeniSandboxTool -e geniGet "profile-1"
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`;
 * `-e geniPost "profile-1/update" -e geniParams "occupation=Pilot"` posts form parameters (pairs joined by `&`, `\n` in a value is a line break).
 */
@RunWith(AndroidJUnit4::class)
class GeniSandboxTool {

    @Test
    fun call() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val get = args.getString("geniGet")
        val post = args.getString("geniPost")
        assumeTrue("no geniGet or geniPost argument", get != null || post != null)
        check(Geni.config.host == "sandbox.geni.com") { "the tool only talks to the sandbox" }
        val client = Geni.client(InstrumentationRegistry.getInstrumentation().targetContext)
        val response = if (get != null) client.getJson(get) else {
            val params = args.getString("geniParams").orEmpty().split('&').filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to it.substringAfter('=', "").replace("\\n", "\n") }
            client.post(post!!, params)
        }
        response.chunked(3000).forEach { Log.i("GeniTool", it) }
    }
}
