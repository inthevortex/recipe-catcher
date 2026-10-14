package org.eu.angsumanray.recipecatcher.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    onNavigateToViewer: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    var showAddSlotDialog by remember { mutableStateOf(false) }
    var selectedSlotForAddMeal by remember { mutableStateOf<PlanSlotEntity?>(null) }
    var entryToMove by remember { mutableStateOf<PlanEntryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meal Planner", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.copyLastWeekLayout() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Copy last week layout")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Week switcher row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { viewModel.prevWeek() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous week")
                }

                val weekEnd = state.weekStartDate.plusDays(6)
                val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                val rangeStr = "${state.weekStartDate.format(formatter)} – ${weekEnd.format(formatter)}"

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = rangeStr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { viewModel.jumpToToday() }) {
                        Text("Today", style = MaterialTheme.typography.labelSmall)
                    }
                }

                IconButton(onClick = { viewModel.nextWeek() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next week")
                }
            }

            // Days horizontal selector
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                items(7) { index ->
                    val dayDate = state.weekStartDate.plusDays(index.toLong())
                    val isSelected = dayDate == state.selectedDate
                    val isToday = dayDate == LocalDate.now()

                    val daySlots = state.weekSlots.filter { it.date == dayDate }
                    val daySlotIds = daySlots.map { it.id }.toSet()
                    val hasMeals = state.weekEntries.any { it.slotId in daySlotIds }

                    Column(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else if (isToday) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { viewModel.selectDate(dayDate) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = dayDate.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dayDate.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (hasMeals) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Slots for selected day
            val currentDaySlots = state.weekSlots.filter { it.date == state.selectedDate }
            val recipeMap = state.allRecipes.associateBy { it.id }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(currentDaySlots, key = { it.id }) { slot ->
                    val slotEntries = state.weekEntries.filter { it.slotId == slot.id }
                    SlotCard(
                        slot = slot,
                        entries = slotEntries,
                        recipes = recipeMap,
                        onAddMeal = { selectedSlotForAddMeal = slot },
                        onDeleteSlot = { viewModel.deleteSlot(slot) },
                        onToggleDone = { entryId -> viewModel.toggleEntryDone(entryId) },
                        onDeleteEntry = { entry -> viewModel.deleteEntry(entry) },
                        onMoveEntry = { entry -> entryToMove = entry },
                        onOpenRecipe = { recipeId -> onNavigateToViewer(recipeId) }
                    )
                }

                item {
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        onClick = { showAddSlotDialog = true }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Custom Slot")
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Add Slot Dialog
    if (showAddSlotDialog) {
        AddSlotDialog(
            date = state.selectedDate,
            onDismiss = { showAddSlotDialog = false },
            onConfirm = { name, time ->
                viewModel.addSlot(state.selectedDate, name, time)
                showAddSlotDialog = false
            }
        )
    }

    // Add Meal Dialog
    selectedSlotForAddMeal?.let { slot ->
        AddMealDialog(
            slot = slot,
            recipes = state.allRecipes,
            availableLeftovers = state.availableLeftovers,
            onDismiss = { selectedSlotForAddMeal = null },
            onAddCook = { recipeId, portions, eatenNow ->
                viewModel.addCookEntry(slot.id, recipeId, portions, eatenNow)
                selectedSlotForAddMeal = null
            },
            onAddPrep = { recipeId, text, portions, prepNightBefore ->
                viewModel.addPrepEntry(slot.id, recipeId, text, portions, prepNightBefore)
                selectedSlotForAddMeal = null
            },
            onAddLeftover = { fromEntryId, portions ->
                viewModel.addLeftoverEntry(slot.id, fromEntryId, portions)
                selectedSlotForAddMeal = null
            }
        )
    }

    // Move Entry Dialog
    entryToMove?.let { entry ->
        MoveEntryDialog(
            entry = entry,
            slots = state.weekSlots,
            onDismiss = { entryToMove = null },
            onConfirm = { newSlotId ->
                viewModel.moveEntry(entry.id, newSlotId)
                entryToMove = null
            }
        )
    }
}

@Composable
fun SlotCard(
    slot: PlanSlotEntity,
    entries: List<PlanEntryEntity>,
    recipes: Map<Long, RecipeEntity>,
    onAddMeal: () -> Unit,
    onDeleteSlot: () -> Unit,
    onToggleDone: (Long) -> Unit,
    onDeleteEntry: (PlanEntryEntity) -> Unit,
    onMoveEntry: (PlanEntryEntity) -> Unit,
    onOpenRecipe: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Slot header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    slot.time?.let {
                        Text(
                            text = it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onAddMeal,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Meal")
                }

                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Slot options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete Slot") },
                            onClick = {
                                showMenu = false
                                onDeleteSlot()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entries.isEmpty()) {
                Text(
                    text = "No meals planned for this slot",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                entries.forEach { entry ->
                    val recipe = entry.recipeId?.let { recipes[it] }
                    val title = recipe?.title ?: entry.text ?: "Meal"

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.recipeId != null) onOpenRecipe(entry.recipeId)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onToggleDone(entry.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = if (entry.doneAt != null) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                    contentDescription = "Toggle done",
                                    tint = if (entry.doneAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (entry.doneAt != null) TextDecoration.LineThrough else null
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = when (entry.kind) {
                                            PlanEntryEntity.KIND_COOK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            PlanEntryEntity.KIND_LEFTOVER -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                            else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        },
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = entry.kind.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${entry.portions} portion(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            IconButton(onClick = { onMoveEntry(entry) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Move entry", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteEntry(entry) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete entry", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddSlotDialog(
    date: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (name: String, time: LocalTime?) -> Unit
) {
    var slotName by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Slot") },
        text = {
            Column {
                OutlinedTextField(
                    value = slotName,
                    onValueChange = { slotName = it },
                    label = { Text("Slot Name (e.g. Afternoon Snack)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text("Time (optional, HH:mm e.g. 16:30)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = slotName.isNotBlank(),
                onClick = {
                    val parsedTime = try {
                        if (timeText.isNotBlank()) LocalTime.parse(timeText.trim()) else null
                    } catch (_: Exception) {
                        null
                    }
                    onConfirm(slotName.trim(), parsedTime)
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddMealDialog(
    slot: PlanSlotEntity,
    recipes: List<RecipeEntity>,
    availableLeftovers: List<org.eu.angsumanray.recipecatcher.core.planner.AvailableLeftover>,
    onDismiss: () -> Unit,
    onAddCook: (recipeId: Long, portions: Int, eatenNow: Int) -> Unit,
    onAddPrep: (recipeId: Long?, text: String?, portions: Int, prepNightBefore: Boolean) -> Unit,
    onAddLeftover: (fromEntryId: Long, portions: Int) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Cook, 1: Prep, 2: Leftover

    // Cook state
    var selectedRecipeId by remember { mutableStateOf<Long?>(recipes.firstOrNull()?.id) }
    var cookPortions by remember { mutableIntStateOf(2) }
    var eatenNow by remember { mutableIntStateOf(1) }

    // Prep state
    var prepText by remember { mutableStateOf("") }
    var prepPortions by remember { mutableIntStateOf(1) }
    var prepNightBefore by remember { mutableStateOf(false) }

    // Leftover state
    var selectedLeftoverEntryId by remember { mutableStateOf<Long?>(availableLeftovers.firstOrNull()?.cookEntryId) }
    var leftoverPortions by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Meal to ${slot.name}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Cook") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Prep") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Leftover") })
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // Cook from recipe
                        if (recipes.isEmpty()) {
                            Text("No recipes saved yet in library.")
                        } else {
                            Text("Select Recipe:", style = MaterialTheme.typography.labelMedium)
                            var expanded by remember { mutableStateOf(false) }
                            val currentRecipe = recipes.firstOrNull { it.id == selectedRecipeId }

                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(currentRecipe?.title ?: "Select Recipe", maxLines = 1)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                recipes.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.title ?: "Untitled") },
                                        onClick = {
                                            selectedRecipeId = r.id
                                            cookPortions = r.servings ?: 2
                                            expanded = false
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Total Portions to Cook:")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { if (cookPortions > 1) cookPortions-- }) { Text("-") }
                                    Text("$cookPortions", fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { cookPortions++ }) { Text("+") }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Portions to Eat Now:")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { if (eatenNow > 1) eatenNow-- }) { Text("-") }
                                    Text("$eatenNow", fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { if (eatenNow < cookPortions) eatenNow++ }) { Text("+") }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Prep
                        OutlinedTextField(
                            value = prepText,
                            onValueChange = { prepText = it },
                            label = { Text("Description (e.g. Overnight oats)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Prep the night before:")
                            Switch(
                                checked = prepNightBefore,
                                onCheckedChange = { prepNightBefore = it }
                            )
                        }
                    }

                    2 -> {
                        // Leftovers
                        if (availableLeftovers.isEmpty()) {
                            Text(
                                "No leftovers available on this date.\nCook earlier meals with extra portions to schedule leftovers.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text("Available Leftovers:", style = MaterialTheme.typography.labelMedium)
                            var expanded by remember { mutableStateOf(false) }
                            val currentLeftover = availableLeftovers.firstOrNull { it.cookEntryId == selectedLeftoverEntryId }
                            val currentRecipe = recipes.firstOrNull { it.id == currentLeftover?.recipeId }

                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val label = "${currentRecipe?.title ?: "Leftover"} (${currentLeftover?.remainingPortions ?: 0} left from ${currentLeftover?.slotDate})"
                                Text(label, maxLines = 1)
                            }

                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                availableLeftovers.forEach { l ->
                                    val r = recipes.firstOrNull { it.id == l.recipeId }
                                    DropdownMenuItem(
                                        text = { Text("${r?.title ?: "Cook entry"} (${l.remainingPortions} left from ${l.slotDate})") },
                                        onClick = {
                                            selectedLeftoverEntryId = l.cookEntryId
                                            leftoverPortions = 1
                                            expanded = false
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            val maxAvailable = currentLeftover?.remainingPortions ?: 1
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Portions to Eat:")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { if (leftoverPortions > 1) leftoverPortions-- }) { Text("-") }
                                    Text("$leftoverPortions", fontWeight = FontWeight.Bold)
                                    IconButton(onClick = { if (leftoverPortions < maxAvailable) leftoverPortions++ }) { Text("+") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = when (selectedTab) {
                    0 -> selectedRecipeId != null
                    1 -> prepText.isNotBlank()
                    2 -> selectedLeftoverEntryId != null
                    else -> false
                },
                onClick = {
                    when (selectedTab) {
                        0 -> selectedRecipeId?.let { onAddCook(it, cookPortions, eatenNow) }
                        1 -> onAddPrep(null, prepText.trim(), prepPortions, prepNightBefore)
                        2 -> selectedLeftoverEntryId?.let { onAddLeftover(it, leftoverPortions) }
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun MoveEntryDialog(
    entry: PlanEntryEntity,
    slots: List<PlanSlotEntity>,
    onDismiss: () -> Unit,
    onConfirm: (newSlotId: Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move Entry to Slot") },
        text = {
            LazyColumn {
                items(slots) { slot ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(slot.id) }
                            .padding(vertical = 4.dp),
                        color = if (slot.id == entry.slotId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${slot.date}: ${slot.name}",
                                fontWeight = FontWeight.SemiBold
                            )
                            slot.time?.let { Text(text = it.toString(), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
