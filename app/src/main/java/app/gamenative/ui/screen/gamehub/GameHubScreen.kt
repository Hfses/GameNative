package app.gamenative.ui.screen.gamehub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.gamehub.GameModel
import app.gamenative.gamehub.GameModelMapper
import app.gamenative.gamehub.InstallState
import app.gamenative.gamehub.StoreConnectionState
import app.gamenative.ui.model.GameHubViewModel
import app.gamenative.ui.model.GameHubViewModel.InstallFilter
import app.gamenative.ui.model.GameHubViewModel.SortBy
import app.gamenative.ui.screen.library.AppScreen
import com.skydoves.landscapist.coil.CoilImage

/**
 * The Game Hub: a unified, source-agnostic view over every registered store. Two tabs — the merged
 * Library (all stores' games in one filterable list) and Stores (per-source connection + counts).
 * Reads only [GameHubViewModel] / StoreManager; it has no knowledge of any concrete store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHubScreen(
    onBack: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit = { _, _ -> },
    onTestGraphics: (String) -> Unit = {},
    onPlayWithDiagnostics: (String) -> Unit = {},
    viewModel: GameHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stores by viewModel.stores.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Tapping a game opens the app's existing, proven detail screen (install / play / configure),
    // reusing the whole per-store install flow instead of reimplementing downloads in the hub.
    var selectedGame by remember { mutableStateOf<GameModel?>(null) }
    val opened = selectedGame
    if (opened != null) {
        AppScreen(
            libraryItem = GameModelMapper.toLibraryItem(opened),
            onClickPlay = { asContainer ->
                viewModel.recordPlayed(opened.id)
                onClickPlay(opened.id, asContainer)
            },
            onTestGraphics = { onTestGraphics(opened.id) },
            onPlayWithDiagnostics = { onPlayWithDiagnostics(opened.id) },
            onBack = { selectedGame = null },
        )
        return
    }

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
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.game_hub_tab_library)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.game_hub_tab_stores)) },
                )
            }

            if (tab == 0) {
                LibraryTab(state = state, viewModel = viewModel, onOpenGame = { selectedGame = it })
            } else {
                StoresTab(stores = stores)
            }
        }
    }
}

@Composable
private fun LibraryTab(
    state: GameHubViewModel.GameHubUiState,
    viewModel: GameHubViewModel,
    onOpenGame: (GameModel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
            FilterChip(
                selected = state.favoritesOnly,
                onClick = { viewModel.setFavoritesOnly(!state.favoritesOnly) },
                label = { Text(stringResource(R.string.game_hub_favorites)) },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            )
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

        // Sort options + how many of the total are showing.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortBy.entries.forEach { sort ->
                FilterChip(
                    selected = state.sortBy == sort,
                    onClick = { viewModel.setSort(sort) },
                    label = { Text(sortLabel(sort)) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.game_hub_count, state.games.size, state.totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.games, key = { it.id }) { game ->
                    GameRow(
                        game = game,
                        onOpen = { onOpenGame(game) },
                        onToggleFavorite = { viewModel.toggleFavorite(game) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoresTab(stores: List<GameHubViewModel.StoreInfo>) {
    if (stores.isEmpty()) {
        Box(
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
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(stores, key = { it.source.name }) { store -> StoreRow(store) }
    }
}

@Composable
private fun StoreRow(store: GameHubViewModel.StoreInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = store.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = connectionLabel(store.connection),
                style = MaterialTheme.typography.bodySmall,
                color = connectionColor(store.connection),
            )
            Text(
                text = stringResource(R.string.game_hub_game_count, store.gameCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameRow(game: GameModel, onOpen: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoilImage(
            modifier = Modifier
                .size(width = 48.dp, height = 64.dp)
                .clip(RoundedCornerShape(6.dp)),
            imageModel = { game.coverUrl.ifEmpty { null } },
        )
        Column(modifier = Modifier.weight(1f)) {
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
        // A quick launch icon for installed games (tapping the row opens the full detail screen
        // with Install/Play/configure, reusing each store's existing flow).
        if (game.isInstalled) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (game.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (game.isFavorite) R.string.game_hub_favorite_remove else R.string.game_hub_favorite_add,
                ),
                tint = if (game.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun sortLabel(sort: SortBy): String = stringResource(
    when (sort) {
        SortBy.NAME -> R.string.game_hub_sort_name
        SortBy.STORE -> R.string.game_hub_sort_store
        SortBy.RECENT -> R.string.game_hub_sort_recent
    },
)

@Composable
private fun connectionLabel(state: StoreConnectionState): String = when (state) {
    is StoreConnectionState.Connected -> stringResource(R.string.game_hub_store_connected)
    is StoreConnectionState.Connecting -> stringResource(R.string.game_hub_store_connecting)
    is StoreConnectionState.Error -> state.reason
    is StoreConnectionState.Disconnected -> stringResource(R.string.game_hub_store_disconnected)
}

@Composable
private fun connectionColor(state: StoreConnectionState): Color = when (state) {
    is StoreConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is StoreConnectionState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

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
