package io.github.yosefchai.mealie.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** השורות של קבוצה אחת. כל שורה מקבלת את צורת המקטע שלה: פינות גדולות רק בקצוות הקבוצה. */
class ListGroupScope {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (ListItemShapes) -> Unit) {
        rows += content
    }
}

/**
 * קבוצת פריטים בסגנון Material 3 Expressive: SegmentedListItem עם רווח קטן ביניהם,
 * ופינות מעוגלות לפי המיקום בקבוצה. כותרת אופציונלית מסומנת ככותרת לקוראי מסך.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListGroup(title: String?, modifier: Modifier = Modifier, build: ListGroupScope.() -> Unit) {
    val rows = ListGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column(modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { i, row -> row(ListItemDefaults.segmentedShapes(index = i, count = rows.size)) }
        }
    }
}

/**
 * צבעי שורות בקבוצה על רקע המסך: מיכל surfaceContainer כדי שהקבוצה תיראה,
 * ושורת מתג מסומנת לא נצבעת כ"נבחרת" (המצב מוצג במתג עצמו).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun groupItemColors(): ListItemColors {
    val c = MaterialTheme.colorScheme
    return ListItemDefaults.segmentedColors(
        containerColor = c.surfaceContainer,
        selectedContainerColor = c.surfaceContainer,
        selectedContentColor = c.onSurface,
        selectedLeadingContentColor = c.onSurfaceVariant,
        selectedTrailingContentColor = c.onSurfaceVariant,
        selectedSupportingContentColor = c.onSurfaceVariant,
    )
}
