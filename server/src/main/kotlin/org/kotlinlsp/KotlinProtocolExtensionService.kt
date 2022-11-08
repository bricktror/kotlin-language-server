package org.kotlinlsp

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.eclipse.lsp4j.*
import org.kotlinlsp.util.parseURI
import org.kotlinlsp.lsp4kt.ProtocolExtensions
import org.kotlinlsp.source.SourceFileRepository
import org.kotlinlsp.file.FileProvider
import org.kotlinlsp.util.getIndexIn
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import kotlin.io.path.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.kotlinlsp.source.SourceFile
import org.kotlinlsp.source.generate.*
import org.kotlinlsp.source.context.findDeclarationAt

class KotlinProtocolExtensionService(
    private val sp: SourceFileRepository,
) : ProtocolExtensions {

    override suspend fun overrideMember(position: TextDocumentPositionParams): List<CodeAction> =
        sp.compileFile(parseURI(position.textDocument.uri))
            ?.let { file->
                val cursor = position.position.getIndexIn(file.content)
                val uri = file.ktFile.toPath().toUri().toString()
                file.ktFile.findElementAt(cursor)
                    .let { it as? KtClass }
                    ?.let { kotlinClass->
                        // Get the functions that need to be implemented
                        val padding = getDeclarationPadding(file.content, kotlinClass)
                        val newMembersStartPosition = getNewMembersStartPosition(file, kotlinClass)
                            .let{Range(it, it) }
                        // For each of the super types used by this class
                        // TODO: does not seem to handle the implicit Any and Object super types that well. Need to find out if that is easily solvable. Finds the methods from them if any super class or interface is present
                        kotlinClass
                            .superTypeListEntries
                            .mapNotNull { superType ->
                                file.context
                                    .findDeclarationAt(superType.startOffset)
                                    ?.let{getClassDescriptor(it)}
                                    ?.takeIf{ it.canBeExtended() }
                                    ?.getMemberScope(getSuperClassTypeProjections(file, superType))
                                    ?.getContributedDescriptors()
                                    ?.filter { classMember ->
                                        classMember is MemberDescriptor &&
                                        classMember.canBeOverriden() &&
                                        !overridesDeclaration(kotlinClass, classMember)
                                    }
                                    ?.mapNotNull { member -> when (member) {
                                        is FunctionDescriptor -> createFunctionStub(member)
                                        is PropertyDescriptor -> createVariableStub(member)
                                        else -> null
                                    } }
                            }
                            .flatten()
                            .map { member ->
                                CodeAction().apply{
                                    edit = WorkspaceEdit(mapOf(
                                        uri to listOf(
                                            TextEdit(
                                                newMembersStartPosition,
                                                System.lineSeparator() + System.lineSeparator() + padding + member)
                                        )
                                    ))
                                    title = member
                                }
                            }
                    }
            }
            ?: emptyList()
}


private fun ClassDescriptor.canBeExtended() =
    this.kind.isInterface ||
    this.modality == Modality.ABSTRACT ||
    this.modality == Modality.OPEN

private fun MemberDescriptor.canBeOverriden() = (Modality.ABSTRACT == this.modality || Modality.OPEN == this.modality) && Modality.FINAL != this.modality && this.visibility != DescriptorVisibilities.PRIVATE && this.visibility != DescriptorVisibilities.PROTECTED
