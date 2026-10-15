package org.eu.angsumanray.recipecatcher.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.MainActivity
import org.eu.angsumanray.recipecatcher.data.db.entity.PlanEntryEntity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
import org.eu.angsumanray.recipecatcher.reminders.ReminderReceiver
import java.time.LocalDate

data class WidgetMealItem(
    val entryId: Long,
    val slotName: String,
    val slotTime: String?,
    val title: String,
    val kind: String,
    val isDone: Boolean
)

class TodayWidget : GlanceAppWidget() {

    companion object {
        /** Redraws every placed Today widget; a no-op when none is on the home screen. */
        suspend fun refresh(context: Context) {
            TodayWidget().updateAll(context)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TodayWidgetEntryPoint {
        fun plannerRepository(): PlannerRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val plannerRepo = EntryPointAccessors.fromApplication(
            context.applicationContext,
            TodayWidgetEntryPoint::class.java
        ).plannerRepository()

        val today = LocalDate.now()
        val initial = withContext(Dispatchers.IO) { todayItems(plannerRepo, today).first() }

        provideContent {
            // Collected inside the composition so a running session follows database changes.
            val items by remember { todayItems(plannerRepo, today) }.collectAsState(initial)
            TodayWidgetContent(items)
        }
    }

    private fun todayItems(plannerRepo: PlannerRepository, date: LocalDate): Flow<List<WidgetMealItem>> =
        combine(
            plannerRepo.observeSlotsForDate(date),
            plannerRepo.observeEntriesForDate(date)
        ) { slots, entries ->
            val slotMap = slots.associateBy { it.id }
            entries.map { entry ->
                val slot = slotMap[entry.slotId]
                val recipe = entry.recipeId?.let { plannerRepo.getRecipe(it) }
                val title = recipe?.title ?: entry.text
                    ?: if (entry.kind == PlanEntryEntity.KIND_PREP) "Prep" else "Deleted recipe"
                WidgetMealItem(
                    entryId = entry.id,
                    slotName = slot?.name ?: "Meal",
                    slotTime = slot?.time?.toString(),
                    title = title,
                    kind = entry.kind,
                    isDone = entry.doneAt != null
                )
            }
        }.flowOn(Dispatchers.IO)

    @Composable
    private fun TodayWidgetContent(items: List<WidgetMealItem>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF1E1B18))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Today's Meals",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(Color(0xFFF3EFEA)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "${items.count { it.isDone }}/${items.size}",
                    style = TextStyle(
                        color = androidx.glance.unit.ColorProvider(Color(0xFFD4C4B5)),
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            if (items.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing planned for today.\nTap to plan.",
                        style = TextStyle(
                            color = androidx.glance.unit.ColorProvider(Color(0xFFA69A8E)),
                            fontSize = 13.sp
                        )
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(items) { item ->
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CheckBox(
                                checked = item.isDone,
                                onCheckedChange = actionRunCallback<ToggleEntryAction>(
                                    actionParametersOf(ToggleEntryAction.ENTRY_ID to item.entryId)
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    text = item.title,
                                    style = TextStyle(
                                        color = androidx.glance.unit.ColorProvider(
                                            if (item.isDone) Color(0xFF8A827A) else Color(0xFFF3EFEA)
                                        ),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1
                                )
                                Text(
                                    text = buildString {
                                        append(item.slotName)
                                        item.slotTime?.let { append(" • $it") }
                                        append(" • ${item.kind.replaceFirstChar { it.uppercase() }}")
                                    },
                                    style = TextStyle(
                                        color = androidx.glance.unit.ColorProvider(Color(0xFFA69A8E)),
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tick box on a widget row: toggles the entry exactly as Today does, cooked log included. */
class ToggleEntryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryId = parameters[ENTRY_ID] ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderReceiver.ReminderReceiverEntryPoint::class.java
        )
        withContext(Dispatchers.IO) {
            entryPoint.plannerRepository().toggleEntryDone(entryId)
            entryPoint.reminderScheduler().rescheduleAll()
            entryPoint.wearSync().publishToday()
        }
        TodayWidget().update(context, glanceId)
    }

    companion object {
        val ENTRY_ID = ActionParameters.Key<Long>("entry_id")
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
