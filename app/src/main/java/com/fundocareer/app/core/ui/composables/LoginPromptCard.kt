package com.fundocareer.app.core.ui.composables

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fundocareer.app.AuthManager
import com.fundocareer.app.SecureTokenStore
import com.fundocareer.app.core.jobalerts.JobAlertLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

@Composable
fun LoginPromptCard(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(com.fundocareer.app.R.string.default_web_client_id))
            .requestEmail()
            .requestProfile()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }
    val tokenStore = remember { SecureTokenStore(context) }
    val authManager = remember { AuthManager(context, tokenStore) }

    var isSigningIn by remember { mutableStateOf(false) }

    fun exchangeGoogleTokenSuspend(idToken: String, onResult: (Boolean) -> Unit) {
        authManager.exchangeGoogleToken(idToken, object : AuthManager.AuthCallback {
            override fun onSuccess(authData: org.json.JSONObject) { onResult(true) }
            override fun onError(error: String) { onResult(false) }
        })
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                scope.launch {
                    isSigningIn = true
                    try {
                        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        val account = task.getResult(ApiException::class.java)
                        val idToken = account.idToken
                        if (idToken != null) {
                            exchangeGoogleTokenSuspend(idToken) { success ->
                                if (success) {
                                    val email = tokenStore.getUserEmail()
                                    if (!email.isNullOrBlank()) {
                                        JobAlertLifecycle.onLogin(context, email, "")
                                    }
                                    onLoginSuccess()
                                } else {
                                    android.widget.Toast.makeText(context, "Sign-in failed", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            android.widget.Toast.makeText(context, "Sign-in failed: no ID token", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: ApiException) {
                        if (e.statusCode != 12501) {
                            android.widget.Toast.makeText(context, "Sign-in cancelled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Sign-in error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    } finally {
                        isSigningIn = false
                    }
                }
            }
        }

    val scrollState = rememberScrollState()
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(scrollState).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("Sign in to use job search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your job search settings will appear here after login.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            googleSignInClient.signOut().addOnCompleteListener {
                                val signInIntent = googleSignInClient.signInIntent
                                googleSignInLauncher.launch(signInIntent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !isSigningIn,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
