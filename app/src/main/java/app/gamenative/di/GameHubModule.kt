package app.gamenative.di

import android.content.Context
import app.gamenative.gamehub.DataStoreGameLibraryRepository
import app.gamenative.gamehub.GameLibraryRepository
import app.gamenative.gamehub.StoreManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the Game Hub. Provides the process-wide [StoreManager] registry and the
 * hub-owned [GameLibraryRepository]. The concrete store providers are registered into the
 * StoreManager by [app.gamenative.gamehub.GameHubRegistrar] (constructor-injected) at startup.
 */
@Module
@InstallIn(SingletonComponent::class)
object GameHubModule {

    @Provides
    @Singleton
    fun provideStoreManager(): StoreManager = StoreManager()

    @Provides
    @Singleton
    fun provideGameLibraryRepository(
        @ApplicationContext context: Context,
    ): GameLibraryRepository = DataStoreGameLibraryRepository(context)
}
