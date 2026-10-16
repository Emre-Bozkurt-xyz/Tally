package tally.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * v1: raw events only (Phase 0).
 * v2: transactions, splits, tags, categories, accounts, merchants, aliases, rules (Phase 1).
 *
 * Every version's schema JSON is committed in `app/schemas/`; migrations are tested
 * against it in `TallyDatabaseMigrationTest`.
 */
@Database(
    entities = [
        RawEventEntity::class,
        TransactionEntity::class,
        SplitEntity::class,
        TagEntity::class,
        TransactionTagCrossRef::class,
        CategoryEntity::class,
        AccountEntity::class,
        MerchantEntity::class,
        AliasEntity::class,
        RuleEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val NAME = "tally.db"

        fun build(context: Context): TallyDatabase =
            Room.databaseBuilder(context, TallyDatabase::class.java, NAME).build()
    }
}
