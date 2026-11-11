import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File

fun main(args:Array<String>) {
    val disposable=Disposer.newDisposable()
    try {
        val environment=KotlinCoreEnvironment.createForProduction(disposable,CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory=KtPsiFactory(environment.project,false)
        var count=0;var errors=0
        File(args[0]).walkTopDown().filter { it.extension=="kt" }.forEach { file ->
            count++
            val parsed=factory.createFile(file.name,file.readText())
            PsiTreeUtil.collectElementsOfType(parsed,PsiErrorElement::class.java).forEach { error ->
                val line=parsed.text.take(error.textOffset).count { it=='\n' }+1
                println("${file.path}:$line: ${error.errorDescription}");errors++
            }
        }
        check(errors==0) { "Kotlin syntax errors: $errors" }
        println("Kotlin syntax: OK ($count files). This is not Android type checking.")
    } finally { Disposer.dispose(disposable) }
}
