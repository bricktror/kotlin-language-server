package org.kotlinlsp.index

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*
import kotlin.sequences.Sequence
import org.kotlinlsp.logging.*

/**
 * A global view of all available symbols across all packages.
 */
class SymbolIndex {
    private val log by findLogger
    // TODO: do we need a database for this?
    //       Can we pull it of binary searching a sorted list since we only search by name and receivertype?
    private val db = Database.connect("jdbc:h2:mem:symbolindex;DB_CLOSE_DELAY=-1", "org.h2.Driver")

    init {
        transaction(db) {
            SchemaUtils.create(SymbolsTable)
        }
    }

    // Removes a list of indexes and adds another list. Everything is done in the same transaction.
    fun updateIndex(
        action: SymbolTransaction.() -> Unit
    ) = transaction(db) {
        log.catchingWithDuration("updating symbol index", {"${it.value} symbol(s)"}) {
            action(object:SymbolTransaction {
                override fun remove(fqn: String, receiverFqn: String?) {
                    SymbolsTable.deleteByFqn(fqn, receiverFqn)
                }
                override fun add(symbol: Symbol) {
                    SymbolsTable.create(symbol)
                }
            })
            lazy{SymbolsTable.slice(SymbolsTable.fqName.count()).selectAll().first()[SymbolsTable.fqName.count()]}
        }
    }

    fun query(
        q: String,
        receiverType: String? = null,
        limit: Int = 20,
        exact: Boolean = false
    ): List<Symbol> = transaction(db) {
        // TODO: Extension completion currently only works if the receiver matches exactly,
        //       ideally this should work with subtypes as well
        val suffix=if (exact) "" else "%"
        SymbolEntity
            .find {
                (SymbolsTable.shortName like "${q}${suffix}") and
                (SymbolsTable.extensionReceiverType eq receiverType)
            }
            .limit(limit)
            .map { Symbol.fromDao(it) }

    }
}

interface SymbolTransaction {
    fun remove(fqn: String, receiverFqn: String?)
    fun add(symbol: Symbol)
}

