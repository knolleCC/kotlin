@file:Suppress("unused") // usages in build scripts are not tracked properly

import org.gradle.api.logging.Logger
import org.objectweb.asm.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarFile
import java.util.zip.ZipOutputStream

/**
 * Removes @kotlin.Metadata annotations from compiled Kotlin classes
 */
fun stripMetadata(logger: Logger, classNamePattern: String, inFile: File, outFile: File) {
    val classRegex = classNamePattern.toRegex()

    assert(inFile.exists()) { "Input file not found at $inFile" }

    fun transform(entryName: String, bytes: ByteArray): ByteArray {
        if (!entryName.endsWith(".class")) return bytes
        if (!classRegex.matches(entryName.removeSuffix(".class"))) return bytes

        var changed = false
        val classWriter = ClassWriter(0)
        val classVisitor = object : ClassVisitor(Opcodes.ASM5, classWriter) {
            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                if (Type.getType(desc).internalName == "kotlin/Metadata") {
                    changed = true
                    return null
                }
                return super.visitAnnotation(desc, visible)
            }
        }
        ClassReader(bytes).accept(classVisitor, 0)
        if (!changed) return bytes

        return classWriter.toByteArray()
    }

    ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { outJar ->
        JarFile(inFile).use { inJar ->
            for (entry in inJar.entries()) {
                val inBytes = inJar.getInputStream(entry).readBytes()
                val outBytes = transform(entry.name, inBytes)

                if (inBytes.size < outBytes.size) {
                    error("Size increased for ${entry.name}: was ${inBytes.size} bytes, became ${outBytes.size} bytes")
                }

                entry.compressedSize = -1L
                outJar.putNextEntry(entry)
                outJar.write(outBytes)
                outJar.closeEntry()
            }
        }
    }

    logger.info("Stripping @kotlin.Metadata annotations from all classes in $inFile")
    logger.info("Class name pattern: $classNamePattern")
    logger.info("Input file size: ${inFile.length()} bytes")
    logger.info("Output written to $outFile")
    logger.info("Output file size: ${outFile.length()} bytes")
}
