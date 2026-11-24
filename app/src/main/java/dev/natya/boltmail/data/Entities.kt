package dev.natya.boltmail.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    /** Sender name shown to recipients. */
    val name: String,
    /** Short nickname shown in the app, e.g. "Studio". */
    val label: String,
    val color: Int,
    val auth: String,
    val username: String,
    /** Keystore-encrypted password; empty for Google accounts. */
    val secret: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    val provider: String? = null,
    val notify: Boolean = true,
    val signature: String = "",
    val sortOrder: Int = 0,
    val error: String? = null,
    val errorKind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

const val AUTH_PASSWORD = "password"
const val AUTH_GOOGLE = "google"
const val SEC_SSL = "ssl"
const val SEC_STARTTLS = "starttls"
const val SEC_NONE = "none"
const val ERR_AUTH = "auth"
const val ERR_SERVER = "server"

fun Account.isGoogle() = auth == AUTH_GOOGLE

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(Account::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "path"], unique = true)],
)
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val path: String,
    val name: String,
    val role: String?,
    val uidValidity: Long = 0,
    val highestUid: Long = 0,
    val synced: Boolean = false,
    val hasMore: Boolean = true,
    val lastSync: Long = 0,
    val sort: Int = 50,
)

object Role {
    const val INBOX = "inbox"
    const val SENT = "sent"
    const val DRAFTS = "drafts"
    const val ARCHIVE = "archive"
    const val ALL = "all"
    const val SPAM = "spam"
    const val TRASH = "trash"

    fun sort(role: String?) = when (role) {
        INBOX -> 0
        SENT -> 1
        DRAFTS -> 2
        ARCHIVE -> 3
        ALL -> 4
        SPAM -> 5
        TRASH -> 6
        else -> 50
    }
}

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(Folder::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["folderId", "uid"], unique = true), Index("accountId"), Index("date")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val messageId: String? = null,
    val inReplyTo: String? = null,
    val refs: String? = null,
    val subject: String = "",
    val fromName: String = "",
    val fromAddress: String = "",
    val replyTo: String = "",
    val toList: String = "",
    val ccList: String = "",
    val date: Long = 0,
    val snippet: String = "",
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val hasAttachments: Boolean = false,
    val size: Int = 0,
    val bodyState: Int = BODY_NONE,
    val hidden: Boolean = false,
    val notified: Boolean = false,
)

const val BODY_NONE = 0
const val BODY_LOADED = 1
const val BODY_FAILED = 2

/** Light projection used by message lists. */
data class MessageRow(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val date: Long,
    val snippet: String,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachments: Boolean,
)

data class FlagState(val id: Long, val uid: Long, val seen: Boolean, val flagged: Boolean, val answered: Boolean)

data class UnreadCount(val folderId: Long, val unread: Int)

@Entity(
    tableName = "bodies",
    foreignKeys = [ForeignKey(Message::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE)],
)
data class Body(
    @PrimaryKey val messageId: Long,
    val html: String?,
    val text: String?,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(Message::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("messageId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** Dot-separated child indexes from the message root, e.g. "1.0". */
    val part: String,
    val name: String,
    val mime: String,
    val size: Long,
    val cid: String?,
    val inline: Boolean,
    val file: String? = null,
)

/** A queued server operation. Local state changes first; the op catches the server up. */
@Entity(
    tableName = "ops",
    foreignKeys = [ForeignKey(Account::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId")],
)
data class Op(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val kind: String,
    val messageId: Long = 0,
    val folderPath: String = "",
    val uid: Long = 0,
    val arg: String = "",
    /** Human label for the outbox, e.g. the subject of a message being sent. */
    val title: String = "",
    val notBefore: Long = 0,
    val attempts: Int = 0,
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
)

object OpKind {
    const val SEEN = "seen"
    const val FLAG = "flag"
    const val MOVE = "move"
    const val DELETE = "delete"
    const val ANSWERED = "answered"
    const val SEND = "send"
}

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val address: String,
    val name: String,
    val lastUsed: Long,
    val count: Int,
)
