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

/** A fresh sign-in, carrying over what [previous] answered in the first run when it is the same account. */
internal fun resumeAccount(fresh: PhoneAccount, previous: PhoneAccount?): PhoneAccount =
    if (previous == null || previous.id != fresh.id || !previous.onboarded) fresh
    else fresh.copy(name = previous.name, department = previous.department, driverId = previous.driverId, onboarded = true)

internal fun accountOwnerKey(account: PhoneAccount): String = "${account.provider.name}:${account.id}"

internal fun requireAccountOwner(owner: String?, account: PhoneAccount) {
    if (owner != null && owner != accountOwnerKey(account))
        throw SignInException("이 기기에 보관된 차량 기록은 다른 계정의 기록이에요. 기존 계정으로 로그인해 주세요.")
}

/** The signed-in account, persisted in no-backup storage so it never leaves the phone through a cloud backup. */
class AccountStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "phone-account.json"))
    private val owner = AtomicFile(File(context.applicationContext.noBackupFilesDir, "phone-data-owner.txt"))
    private val mutable = MutableStateFlow(runCatching { if (file.baseFile.exists()) decodeAccount(file.readFully().toString(Charsets.UTF_8)) else null }.getOrNull())
    val account: StateFlow<PhoneAccount?> = mutable.asStateFlow()

    suspend fun save(account: PhoneAccount) = withContext(Dispatchers.IO) {
        keepOwner(account)
        val stream = file.startWrite()
        try { stream.write(encodeAccount(account).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = account
    }

    suspend fun update(transform: (PhoneAccount) -> PhoneAccount) { account.value?.let { save(transform(it)) } }

    // The first-run answers of the account that signed out last, so signing in again with it skips the onboarding.
    private val last = File(context.applicationContext.noBackupFilesDir, "phone-account-last.json")

    // shortcut: one account owns this installation; add isolated stores before supporting account switching.
    private fun keepOwner(account: PhoneAccount) {
        val previous = if (owner.baseFile.exists()) owner.readFully().toString(Charsets.UTF_8)
            else mutable.value?.let(::accountOwnerKey) ?: if (last.exists()) accountOwnerKey(decodeAccount(last.readText(Charsets.UTF_8))) else null
        requireAccountOwner(previous, account)
        if (!owner.baseFile.exists()) {
            val stream = owner.startWrite()
            try { stream.write(accountOwnerKey(account).toByteArray(Charsets.UTF_8)); owner.finishWrite(stream) }
            catch (e: Exception) { owner.failWrite(stream); throw e }
        }
    }

    /** Stores a fresh sign-in; the same account signing in again keeps its name, department, employee and finished onboarding. */
    suspend fun signIn(account: PhoneAccount) {
        val previous = withContext(Dispatchers.IO) { runCatching { if (last.exists()) decodeAccount(last.readText(Charsets.UTF_8)) else null }.getOrNull() }
        save(resumeAccount(account, previous))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutable.value?.let { keepOwner(it) }
        mutable.value?.let { runCatching { last.writeText(encodeAccount(it), Charsets.UTF_8) } }
        file.delete(); mutable.value = null
    }

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
    AccountStore.get(context).clear()
    PhoneSyncClient.get(context).stop()
    runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
}
