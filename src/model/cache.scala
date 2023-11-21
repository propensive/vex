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

import anticipation.*, fileApi.galileiApi
import aviation.*
import cellulose.*
import eucalyptus.*
import galilei.*, filesystemOptions.{dereferenceSymlinks, createNonexistent, createNonexistentParents}
import hieroglyph.*, charDecoders.utf8, badEncodingHandlers.collect
import nettlesome.*
import nonagenarian.*
import parasite.*
import perforate.*
import guillotine.*
import fulminate.*
import punctuation.*
import gossamer.*
import rudiments.*
import serpentine.*, hierarchies.unixOrWindows
import spectacular.*
import turbulence.*

import scala.collection.mutable as scm

object Cache:
  private val ecosystems: scm.HashMap[Ecosystem, Async[Vault]] = scm.HashMap()
  private val snapshots: scm.HashMap[Snapshot, Async[Directory]] = scm.HashMap()
  private val workspaces: scm.HashMap[Path, Async[Workspace]] = scm.HashMap()

  def gitProgress(stream: LazyList[Progress]): LazyList[TaskEvent] = stream.collect:
    case Progress.Receiving(percent)         => TaskEvent.Progress(t"receiving", percent)
    case Progress.Unpacking(percent)         => TaskEvent.Progress(t"unpacking", percent)
    case Progress.Resolving(percent)         => TaskEvent.Progress(t"resolving", percent)
    case Progress.RemoteCounting(percent)    => TaskEvent.Progress(t"counting", percent)
    case Progress.RemoteCompressing(percent) => TaskEvent.Progress(t"compressing", percent)

  def apply
      (snapshot: Snapshot)
      (using installation: Installation)
      (using Internet, Log, Monitor, FrontEnd, WorkingDirectory, Raises[ExecError], Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NotFoundError], Raises[GitRefError], Raises[NumberError], Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[CodlReadError], Raises[MarkdownError], Raises[PathError], Raises[IoError], Raises[StreamCutError], Raises[GitError], GitCommand)
      : Async[Directory] =
    snapshots.synchronized:
      snapshots.getOrElseUpdate(snapshot, Async:
        val destination = unsafely(installation.snapshots.path / PathName(snapshot.commit.show))
        
        if destination.exists() then destination.as[Directory] else
          log(msg"Cloning ${snapshot.url}")
          val process = Git.cloneCommit(snapshot.url.encode, destination, snapshot.commit)
          follow(msg"Cloning ${snapshot.url}")(gitProgress(process.progress))
          process.complete().workTree.vouch(using Unsafe).tap: _ =>
            log(msg"Finished cloning ${snapshot.url}")
      )
        
  def apply
      (ecosystem: Ecosystem)
      (using installation: Installation)
      (using Internet, Log, Monitor, FrontEnd, WorkingDirectory, Raises[ExecError], Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NotFoundError], Raises[GitRefError], Raises[NumberError], Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[CodlReadError], Raises[MarkdownError], Raises[PathError], Raises[IoError], Raises[StreamCutError], Raises[GitError], GitCommand)
      : Async[Vault] =
    ecosystems.synchronized:
      ecosystems.getOrElseUpdate(ecosystem, Async:
        val destination = unsafely(installation.vault.path / PathName(ecosystem.id.show) / PathName(ecosystem.branch.show))
        
        if !destination.exists() then
          log(msg"Cloning ${ecosystem.url}")
          val process = Git.clone(ecosystem.url.encode, destination, branch = ecosystem.branch)
          follow(msg"Cloning ${ecosystem.url}")(gitProgress(process.progress))
          process.complete().tap: _ =>
            log(msg"Finished cloning ${ecosystem.url}")
        
        Codl.read[Vault]((destination / p"vault.codl").as[File])
      )

  def workspace(path: Path)
      (using installation: Installation)
      (using Internet, Log, Stdio, Monitor, FrontEnd, WorkingDirectory, Raises[ExecError], Raises[UndecodableCharError], Raises[UnencodableCharError], Raises[NotFoundError], Raises[GitRefError], Raises[NumberError], Raises[InvalidRefError], Raises[DateError], Raises[UrlError], Raises[CodlReadError], Raises[MarkdownError], Raises[PathError], Raises[IoError], Raises[StreamCutError], Raises[GitError], GitCommand)
      : Async[Workspace] =
    workspaces.synchronized:
      workspaces.getOrElseUpdate(path, Async(Workspace(path)))
