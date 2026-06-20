package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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

    suspend fun signInGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut() = auth.signOut()

    suspend fun isAuthorized(): Boolean {
        val email = currentEmail() ?: return false
        return db.collection("authorized_emails").document(email).get().await().exists()
    }

    suspend fun isAdmin(): Boolean {
        val email = currentEmail() ?: return false
        val doc = db.collection("authorized_emails").document(email).get().await()
        return doc.exists() && doc.getString("role") == "admin"
    }
}
