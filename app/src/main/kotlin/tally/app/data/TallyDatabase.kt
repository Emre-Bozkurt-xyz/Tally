package tally.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1 holds raw events only. Phase 1 adds the remaining tables through a real
 * 1 -> 2 migration, so the migration path gets exercised early. Commit `app/schemas/`.
 */
@Database(entities = [RawEventEntity::class], version = 1, exportSchema = true)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao

    companion object {
        fun build(context: Context): TallyDatabase =
            Room.databaseBuilder(context, TallyDatabase::class.java, "tally.db").build()
    }
}
