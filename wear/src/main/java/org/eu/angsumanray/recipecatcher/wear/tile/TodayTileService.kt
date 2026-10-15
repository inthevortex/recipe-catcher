package org.eu.angsumanray.recipecatcher.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eu.angsumanray.recipecatcher.wear.data.TodayRepository
import org.eu.angsumanray.recipecatcher.wear.data.WearMealItem
import org.eu.angsumanray.recipecatcher.wear.ui.MainActivity

open class TodayTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    public override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = serviceScope.listenableFuture {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    public override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.listenableFuture {
        val repository = TodayRepository.getInstance(applicationContext)
        tickedEntryId(requestParams.currentState.lastClickableId)?.let { repository.markDone(it) }
        buildTile(repository.meals(), requestParams.deviceConfiguration)
    }

    private fun buildTile(
        meals: List<WearMealItem>,
        deviceConfiguration: DeviceParametersBuilders.DeviceParameters
    ): TileBuilders.Tile {
        val remaining = meals.filter { !it.done }
        val displayItems = remaining.take(MAX_ROWS)
        val restCount = remaining.size - displayItems.size

        val openApp = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(OPEN_APP_ID)
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        when {
            meals.isEmpty() -> columnBuilder.addContent(
                Text.Builder(this, "No meals planned")
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(argb(0xFFB0B0B0.toInt()))
                    .setModifiers(openApp)
                    .build()
            )
            remaining.isEmpty() -> columnBuilder.addContent(
                Text.Builder(this, "All done for today")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(0xFF81C784.toInt()))
                    .setModifiers(openApp)
                    .build()
            )
            else -> {
                for (item in displayItems) {
                    columnBuilder.addContent(
                        Text.Builder(this, rowText(item))
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .setMaxLines(1)
                            .setModifiers(tickModifier(item.id))
                            .build()
                    )
                }
                if (restCount > 0) {
                    columnBuilder.addContent(
                        Text.Builder(this, "+$restCount more")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(0xFFFFB74D.toInt()))
                            .setModifiers(openApp)
                            .build()
                    )
                }
            }
        }

        val layout = PrimaryLayout.Builder(deviceConfiguration)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "Today")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(0xFFFF8A65.toInt()))
                    .setModifiers(openApp)
                    .build()
            )
            .setContent(columnBuilder.build())
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout).build())
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(0)
            .build()
    }

    /** Tapping a row reloads the tile with that row's id as the last clickable, which ticks it. */
    private fun tickModifier(entryId: Long) = ModifiersBuilders.Modifiers.Builder()
        .setClickable(
            ModifiersBuilders.Clickable.Builder()
                .setId(TICK_ID_PREFIX + entryId)
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .build()
        )
        .build()

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val MAX_ROWS = 4
        private const val OPEN_APP_ID = "open_today"
        private const val TICK_ID_PREFIX = "tick:"
        private const val MAX_TITLE_CHARS = 20

        fun tickedEntryId(lastClickableId: String?): Long? =
            lastClickableId?.takeIf { it.startsWith(TICK_ID_PREFIX) }
                ?.removePrefix(TICK_ID_PREFIX)
                ?.toLongOrNull()

        fun rowText(item: WearMealItem): String {
            val title = if (item.title.length > MAX_TITLE_CHARS) item.title.take(MAX_TITLE_CHARS - 1) + "…" else item.title
            return "○ ${item.slot}: $title"
        }
    }
}
