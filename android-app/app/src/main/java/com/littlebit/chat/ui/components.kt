package com.littlebit.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small coloured status chip used for model badges (Recommended / warnings). */
@Composable
fun BadgeChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Top-bar pill showing the loaded model; opens the Model Hub when tapped. */
@Composable
fun ModelSelectorPill(
    modelName: String?,
    loaded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (loaded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = modelName ?: "Select Model",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Segmented Fast / Think toggle used in the chat input bar. */
@Composable
fun FastThinkPill(
    fastMode: Boolean,
    onFast: () -> Unit,
    onThink: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(3.dp)) {
            PillOption("Fast", fastMode, onFast)
            PillOption("Think", !fastMode, onThink)
        }
    }
}

@Composable
private fun PillOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
