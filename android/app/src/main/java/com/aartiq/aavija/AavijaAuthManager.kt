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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AavijaUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val isAnonymous: Boolean = false,
)

/**
 * Manages Google Sign-In with Firebase Authentication for Aavija.
 */
class AavijaAuthManager(private val context: Context) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val _user = MutableStateFlow<AavijaUser?>(null)
    val user: StateFlow<AavijaUser?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var googleSignInClient: GoogleSignInClient? = null

    init {
        updateFromFirebase(auth.currentUser)
        auth.addAuthStateListener { firebaseAuth ->
            updateFromFirebase(firebaseAuth.currentUser)
        }
    }

    private fun updateFromFirebase(firebaseUser: FirebaseUser?) {
        if (firebaseUser != null) {
            _user.value = AavijaUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                isAnonymous = firebaseUser.isAnonymous,
            )
        } else {
            _user.value = null
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
                            val err = authTask.exception?.message ?: "Firebase authentication failed."
                            _statusMessage.value = err
                            onComplete(false, err)
                        }
                    }
            } else {
                _isLoading.value = false
                _user.value = AavijaUser(
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
