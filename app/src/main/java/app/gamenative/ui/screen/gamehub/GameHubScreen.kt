package app.gamenative.ui.screen.gamehub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.gamehub.GameModel
import app.gamenative.gamehub.InstallState
import app.gamenative.ui.model.GameHubViewModel
import app.gamenative.ui.model.GameHubViewModel.InstallFilter
import com.skydoves.landscapist.coil.CoilImage

/**
 * The unified Game Hub library: every registered store's games merged into one source-agnostic
 * list, with install/source/text filters. Reads only [GameHubViewModel] / [StoreManager] — it has
 * no knowledge of any concrete store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHubScreen(
    onBack: () -> Unit,
    viewModel: GameHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.game_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.game_hub_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.game_hub_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InstallFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.installFilter == filter,
                        onClick = { viewModel.setInstallFilter(filter) },
                        label = { Text(installFilterLabel(filter)) },
                    )
                }
            }

            if (state.sources.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.sourceFilter == null,
                        onClick = { viewModel.setSourceFilter(null) },
                        label = { Text(stringResource(R.string.game_hub_all_sources)) },
                    )
                    state.sources.forEach { source ->
                        FilterChip(
                            selected = state.sourceFilter == source,
                            onClick = { viewModel.setSourceFilter(source) },
                            label = { Text(sourceLabel(source)) },
                        )
                    }
                }
            }

            when {
                state.loading && state.games.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.games.isEmpty() -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.game_hub_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.games, key = { it.id }) { game -> GameRow(game) }
                }
            }
        }
    }
}

@Composable
private fun GameRow(game: GameModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoilImage(
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(6.dp)),
            imageModel = { game.coverUrl.ifEmpty { null } },
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${sourceLabel(game.source)} · ${installStateLabel(game.installState)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun installFilterLabel(filter: InstallFilter): String = stringResource(
    when (filter) {
        InstallFilter.ALL -> R.string.game_hub_filter_all
        InstallFilter.INSTALLED -> R.string.game_hub_filter_installed
        InstallFilter.NOT_INSTALLED -> R.string.game_hub_filter_not_installed
    },
)

private fun sourceLabel(source: GameSource): String = when (source) {
    GameSource.STEAM -> "Steam"
    GameSource.CUSTOM_GAME -> "Local"
    GameSource.GOG -> "GOG"
    GameSource.EPIC -> "Epic"
    GameSource.AMAZON -> "Amazon"
}

@Composable
private fun installStateLabel(installState: InstallState): String = stringResource(
    if (installState == InstallState.INSTALLED) R.string.game_hub_filter_installed
    else R.string.game_hub_filter_not_installed,
)
