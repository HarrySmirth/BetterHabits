package app.betterhabits.data.remote

import app.betterhabits.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Builds the Supabase client from BuildConfig. Returns null when the build has no Supabase
 * configuration (e.g. CI or a fresh clone), so the app can show a clear "not configured" state.
 * Only the publishable key is ever used; authorisation is enforced server-side by RLS.
 */
object SupabaseProvider {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    fun create(): SupabaseClient? =
        if (isConfigured) create(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) else null

    /** Every plugin the app uses must be installed here (see SupabaseProviderTest). */
    fun create(url: String, publishableKey: String): SupabaseClient =
        createSupabaseClient(url, publishableKey) {
            httpEngine = OkHttp.create()
            install(Auth)
            install(Postgrest)
            install(Functions)
            install(Realtime)
        }
}
