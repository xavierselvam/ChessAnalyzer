package com.chessanalyzer.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PositionEvalCacheDao {

    @Query("SELECT * FROM position_eval_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): PositionEvalCacheEntity?

    /**
     * IGNORE on conflict: the first result written wins. This is intentional — evals
     * for the same position/depth should be identical; no need to overwrite.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PositionEvalCacheEntity)
}
