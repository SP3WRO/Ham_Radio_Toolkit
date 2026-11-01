package io.github.sp3wro.hamradiotoolkit.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Osobny plik, wyłączony z kopii zapasowej w chmurze (res/xml/backup_rules.xml, data_extraction_rules.xml)
private val Context.credentialsStore: DataStore<Preferences> by preferencesDataStore(
    name = "service_credentials",
    produceMigrations = { listOf(RemoveRepeaterBookCredentials) }
)

/** Usuwa z telefonu token i e-mail po wycofanym źródle RepeaterBook. */
private object RemoveRepeaterBookCredentials : DataMigration<Preferences> {
    private val keys = listOf(stringPreferencesKey("repeaterbook_token"), stringPreferencesKey("repeaterbook_contact"))

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = keys.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { keys.forEach { remove(it) } }.toPreferences()

    override suspend fun cleanUp() = Unit
}

data class ServiceCredentials(
    val hamQthUser: String = "",
    val hamQthPassword: String = "",
    val qrzcqUser: String = "",
    val qrzcqPassword: String = "",
    val qrzLogbookKey: String = "",
    val eqslUser: String = "",
    val eqslPassword: String = ""
) {
    val hasHamQth: Boolean get() = hamQthUser.isNotBlank() && hamQthPassword.isNotBlank()
    val hasQrzcq: Boolean get() = qrzcqUser.isNotBlank() && qrzcqPassword.isNotBlank()
    val hasQrz: Boolean get() = qrzLogbookKey.isNotBlank()
    val hasEqsl: Boolean get() = eqslUser.isNotBlank() && eqslPassword.isNotBlank()
}

class CredentialsRepository(context: Context) {
    private val store = context.applicationContext.credentialsStore

    private val hamQthUserKey = stringPreferencesKey("hamqth_user")
    private val hamQthPasswordKey = stringPreferencesKey("hamqth_password")
    private val qrzcqUserKey = stringPreferencesKey("qrzcq_user")
    private val qrzcqPasswordKey = stringPreferencesKey("qrzcq_password")
    private val qrzLogbookKeyKey = stringPreferencesKey("qrz_logbook_key")
    private val eqslUserKey = stringPreferencesKey("eqsl_user")
    private val eqslPasswordKey = stringPreferencesKey("eqsl_password")

    val credentials: Flow<ServiceCredentials> = store.data.map {
        ServiceCredentials(
            hamQthUser = it[hamQthUserKey].orEmpty(),
            hamQthPassword = it[hamQthPasswordKey].orEmpty(),
            qrzcqUser = it[qrzcqUserKey].orEmpty(),
            qrzcqPassword = it[qrzcqPasswordKey].orEmpty(),
            qrzLogbookKey = it[qrzLogbookKeyKey].orEmpty(),
            eqslUser = it[eqslUserKey].orEmpty(),
            eqslPassword = it[eqslPasswordKey].orEmpty()
        )
    }

    suspend fun current(): ServiceCredentials = credentials.first()

    suspend fun save(credentials: ServiceCredentials) {
        store.edit {
            it[hamQthUserKey] = credentials.hamQthUser.trim()
            it[hamQthPasswordKey] = credentials.hamQthPassword
            it[qrzcqUserKey] = credentials.qrzcqUser.trim()
            it[qrzcqPasswordKey] = credentials.qrzcqPassword
            it[qrzLogbookKeyKey] = credentials.qrzLogbookKey.trim()
            it[eqslUserKey] = credentials.eqslUser.trim()
            it[eqslPasswordKey] = credentials.eqslPassword
        }
    }
}
