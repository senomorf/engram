package cam.engram.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.engram.app.R

private data class Page(
    val titleRes: Int,
    val bodyRes: Int,
)

private val pages =
    listOf(
        Page(R.string.onboard_1_title, R.string.onboard_1_body),
        Page(R.string.onboard_2_title, R.string.onboard_2_body),
        Page(R.string.onboard_honesty_title, R.string.onboard_honesty_body),
    )

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = { if (index < pages.lastIndex) index++ else onDone() },
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            ) {
                Text(
                    stringResource(
                        if (index < pages.lastIndex) R.string.onboard_next else R.string.onboard_start,
                    ),
                )
            }
            if (index < pages.lastIndex) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.onboard_skip)) }
            }
        }
    }
}
