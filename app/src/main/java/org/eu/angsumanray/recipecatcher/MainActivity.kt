package org.eu.angsumanray.recipecatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import org.eu.angsumanray.recipecatcher.storage.ImageCleanupWorker
import org.eu.angsumanray.recipecatcher.updates.UpdateCheckWorker
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.ui.chat.ChatScreen
import org.eu.angsumanray.recipecatcher.ui.chat.ChatViewModel
import org.eu.angsumanray.recipecatcher.ui.cook.CookingModeScreen
import org.eu.angsumanray.recipecatcher.ui.cook.CookingModeViewModel
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeScreen
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel
import org.eu.angsumanray.recipecatcher.ui.library.LibraryScreen
import org.eu.angsumanray.recipecatcher.ui.library.LibraryViewModel
import org.eu.angsumanray.recipecatcher.ui.lists.ListsScreen
import org.eu.angsumanray.recipecatcher.ui.lists.ListsViewModel
import org.eu.angsumanray.recipecatcher.ui.paste.PasteLinkScreen
import org.eu.angsumanray.recipecatcher.ui.plan.PlanScreen
import org.eu.angsumanray.recipecatcher.ui.plan.PlanViewModel
import org.eu.angsumanray.recipecatcher.ui.review.ReviewScreen
import org.eu.angsumanray.recipecatcher.ui.review.ReviewViewModel
import org.eu.angsumanray.recipecatcher.ui.settings.ListManagerScreen
import org.eu.angsumanray.recipecatcher.ui.settings.ListManagerViewModel
import org.eu.angsumanray.recipecatcher.ui.settings.SettingsScreen
import org.eu.angsumanray.recipecatcher.ui.settings.SettingsViewModel
import org.eu.angsumanray.recipecatcher.ui.theme.RecipeCatcherTheme
import org.eu.angsumanray.recipecatcher.ui.today.TodayScreen
import org.eu.angsumanray.recipecatcher.ui.today.TodayViewModel
import org.eu.angsumanray.recipecatcher.ui.viewer.RecipeViewerScreen
import org.eu.angsumanray.recipecatcher.ui.viewer.RecipeViewerViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var recipeRepository: RecipeRepository

    @Inject
    lateinit var captureLauncher: org.eu.angsumanray.recipecatcher.capture.CaptureLauncher

    // Prep and start-cooking reminders are silent until this is granted.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ImageCleanupWorker.scheduleWeekly(this)
        UpdateCheckWorker.scheduleDaily(this)
        // After recreation the nav back stack is restored, so only a fresh launch acts on its intent.
        if (savedInstanceState == null) launchIntentState.value = intent

        setContent {
            val themeSetting by settingsStore.themeFlow.collectAsState(initial = SettingsStore.THEME_DARK)
            val isDark = when (themeSetting) {
                SettingsStore.THEME_LIGHT -> false
                SettingsStore.THEME_SYSTEM -> isSystemInDarkTheme()
                else -> true
            }

            RecipeCatcherTheme(darkTheme = isDark) {
                val navController = rememberNavController()

                val launchIntent by launchIntentState
                LaunchedEffect(launchIntent) {
                    val incoming = launchIntent ?: return@LaunchedEffect
                    val pendingId = incoming.getLongExtra(EXTRA_PENDING_RECIPE_ID, -1L)
                    val existingId = incoming.getLongExtra(EXTRA_RECIPE_ID, -1L)
                    when {
                        pendingId != -1L -> navController.navigate("review/$pendingId")
                        existingId != -1L -> {
                            val saved = recipeRepository.getRecipe(existingId)?.status == RecipeEntity.STATUS_SAVED
                            navController.navigate(if (saved) "viewer/$existingId" else "review/$existingId")
                        }
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val primaryRoutes = setOf("today", "plan", "library", "lists")
                val showBottomBar = currentRoute in primaryRoutes

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = "Today") },
                                    label = { Text("Today") },
                                    selected = currentRoute == "today",
                                    onClick = {
                                        navController.navigate("today") {
                                            popUpTo("today") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.DateRange, contentDescription = "Plan") },
                                    label = { Text("Plan") },
                                    selected = currentRoute == "plan",
                                    onClick = {
                                        navController.navigate("plan") {
                                            popUpTo("today") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Library") },
                                    label = { Text("Library") },
                                    selected = currentRoute == "library",
                                    onClick = {
                                        navController.navigate("library") {
                                            popUpTo("today") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Lists") },
                                    label = { Text("Lists") },
                                    selected = currentRoute == "lists",
                                    onClick = {
                                        navController.navigate("lists") {
                                            popUpTo("today") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "today",
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        composable("today") {
                            val viewModel = hiltViewModel<TodayViewModel>()
                            TodayScreen(
                                viewModel = viewModel,
                                onNavigateToPlan = { navController.navigate("plan") },
                                onNavigateToViewer = { id -> navController.navigate("viewer/$id") },
                                onNavigateToCookingMode = { id, servings -> navController.navigate("cook/$id?servings=$servings") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToChat = { navController.navigate("chat") }
                            )
                        }

                        composable("plan") {
                            val viewModel = hiltViewModel<PlanViewModel>()
                            PlanScreen(
                                viewModel = viewModel,
                                onNavigateToViewer = { id -> navController.navigate("viewer/$id") }
                            )
                        }

                        composable("library") {
                            val viewModel = hiltViewModel<LibraryViewModel>()
                            LibraryScreen(
                                viewModel = viewModel,
                                onNavigateToPasteLink = { navController.navigate("paste_link") },
                                onNavigateToWriteRecipe = { navController.navigate("edit/0") },
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToViewer = { id -> navController.navigate("viewer/$id") },
                                onNavigateToEdit = { id -> navController.navigate("edit/$id") },
                                onNavigateToReview = { id -> navController.navigate("review/$id") },
                                onNavigateToChat = { navController.navigate("chat") }
                            )
                        }

                        composable("lists") {
                            val viewModel = hiltViewModel<ListsViewModel>()
                            ListsScreen(
                                viewModel = viewModel
                            )
                        }

                    composable("paste_link") {
                        PasteLinkScreen(
                            recipeRepository = recipeRepository,
                            captureLauncher = captureLauncher,
                            onNavigateBack = { navController.popBackStack() },
                            onRecipeCaptured = { id ->
                                navController.popBackStack()
                                navController.navigate("review/$id")
                            }
                        )
                    }

                    composable(
                        route = "viewer/{recipeId}",
                        arguments = listOf(navArgument("recipeId") { type = NavType.LongType })
                    ) {
                        val viewModel = hiltViewModel<RecipeViewerViewModel>()
                        RecipeViewerScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToCookingMode = { id, servings -> navController.navigate("cook/$id?servings=$servings") },
                            onNavigateToEdit = { id -> navController.navigate("edit/$id") }
                        )
                    }

                    composable(
                        route = "cook/{recipeId}?servings={servings}",
                        arguments = listOf(
                            navArgument("recipeId") { type = NavType.LongType },
                            navArgument("servings") {
                                type = NavType.IntType
                                defaultValue = -1
                            }
                        )
                    ) {
                        val viewModel = hiltViewModel<CookingModeViewModel>()
                        CookingModeScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = "edit/{recipeId}",
                        arguments = listOf(navArgument("recipeId") { type = NavType.LongType })
                    ) {
                        val viewModel = hiltViewModel<EditRecipeViewModel>()
                        EditRecipeScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onSaveSuccess = { id ->
                                navController.popBackStack()
                                // Editing from the viewer returns to it rather than stacking a second copy.
                                val previous = navController.currentBackStackEntry
                                val backOnViewer = previous?.destination?.route == "viewer/{recipeId}" &&
                                    previous.arguments?.getLong("recipeId") == id
                                if (!backOnViewer) navController.navigate("viewer/$id")
                            }
                        )
                    }

                    composable(
                        route = "review/{recipeId}",
                        arguments = listOf(navArgument("recipeId") { type = NavType.LongType })
                    ) {
                        val viewModel = hiltViewModel<ReviewViewModel>()
                        ReviewScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onSaveSuccess = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        val viewModel = hiltViewModel<SettingsViewModel>()
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToListManager = { navController.navigate("list_manager") },
                            onNavigateToImportSaves = { navController.navigate("import_saves") }
                        )
                    }

                    composable("import_saves") {
                        val viewModel = hiltViewModel<org.eu.angsumanray.recipecatcher.ui.importer.ImportViewModel>()
                        org.eu.angsumanray.recipecatcher.ui.importer.ImportScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable("list_manager") {
                        val viewModel = hiltViewModel<ListManagerViewModel>()
                        ListManagerScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable("chat") {
                        val viewModel = hiltViewModel<ChatViewModel>()
                        ChatScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToViewer = { id -> navController.navigate("viewer/$id") },
                            onNavigateToReview = { id -> navController.navigate("review/$id") }
                        )
                    }
                }
            }
        }
    }
}

    private val launchIntentState = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntentState.value = intent
    }

    companion object {
        const val EXTRA_PENDING_RECIPE_ID = "pending_recipe_id"
        const val EXTRA_RECIPE_ID = "recipe_id"
    }
}
