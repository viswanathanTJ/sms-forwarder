package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun CloudNav(vm: CloudViewModel) {
    val nav = rememberNavController()
    val signedIn by vm.signedIn.collectAsState()
    val start = if (signedIn) "cloud" else "signin"
    NavHost(navController = nav, startDestination = start) {
        composable("signin") { SignInScreen(vm) { nav.navigate("cloud") { popUpTo("signin") { inclusive = true } } } }
        composable("cloud") { CloudSmsScreen(vm, onOpenWatch = { nav.navigate("watch") }, onOpenAdmin = { nav.navigate("admin") }) }
        composable("watch") { WatchScreen(vm) { nav.popBackStack() } }
        composable("admin") { AdminScreen(vm) { nav.popBackStack() } }
    }
}
