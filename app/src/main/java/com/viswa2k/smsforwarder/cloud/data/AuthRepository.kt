package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseProvider.auth,
    private val db: FirebaseFirestore = FirebaseProvider.db,
) {
    fun currentEmail(): String? = auth.currentUser?.email
    fun currentDisplayName(): String? = auth.currentUser?.displayName

    val authState: Flow<Boolean> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser != null) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun signUpEmail(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
    }

    /**
     * Sends a password-reset email. For an account that only has the Google provider this
     * lets the user set a password, which adds the email/password credential — after that
     * they can sign in with email + password too (useful where Google sign-in isn't available).
     */
    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email).await()
    }

    suspend fun signInGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut() = auth.signOut()

    /** True if the signed-in account already has an email/password credential. */
    fun hasPasswordProvider(): Boolean =
        auth.currentUser?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } ?: false

    /**
     * Sets (or changes) the password for the signed-in account. For a Google-only account
     * this links an email/password credential so the user can also sign in with email +
     * password; if a password already exists it is updated. May require a recent sign-in.
     */
    suspend fun setPassword(newPassword: String) {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
        if (hasPasswordProvider()) {
            user.updatePassword(newPassword).await()
        } else {
            val email = user.email ?: throw IllegalStateException("Account has no email")
            user.linkWithCredential(EmailAuthProvider.getCredential(email, newPassword)).await()
        }
    }

    suspend fun isAuthorized(): Boolean {
        val email = currentEmail() ?: return false
        return db.collection("authorized_emails").document(email).get().await().exists()
    }

    suspend fun isAdmin(): Boolean {
        val email = currentEmail() ?: return false
        val doc = db.collection("authorized_emails").document(email).get().await()
        return doc.exists() && doc.getString("role") == "admin"
    }

    /** Submit a self-service access request for the signed-in (but unapproved) account. */
    suspend fun requestAccess() {
        val email = currentEmail() ?: return
        db.collection("access_requests").document(email).set(
            mapOf(
                "email" to email,
                "displayName" to (currentDisplayName() ?: ""),
                "status" to "pending",
                "requestedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** True if this account already has a pending access request. */
    suspend fun hasPendingRequest(): Boolean {
        val email = currentEmail() ?: return false
        return db.collection("access_requests").document(email).get().await().exists()
    }
}
