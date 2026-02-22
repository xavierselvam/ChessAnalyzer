package com.chessanalyzer.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GameEntity::class, MoveEvaluationEntity::class, PositionEvalCacheEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun moveEvaluationDao(): MoveEvaluationDao
    abstract fun positionEvalCacheDao(): PositionEvalCacheDao

    companion object {
        const val DATABASE_NAME = "chess_analyzer_db"

        /** Preserves all existing games; adds the three new columns with safe defaults. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE games ADD COLUMN gameType TEXT NOT NULL DEFAULT 'rapid'")
                database.execSQL("ALTER TABLE games ADD COLUMN userRating INTEGER")
                database.execSQL("ALTER TABLE games ADD COLUMN opponentRating INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_playedAt ON games (playedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_gameType ON games (gameType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_platform ON games (platform)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_gameType_playedAt ON games (gameType, playedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_platform_playedAt ON games (platform, playedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_games_platform_gameType_playedAt ON games (platform, gameType, playedAt)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE move_evaluations ADD COLUMN fenBefore TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE move_evaluations ADD COLUMN moveStatus TEXT NOT NULL DEFAULT 'stockfish_done'"
                )
                database.execSQL(
                    "ALTER TABLE move_evaluations ADD COLUMN evalBeforeSource TEXT NOT NULL DEFAULT 'stockfish'"
                )
                database.execSQL(
                    "ALTER TABLE move_evaluations ADD COLUMN evalAfterSource TEXT NOT NULL DEFAULT 'stockfish'"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_move_evaluations_moveStatus ON move_evaluations (moveStatus)"
                )
            }
        }
    }
}
