package org.eu.angsumanray.recipecatcher.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem

@Composable
fun TodayScreen(
    repository: TodayRepository,
    modifier: Modifier = Modifier
) {
    val meals by repository.mealsFlow.collectAsState()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        if (meals.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No meals planned",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Plan meals on your phone to see them here.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            ScalingLazyColumn(
                modifier = modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    ListHeader {
                        Text(text = "Today's Meals", fontWeight = FontWeight.Bold)
                    }
                }

                items(meals, key = { it.id }) { meal ->
                    MealItemRow(
                        meal = meal,
                        onToggle = {
                            scope.launch {
                                repository.markDone(meal.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MealItemRow(
    meal: WearMealItem,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ticks only go one way: the phone owns un-ticking, so a done row stays done here.
    ToggleChip(
        modifier = modifier.fillMaxWidth(),
        checked = meal.done,
        onCheckedChange = { checked -> if (checked) onToggle() },
        label = {
            Text(
                text = meal.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (meal.done) TextDecoration.LineThrough else TextDecoration.None
            )
        },
        secondaryLabel = {
            val subtitle = buildString {
                append(meal.slot)
                meal.time?.let { append(" • $it") }
                append(" • ${meal.kind.replaceFirstChar { it.uppercase() }}")
            }
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        toggleControl = {
            Checkbox(checked = meal.done)
        },
        colors = ToggleChipDefaults.toggleChipColors()
    )
}
