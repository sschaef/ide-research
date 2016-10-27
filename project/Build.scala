import sbt._
import sbt.Keys._
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import com.typesafe.sbt.jse.SbtJsEngine
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.Import._
import com.typesafe.sbteclipse.core.EclipsePlugin._
import spray.revolver.RevolverPlugin._
import sbtassembly.AssemblyKeys._

object Build extends sbt.Build {

  val genElectronMain = TaskKey[Unit]("gen-electron-main", "Generates Electron application's main file.")
  val genFirefoxPlugin = TaskKey[Unit]("gen-firefox-plugin", "Generates the Firefox plugin.")
  val genBundle = TaskKey[File]("gen-bundle", "Generates a bundle that contains all NPM dependencies to be used in the browser.")

  lazy val commonSettings = Seq(
    scalaVersion := "2.11.8",
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-language:_",
      "-unchecked",
      "-Xlint",
      "-Xfuture",
      //"-Xfatal-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused-import",
      "-Ywarn-unused"
    ),

    EclipseKeys.withSource := true,

    incOptions := incOptions.value.withNameHashing(true),
    updateOptions := updateOptions.value.withCachedResolution(true),

    cancelable := true
  )

  lazy val protocol = crossProject crossType CrossType.Pure in file("protocol") settings (
    name := "protocol",
    // We need to explicitly set this to the default Eclipse output folder, otherwise another one is created
    EclipseKeys.eclipseOutput := Some("bin/"),

    libraryDependencies ++= deps.protocol.value
  ) settings (commonSettings: _*)

  lazy val protocolJvm = protocol.jvm

  lazy val protocolJs = protocol.js

  lazy val firefoxPlugin = project in file("firefox-plugin") enablePlugins(ScalaJSPlugin) settings commonSettings ++ Seq(
    name := "firefox-plugin",

    persistLauncher in Compile := true,
    persistLauncher in Test := false,

    libraryDependencies ++= deps.firefoxPlugin.value,

    genFirefoxPlugin := {
      import java.nio.charset.Charset
      // TODO we rely on the files written on disk but it would be better to be able to get the actual content directly from the tasks
      val launchCode = IO.read((packageScalaJSLauncher in Compile).value.data, Charset.forName("UTF-8"))
      val sjsRuntime = IO.read((fastOptJS in Compile).value.data, Charset.forName("UTF-8"))
      val jsDeps = IO.read((packageJSDependencies in Compile).value, Charset.forName("UTF-8"))
      val pluginJsName = "plugin.js"
      val mainJsName = "main.js"
      val jsDepsName = "jsdeps.js"

      val pkgJson = s"""
      {
        "title": "amora",
        "name": "amora-plugin",
        "version": "0.0.1",
        "description": "amora-plugin",
        "main": "$mainJsName",
        "author": "Simon Schäfer",
        "engines": {
          "firefox": ">=38.0a1"
        },
        "license": "MIT",
        "repository": {
          "type": "git",
          "url": "https://github.com/sschaef/amora"
        },
        "keywords": [
          "jetpack"
        ]
      }
      """

      // holds the JS code in the end, which is generated by Scala.js
      val pluginJs = s"""
        $sjsRuntime
        $launchCode
      """

      // entry point of the Firefox plugin. It loads all other JS files.
      val mainJs = s"""
        const data = require("sdk/self").data
        const pageMod = require('sdk/page-mod')

        pageMod.PageMod({
          include: ['https://github.com/*'],
          contentScriptFile: ['./$jsDepsName', './$pluginJsName']
        })
      """

      val dest = (classDirectory in Compile).value / ".."
      IO.write(dest / "package.json", pkgJson)
      IO.write(dest / mainJsName, mainJs)
      IO.write(dest / "data" / jsDepsName, jsDeps)
      IO.write(dest / "data" / pluginJsName, pluginJs)
    }
  )

  lazy val electron = project in file("electron") enablePlugins(ScalaJSPlugin) settings commonSettings ++ Seq(
    name := "electron",

    persistLauncher in Compile := true,
    persistLauncher in Test := false,

    /*
     * We need to generate the Electron's main files "package.json" and "main.js".
     */
    genElectronMain := {
      import java.nio.charset.Charset
      // TODO we rely on the files written on disk but it would be better to be able to get the actual content directly from the tasks
      val launchCode = IO.read((packageScalaJSLauncher in Compile).value.data, Charset.forName("UTF-8"))
      val jsCode = IO.read((fullOptJS in Compile).value.data, Charset.forName("UTF-8"))

      val pkgJson = """
      {
        "name": "electron-starter",
        "version": "0.1",
        "main": "main.js",
        "repository": {
          "type": "git",
          "url": "https://github.com/sschaef/amora"
        },
        "license": "MIT"
      }
      """.stripMargin

      // hack to get require and __dirname to work in the main process
      // see https://gitter.im/scala-js/scala-js/archives/2015/04/25
      val mainJs = s"""
        var addGlobalProps = function(obj) {
          obj.require = require;
          obj.__dirname = __dirname;
        }

        if((typeof __ScalaJSEnv === "object") && typeof __ScalaJSEnv.global === "object") {
          addGlobalProps(__ScalaJSEnv.global);
        } else if(typeof  global === "object") {
          addGlobalProps(global);
        } else if(typeof __ScalaJSEnv === "object") {
          __ScalaJSEnv.global = {};
          addGlobalProps(__ScalaJSEnv.global);
        } else {
          var __ScalaJSEnv = { global: {} };
          addGlobalProps(__ScalaJSEnv.global)
        }
        $jsCode
        $launchCode
      """

      val dest = (classDirectory in Compile).value / ".."
      IO.write(dest / "package.json", pkgJson)
      IO.write(dest / "main.js", mainJs)
    }
  )

  /**
   * The electron based UI for neovim.
   */
  lazy val ui = project in file("ui") enablePlugins(ScalaJSPlugin, SbtWeb) settings commonSettings ++ Seq(
    name := "ui",
    scalaJSStage in Global := FastOptStage,

    // for codemirror-facade
    resolvers += sbt.Resolver.bintrayRepo("denigma", "denigma-releases"),

    libraryDependencies ++= deps.sjs.value,

    skip in packageJSDependencies := false,
    jsDependencies ++= deps.webjars.value,

    persistLauncher in Compile := true,
    persistLauncher in Test := false
  ) dependsOn (protocolJs)

  /**
   * This plugin makes it possible to include NPM dependencies in our sbt build. It provides the `genBundle` task,
   * which downloads the dependencies and creates a file called "bundle.js", which contains all of the dependencies.
   * The `getBundle` task returns the location of the bundled JS file,
   */
  lazy val bundle = project in file("bundle") enablePlugins(SbtWeb, SbtJsEngine) settings commonSettings ++ Seq(
    name := "bundle",

    genBundle := {
      import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys._
      import com.typesafe.sbt.jse.SbtJsTask._
      import com.typesafe.sbt.jse.SbtJsEngine.autoImport.JsEngineKeys._
      import scala.concurrent.duration._

      (npmNodeModules in Assets).value
      val log = streams.value.log
      val in = (baseDirectory.value / "dependencies.js").getAbsolutePath
      val out = (baseDirectory.value / "target" / "bundle.js").getAbsolutePath
      val modules =  (baseDirectory.value / "node_modules").getAbsolutePath
      log.info(s"Bundling NPM dependencies into file '$out'.")
      executeJs(
        state.value,
        engineType.value,
        None,
        Seq(modules),
        baseDirectory.value / "browserify.js",
        Seq(in, out),
        30.seconds)
      file(out)
    }
  )

  /**
   * The web interface of the backend.
   */
  lazy val webUi = project in file("web-ui") enablePlugins(ScalaJSPlugin, SbtWeb) settings commonSettings ++ Seq(
    name := "web-ui",
    scalaJSStage in Global := FastOptStage,

    libraryDependencies ++= deps.webUi.value,

    // add bundle JS files to resources
    resourceGenerators in Compile += (genBundle in bundle).map(r => Seq(r)).taskValue,
    // mae bundled JS file availabl to Scala.js
    jsDependencies += ProvidedJS / "bundle.js",

    skip in packageJSDependencies := false,

    persistLauncher in Compile := true,
    persistLauncher in Test := false
  ) dependsOn (protocolJs)

  lazy val nvim = project in file("nvim") settings commonSettings ++ Seq(
    name := "nvim",

    libraryDependencies ++= deps.nvim.value
  )

  lazy val backend = project in file("backend") settings commonSettings ++ Revolver.settings ++ Seq(
    name := "backend",

    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= deps.backend.value,
    javaOptions ++= Seq(
      "-agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n"
    ),

    // We need to explicitly set this to the default Eclipse output folder, otherwise another one is created
    EclipseKeys.eclipseOutput := Some("bin/"),

    // see https://github.com/sbt/junit-interface for an explanation of the arguments
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-q", "-v", "-s"),
    // we don't want to run the tests in sbt because they consume lots of resources
    fork in Test := true,

    // add ui JS files to resources: *fastopt.js, *fullopt.js, *launcher.js, *jsdeps.js
    resourceGenerators in Compile += (fastOptJS in Compile in ui).map(r => Seq(r.data)).taskValue,
    //resourceGenerators in Compile += ((fullOptJS in Compile in ui).map(r => Seq(r.data)).taskValue,
    resourceGenerators in Compile += (packageScalaJSLauncher in Compile in ui).map(r => Seq(r.data)).taskValue,
    resourceGenerators in Compile += (packageJSDependencies in Compile in ui).map(Seq(_)).taskValue,
    // add folder of webjars to resources
    unmanagedResourceDirectories in Compile += (WebKeys.webTarget in Compile in ui).value / "web-modules" / "main" / "webjars" / "lib",

    // add webUi JS files to resources: *fastopt.js, *fullopt.js, *launcher.js, *jsdeps.js
    resourceGenerators in Compile += (fastOptJS in Compile in webUi).map(r => Seq(r.data)).taskValue,
    resourceGenerators in Compile += (packageScalaJSLauncher in Compile in webUi).map(r => Seq(r.data)).taskValue,
    resourceGenerators in Compile += (packageJSDependencies in Compile in webUi).map(Seq(_)).taskValue,

    // depend on the genElectronMain task but don't add its generated resources since we don't need to route them at runtime
    resourceGenerators in Compile += (genElectronMain in Compile in electron).map(_ => Seq()).taskValue,

    // add schema definitions to resource directories
    unmanagedResourceDirectories in Compile += (baseDirectory in ThisBuild).value / "schema",

    // use resoure generators also in Test config
    resourceGenerators in Test := (resourceGenerators in Compile).value,
    unmanagedResourceDirectories in Test := (unmanagedResourceDirectories in Compile).value,

    // We want to have a slightly different configuration when the tests run in the build tool
    // than when they run in the IDE. `test-application.conf` is loaded during the tests and
    // therefore gives us the possibility to override the default configuration.
    resourceGenerators in Test += (classDirectory in Test).map { dir =>
      IO.write(dir / "test-application.conf", """
        akka {
          # Do not log anything - we are only interested in the test results
          loglevel = OFF
        }
        app {
          # We do not need additional logging during the build
          log-additional-debug-data-in-tests = false
        }
      """)

      Nil
    }.taskValue,

    // once the server is started, we also want to restart it on changes in the protocol project
    watchSources ++= (watchSources in protocolJvm).value
  ) dependsOn (protocolJvm, nvim, javacConverter)

  lazy val scalacPlugin = project in file("scalac-plugin") settings commonSettings ++ Seq(
    name := "scalac-plugin",

    scalacOptions in console in Compile += s"-Xplugin:${(packageBin in Compile).value}",
    //scalacOptions in Test += s"-Xplugin:${(packageBin in Compile).value}",

    // add plugin timestamp to compiler options to trigger recompile of
    // code after editing the plugin. (Otherwise a 'clean' is needed.)
    scalacOptions in Test ++= ((Keys.`package` in Compile) map { (jar: File) =>
      System.setProperty("sbt.paths.plugin.jar", jar.getAbsolutePath)
      Seq("-Xplugin:" + jar.getAbsolutePath, "-Jdummy=" + jar.lastModified)
    }).value,

    // adds project depedencies to JAR file of compiler plugin. If we don't do this,
    // the dependencies are not available when the compiler plugin is executed.
    // copied from https://github.com/matanster/extractor/blob/95d16d80d534cb9b5113b5e6824021a9382168a9/build.sbt#L55-L66
    isSnapshot := true,
    test in assembly := {},
    assemblyJarName in assembly := name.value + "_" + scalaVersion.value + "-" + version.value + "-assembly.jar",
    assemblyOption in assembly ~= { _.copy(includeScala = false) },
    packagedArtifact in Compile in packageBin := {
      val temp = (packagedArtifact in Compile in packageBin).value
      val (art, slimJar) = temp
      val fatJar = new File(crossTarget.value + "/" + (assemblyJarName in assembly).value)
      IO.copy(List(fatJar -> slimJar), overwrite = true)
      println("Using sbt-assembly to package library dependencies into a fat jar for publication")
      (art, slimJar)
    },
    test in Test := (test in Test).dependsOn(packagedArtifacts).value,

    // show stack traces up to first sbt stack frame
    traceLevel in Test := 0
  ) dependsOn (scalacConverter)

  /**
   * Contains common definitions that are needed by all converters and by the indexer.
   */
  lazy val converterProtocol = project in file("converter/protocol") settings commonSettings ++ Seq(
    name := "converter-protocol"
  )

  lazy val scalacConverter = project in file("converter/scalac") settings commonSettings ++ Seq(
    name := "scalac-converter",

    libraryDependencies ++= deps.scalacConverter.value
  ) dependsOn (converterProtocol)

  lazy val javacConverter = project in file("converter/javac") settings commonSettings ++ Seq(
    name := "javac-converter",

    libraryDependencies ++= deps.javacConverter.value
  ) dependsOn (converterProtocol)

  lazy val dotcConverter = project in file("converter/dotc") settings commonSettings ++ Seq(
    name := "dotc-converter",

    // dotc ships with a fork of scalac, we therefore don't want to use the compiler that is bundled with Eclipse
    EclipseKeys.withBundledScalaContainers := false,

    libraryDependencies ++= deps.dotcConverter.value
  ) dependsOn (converterProtocol)

  lazy val scalaCompilerService = project in file("services/scala-compiler") settings commonSettings ++ Seq(
    name := "scala-compiler-service",

    libraryDependencies ++= deps.scalaCompilerService.value
  ) dependsOn (backend % "compile;test->test")

  lazy val scalacService = project in file("services/scalac") settings commonSettings ++ Seq(
    name := "scalac-service"
  ) dependsOn (scalacConverter, scalaCompilerService % "compile;test->test")

  lazy val dotcService = project in file("services/dotc") settings commonSettings ++ Seq(
    name := "dotc-service",

    // dotc ships with a fork of scalac, we therefore don't want to use the compiler that is bundled with Eclipse
    EclipseKeys.withBundledScalaContainers := false
  ) dependsOn (dotcConverter, scalaCompilerService % "compile;test->test")

  object versions {
    // https://github.com/lihaoyi/scalatags
    val scalatags       = "0.5.2"
    // https://github.com/lloydmeta/enumeratum
    val enumeratum      = "1.3.7"
    val akka            = "2.4.11"
    val scalameta       = "0.1.0-SNAPSHOT"
    // https://github.com/typesafehub/scala-logging
    val scalaLogging    = "3.1.0"
    // https://github.com/ochrons/boopickle
    val boopickle       = "1.1.0"
    // https://github.com/msgpack4z/msgpack4z-core
    val msgpack4zCore   = "0.2.0"
    // https://github.com/msgpack4z/msgpack4z-java07
    val msgpack4zJava07 = "0.2.0"
    // https://github.com/antonkulaga/codemirror-facade
    val codemirror      = "5.5-0.5"
    val jquery          = "0.8.0"
    val junitInterface  = "0.11"
  }

  object deps {
    lazy val protocol = Def.setting(Seq(
      "me.chrons"                      %%% "boopickle"                         % versions.boopickle
    ))

    lazy val backend = Def.setting(Seq(
      "com.typesafe.akka"              %%  "akka-http-core"                    % versions.akka,
      "com.typesafe.akka"              %%  "akka-http-experimental"            % versions.akka,
      "com.typesafe.akka"              %%  "akka-http-spray-json-experimental" % versions.akka,
      "com.typesafe.akka"              %%  "akka-stream"                       % versions.akka,
      "com.typesafe.akka"              %%  "akka-slf4j"                        % versions.akka,
      "org.scala-lang"                 %   "scala-compiler"                    % scalaVersion.value,
      "com.beachape"                   %%  "enumeratum"                        % versions.enumeratum,
      "com.lihaoyi"                    %%% "scalatags"                         % versions.scalatags,
      "org.apache.jena"                %   "apache-jena-libs"                  % "3.0.1",
      "io.get-coursier"                %%  "coursier"                          % "1.0.0-M11",
      "io.get-coursier"                %%  "coursier-cache"                    % "1.0.0-M11",
      // https://github.com/scalaj/scalaj-http
      "org.scalaj"                     %%  "scalaj-http"                       % "2.3.0",
      "com.novocode"                   %   "junit-interface"                   % versions.junitInterface   % "test",
      "com.typesafe.akka"              %%  "akka-http-testkit"                 % versions.akka             % "test"
    ))

    lazy val nvim = Def.setting(Seq(
      "com.github.xuwei-k"             %%  "msgpack4z-core"                    % versions.msgpack4zCore,
      "com.github.xuwei-k"             %   "msgpack4z-java07"                  % versions.msgpack4zJava07,
      "com.beachape"                   %%  "enumeratum"                        % versions.enumeratum,
      "org.scala-lang"                 %   "scala-compiler"                    % scalaVersion.value,
      "com.typesafe.scala-logging"     %%  "scala-logging"                     % versions.scalaLogging
    ))

    lazy val sjs = Def.setting(Seq(
      "be.doeraene"                    %%% "scalajs-jquery"                    % versions.jquery,
      "org.denigma"                    %%% "codemirror-facade"                 % versions.codemirror,
      "com.lihaoyi"                    %%% "scalatags"                         % versions.scalatags
    ))

    lazy val webUi = Def.setting(Seq(
      "be.doeraene"                    %%% "scalajs-jquery"                    % versions.jquery,
      "com.lihaoyi"                    %%% "scalatags"                         % versions.scalatags
    ))

    lazy val webjars = Def.setting(Seq(
      "org.webjars"                    %   "codemirror"                        % "5.5"                     / "codemirror.js",
      // https://github.com/chjj/marked
      "org.webjars.bower"              %   "marked"                            % "0.3.3"                   / "marked.js",
      "org.webjars"                    %   "d3js"                              % "3.5.5-1"                 / "d3.js",
      // https://github.com/fgnass/spin.js
      "org.webjars.bower"              %   "spin.js"                           % "2.3.1"                   / "spin.js"
    ))

    lazy val firefoxPlugin = Def.setting(Seq(
      "be.doeraene"                    %%% "scalajs-jquery"                    % versions.jquery,
      "com.lihaoyi"                    %%% "scalatags"                         % versions.scalatags
    ))

    lazy val scalacConverter = Def.setting(Seq(
      "org.scala-lang"                 %   "scala-compiler"                    % scalaVersion.value,
      "org.scala-refactoring"          %%  "org.scala-refactoring.library"     % "0.10.0"                  cross CrossVersion.full
    ))

    lazy val javacConverter = Def.setting(Seq(
      "org.ow2.asm"                    %   "asm-commons"                       % "5.0.4",
      "org.ow2.asm"                    %   "asm-util"                          % "5.0.4"
    ))

    lazy val dotcConverter = Def.setting(Seq(
      "ch.epfl.lamp"                   %%  "dotty"                             % "0.1-SNAPSHOT",
      "me.d-d"                         %   "scala-compiler"                    % "2.11.5-20160322-171045-e19b30b3cd"
    ))

    lazy val scalaCompilerService = Def.setting(Seq(
      "com.novocode"                   %   "junit-interface"                   % versions.junitInterface   % "test"
    ))
  }
}
