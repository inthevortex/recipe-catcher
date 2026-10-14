package org.eu.angsumanray.recipecatcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import org.eu.angsumanray.recipecatcher.capture.ImageCleanupWorker
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.ui.cook.CookingModeScreen
import org.eu.angsumanray.recipecatcher.ui.cook.CookingModeViewModel
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeScreen
import org.eu.angsumanray.recipecatcher.ui.edit.EditRecipeViewModel
import org.eu.angsumanray.recipecatcher.ui.library.LibraryScreen
import org.eu.angsumanray.recipecatcher.ui.library.LibraryViewModel
import org.eu.angsumanray.recipecatcher.ui.paste.PasteLinkScreen
import org.eu.angsumanray.recipecatcher.ui.review.ReviewScreen
import org.eu.angsumanray.recipecatcher.ui.review.ReviewViewModel
import org.eu.angsumanray.recipecatcher.ui.settings.ListManagerScreen
import org.eu.angsumanray.recipecatcher.ui.settings.ListManagerViewModel
import org.eu.angsumanray.recipecatcher.ui.settings.SettingsScreen
import org.eu.angsumanray.recipecatcher.ui.settings.SettingsViewModel
import org.eu.angsumanray.recipecatcher.ui.theme.RecipeCatcherTheme
import org.eu.angsumanray.recipecatcher.ui.viewer.RecipeViewerScreen
import org.eu.angsumanray.recipecatcher.ui.viewer.RecipeViewerViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var recipeRepository: RecipeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Schedule periodic orphaned image cleanup weekly
        ImageCleanupWorker.scheduleWeekly(this)

        setContent {
            val themeSetting by settingsStore.themeFlow.collectAsState(initial = SettingsStore.THEME_DARK)
            val isDark = when (themeSetting) {
                SettingsStore.THEME_LIGHT -> false
                SettingsStore.THEME_SYSTEM -> isSystemInDarkTheme()
                else -> true
            }

            RecipeCatcherTheme(darkTheme = isDark) {
                val navController = rememberNavController()

                // Check intent extras for incoming recipe
                LaunchedEffect(intent) {
                    val pendingId = intent?.getLongExtra("pending_recipe_id", -1L) ?: -1L
                    val recipeId = intent?.getLongExtra("recipe_id", -1L) ?: -1L
                    val targetId = if (pendingId != -1L) pendingId else recipeId
                    if (targetId != -1L) {
                        navController.navigate("edit/$targetId")
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "library"
                ) {
                    composable("library") {
                        val viewModel = hiltViewModel<LibraryViewModel>()
                        LibraryScreen(
                            viewModel = viewModel,
                            onNavigateToPasteLink = { navController.navigate("paste_link") },
                            onNavigateToWriteRecipe = { navController.navigate("edit/0") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToViewer = { id -> navController.navigate("viewer/$id") },
                            onNavigateToEdit = { id -> navController.navigate("edit/$id") }
                        )
                    }

                    composable("paste_link") {
                        PasteLinkScreen(
                            recipeRepository = recipeRepository,
                            onNavigateBack = { navController.popBackStack() },
                            onRecipeCaptured = { id ->
                                navController.popBackStack()
                                navController.navigate("edit/$id")
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
                            onNavigateToCookingMode = { id -> navController.navigate("cook/$id") },
                            onNavigateToEdit = { id -> navController.navigate("edit/$id") }
                        )
                    }

                    composable(
                        route = "cook/{recipeId}",
                        arguments = listOf(navArgument("recipeId") { type = NavType.LongType })
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
                                navController.navigate("viewer/$id")
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
                            onNavigateToListManager = { navController.navigate("list_manager") }
                        )
                    }

                    composable("list_manager") {
                        val viewModel = hiltViewModel<ListManagerViewModel>()
                        ListManagerScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
