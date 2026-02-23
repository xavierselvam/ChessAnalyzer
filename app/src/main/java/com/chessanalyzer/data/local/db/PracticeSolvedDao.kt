package com.chessanalyzer.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PracticeSolvedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(solved: PracticeSolvedEntity)

    @Query("SELECT positionKey FROM practice_solved")
    suspend fun getAllSolvedKeys(): List<String>

    @Query("SELECT COUNT(*) FROM practice_solved")
    suspend fun getSolvedCount(): Int

    @Query("DELETE FROM practice_solved")
    suspend fun clearAll()
}
