package app.boltmail.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Account::class, Folder::class, Message::class, Body::class, Attachment::class, Op::class, Contact::class],
    version = 1,
    exportSchema = false,
)
abstract class BoltDb : RoomDatabase() {
    abstract fun dao(): MailDao

    companion object {
        fun create(context: Context): BoltDb =
            Room.databaseBuilder(context, BoltDb::class.java, "boltmail.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
