package org.eu.angsumanray.recipecatcher.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

open class TodayTileService : TileService() {

    public override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return ImmediateFuture(resources)
    }

    public override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val repository = TodayRepository.getInstance(applicationContext)
        val meals = repository.getMeals()
        val remaining = meals.filter { !it.done }
        val displayItems = remaining.take(4)
        val restCount = (remaining.size - displayItems.size).coerceAtLeast(0)

        val clickAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("org.eu.angsumanray.recipecatcher.wear.ui.MainActivity")
                    .build()
            )
            .build()

        val tileModifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open_today")
                    .setOnClick(clickAction)
                    .build()
            )
            .build()

        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (meals.isEmpty()) {
            columnBuilder.addContent(
                Text.Builder(this, "No meals planned")
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(argb(0xFFB0B0B0.toInt()))
                    .build()
            )
        } else if (remaining.isEmpty()) {
            columnBuilder.addContent(
                Text.Builder(this, "All meals done! 🎉")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(0xFF81C784.toInt()))
                    .build()
            )
        } else {
            for (item in displayItems) {
                val itemTitle = if (item.title.length > 20) item.title.take(18) + "…" else item.title
                val rowText = "${item.slot}: $itemTitle"
                columnBuilder.addContent(
                    Text.Builder(this, rowText)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .setMaxLines(1)
                        .build()
                )
            }
            if (restCount > 0) {
                columnBuilder.addContent(
                    Text.Builder(this, "+$restCount more")
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                        .setColor(argb(0xFFFFB74D.toInt()))
                        .build()
                )
            }
        }

        val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Today")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFFFF8A65.toInt()))
                    .build()
            )
            .setContent(columnBuilder.build())
            .build()

        val timelineEntry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(
                LayoutElementBuilders.Layout.Builder()
                    .setRoot(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(expand())
                            .setHeight(expand())
                            .setModifiers(tileModifiers)
                            .addContent(layout)
                            .build()
                    )
                    .build()
            )
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(timelineEntry)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(0)
            .build()

        return ImmediateFuture(tile)
    }

    private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get() = value
        override fun get(timeout: Long, unit: TimeUnit) = value
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
