/*
    Fury, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package fury

import acyclicity.*

import galilei.*, filesystemOptions.{createNonexistent, createNonexistentParents, dereferenceSymlinks}
import anticipation.*, fileApi.galileiApi
import rudiments.*
import parasite.*
import aviation.*, calendars.gregorian
import guillotine.*
import fulminate.*
import ambience.*, environments.jvm, systemProperties.jvm
import gossamer.*
import perforate.*
import eucalyptus.*
import gastronomy.*
import punctuation.*
import turbulence.*
import hieroglyph.*, charDecoders.utf8, badEncodingHandlers.strict
import imperial.*
import serpentine.*, hierarchies.unixOrWindows
import cellulose.*
import spectacular.*
import symbolism.*
import nettlesome.*
import nonagenarian.*

import scala.collection.mutable as scm

object Installation:
  def apply(cache: Directory): Installation throws AppError =
    try
      throwErrors:
        val configPath: Path = Home.Config() / p"fury"
        val config: File = (configPath / p"config.codl").as[File]
        val vault: Directory = (cache / p"vault").as[Directory]
        val snapshots: Directory = (cache / p"repos").as[Directory]
        val lib: Directory = (cache / p"lib").as[Directory]
        val tmp: Directory = (cache / p"tmp").as[Directory]
      
        Installation(config, cache, vault, lib, tmp, snapshots)
    
    catch
      case error: StreamCutError =>
        throw AppError(msg"The stream was cut while reading a file", error)
      
      case error: EnvironmentError => error match
        case EnvironmentError(variable) =>
          throw AppError(msg"The environment variable $variable could not be accessed", error)
      
      case error: SystemPropertyError => error match
        case SystemPropertyError(property) =>
           throw AppError(msg"The JVM system property $property could not be accessed", error)
      
      case error: IoError => error match
        case IoError(path) =>
          throw AppError(msg"An I/O error occurred while trying to access $path", error)
      
      case error: PathError => error match
        case PathError(reason) =>
          throw AppError(msg"The path was not valid because $reason", error)
    
case class Installation
    (config: File, cache: Directory, vault: Directory, lib: Directory, tmp: Directory, snapshots: Directory):
  
  def libJar(hash: Digest[Crc32])(using Raises[IoError], Raises[PathError]): File =
    unsafely(lib / t"${hash.encodeAs[Hex].lower}.jar").as[File]
  
inline def installation(using inline installation: Installation): Installation = installation

object Workspace:
  def apply
      (path: Path)
      (using Stdio, Raises[CodlReadError], Raises[GitRefError], Raises[AggregateError[CodlError]], Raises[StreamCutError], Raises[IoError], Raises[InvalidRefError], Raises[NumberError], Raises[NotFoundError], Raises[UrlError], Raises[PathError], Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[MarkdownError])
      : Workspace =
    val dir: Directory = path.as[Directory]
    val buildFile: File = (dir / p".fury").as[File]

    val buildDoc: CodlDoc = Codl.parse(buildFile)
    val build: Build = Codl.read[Build](buildFile)
    val localPath: Path = dir / p".local"
    val localFile: Maybe[File] = if localPath.exists() then localPath.as[File] else Unset
    val local: Maybe[Local] = localFile.mm(Codl.read[Local](_))

    Workspace(dir, buildDoc, build, local)


object Engine:
  private val builds: scm.HashMap[ModuleRef, Async[Unit]] = scm.HashMap()
  
  def build(moduleRef: ModuleRef)(using universe: Universe)
      (using Monitor, Clock, Log, FrontEnd, Stdio, WorkingDirectory, Internet, Installation, GitCommand, Raises[NotFoundError],
          Raises[UnknownRefError], Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NumberError],
          Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[MarkdownError],
          Raises[CodlReadError], Raises[GitError], Raises[ExecError], Raises[PathError], Raises[IoError], Raises[StreamCutError],
          Raises[GitRefError], Raises[CancelError])
      : Async[Unit] =
    builds.synchronized:
      builds.getOrElseUpdate(moduleRef, Async:
        val workspace = universe(moduleRef.projectId).source match
          case vault: Vault         => Workspace(Cache(vault.index.releases(moduleRef.projectId).repo).await().path)
          case workspace: Workspace => workspace
        
        val project: Project = workspace(moduleRef.projectId)
        val module = project(moduleRef.moduleId)
    
        module.includes.map(Engine.build(_)).foreach(_.await())
        log(msg"Starting to build ${moduleRef}")
        
        val part = (math.random*100).toLong
        val progress = LazyList.range(0, 100).map: pc =>
          Thread.sleep(part)
          TaskEvent.Progress(t"typer", pc/100.0)
        
        follow(msg"Building $moduleRef")(progress #::: LazyList(TaskEvent.Complete()))
        progress.length
      )


case class Workspace(directory: Directory, buildDoc: CodlDoc, build: Build, local: Maybe[Local]):
  val ecosystem = build.ecosystem
  lazy val actions: Map[ActionName, Action] = unsafely(build.actions.indexBy(_.name))
  lazy val projects: Map[ProjectId, Project] = unsafely(build.projects.indexBy(_.id))
  lazy val mounts: Map[WorkPath, Mount] = unsafely(build.mounts.indexBy(_.path))

  def locals
      (ancestors: Set[Path] = Set())
      (using Monitor, Log, FrontEnd, Stdio, WorkingDirectory, Internet, Installation, GitCommand, Raises[NotFoundError],
          Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NumberError],
          Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[MarkdownError],
          Raises[CodlReadError], Raises[GitError], Raises[ExecError], Raises[PathError], Raises[IoError], Raises[StreamCutError],
          Raises[GitRefError], Raises[CancelError])
      : Map[ProjectId, Definition] =
    local.mm: local =>
      local.forks.map: fork =>
        val workspace = Cache.workspace(fork.path).await()
        val projects = workspace.projects
        workspace.locals(ancestors + fork.path)
        
    .or(Nil).foldRight(projects.mapValues(_.definition(this)).to(Map))(_ ++ _)
  
  def universe
      ()
      (using Monitor, Clock, Log, FrontEnd, Stdio, WorkingDirectory, Internet, Installation, GitCommand, Raises[NotFoundError],
          Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NumberError],
          Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[MarkdownError],
          Raises[CodlReadError], Raises[GitError], Raises[ExecError], Raises[PathError], Raises[IoError], Raises[StreamCutError],
          Raises[GitRefError], Raises[CancelError])
      : Universe =
    given Timezone = tz"Etc/UTC"
    val vaultProjects = Cache(ecosystem).await()
    val localProjects = locals()
    
    val projects: Map[ProjectId, Definition] =
      vaultProjects.releases.filter(_.expiry <= today()).map: release =>
        (release.id, release.definition(vaultProjects))
      .to(Map)
    
    Universe(projects -- localProjects.keySet ++ localProjects)

  def apply(projectId: ProjectId): Project = projects(projectId)

  def apply
      (path: WorkPath)
      (using Installation, Internet, Stdio, Monitor, FrontEnd, WorkingDirectory, Log)
      : Directory raises CancelError | GitRefError | GitError | PathError | ExecError | IoError | UndecodableCharError | UnencodableCharError | StreamCutError | NotFoundError | NumberError | InvalidRefError | MarkdownError | CodlReadError | DateError | UrlError =
    mounts.keys.find(_.precedes(path)).match
      case None        => directory.path + path
      case Some(mount) => Cache(mounts(mount).repo).await().path + path
    .as[Directory]

case class Universe(projects: Map[ProjectId, Definition]):
  def apply(id: ProjectId)(using Raises[UnknownRefError]): Definition =
    projects.getOrElse(id, abort(UnknownRefError(id)))

enum Compiler:
  case Java(version: Int)
  case Scala

case class Stage(sources: List[File], dependencies: List[Digest[Sha2[224]]], binaries: List[Digest[Sha2[224]]]):
  def digest: Digest[Sha2[224]] raises StreamCutError | IoError =
    (sources.map(_.read[Bytes]), dependencies, binaries).digest