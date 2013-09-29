/*
 * Copyright 2011 Steffen Fritzsche.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbtantlr

import sbt._
import Process._
import Keys._
import org.antlr.Tool
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import Project.Initialize

object SbtAntlrPlugin extends Plugin {

  sealed trait TargetLanguage { def id: String; def suffix: String }
  case object JAVA extends TargetLanguage { val id = "java"; val suffix = ".java" }
  case object SCALA extends TargetLanguage { val id = "scala"; val suffix = ".scala" }

  final case class AntlrToolConfiguration(
    report: Boolean = false,
    printGrammar: Boolean = false,
    debug: Boolean = false,
    profile: Boolean = false,
    nfa: Boolean = false,
    dfa: Boolean = false,
    trace: Boolean = false,
    messageFormat: String = "antlr",
    verbose: Boolean = false)

  final case class AntlrGeneratorConfiguration(
    maxSwitchCaseLabels: Int = 300,
    minSwitchAlts: Int = 3)

  final case class PluginConfiguration(
    grammarSuffix: String = ".g",
    targetLanguage: TargetLanguage = JAVA)

  val Antlr = config("antlr")
  val generate = TaskKey[Seq[File]]("generate")
  val copyTokens = TaskKey[Seq[File]]("copy-tokens")
  val antlrDependency = SettingKey[ModuleID]("antlr-dependency")
  val antlrToolConfiguration = SettingKey[AntlrToolConfiguration]("antlr-tool-configuration")
  val antlrGeneratorConfiguration = SettingKey[AntlrGeneratorConfiguration]("antlr-generator-configuration")
  val antlrPluginConfiguration = SettingKey[PluginConfiguration]("antlr-plugin-configuration")
  val antlrTokensResource = SettingKey[File]("antlr-tokens-resource-directory")

  lazy val antlrSettings: Seq[Project.Setting[_]] = inConfig(Antlr)(Seq(
    antlrToolConfiguration := AntlrToolConfiguration(),
    antlrGeneratorConfiguration := AntlrGeneratorConfiguration(),
    antlrPluginConfiguration := PluginConfiguration(),
    antlrDependency := "org.antlr" % "antlr" % "3.0.1",

    sourceDirectory <<= (sourceDirectory in Compile) { _ / "antlr3" },
    javaSource <<= sourceManaged in Compile,
    antlrTokensResource <<= sourceManaged in Compile,
    
    managedClasspath <<= (classpathTypes in Antlr, update) map { (ct, report) =>
      Classpaths.managedJars(Antlr, ct, report)
    },

    generate <<= sourceGeneratorTask,
    copyTokens <<= copyTokensTask

  )) ++ Seq(
    unmanagedSourceDirectories in Compile <+= (sourceDirectory in Antlr),
    sourceGenerators in Compile <+= (generate in Antlr),
    resourceGenerators in Compile <+= (copyTokens in Antlr),
    cleanFiles <+= (javaSource in Antlr),
    libraryDependencies <+= (antlrDependency in Antlr),
    ivyConfigurations += Antlr
  )

  private def copyTokensTask = (streams, antlrTokensResource in Antlr) map {
    (out, srcDir) =>
      val tokens = ((srcDir ** ("*.tokens")).get.toSet).toSeq
      tokens foreach { t =>
        out.log.debug("ANTLR: Copying token file %s" format (t))
      }
      tokens
  }

  private def sourceGeneratorTask = (streams, sourceDirectory in Antlr, javaSource in Antlr,
    antlrToolConfiguration in Antlr, antlrGeneratorConfiguration in Antlr, antlrPluginConfiguration in Antlr, cacheDirectory) map {
      (out, srcDir, targetDir, tool, gen, options, cache) =>
        val cachedCompile = FileFunction.cached(cache / "antlr3", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
          generateWithAntlr(srcDir, targetDir, tool, gen, options, out.log)
        }
        cachedCompile((srcDir ** ("*" + options.grammarSuffix)).get.toSet).toSeq
    }

  private def generateWithAntlr(srcDir: File, target: File, tool: AntlrToolConfiguration,
                                gen: AntlrGeneratorConfiguration, options: PluginConfiguration, log: Logger) = {
    printAntlrOptions(log, tool, gen)

    // prepare target
    target.mkdirs()

    // configure antlr tool
    val antlr = new Tool
    log.info("ANTLR: Using ANTLR version %s to generate source files.".format("3.0.1"))
    var processArgsArr = new ArrayBuffer[String]
    processArgsArr.append("-message-format")
    processArgsArr.append(gen.minSwitchAlts.toString)

    // process grammars
    val grammars = (srcDir ** ("*" + options.grammarSuffix)).get
    log.info("ANTLR: Generating source files for %d grammars.".format(grammars.size))

    // add each grammar file to the list of grammar files to process
    grammars foreach { g =>
      val relPath = g relativeTo srcDir
      log.info("ANTLR: Grammar file '%s' detected, full path: %s.".format(relPath.get.getPath, g.getAbsolutePath))
      processArgsArr.append(g.getAbsolutePath)
    }

    antlr.setOutputDirectory(target.getPath)

    antlr.processArgs(processArgsArr.toArray)

    // process all grammars
    antlr.process

    (target ** ("*" + options.targetLanguage.suffix)).get.toSet
  }

  private def printAntlrOptions(log: Logger, options: AntlrToolConfiguration, gen: AntlrGeneratorConfiguration) {
    log.debug("ANTLR: report              : " + options.report)
    log.debug("ANTLR: printGrammar        : " + options.printGrammar)
    log.debug("ANTLR: debug               : " + options.debug)
    log.debug("ANTLR: profile             : " + options.profile)
    log.debug("ANTLR: nfa                 : " + options.nfa)
    log.debug("ANTLR: dfa                 : " + options.dfa)
    log.debug("ANTLR: trace               : " + options.trace)
    log.debug("ANTLR: messageFormat       : " + options.messageFormat)
    log.debug("ANTLR: maxSwitchCaseLabels : " + gen.maxSwitchCaseLabels)
    log.debug("ANTLR: minSwitchAlts       : " + gen.minSwitchAlts)
    log.debug("ANTLR: verbose             : " + options.verbose)
  }

}
