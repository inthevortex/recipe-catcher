package org.eu.angsumanray.recipecatcher.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanSlotEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlanSheet(
    recipeId: Long,
    defaultServings: Int,
    plannerRepository: PlannerRepository,
    onDismiss: () -> Unit,
    onAddedSuccess: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var availableSlots by remember { mutableStateOf<List<PlanSlotEntity>>(emptyList()) }
    var selectedSlotId by remember { mutableStateOf<Long?>(null) }
    var portions by remember { mutableIntStateOf(defaultServings.coerceAtLeast(1)) }
    var eatenNow by remember { mutableIntStateOf(1) }

    LaunchedEffect(selectedDate) {
        val slots = plannerRepository.ensureDefaultSlotsForDate(selectedDate)
        availableSlots = slots
        selectedSlotId = slots.firstOrNull()?.id
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Add to Meal Plan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Date selector (next 7 days)
            Text("Select Day:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(7) { offset ->
                    val date = LocalDate.now().plusDays(offset.toLong())
                    val isSelected = date == selectedDate
                    val label = if (offset == 0) "Today" else date.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))

                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedDate = date },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slot selector
            Text("Select Slot:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableSlots.forEach { slot ->
                    FilterChip(
                        selected = slot.id == selectedSlotId,
                        onClick = { selectedSlotId = slot.id },
                        label = { Text(slot.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Portions stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Total Portions to Cook:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (portions > 1) {
                            portions--
                            eatenNow = eatenNow.coerceAtMost(portions)
                        }
                    }) { Text("-") }
                    Text("$portions", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { portions++ }) { Text("+") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Portions to Eat Now:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (eatenNow > 0) eatenNow-- }) { Text("-") }
                    Text("$eatenNow", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (eatenNow < portions) eatenNow++ }) { Text("+") }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedSlotId != null,
                onClick = {
                    val slotId = selectedSlotId ?: return@Button
                    scope.launch {
                        plannerRepository.addCookEntry(slotId, recipeId, portions, eatenNow)
                        sheetState.hide()
                        onAddedSuccess()
                    }
                }
            ) {
                Text("Add to Plan")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
