package ai.sharemate.app

import android.content.Context
import android.util.AtomicFile
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/*
 * Who is using this phone (2.9). The phone signs in with the employee's Google account; the account is kept on the phone
 * only (no SHAREMATE server), and is what the first-run onboarding, the reservations and the settings page are tied to.
 * The car still decides every change; the account says who asked.
 */

enum class AccountProvider(val label: String) { GOOGLE("Google 계정"), DEMO("시연 계정") }

data class PhoneAccount(
    val id: String,
    val email: String,
    val name: String,
    val photoUrl: String? = null,
    val provider: AccountProvider = AccountProvider.GOOGLE,
    val signedInAt: Long = 0,
    /** False until the first-run onboarding (profile and the first company vehicle) is finished or skipped. */
    val onboarded: Boolean = false,
    /** The employee this account is on the selected car's driver list, once chosen. */
    val driverId: String? = null,
    val department: String = "",
)

internal fun encodeAccount(account: PhoneAccount): String = JSONObject().put("id", account.id).put("email", account.email).put("name", account.name)
    .put("photoUrl", account.photoUrl ?: JSONObject.NULL).put("provider", account.provider.name).put("signedInAt", account.signedInAt)
    .put("onboarded", account.onboarded).put("driverId", account.driverId ?: JSONObject.NULL).put("department", account.department).toString()

internal fun decodeAccount(text: String): PhoneAccount = JSONObject(text).let { j ->
    PhoneAccount(j.getString("id"), j.getString("email"), j.getString("name"), if (j.isNull("photoUrl")) null else j.getString("photoUrl"),
        runCatching { AccountProvider.valueOf(j.getString("provider")) }.getOrDefault(AccountProvider.GOOGLE), j.optLong("signedInAt"),
        j.optBoolean("onboarded"), if (j.isNull("driverId")) null else j.optString("driverId").takeIf { it.isNotBlank() }, j.optString("department"))
}

/** The signed-in account, persisted in no-backup storage so it never leaves the phone through a cloud backup. */
class AccountStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "phone-account.json"))
    private val mutable = MutableStateFlow(runCatching { if (file.baseFile.exists()) decodeAccount(file.readFully().toString(Charsets.UTF_8)) else null }.getOrNull())
    val account: StateFlow<PhoneAccount?> = mutable.asStateFlow()

    suspend fun save(account: PhoneAccount) = withContext(Dispatchers.IO) {
        val stream = file.startWrite()
        try { stream.write(encodeAccount(account).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = account
    }

    suspend fun update(transform: (PhoneAccount) -> PhoneAccount) { account.value?.let { save(transform(it)) } }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete(); mutable.value = null }

    companion object {
        @Volatile private var instance: AccountStore? = null
        fun get(context: Context): AccountStore = instance ?: synchronized(this) { instance ?: AccountStore(context).also { instance = it } }
    }
}

/** Why Google sign-in could not finish, in the sentence the sign-in screen shows. */
class SignInException(message: String, val cancelled: Boolean = false) : Exception(message)

/** True when this build carries the company's Google OAuth Web client ID (see app/build.gradle.kts). */
internal val googleSignInConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

/**
 * Signs in with Google through Credential Manager ("Sign in with Google" button flow). [activityContext] must be an Activity.
 * The ID token is not sent anywhere: SHAREMATE has no server, so the account identifies the user on this phone only.
 */
internal suspend fun signInWithGoogle(activityContext: Context, now: Long = System.currentTimeMillis()): PhoneAccount {
    if (!googleSignInConfigured) throw SignInException("이 빌드에는 Google 로그인 설정(OAuth 클라이언트 ID)이 없어요. 관리자에게 문의해 주세요.")
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = try {
        CredentialManager.create(activityContext).getCredential(activityContext, request).credential
    } catch (e: GetCredentialCancellationException) { throw SignInException("로그인을 취소했어요.", cancelled = true) }
    catch (e: NoCredentialException) { throw SignInException("이 휴대폰에 Google 계정이 없어요. 설정에서 계정을 추가한 뒤 다시 시도해 주세요.") }
    catch (e: GetCredentialException) { throw SignInException("Google 로그인을 마치지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.") }
    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw SignInException("Google 계정 정보를 읽지 못했어요.")
    val google = runCatching { GoogleIdTokenCredential.createFrom(credential.data) }.getOrElse { throw SignInException("Google 계정 정보를 읽지 못했어요.") }
    return PhoneAccount(id = google.id, email = google.id, name = google.displayName?.takeIf { it.isNotBlank() } ?: google.id.substringBefore('@'),
        photoUrl = google.profilePictureUri?.toString(), provider = AccountProvider.GOOGLE, signedInAt = now)
}

/** A clearly labelled local account for debug builds and the demo, so the flow can be shown without a Google project. */
internal fun demoAccount(now: Long = System.currentTimeMillis()) =
    PhoneAccount(id = "demo@sharemate.local", email = "demo@sharemate.local", name = "시연 사용자", provider = AccountProvider.DEMO, signedInAt = now)

/** Signs out: forgets the account on the phone and clears the Credential Manager state so the next sign-in asks again. */
internal suspend fun signOutEverywhere(context: Context) {
    runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    AccountStore.get(context).clear()
}
