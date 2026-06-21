package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Top-level gate: sign-in → (if not allow-listed) request-access → cloud.
 * The screens are chosen from auth state, so transitions happen automatically
 * as the user signs in / gets approved.
 */
@Composable
fun CloudNav(vm: CloudViewModel) {
    val signedIn by vm.signedIn.collectAsState()
    val authorized by vm.authorized.collectAsState()

    when {
        !signedIn -> SignInScreen(vm)
        !authorized -> RequestAccessScreen(vm)
        else -> {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "cloud") {
                composable("cloud") {
                    CloudSmsScreen(vm, onOpenWatch = { nav.navigate("watch") }, onOpenAdmin = { nav.navigate("admin") })
                }
                composable("watch") { WatchScreen(vm) { nav.popBackStack() } }
                composable("admin") { AdminScreen(vm) { nav.popBackStack() } }
            }
        }
    }
}
