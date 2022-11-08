package org.kotlinlsp.source.generate

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.types.KotlinType

import org.kotlinlsp.util.indexToPosition

fun tryCreateStub(member: MemberDescriptor) = when(member) {
    is FunctionDescriptor -> createFunctionStub(member)
    is PropertyDescriptor -> createVariableStub(member)
    else -> null
}

fun createVariableStub(variable: PropertyDescriptor): String {
    val variableType = variable.returnType?.unwrappedType()?.toString()?.takeIf { "Unit" != it }
    return "override val ${variable.name}${variableType?.let { ": $it" } ?: ""} = TODO(\"SET VALUE\")"
}

fun createFunctionStub(function: FunctionDescriptor): String {
    val arguments = function.valueParameters
        .map { argument ->
            val argumentName = argument.name
            val argumentType = argument.type.unwrappedType()

            "$argumentName: $argumentType"
        }
        .joinToString(", ")
    val returnType = function.returnType
        ?.unwrappedType()
        ?.toString()
        ?.takeIf { "Unit" != it }
        ?.let{": ${it}"}
        ?: ""
    return "override fun ${function.name}(${arguments})${returnType} { }"
}

fun getDeclarationPadding(content: String, kotlinClass: KtClass): String =
    kotlinClass.declarations
        .lastOrNull()
        ?.startOffset
        .let{it?: kotlinClass.startOffset}
        .let{ indexToPosition( content, it) }
        .character
        .let{" ".repeat(it)}


// about types: regular Kotlin types are marked T or T?, but types from Java are (T..T?) because
// nullability cannot be decided.
// Therefore we have to unpack in case we have the Java type. Fortunately, the Java types are not
// marked nullable, so we default to non nullable types. Let the user decide if they want nullable
// types instead. With this implementation Kotlin types also keeps their nullability
fun KotlinType.unwrappedType(): KotlinType =
        this.unwrap().makeNullableAsSpecified(this.isMarkedNullable)
