package org.eu.angsumanray.recipecatcher.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
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
import kotlinx.coroutines.withContext
import org.eu.angsumanray.recipecatcher.MainActivity
import org.eu.angsumanray.recipecatcher.data.repo.PlannerRepository
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TodayWidgetEntryPoint {
        fun plannerRepository(): PlannerRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            TodayWidgetEntryPoint::class.java
        )
        val plannerRepo = entryPoint.plannerRepository()

        val items = withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val slots = plannerRepo.getSlotsForDate(today)
            val slotMap = slots.associateBy { it.id }
            val entries = plannerRepo.getEntriesForDate(today)

            entries.map { entry ->
                val slot = slotMap[entry.slotId]
                val recipe = entry.recipeId?.let { plannerRepo.getRecipe(it) }
                val title = recipe?.title ?: entry.text ?: "Meal"
                WidgetMealItem(
                    entryId = entry.id,
                    slotName = slot?.name ?: "Meal",
                    slotTime = slot?.time?.toString(),
                    title = title,
                    kind = entry.kind,
                    isDone = entry.doneAt != null
                )
            }
        }

        provideContent {
            TodayWidgetContent(context, items)
        }
    }

    @Composable
    private fun TodayWidgetContent(context: Context, items: List<WidgetMealItem>) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

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
                            Text(
                                text = if (item.isDone) "✓" else "○",
                                style = TextStyle(
                                    color = androidx.glance.unit.ColorProvider(
                                        if (item.isDone) Color(0xFF81C784) else Color(0xFFD4C4B5)
                                    ),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))
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

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
