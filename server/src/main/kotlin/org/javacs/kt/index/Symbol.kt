package org.javacs.kt.index

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*

import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

object SymbolsTable : IntIdTable() {
    const val MAX_FQNAME_LENGTH = 255
    const val MAX_SHORT_NAME_LENGTH = 80

    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH).index()
    val shortName = varchar("shortname", length = MAX_SHORT_NAME_LENGTH)
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = MAX_FQNAME_LENGTH).nullable()

    fun create(symbol: Symbol) =
        SymbolEntity.new {
            fqName = symbol.fqName.toString()
            shortName = symbol.fqName.shortName().toString()
            kind = symbol.kind.rawValue
            visibility = symbol.visibility.rawValue
            extensionReceiverType = symbol.extensionReceiverType.toString()
        }

    fun deleteByFqn(fqn: String, extensionReceiverFqn: String?) {
        deleteWhere {
            (fqName eq fqn.substring(0, MAX_FQNAME_LENGTH)) and
                (extensionReceiverType eq extensionReceiverFqn?.substring(0, MAX_FQNAME_LENGTH))
        }
    }
}

class SymbolEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SymbolEntity>(SymbolsTable)

    var fqName by SymbolsTable.fqName
    var shortName by SymbolsTable.shortName
    var kind by SymbolsTable.kind
    var visibility by SymbolsTable.visibility
    var extensionReceiverType by SymbolsTable.extensionReceiverType
}

data class Symbol(
    val fqName: FqName,
    val kind: Kind,
    val visibility: Visibility,
    val extensionReceiverType: FqName?
) {
    companion object {
        fun fromDao(dao: SymbolEntity)=
            Symbol(
                fqName = FqName(dao.fqName),
                kind = Kind.fromRaw(dao.kind),
                visibility = Visibility.fromRaw(dao.visibility),
                extensionReceiverType = dao.extensionReceiverType?.let(::FqName))
        fun fromDeclaration(declaration: DeclarationDescriptor) =
            declaration.run {
                Symbol(
                    fqName = fqNameSafe,
                    kind = accept(ExtractSymbolKind, Unit)
                        .rawValue
                        .let(Kind::fromRaw),
                    visibility = accept(ExtractSymbolVisibility, Unit)
                        .rawValue
                        .let(Visibility::fromRaw),
                    extensionReceiverType = accept(ExtractSymbolExtensionReceiverType, Unit)
                        ?.takeUnless{ it.isRoot }
                 )
            }
    }

    enum class Kind(val rawValue: Int) {
        CLASS(0),
        INTERFACE(1),
        FUNCTION(2),
        VARIABLE(3),
        MODULE(4),
        ENUM(5),
        ENUM_MEMBER(6),
        CONSTRUCTOR(7),
        FIELD(8),
        UNKNOWN(9);

        companion object {
            fun fromRaw(rawValue: Int) = Kind.values().firstOrNull { it.rawValue == rawValue } ?: Kind.UNKNOWN
        }
    }

    enum class Visibility(val rawValue: Int) {
        PRIVATE_TO_THIS(0),
        PRIVATE(1),
        INTERNAL(2),
        PROTECTED(3),
        PUBLIC(4),
        UNKNOWN(5);

        companion object {
            fun fromRaw(rawValue: Int) = Visibility.values().firstOrNull { it.rawValue == rawValue } ?: Visibility.UNKNOWN
        }
    }
}
