package app.boltmail.mail

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * "Sign in with Google" through Play services. Needs an Android OAuth client registered in
 * Google Cloud for this package name and signing certificate (see GOOGLE_SIGN_IN.md).
 */
object GoogleAuth {
    private const val MAIL_SCOPE = "https://mail.google.com/"

    /** Google wants the user to pick an account or consent again. */
    class NeedsConsent(val pendingIntent: PendingIntent?) : IOException("Google needs you to sign in again")

    /** Play services rejected the request; usually the OAuth client isn't set up for this build. */
    class NotConfigured(cause: Throwable) : IOException("Google sign-in isn't set up for this copy of BoltMail", cause)

    fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(MAIL_SCOPE), Scope("email"), Scope("profile")))
        if (!email.isNullOrBlank()) b.setAccount(android.accounts.Account(email, "com.google"))
        return b.build()
    }

    /** Returns a valid access token, refreshing it if needed. Blocking; call off the main thread. */
    fun token(context: Context, email: String): String {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email)), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw map(e.cause ?: e)
        }
        if (result.hasResolution()) throw NeedsConsent(result.pendingIntent)
        return result.accessToken ?: throw IOException("Google returned no access token")
    }

    fun map(t: Throwable): IOException = when {
        t is ApiException && t.statusCode == CommonStatusCodes.DEVELOPER_ERROR -> NotConfigured(t)
        t is ApiException && t.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED -> NeedsConsent(null)
        t is IOException -> t
        else -> IOException(t.message ?: "Google sign-in failed", t)
    }

    fun invalidate(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    /** Email address and display name for a fresh authorization. Blocking. */
    fun profile(result: AuthorizationResult): Pair<String?, String?> {
        @Suppress("DEPRECATION")
        val acct = runCatching { result.toGoogleSignInAccount() }.getOrNull()
        if (acct?.email != null) return acct.email to acct.displayName
        val token = result.accessToken ?: return null to null
        return runCatching {
            val c = URL("https://openidconnect.googleapis.com/v1/userinfo").openConnection() as HttpsURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer $token")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            json.optString("email").ifBlank { null } to json.optString("name").ifBlank { null }
        }.getOrDefault(null to null)
    }
}
