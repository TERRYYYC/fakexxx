package name.caiyao.fakegps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT id, addname, latitude, longitude FROM temp ORDER BY id DESC")
    fun observeAll(): Flow<List<ProfileSummary>>

    @Query("SELECT * FROM temp WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM temp ORDER BY id DESC")
    fun observeEntities(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM temp ORDER BY id ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Insert
    suspend fun insertAll(profiles: List<ProfileEntity>): List<Long>

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM temp WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM temp")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM temp")
    fun observeCount(): Flow<Int>
}

data class ProfileSummary(
    val id: Long,
    val addname: String?,
    val latitude: Double?,
    val longitude: Double?,
)
