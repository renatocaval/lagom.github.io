import java.io.Closeable

lazy val `lagom-docs` = (project in file("."))
  .enablePlugins(SbtTwirl, SbtWeb)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "org.webjars" % "normalize.css" % "3.0.2",
  "org.webjars" % "foundation" % "6.2.0",
  "org.webjars" % "jquery" % "2.2.1",
  "org.webjars" % "prettify" % "4-Mar-2013"
)

val httpServer = AttributeKey[Closeable]("http-server")

val stopCommand = Command.command("stop") { state =>
  state.attributes.get(httpServer) match {
    case Some(server) =>
      server.close()
      state.remove(httpServer)
    case None => state
  }
}

val runCommand = Command.make("run") { state =>
  import complete.Parsers._
  import complete.Parser

  (Space ~> NatBasic).?.map { maybePort =>
    () =>
      val port = maybePort.getOrElse(8000)

      val log = state.log
      val extracted = Project.extract(state)
      val (webStageState, stageDir) = extracted.runTask(WebKeys.stage, state)

      log.info(s"Running HTTP server on port $port...")
      val httpServerProcess = Process(s"python -m SimpleHTTPServer $port", stageDir).run(new ProcessLogger {
        override def info(s: => String): Unit = log.info(s)
        override def error(s: => String): Unit = log.info(s)
        override def buffer[T](f: => T): T = f
      })

      val stateWithStop = "stop" :: webStageState.put(httpServer, new Closeable {
        override def close(): Unit = {
          log.info("Shutting down HTTP server")
          httpServerProcess.destroy()
        }
      }).addExitHook(() => httpServerProcess.destroy())

      Parser.parse("~web-stage", stateWithStop.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg) => throw sys.error(s"Invalid command:\n$msg")
      }
  }
}

commands ++= Seq(runCommand, stopCommand)

val generateHtml = taskKey[Seq[File]]("Generate the site HTML")

target in generateHtml := WebKeys.webTarget.value / "generated-html"
generateHtml <<= Def.taskDyn {
  val outputDir = (target in generateHtml).value
  Def.task {
    (runMain in Compile).toTask(Seq(
      "com.lightbend.lagom.docs.DocumentationGenerator",
      outputDir
    ).mkString(" ", " ", "")).value
    outputDir.***.filter(_.isFile).get
  }
}

WebKeys.pipeline ++= {
  generateHtml.value pair relativeTo((target in generateHtml).value)
}
