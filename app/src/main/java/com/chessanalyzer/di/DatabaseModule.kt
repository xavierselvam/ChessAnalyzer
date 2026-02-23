package com.chessanalyzer.di

import android.content.Context
import androidx.room.Room
import com.chessanalyzer.data.local.db.AppDatabase
import com.chessanalyzer.data.local.db.GameDao
import com.chessanalyzer.data.local.db.MoveEvaluationDao
import com.chessanalyzer.data.local.db.PositionEvalCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideGameDao(database: AppDatabase): GameDao = database.gameDao()

    @Provides
    fun provideMoveEvaluationDao(database: AppDatabase): MoveEvaluationDao =
        database.moveEvaluationDao()

    @Provides
    fun providePositionEvalCacheDao(database: AppDatabase): PositionEvalCacheDao =
        database.positionEvalCacheDao()
}
