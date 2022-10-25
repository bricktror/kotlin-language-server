package org.kotlinlsp.util

import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.nio.file.Path
import java.nio.file.Paths

inline fun<reified Find> PsiElement.findParent() =
        this.parentsWithSelf.filterIsInstance<Find>().firstOrNull()

fun PsiElement.preOrderTraversal(shouldTraverse: (PsiElement) -> Boolean = { true }): Sequence<PsiElement> {
    val root = this

    return sequence {
        if (shouldTraverse(root)) {
            yield(root)

            for (child in root.children) {
                if (shouldTraverse(child)) {
                    yieldAll(child.preOrderTraversal(shouldTraverse))
                }
            }
        }
    }
}

fun PsiFile.toPath(): Path =
    this.originalFile.viewProvider.virtualFile.path.let{ path->
        if (path.get(2) == ':' && path.get(0) == '/') {
            // Strip leading '/' when dealing with paths on Windows
            return Paths.get(path.substring(1))
        } else {
            return Paths.get(path)
        }
    }

