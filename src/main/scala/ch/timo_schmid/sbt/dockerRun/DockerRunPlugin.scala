package ch.timo_schmid.sbt.dockerRun

import play.api.libs.json._
import sbt._
import sbt.Keys._
import scala.language.implicitConversions
import scala.sys.process.Process

object DockerRunPlugin extends AutoPlugin {

  object autoImport {

    implicit def toPortOps(port: Int): PortOps =
      new PortOps(port)

    implicit def toPortMapping(port: Int): PortMapping =
      PortMapping(port, port)

    lazy val dockerContainers: SettingKey[Seq[DockerContainer]] =
      settingKey("Docker containers to run before the app starts")

    lazy val dockerRun: TaskKey[Seq[DockerContainer]] =
      taskKey("Runs the docker containers")

  }

  import autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    run := {
      dockerRun.value
      (run in Compile).evaluated
    },
    dockerRun := { runDocker(dockerContainers.value) },
    dockerContainers := Nil
  )

  private def runDocker(dockerContainers: Seq[DockerContainer]): Seq[DockerContainer] =
    dockerContainers.map(runDockerContainer)

  private def runDockerContainer(container: DockerContainer): DockerContainer = {
    if(!dockerContainerIsRunning(container)) {
      startDockerContainer(container)
    }
    container
  }

  // TODO (timo) Make this a setting
  lazy val dockerBin: String = Process("which docker").!!.split("\n").head

  private def dockerContainerIsRunning(container: DockerContainer): Boolean = {
    val dockerPsCmd = s"""$dockerBin ps -a""" // not working: --format "{{.ID}} {{.Names}}"
    val dockerPs: String = Process(dockerPsCmd).!!
    val containerLines = dockerPs.split("\n").tail
    containerLines.exists { line =>
      val infos = line.split(" ")
      val containerId = infos.head
      val containerName = infos.last
      if (container.id == containerName)
        if (isContainerUpToDate(containerId, container)) {
          println(s"Docker container $containerName is up-to-date.")
          true
        } else {
          removeDockerContainer(containerId)
          false
        }
      else
        false
    }
  }

  private implicit def toJsValueOps(jsValue: JsValue): JsValueOps =
    new JsValueOps(jsValue)

  private def isContainerUpToDate(containerId: String, container: DockerContainer): Boolean = {
    val json = Process(s"""$dockerBin inspect $containerId""").!!
    val jsonContainer = Json.parse(json).asArray.head
    isUp(jsonContainer) &&
      compareImage(jsonContainer, container.name, container.version) &&
      comparePorts(jsonContainer, container.ports) &&
      compareEnvVars(jsonContainer, container.environment)
  }

  private def isUp(value: JsValue): Boolean =
    value
      .field("State")
      .field("Status")
      .asString == "running"

  private def compareImage(jsObject: JsValue, image: String, version: String): Boolean =
    jsObject
      .field("Config")
      .field("Image")
      .asString == s"$image:$version"

  private def comparePorts(jsObject: JsValue, ports: Seq[PortMapping]): Boolean =
    ports.forall { portMapping =>
      jsObject
        .field("HostConfig")
        .field("PortBindings")
        .field(s"${portMapping.local}/tcp")
        .asArray.exists { portBinding =>
        portBinding
          .field("HostPort")
          .asString == s"${portMapping.container}"
      }
    }

  private def compareEnvVars(jsObject: JsValue, environment: Map[String, String]): Boolean =
    environment.toSeq.forall { case (k, v) =>
      jsObject
        .field("Config")
        .field("Env")
        .asArray
        .map(_.asString)
        .contains(s"$k=$v")
    }

  private def removeDockerContainer(containerId: String): Unit = {
    Process(s"""$dockerBin rm -f $containerId""").!!
    println(s"Removed: $containerId")
  }

  private def startDockerContainer(container: DockerContainer): Unit = {
    println(s"Starting ${container.name}:${container.version} as ${container.id}")
    val containerPorts = container.ports.map(port => s"-p ${port.local}:${port.container}").mkString(" ")
    val containerEnv = container.environment.toSeq.map{ case (k: String, v: String) => s"-e $k=$v" }.mkString(" ")
    val dockerRunCommand = s"""$dockerBin run --name ${container.id} -d $containerPorts $containerEnv ${container.name}:${container.version}"""
    val containerId: String = Process(dockerRunCommand).!!
    println(s"Started: $containerId")
  }

}