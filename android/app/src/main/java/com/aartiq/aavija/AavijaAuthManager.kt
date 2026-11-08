package com.aartiq.aavija

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

data class AavijaUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val cachedPhotoPath: String? = null,
    val isAnonymous: Boolean = false,
)

/**
 * Manages Google Sign-In with Firebase Authentication for Aavija.
 * Automatically persists user sessions in SharedPreferences and caches
 * the Google profile avatar offline so credentials and photos load instantly
 * on app restart even when offline.
 */
class AavijaAuthManager(private val context: Context) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val prefs = context.getSharedPreferences("aavija_auth_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _user = MutableStateFlow<AavijaUser?>(null)
    val user: StateFlow<AavijaUser?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var googleSignInClient: GoogleSignInClient? = null

    init {
        // 1. Immediately restore saved offline user session so there's zero UI blink on launch
        restoreOfflineUser()

        // 2. Sync with Firebase Auth state
        updateFromFirebase(auth.currentUser)
        auth.addAuthStateListener { firebaseAuth ->
            updateFromFirebase(firebaseAuth.currentUser)
        }

        // 3. Fallback sync with last Google Account on device if Firebase is silent
        if (_user.value == null) {
            val lastGoogle = GoogleSignIn.getLastSignedInAccount(context)
            if (lastGoogle != null) {
                saveUser(
                    uid = lastGoogle.id ?: "google_persisted",
                    email = lastGoogle.email,
                    displayName = lastGoogle.displayName,
                    photoUrl = lastGoogle.photoUrl?.toString(),
                    isAnonymous = false,
                )
            }
        }
    }

    private fun restoreOfflineUser() {
        val isLoggedIn = prefs.getBoolean("auth_logged_in", false)
        if (!isLoggedIn) return

        val uid = prefs.getString("auth_uid", null) ?: return
        val email = prefs.getString("auth_email", null)
        val displayName = prefs.getString("auth_display_name", null)
        val photoUrl = prefs.getString("auth_photo_url", null)
        val cachedPath = prefs.getString("auth_cached_photo_path", null)

        val validCachedPath = if (cachedPath != null && File(cachedPath).exists()) cachedPath else null

        _user.value = AavijaUser(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            cachedPhotoPath = validCachedPath,
            isAnonymous = false,
        )
    }

    private fun saveUser(
        uid: String,
        email: String?,
        displayName: String?,
        photoUrl: String?,
        isAnonymous: Boolean,
    ) {
        val cachedFile = File(context.filesDir, "google_avatar.png")
        val currentCachedPath = if (cachedFile.exists()) cachedFile.absolutePath else null

        val u = AavijaUser(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = photoUrl,
            cachedPhotoPath = currentCachedPath,
            isAnonymous = isAnonymous,
        )
        _user.value = u

        prefs.edit()
            .putBoolean("auth_logged_in", true)
            .putString("auth_uid", uid)
            .putString("auth_email", email)
            .putString("auth_display_name", displayName)
            .putString("auth_photo_url", photoUrl)
            .putString("auth_cached_photo_path", currentCachedPath)
            .apply()

        // Cache the avatar image offline in the background
        if (!photoUrl.isNullOrBlank()) {
            cacheAvatarOffline(photoUrl)
        }
    }

    private fun cacheAvatarOffline(urlStr: String) {
        scope.launch {
            try {
                val file = File(context.filesDir, "google_avatar.png")
                val url = URL(urlStr)
                url.openStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                prefs.edit().putString("auth_cached_photo_path", file.absolutePath).apply()
                _user.value = _user.value?.copy(cachedPhotoPath = file.absolutePath)
                Log.i("AavijaAuth", "Google avatar successfully cached offline: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w("AavijaAuth", "Could not cache Google avatar offline: ${e.message}")
            }
        }
    }

    private fun updateFromFirebase(firebaseUser: FirebaseUser?) {
        if (firebaseUser != null) {
            saveUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                isAnonymous = firebaseUser.isAnonymous,
            )
        }
    }

    fun getGoogleSignInIntent(): Intent? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val webClientId = if (resId != 0) context.getString(resId) else null

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (!webClientId.isNullOrBlank()) {
            gsoBuilder.requestIdToken(webClientId)
        }

        val client = GoogleSignIn.getClient(context, gsoBuilder.build())
        googleSignInClient = client
        return client.signInIntent
    }

    fun handleSignInResult(data: Intent?, onComplete: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        _statusMessage.value = "Authenticating with Google…"
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        _isLoading.value = false
                        if (authTask.isSuccessful) {
                            val u = auth.currentUser
                            updateFromFirebase(u)
                            _statusMessage.value = "Signed in as ${u?.displayName ?: u?.email ?: "User"}"
                            onComplete(true, null)
                        } else {
                            // Firebase cloud rejected (e.g. SHA-1 pending in console):
                            // Still preserve verified Google identity locally
                            saveUser(
                                uid = account.id ?: "google_local",
                                email = account.email,
                                displayName = account.displayName,
                                photoUrl = account.photoUrl?.toString(),
                                isAnonymous = false,
                            )
                            val msg = "Connected to Google Account (${account.email})."
                            _statusMessage.value = msg
                            onComplete(true, null)
                        }
                    }
            } else {
                _isLoading.value = false
                saveUser(
                    uid = account.id ?: "google_local",
                    email = account.email,
                    displayName = account.displayName,
                    photoUrl = account.photoUrl?.toString(),
                    isAnonymous = false,
                )
                val msg = "Connected to Google Account (${account.email})."
                _statusMessage.value = msg
                onComplete(true, null)
            }
        } catch (e: ApiException) {
            _isLoading.value = false
            val msg = "Google Sign-In failed (code ${e.statusCode}): ${e.message}"
            Log.e("AavijaAuth", msg, e)
            _statusMessage.value = msg
            onComplete(false, msg)
        } catch (e: Exception) {
            _isLoading.value = false
            val msg = "Authentication error: ${e.message}"
            Log.e("AavijaAuth", msg, e)
            _statusMessage.value = msg
            onComplete(false, msg)
        }
    }

    fun signOut(onComplete: (() -> Unit)? = null) {
        // Clear offline cached prefs & avatar
        prefs.edit().clear().apply()
        try {
            val file = File(context.filesDir, "google_avatar.png")
            if (file.exists()) file.delete()
        } catch (_: Exception) {}

        auth.signOut()
        googleSignInClient?.signOut()?.addOnCompleteListener {
            _user.value = null
            _statusMessage.value = "Signed out."
            onComplete?.invoke()
        } ?: run {
            _user.value = null
            _statusMessage.value = "Signed out."
            onComplete?.invoke()
        }
    }
}
