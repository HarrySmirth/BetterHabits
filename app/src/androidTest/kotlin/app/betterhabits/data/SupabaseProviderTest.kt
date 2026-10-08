package app.betterhabits.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.betterhabits.data.remote.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.realtime
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test: a missing plugin only fails when first used on a real client (the Phase 3
 * startup crash was Realtime not being installed). Accessing each plugin throws if it's missing.
 */
@RunWith(AndroidJUnit4::class)
class SupabaseProviderTest {

    @Test
    fun everyPluginTheAppUsesIsInstalled() {
        val client = SupabaseProvider.create("https://example.supabase.co", "sb_publishable_test")
        client.auth
        client.postgrest
        client.functions
        client.realtime
    }
}
