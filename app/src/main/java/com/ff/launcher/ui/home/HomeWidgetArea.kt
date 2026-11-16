package com.ff.launcher.ui.home

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ff.launcher.R
import com.ff.launcher.data.db.HOME_WIDGET_SLOT_ID
import com.ff.launcher.data.glance.HeaderSize
import com.ff.launcher.widget.LauncherAppWidgetHostView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeCustomWidgetView(
    appWidgetId: Int,
    headerSize: HeaderSize = HeaderSize.MEDIUM,
    onSizeChange: (HeaderSize) -> Unit = {},
    obtainHostView: (Context, Int, Int) -> LauncherAppWidgetHostView?,
    onReplaceWidget: () -> Unit,
    onRestoreDefaultClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var showSheet by remember { mutableStateOf(false) }

    val providerAlive = remember(appWidgetId) {
        AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId) != null
    }

    LaunchedEffect(appWidgetId, providerAlive) {
        if (!providerAlive) {
            onRestoreDefaultClock()
        }
    }

    if (!providerAlive) return

    val hostView = remember(appWidgetId) {
        obtainHostView(context, HOME_WIDGET_SLOT_ID, appWidgetId)
    }

    val widgetHeightModifier = when (headerSize) {
        HeaderSize.SMALL -> Modifier.height(110.dp)
        HeaderSize.MEDIUM -> Modifier.height(170.dp)
        HeaderSize.LARGE -> Modifier.height(230.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    showSheet = true
                },
            ),
    ) {
        if (hostView != null) {
            AndroidView(
                factory = { _ ->
                    (hostView.parent as? ViewGroup)?.removeView(hostView)
                    hostView.onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSheet = true
                    }
                    hostView.post {
                        hostView.updateAppWidgetSizeFromLayout()
                        hostView.requestLayout()
                        hostView.invalidate()
                    }
                    hostView
                },
                update = { view ->
                    view.onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showSheet = true
                    }
                    view.post { view.updateAppWidgetSizeFromLayout() }
                },
                onRelease = { view -> view.onLongPress = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(widgetHeightModifier),
            )
        }
    }

    if (showSheet) {
        HomeWidgetOptionsSheet(
            currentSize = headerSize,
            onSizeChange = onSizeChange,
            onReplace = {
                showSheet = false
                onReplaceWidget()
            },
            onRestore = {
                showSheet = false
                onRestoreDefaultClock()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeWidgetOptionsSheet(
    currentSize: HeaderSize,
    onSizeChange: (HeaderSize) -> Unit,
    onReplace: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.home_widget_options),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(R.string.setting_header_size).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = currentSize == HeaderSize.SMALL,
                    onClick = { onSizeChange(HeaderSize.SMALL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) {
                    Text(stringResource(R.string.size_small))
                }
                SegmentedButton(
                    selected = currentSize == HeaderSize.MEDIUM,
                    onClick = { onSizeChange(HeaderSize.MEDIUM) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) {
                    Text(stringResource(R.string.size_medium))
                }
                SegmentedButton(
                    selected = currentSize == HeaderSize.LARGE,
                    onClick = { onSizeChange(HeaderSize.LARGE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) {
                    Text(stringResource(R.string.size_large))
                }
            }

            Spacer(Modifier.height(4.dp))

            FilledTonalButton(
                onClick = onReplace,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_widget_replace),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onRestore,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_widget_restore),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
