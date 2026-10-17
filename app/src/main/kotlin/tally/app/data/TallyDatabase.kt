package tally.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * v1: raw events only (Phase 0).
 * v2: transactions, splits, tags, categories, accounts, merchants, aliases, rules (Phase 1).
 * v3: account_identifiers (several last-4s per account; Phase 2).
 * v4: aliases.priority (alias order; Phase 3).
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
        AccountIdentifierEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun merchantDao(): MerchantDao
    abstract fun accountIdentifierDao(): AccountIdentifierDao
    abstract fun ruleDao(): RuleDao
    abstract fun aliasDao(): AliasDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "tally.db"

        fun build(context: Context): TallyDatabase =
            Room.databaseBuilder(context, TallyDatabase::class.java, NAME).build()
    }
}
