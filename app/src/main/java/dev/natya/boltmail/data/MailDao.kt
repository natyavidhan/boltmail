package dev.natya.boltmail.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val ROW = "m.id, m.accountId, m.folderId, m.subject, m.fromName, m.fromAddress, m.date, m.snippet, m.seen, m.flagged, m.hasAttachments"

@Dao
interface MailDao {
    // ---- accounts
    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun accountsFlow(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder, id")
    fun accounts(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun account(id: Long): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun accountFlow(id: Long): Flow<Account?>

    @Query("SELECT COUNT(*) FROM accounts WHERE email = :email COLLATE NOCASE")
    fun accountsWithEmail(email: String): Int

    @Insert
    fun insertAccount(a: Account): Long

    @Update
    fun updateAccount(a: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    fun deleteAccount(id: Long)

    @Query("UPDATE accounts SET error = :error, errorKind = :kind WHERE id = :id")
    fun setAccountError(id: Long, error: String?, kind: String?)

    // ---- folders
    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY sort, name")
    fun folders(accountId: Long): List<Folder>

    @Query("SELECT * FROM folders ORDER BY accountId, sort, name")
    fun foldersFlow(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun folder(id: Long): Folder?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND role = :role LIMIT 1")
    fun folderByRole(accountId: Long, role: String): Folder?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND path = :path")
    fun folderByPath(accountId: Long, path: String): Folder?

    @Query("SELECT * FROM folders WHERE role = 'inbox'")
    fun inboxes(): List<Folder>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFolder(f: Folder): Long

    @Update
    fun updateFolder(f: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    fun deleteFolder(id: Long)

    @Query("UPDATE folders SET highestUid = MAX(highestUid, :uid) WHERE id = :id")
    fun bumpHighestUid(id: Long, uid: Long)

    @Query("UPDATE folders SET uidValidity = :uidValidity, highestUid = 0, synced = 0, hasMore = 1 WHERE id = :id")
    fun resetFolder(id: Long, uidValidity: Long)

    @Query("UPDATE folders SET synced = 1, lastSync = :time, hasMore = :hasMore WHERE id = :id")
    fun markSynced(id: Long, time: Long, hasMore: Boolean)

    @Query("UPDATE folders SET hasMore = :hasMore WHERE id = :id")
    fun setHasMore(id: Long, hasMore: Boolean)

    // ---- message lists
    @Query(
        "SELECT $ROW FROM messages m JOIN folders f ON f.id = m.folderId " +
            "WHERE f.role = 'inbox' AND m.hidden = 0 ORDER BY m.date DESC LIMIT :limit",
    )
    fun inboxRows(limit: Int): Flow<List<MessageRow>>

    @Query("SELECT $ROW FROM messages m WHERE m.folderId = :folderId AND m.hidden = 0 ORDER BY m.date DESC LIMIT :limit")
    fun folderRows(folderId: Long, limit: Int): Flow<List<MessageRow>>

    @Query(
        "SELECT $ROW FROM messages m WHERE m.hidden = 0 AND (:accountId = 0 OR m.accountId = :accountId) AND " +
            "(m.subject LIKE :q OR m.fromName LIKE :q OR m.fromAddress LIKE :q OR m.snippet LIKE :q OR m.toList LIKE :q) " +
            "ORDER BY m.date DESC LIMIT 200",
    )
    fun search(q: String, accountId: Long): Flow<List<MessageRow>>

    @Query("SELECT m.folderId AS folderId, COUNT(*) AS unread FROM messages m WHERE m.seen = 0 AND m.hidden = 0 GROUP BY m.folderId")
    fun unreadCounts(): Flow<List<UnreadCount>>

    // ---- messages
    @Query("SELECT * FROM messages WHERE id = :id")
    fun message(id: Long): Message?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun messageFlow(id: Long): Flow<Message?>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND uid = :uid")
    fun messageByUid(folderId: Long, uid: Long): Message?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(m: Message): Long

    @Query("UPDATE messages SET seen = :seen WHERE id = :id")
    fun setSeen(id: Long, seen: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id = :id")
    fun setFlagged(id: Long, flagged: Boolean)

    @Query("UPDATE messages SET hidden = :hidden WHERE id = :id")
    fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE messages SET seen = :seen, flagged = :flagged, answered = :answered WHERE id = :id")
    fun setFlags(id: Long, seen: Boolean, flagged: Boolean, answered: Boolean)

    @Query("UPDATE messages SET snippet = :snippet, bodyState = :state, hasAttachments = :hasAttachments WHERE id = :id")
    fun setBodyInfo(id: Long, snippet: String, state: Int, hasAttachments: Boolean)

    @Query("UPDATE messages SET bodyState = :state WHERE id = :id")
    fun setBodyState(id: Long, state: Int)

    @Query("UPDATE messages SET notified = 1 WHERE id = :id")
    fun markNotified(id: Long)

    @Query("SELECT id, uid, seen, flagged, answered FROM messages WHERE folderId = :folderId AND uid >= :minUid")
    fun flagStates(folderId: Long, minUid: Long): List<FlagState>

    @Query("SELECT MIN(uid) FROM messages WHERE folderId = :folderId")
    fun minUid(folderId: Long): Long?

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE id = :id")
    fun deleteMessage(id: Long)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    fun clearFolder(folderId: Long)

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND bodyState = 0 AND hidden = 0 ORDER BY uid DESC LIMIT :limit")
    fun needingBodies(folderId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND seen = 0 AND notified = 0 AND hidden = 0")
    fun unnotified(folderId: Long): List<Message>

    // ---- bodies and attachments
    @Query("SELECT * FROM bodies WHERE messageId = :id")
    fun body(id: Long): Body?

    @Query("SELECT * FROM bodies WHERE messageId = :id")
    fun bodyFlow(id: Long): Flow<Body?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBody(b: Body)

    @Query("SELECT * FROM attachments WHERE messageId = :id ORDER BY id")
    fun attachments(id: Long): List<Attachment>

    @Query("SELECT * FROM attachments WHERE messageId = :id ORDER BY id")
    fun attachmentsFlow(id: Long): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    fun attachment(id: Long): Attachment?

    @Insert
    fun insertAttachments(list: List<Attachment>)

    @Query("DELETE FROM attachments WHERE messageId = :id")
    fun clearAttachments(id: Long)

    @Query("UPDATE attachments SET file = :file WHERE id = :id")
    fun setAttachmentFile(id: Long, file: String?)

    // ---- ops
    @Insert
    fun insertOp(op: Op): Long

    @Update
    fun updateOp(op: Op)

    @Query("SELECT * FROM ops WHERE id = :id")
    fun op(id: Long): Op?

    @Query("DELETE FROM ops WHERE id = :id")
    fun deleteOp(id: Long)

    @Query("SELECT * FROM ops WHERE notBefore <= :now AND error IS NULL ORDER BY id")
    fun dueOps(now: Long): List<Op>

    @Query("SELECT MIN(notBefore) FROM ops WHERE error IS NULL")
    fun nextOpTime(): Long?

    @Query("SELECT DISTINCT messageId FROM ops WHERE messageId != 0")
    fun messagesWithOps(): List<Long>

    @Query("SELECT * FROM ops WHERE kind = 'send' ORDER BY id")
    fun sendOpsFlow(): Flow<List<Op>>

    // ---- contacts
    @Upsert
    fun upsertContact(c: Contact)

    @Query("SELECT * FROM contacts WHERE address = :address")
    fun contact(address: String): Contact?

    @Query("SELECT * FROM contacts WHERE address LIKE :q OR name LIKE :q ORDER BY count DESC, lastUsed DESC LIMIT 6")
    fun searchContacts(q: String): List<Contact>

    @Query(
        "SELECT fromAddress AS address, MAX(fromName) AS name, MAX(date) AS lastUsed, COUNT(*) AS count FROM messages " +
            "WHERE fromAddress != '' AND (fromAddress LIKE :q OR fromName LIKE :q) GROUP BY fromAddress ORDER BY count DESC LIMIT 6",
    )
    fun searchSenders(q: String): List<Contact>
}
