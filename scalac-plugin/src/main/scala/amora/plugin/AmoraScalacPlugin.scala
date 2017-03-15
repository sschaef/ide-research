package amora.plugin

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

import scala.reflect.io.AbstractFile
import scala.reflect.io.NoAbstractFile
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent
import scala.util.Failure
import scala.util.Success

import amora.backend.schema.Schema
import amora.converter.ScalacConverter
import amora.converter.protocol._
import amora.converter.protocol.Attachment.JvmClass
import amora.converter.protocol.Attachment.SourceFile

class AmoraScalacPlugin(override val global: Global) extends Plugin {
  override val name = "AmoraScalacPlugin"

  override val description = "Generates information from scalac trees"

  override val components = List(new AmoraScalacComponent(global))
}

class AmoraScalacComponent(override val global: Global) extends PluginComponent {
  import global._

  override def newPhase(prev: Phase): Phase = new Phase(prev) {
    override def run(): Unit = {
      import scala.collection.JavaConverters._
      if (currentRun.units.isEmpty)
        return

      val outputDir = global.settings.outputDirs.getSingleOutput.getOrElse {
        throw new IllegalStateException("scalac has no output directory configured, therefore Amora compiler plugin can't store its data.")
      }.file.getAbsolutePath
      val depsFile = Paths.get(s"$outputDir/amora_dependencies")
      if (!depsFile.toFile().exists())
        throw new IllegalStateException(s"The automatically generated file `$depsFile` doesn't exist, make sure it wasn't accidentally removed.")
      val lines = Files.readAllLines(depsFile).asScala
      val srcDirs = lines.head.split(",")
      val classData = lines.tail.map { line ⇒
        val Array(organization, name, version, file) = line.split(",")
        file → File(Artifact(Project(organization), organization, name, version), file)
      }
      val classDir = classData.head._2
      val classDeps = classData.tail.toMap

      val addDeclAttachment = (sym: Symbol, decl: Decl) ⇒ {
        val file = sym.associatedFile
        if (file != NoAbstractFile) {
          val path = file.underlyingSource.get.path
          if (path.endsWith(".scala")) {
            srcDirs.find(path.startsWith).map(dir ⇒ path.drop(dir.length + 1)) foreach { filePath ⇒
              decl.addAttachments(SourceFile(File(classDir.owner, filePath)), JvmClass(sym.javaClassName))
            }
          }
          else {
            classDeps.get(path) match {
              case Some(file) ⇒
                decl.addAttachments(SourceFile(file.owner), JvmClass(sym.javaClassName))
              case None ⇒
                // TODO JVM classes are not added to dependency list and it is not
                // an artifact. Let's find a better way to handle them
                decl.addAttachments(SourceFile(Artifact(Project("java"), "openjdk", "java", "jdk8")), JvmClass(sym.javaClassName))
            }
          }
        }
        else if (sym.toType == definitions.AnyRefTpe) {
          val file = classDeps.find(_._1.contains("scala-library")).map(_._2).getOrElse {
            throw new IllegalStateException("No entry for scala-library found in the dependency list.")
          }
          decl.addAttachments(SourceFile(file.owner), JvmClass(sym.javaClassName))
        }
      }
      val addRefAttachment = (file: AbstractFile, ref: Ref) ⇒ {
        val path = file.canonicalPath
        if (path.endsWith(".scala")) {
          srcDirs.find(path.startsWith).map(dir ⇒ path.drop(dir.length + 1)) foreach { filePath ⇒
            ref.addAttachments(SourceFile(File(classDir.owner, filePath)))
          }
        }
      }

      println("Amora compiler plugin writes databases:")
      currentRun.units foreach { u ⇒
        val file = u.source.file.file
        val fileName = file.getAbsolutePath.replace('/', '%')
        val res =
            new ScalacConverter[global.type](global, addDeclAttachment, addRefAttachment)
            .convert(u.body)
            .map(Schema.mkTurtleUpdate)

        val data = res match {
          case Success(res) ⇒
            res
          case Failure(f) ⇒
            val sw = new StringWriter
            val pw = new PrintWriter(sw)
            f.printStackTrace(pw)
            sw.toString()
        }

        val filePath =
          if (res.isSuccess)
            s"$outputDir/$fileName.amoradb"
          else
            s"$outputDir/$fileName.error"

        Files.write(Paths.get(filePath), Seq(data).asJava, Charset.forName("UTF-8"))
        if (res.isSuccess)
          println(s"- success:     $filePath")
        else
          println(s"- with errors: $filePath")
      }
    }

    override def name = "AmoraPhase"
  }

  override val phaseName = "AmoraComponent"

  override val runsAfter = List("typer")
}
