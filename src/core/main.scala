package irk

import gossamer.*
import rudiments.*
import turbulence.*
import acyclicity.*
import euphemism.*
import eucalyptus.Log
import joviality.*, filesystems.unix
import anticipation.*, integration.jovialityPath
import guillotine.*
import parasitism.*, threading.platform, monitors.global
import kaleidoscope.*
import escapade.*
import gastronomy.*
import oubliette.*
import harlequin.*
import iridescence.*, solarized.*
import serpentine.*
import exoskeleton.*
import imperial.*
import tarantula.*
import profanity.*
import xylophone.*
import telekinesis.*
import escritoire.*
import tetromino.*, allocators.default
import surveillance.*

import timekeeping.long
import encodings.Utf8
import rendering.ansi

import scala.collection.mutable as scm
import scala.util.chaining.scalaUtilChainingOps

import java.nio.BufferOverflowException

object Irk extends Daemon():
  
  def main(using CommandLine, Environment): ExitStatus =
    Sys.scala.concurrent.context.maxExtraThreads() = t"800"
    Sys.scala.concurrent.context.maxThreads() = t"1000"
    Sys.scala.concurrent.context.minThreads() = t"100"


    try supervise(t"session-${sessionCount()}"):
      given Allocator = allocators.default
      cli.args match
        case t"about" :: _        => Irk.about()
        case t"help" :: _         => Irk.help()
        case t"init" :: name :: _ => Irk.init(unsafely(env.pwd.directory(Expect)), name)
        case t"version" :: _      => Irk.showVersion()
        case t"go" :: _           =>
          unsafely:
            Tty.capture:
              Tty.stream[Keypress].take(10).foreach: event =>
                eucalyptus.Log.info(t"Received keypress: ${event}")
          ExitStatus.Ok
        case t"build" :: params   =>
          val target = params.headOption.filter(!_.startsWith(t"-"))
          val watch = params.contains(t"-w") || params.contains(t"--watch")
          val exec = params.contains(t"-b") || params.contains(t"--browser")
          Irk.build(target, false, watch, cli.script, exec)
        case t"stop" :: params    => Irk.stop(cli)
        case params               =>
          val target = params.headOption.filter(!_.startsWith(t"-"))
          val watch = params.contains(t"-w") || params.contains(t"--watch")
          val exec = params.contains(t"-b") || params.contains(t"--browser")
          Irk.build(target, false, watch, cli.script, exec)
  
    catch
      case error: AppError => error match
        case AppError(message, _) =>
          Out.println(message)
          ExitStatus.Fail(1)
      case err: Throwable =>
        try
          Out.println(StackTrace(err).ansi)
          ExitStatus.Fail(1)
        catch case err2: Throwable =>
          System.out.nn.println(err2.toString)
          err2.printStackTrace()
          System.out.nn.println("Caused by:")
          System.out.nn.println(err.toString)
          err.printStackTrace()
          ExitStatus.Fail(2)
  
  def version: Text = Option(getClass.nn.getPackage.nn.getImplementationVersion).fold(t"0")(_.nn.show)
  def javaVersion: Text = safely(Sys.java.version()).otherwise(t"unknown")
  def githubActions(using Environment): Boolean = env(t"GITHUB_ACTIONS") != Unset

  def scalaVersion: Text =
    val props = java.util.Properties()
    props.load(getClass.nn.getClassLoader.nn.getResourceAsStream("compiler.properties").nn)
    props.get("version.number").toString.show
 
  def homeDir(using Environment): Directory[Unix] = unsafely(Home[DiskPath[Unix]]().directory(Expect))
      
  def cacheDir(using Environment): Directory[Unix] =
    try (Home.Cache[DiskPath[Unix]]() / p"irk").directory(Ensure)
    catch case err: IoError => throw AppError(t"The user's cache directory could not be created", err)

  def libDir(using Environment): Directory[Unix] =
    try (cacheDir / p"lib").directory(Ensure)
    catch case err: IoError => throw AppError(t"The user's lib directory could not be created", err)
  
  def libJar(hash: Digest[Crc32])(using Environment): DiskPath[Unix] =
    unsafely(libDir / t"${hash.encode[Hex].lower}.jar")
  
  def tmpDir(using Environment): Directory[Unix] =
    try (cacheDir / p"tmp").directory(Ensure)
    catch case err: IoError => throw AppError(t"The user's tmp directory could not be created", err)
  
  def hashDir(using Environment): Directory[Unix] =
    try (cacheDir / p"hash").directory(Ensure)
    catch case err: IoError => throw AppError(t"The user's hash directory could not be created", err)

  private val prefixes = Set(t"/scala", t"/dotty", t"/compiler.properties", t"/org/scalajs", t"/com",
      t"/incrementalcompiler.version.properties", t"/library.properties", t"/NOTICE")

  private def scriptSize(using Allocator): Int throws StreamCutError | ClasspathRefError =
    unsafely(Classpath() / p"exoskeleton" / p"invoke").resource.read[Bytes]().size

  def irkJar(scriptFile: File[Unix])(using Stdout, Allocator, Environment): File[Unix] throws StreamCutError = synchronized:
    import java.nio.file.*
    val jarPath = unsafely(libDir.path / t"base-$version.jar")

    try if jarPath.exists() then jarPath.file(Expect) else
      val buf = Files.readAllBytes(scriptFile.javaPath).nn
      val output = java.io.BufferedOutputStream(java.io.FileOutputStream(jarPath.javaFile).nn)
      
      try output.write(buf, scriptSize, buf.length - scriptSize)
      catch case err: ClasspathRefError =>
        throw AppError(t"Could not determine the size of the bootloader script")
      
      output.close()
      val fs = FileSystems.newFileSystem(java.net.URI(t"jar:file:${jarPath.fullname}".s), Map("zipinfo-time" -> "false").asJava).nn
      
      Files.walk(fs.getPath("/")).nn.iterator.nn.asScala.to(List).sortBy(-_.toString.length).foreach:
        file =>
          def keep(name: Text): Boolean =
            name == t"" || name == t"/org" || prefixes.exists(name.startsWith(_))
          
          try
            if !keep(file.nn.toString.show) then Files.delete(file.nn)
            else Files.setLastModifiedTime(file.nn, Zip.epoch)
          catch case err: Exception => () //Out.println(t"Got a NPE on ${file.nn.toString.show}")
      
      fs.close()
      
      jarPath.file(Expect)
    catch case err: IoError =>
      throw AppError(t"The Irk binary could not be copied to the user's cache directory")

  def getFile(ref: Text)(using Environment): File[Unix] = unsafely(libJar(ref.digest[Crc32]).file(Expect))

  def fetchFile(ref: Text, funnel: Option[Funnel[Progress.Update]])(using Stdout, Internet, Monitor, Environment)
               : Task[File[Unix]] =
    if ref.startsWith(t"https:") then
      val hash = ref.digest[Crc32]
      if libJar(hash).exists() then Task(t"hash"):
        try libJar(hash).file(Expect) catch case err: IoError =>
          throw AppError(t"Could not access the dependency JAR, $ref", err)
      else Task(t"download"):
        val verb = Verb.Download(ansi"${colors.CornflowerBlue}($ref)")
        funnel.foreach(_.put(Progress.Update.Add(verb, (hash))))
        try
          val file = libJar(hash).file(Create)
          Uri(ref).writeTo(file)
          funnel.foreach(_.put(Progress.Update.Remove(verb, Result.Complete())))
          file
        catch
          case err: StreamCutError =>
            funnel.foreach(_.put(Progress.Update.Remove(verb, Result.Terminal(ansi"A stream error occurred"))))
            throw AppError(t"Could not download the file $ref", err)
          
          case err: IoError =>
            funnel.foreach(_.put(Progress.Update.Remove(verb, Result.Terminal(ansi"An I/O error occurred"))))
            throw AppError(t"The downloaded file could not be written to ${libJar(hash).fullname}", err)
        
    else Task(t"parse"):
      try Unix.parse(ref).file(Expect) catch
        case err: InvalidPathError => throw AppError(t"Could not access the dependency JAR, $ref", err)
        case err: IoError          => throw AppError(t"Could not access the dependency JAR, $ref", err)

  val sessionCount: Counter = Counter(0)

  private lazy val fileHashes: FileCache[Digest[Crc32]] = new FileCache()

  private def initBuild(pwd: Directory[Unix])(using Stdout, Monitor, Allocator, Environment)
                       : Build throws IoError | BuildfileError =
    val path = unsafely(pwd / t"build.irk")
    readBuilds(Build(pwd, Map(), None, Map(), Map()), Set(), path)
  
  def hashFile(file: File[Unix])(using Allocator): Digest[Crc32] throws IoError | AppError =
    try fileHashes(file.path.fullname, file.modified):
      file.read[Bytes]().digest[Crc32]
    catch
      case err: StreamCutError  => throw AppError(t"The stream was cut while hashing a file", err)
      case err: ExcessDataError => throw AppError(t"The file was too big to hash", err)
      case err: Error[?]        => throw AppError(t"An unexpected error occurred", err)
  
  def cloneRepo(path: DiskPath[Unix], url: Text)(using Environment): Unit =
    try sh"git clone -q $url ${path.fullname}".exec[Unit]()
    catch
      case err: ExecError => throw AppError(t"Could not run `git clone` for repository $url")
      case err: EnvError  => throw AppError(t"Could not run `git clone` for repository $url")

  def readBuilds(build: Build, seen: Set[Digest[Crc32]], files: DiskPath[Unix]*)
                (using Stdout, Allocator, Environment)
                : Build throws BuildfileError | IoError =
    try
      files.to(List) match
        case Nil =>
          build
        
        case path :: tail =>
          def digest: Digest[Crc32] = hashFile(path.file())
          if path.exists() && seen.contains(digest) then readBuilds(build, seen, tail*)
          else if path.exists() then
            Out.println(ansi"Reading build file ${palette.File}(${path.relativeTo(build.pwd.path).show})")
            val buildConfig = Json.parse(path.file().read[Text]()).as[BuildConfig]
            buildConfig.gen(path, build, seen + digest, files*)
          else throw AppError(txt"""Build contains an import reference to a nonexistant build""")
    catch
      case err: IoError         => throw AppError(t"There was an I/O error", err)
      case err: ExcessDataError => throw BuildfileError(t"The build file was larger than 1MB")
      case err: StreamCutError  => throw AppError(t"The configuration file could not be read completely", err)
      case err: JsonParseError  => throw BuildfileError(err.message)
      case err: JsonAccessError => throw BuildfileError(err.message)

  def readImports(seen: Map[Digest[Crc32], File[Unix]], files: File[Unix]*)
                 (using Stdout, Allocator, Environment)
                 : Set[File[Unix]] =
    case class Imports(repos: Option[List[Repo]], imports: Option[List[Text]]):
      def repoList: List[Repo] = repos.presume
      def gen(seen: Map[Digest[Crc32], File[Unix]], files: File[Unix]*): Set[File[Unix]] = files.to(List) match
        case file :: tail =>
          val importFiles: List[File[Unix]] = imports.presume.flatMap: path =>
            val ref = unsafely(file.parent.path + Relative.parse(path))
            try
              if ref.exists() then
                List(unsafely(file.parent.path + Relative.parse(path)).file(Expect))
              else
                Out.println(t"Build file $ref does not exist; attempting to clone")
                if unsafely(ref.parent).exists()
                then throw AppError(t"The build ${ref.name} does not exist in ${unsafely(ref.parent)}")
                else
                  repoList.find(_.basePath(unsafely(file.parent).path) == unsafely(ref.parent)) match
                    case None =>
                      throw AppError(txt"""Could not find a remote repository containing the import $path""")
                    
                    case Some(repo) =>
                      Irk.cloneRepo(unsafely(ref.parent), repo.url)
                      List(ref.file(Expect))
                        
            catch case err: IoError =>
              throw AppError(t"Imported build $ref could not be read", err)
          
          readImports(seen, (importFiles ++ tail)*)
        
        case Nil => seen.values.to(Set)
    
    files.to(List) match
      case Nil =>
        seen.values.to(Set)
      
      case file :: tail =>
        try
          def interpret(digest: Option[Digest[Crc32]]) =
            Json.parse(file.read[Text]()).as[Imports].gen(digest.fold(seen)(seen.updated(_, file)), files*)
          
          if file.exists() then
            val digest: Digest[Crc32] = hashFile(file)
            if !seen.contains(digest) then interpret(Some(digest)) else readImports(seen, tail*)
          else interpret(None)

        catch case err: Exception => readImports(seen, tail*)

  def stop(cli: CommandLine)(using Stdout): ExitStatus =
    cli.shutdown()
    ExitStatus.Ok

  def showVersion()(using Stdout): ExitStatus =
    Out.println(Irk.version)
    ExitStatus.Ok
  
  def about()(using Stdout): ExitStatus =
    Out.println(t"Irk ${Irk.version}")
    Out.println(t"Scala ${Irk.scalaVersion}")
    Out.println(t"Java ${Irk.javaVersion}")
    ExitStatus.Ok

  def help()(using Stdout): ExitStatus =
    Out.println(t"Usage: irk [subcommand] [options]")
    Out.println(t"")
    Out.println(t"Subcommands:")
    Out.println(t"  build    -- runs the build in the current directory (default)")
    Out.println(t"  about    -- show version information for Irk")
    Out.println(t"  help     -- show this message")
    Out.println(t"  stop     -- stop the Irk daemon process")
    Out.println(t"")
    Out.println(t"Options:")
    Out.println(t"  -w, --watch    Wait for changes and re-run build.")
    ExitStatus.Ok

  def init(pwd: Directory[Unix], name: Text)(using Stdout): ExitStatus =
    val buildPath = unsafely(pwd / t"build.irk")
    if buildPath.exists() then
      Out.println(t"Build file build.irk already exists")
      ExitStatus.Fail(1)
    else
      import unsafeExceptions.canThrowAny
      val buildFile = buildPath.file(Create)
      val src = (pwd / t"src").directory(Ensure)
      val sourceDir = (src / t"core").directory(Ensure)
      
      val module = Module(name, t"${pwd.path.name}/core", None, None,
          Set(sourceDir.path.relativeTo(pwd.path).show), None, None, None, None, None, None, None, None)

      val config = BuildConfig(None, None, List(module), None, None)
      try
        config.json.show.writeTo(buildFile)
        ExitStatus.Ok
      catch
        case err: IoError =>
          Out.println(t"Could not write to build.irk")
          ExitStatus.Fail(1)
        case err: StreamCutError =>
          Out.println(t"Could not write to build.irk")
          ExitStatus.Fail(1)

  private val snip = t" — "*100

  private def report(result: Result, columns: Int): Text =
    val buf = StringBuilder("\n")
    def append(text: AnsiText): Unit = buf.append(text.render)
    
    def appendln(text: AnsiText): Unit =
      buf.append(text.render)
      buf.append('\n')
    
    val sorted =
      try
        result.issues.groupBy(_.baseDir).to(List).map: (baseDir, issues) =>
          issues.groupBy(_.code.path).to(List).flatMap: (path, issues) =>
            path match
              case Unset =>
                Log.warn(t"Unknown source path")
                None
              case path: Relative =>
                val file = unsafely(baseDir + path)
                if !file.exists() then
                  Log.warn(t"Missing source file: ${file}")
                  None
                else Some(file.file(Ensure) -> issues)
          .sortBy(_(0).modified)
        .sortBy(_.last(0).modified).flatten
        
      catch case err: IoError =>
        throw AppError(ansi"a file containing an error was deleted: ${err.toString.show}", err)//: ${err.ansi}", err)
    
    def arrow(k1: (Srgb, Text), k2: (Srgb, Text)): AnsiText =
      def hc(c: Srgb): Srgb = if c.hsl.lightness > 0.5 then colors.Black else colors.White
      ansi"${Bg(k1(0))}( ${hc(k1(0))}(${k1(1)}) )${Bg(k2(0))}(${k1(0)}() ${hc(k2(0))}(${k2(1)}) )${k2(0)}()"

    val highlighting: scm.Map[Text, IArray[Seq[Token]]] = scm.HashMap[Text, IArray[Seq[Token]]]()
    
    def highlight(text: Text): IArray[Seq[Token]] = if highlighting.contains(text) then highlighting(text) else
      highlighting(text) = ScalaSyntax.highlight(text)
      highlighting(text)
    
    val bg = Bg(Srgb(0.1, 0.0, 0.1))
    
    def format(text: Text, line: Int, margin: Int) =
      val syntax = highlight(text)
      if line >= syntax.length then ansi""
      else syntax(line).map:
        case Token.Code(code, flair) => flair match
          case Flair.Type              => ansi"${colors.YellowGreen}(${code})"
          case Flair.Term              => ansi"${colors.CadetBlue}(${code})"
          case Flair.Symbol            => ansi"${colors.Turquoise}(${code})"
          case Flair.Keyword           => ansi"${colors.DarkOrange}(${code})"
          case Flair.Modifier          => ansi"${colors.Chocolate}(${code})"
          case Flair.Ident             => ansi"${colors.BurlyWood}(${code})"
          case Flair.Error             => ansi"${colors.OrangeRed}($Underline(${code}))"
          case Flair.Number            => ansi"${colors.Gold}(${code})"
          case Flair.String            => ansi"${colors.Plum}(${code})"
          case other                   => ansi"${code}"
        case Token.Unparsed(txt)     => ansi"$txt"
        case Token.Markup(_)         => ansi""
        case Token.Newline           => throw Mistake("Should not have a newline")
      .join.take(columns - 2 - margin)

    def codeLine(margin: Int, codeText: Text, line: Int): AnsiText =
      val lineNo = ansi"${colors.Orange}(${line.show.pad(margin, Rtl)})"
      val bar = ansi"${colors.DarkSlateGray}(║)$bg "
      val code = format(codeText, line - 1, margin)
      ansi"${escapes.Reset}$lineNo$bar$code${t" "*(columns - 2 - margin - code.length)}${escapes.Reset}"

    sorted.foreach:
      case (file, issues) =>
        val codeText = issues.head.code.content.text
        //val syntax: IArray[Seq[Token]] = highlight(codeText)
        issues.groupBy(_.code.startLine).to(List).sortBy(_(0)).foreach:
          case (ln, issues) => issues.head match
            case Issue(level, baseDir, pos, stack, message) =>
              val margin = (pos.endLine + 2).show.length
              val codeWidth = columns - 2 - margin
              val path = pos.path.mfold(t"«unknown»")(_.show)
              val posText = t"${path}:${pos.startLine + 1}:${pos.from}"
              
              val shade = level match
                case Level.Error => colors.Crimson
                case Level.Warn  => colors.Orange
                case Level.Info  => colors.SteelBlue
              
              appendln(arrow(colors.Purple -> pos.module.option.fold(t"[external]")(_.show), shade -> posText))
              
              if pos.startLine > 1 then appendln(codeLine(margin, codeText, pos.startLine))

              (pos.startLine to pos.endLine).foreach: lineNo =>
                if lineNo < (pos.startLine + 2) || lineNo > (pos.endLine - 2) then
                  val code = format(codeText, lineNo, margin)
                  val before = if lineNo == pos.startLine then code.take(pos.from) else ansi""
                  val after = if lineNo == pos.endLine then code.drop(pos.to) else ansi""
                  
                  val highlighted =
                    if lineNo == pos.startLine then
                      if lineNo == pos.endLine then code.slice(pos.from, pos.to) else code.drop(pos.from)
                    else if lineNo == pos.endLine then code.take(pos.to) else code

                  append(ansi"${escapes.Reset}${colors.Orange}($Bold(${(lineNo + 1).show.pad(margin, Rtl)}))")
                  append(ansi"${colors.DarkSlateGray}(║)$bg ")
                  append(before)
                  append(ansi"${colors.OrangeRed}(${highlighted.plain})")
                  val width = highlighted.length + before.length + after.length
                  appendln(ansi"$after${t" "*(codeWidth - width)}${escapes.Reset}")
                else if lineNo == pos.startLine + 2 then
                  val code = format(codeText, pos.startLine + 1, margin)
                  val break = snip.take(codeWidth)
                  append(ansi"${escapes.Reset}${t"".pad(margin, Rtl)}${colors.DarkSlateGray}(╫)$bg ")
                  appendln(ansi"${colors.RebeccaPurple}($break)")

              if pos.endLine + 1 < highlight(codeText).length
              then appendln(codeLine(margin, codeText, pos.endLine + 2))

              if pos.startLine == pos.endLine then
                append(ansi"${colors.DarkSlateGray}(${t" "*margin}╟${t"─"*pos.from}┴${t"─"*(pos.to - pos.from)}┘)")
                appendln(ansi"${t"\e[K"}")
              else appendln(ansi"${escapes.Reset}${t" "*margin}${colors.DarkSlateGray}(║)")
              
              message.cut(t"\n").foreach: line =>
                appendln(ansi"${escapes.Reset}${t" "*margin}${colors.DarkSlateGray}(║) ${Bold}($line)")
              
              appendln(ansi"${escapes.Reset}${t" "*margin}${colors.DarkSlateGray}(╨)")
              
              if !stack.isEmpty then
                appendln(ansi"This includes inlined code from:")
                val pathWidth = stack.map(_.path.mfold(9)(_.show.length)).max
                val refWidth = stack.map(_.module.option.fold(10)(_.show.length)).max
                val indent = pathWidth + refWidth + 7
                
                stack.foreach: pos =>
                  val ref = pos.module.option.fold(t"[external]")(_.show).pad(refWidth, Rtl)
                  val path = pos.path.mmap(_.show.pad(pathWidth, Rtl))
                  val code = codeLine(margin + indent, pos.content.text, pos.startLine).drop(indent)
                  appendln(ansi"${arrow(colors.DarkCyan -> ref, colors.LightSeaGreen -> path.otherwise(t"«unknown»"))} $code${escapes.Reset}")
                
                appendln(ansi"")
              appendln(ansi"${escapes.Reset}")

    buf.text


  case class Change(changeType: ChangeType, path: DiskPath[Unix])
  enum ChangeType:
    case Build, Source, Resource

    def rebuild = this == Build
    def recompile = this != Resource

    def category: Text = this match
      case Build     => t"build"
      case Source    => t"source"
      case Resource  => t"resource"

  def build(target: Option[Text], publishSonatype: Boolean, watch: Boolean = false, scriptFile: File[Unix],
                exec: Boolean)
           (using Stdout, InputSource, Monitor, Allocator, Environment)
           : ExitStatus throws AppError = unsafely:
    Tty.capture:
      val rootBuild = unsafely(env.pwd / t"build.irk")
      val tap: Tap = Tap(true)

      def interrupts = Tty.stream[Keypress].map:
        case key =>
          Log.fine(t"Got input: $key")
          key
      .collect:
        case Keypress.Ctrl('C')        =>
          Log.fine(t"Received interrupt")
          Event.Interrupt
        case Keypress.Resize(width, _) =>
          Log.fine(t"Received resize message")
          Event.Resize(width)
      .filter:
        case Event.Interrupt =>
          if !tap.state() then
            summon[Monitor].cancel()
            Log.fine(t"Ignored interrupt")
            false
          else
            Log.fine(t"Propagated interrupt")
            true
        
        case _ =>
          true

      lazy val watcher: Watcher[Directory[Unix]] = try  
        val watcher = List[Directory[Unix]]().watch()
        
        if watch then
          val dirs = readImports(Map(), rootBuild.file(Expect)).map(_.parent).to(Set)
          dirs.sift[Directory[Unix]].foreach(watcher.add(_))
        
        watcher
      catch
        case err: InotifyError => throw AppError(t"Could not watch directories", err)
        case err: IoError      => throw AppError(t"Could not watch directories", err)

      val suffixes = Set(t".scala", t".java", t".irk")
      
      def updateWatches(watcher: => Watcher[Directory[Unix]], build: Build): Build = try
        val buildDirs = readImports(Map(), rootBuild.file(Expect)).map(_.parent).to(Set)
        val dirs = (buildDirs ++ build.sourceDirs ++ build.resourceDirs).sift[Directory[Unix]]
        
        (dirs -- watcher.directories).foreach(watcher.add(_))
        (watcher.directories -- dirs).foreach(watcher.remove(_))
        
        build
      catch
        case err: InotifyError => throw AppError(t"Could not update watch directories", err)
        case err: IoError      => throw AppError(t"Could not update watch directories", err)

      def generateBuild(): Build =
        try
          if watch then updateWatches(watcher, initBuild(env.pwd.directory(Expect)))
          else initBuild(env.pwd.directory(Expect))
        catch
          case err: BuildfileError => throw AppError(err.message)
          case err: IoError => throw AppError(t"The build file could not be read")

      @tailrec
      def loop(first: Boolean, stream: LazyList[Event], oldBuild: Build, lastSuccess: Boolean,
                        browser: List[WebDriver#Session], running: List[Jvm] = Nil,
                        count: Int = 1, columns: Int = 120)(using Internet, Monitor)
                   : ExitStatus =
        import unsafeExceptions.canThrowAny
        tap.open()
        val cancel: Promise[Unit] = Promise()

        if stream.isEmpty then if lastSuccess then ExitStatus.Ok else ExitStatus.Fail(1)
        else stream.head match
          case Event.Interrupt =>
            running.foreach(_.abort())
            if lastSuccess then ExitStatus.Ok else ExitStatus.Fail(1)
          case Event.Resize(width) =>
            loop(first, stream.tail, oldBuild, lastSuccess, browser, running, count, width)
          case Event.Changeset(fileChanges) =>
            tap.pause()
            
            def ephemeral(events: List[WatchEvent]): Boolean = events match
              case WatchEvent.NewFile(_, _) +: _ :+ WatchEvent.Delete(_, _) => true
              case _                                                        => false
            
            val changes: List[Change] =
              fileChanges.groupBy(_.path[DiskPath[Unix]]).collect:
                case (path, es) if !ephemeral(es) => es.last
              .foldLeft[List[Change]](Nil): (changes, event) =>
                Option(event).collect:
                  case WatchEvent.Delete(_, _)  => t"deleted"
                  case WatchEvent.Modify(_, _)  => t"modified"
                  case WatchEvent.NewFile(_, _) => t"created"
                .foreach: change =>
                  val file = ansi"${palette.File}(${event.path[DiskPath[Unix]].relativeTo(env.pwd)})"
                  Out.println(ansi"The file $file was $change")
                
                if event.path[DiskPath[Unix]].name.endsWith(t".irk")
                then Change(ChangeType.Build, event.path) :: changes
                else if event.path[DiskPath[Unix]].name.endsWith(t".scala")
                then Change(ChangeType.Source, event.path) :: changes
                else if oldBuild.resourceDirs.exists(_.path.precedes(event.path))
                then Change(ChangeType.Resource, event.path) :: changes
                else changes
            
            if changes.isEmpty && count > 1 then
              loop(false, stream.tail, oldBuild, lastSuccess, browser, running, count, columns)
            else
              changes.foreach: change =>
                Out.println(ansi"Rebuild triggered by change to a ${change.changeType.category} file")
  
              val build: Build =
                if changes.exists(_.changeType.rebuild)
                then safely(generateBuild()).otherwise(oldBuild)
                else oldBuild

              build.clearHashes()
  
              val oldHashes = build.cache
              Out.println(t"")
              Out.print(ansi"${colors.Black}(${Bg(colors.DarkTurquoise)}( Build #$count ))")
              Out.print(ansi"${colors.DarkTurquoise}(${Bg(colors.DodgerBlue)}( ${colors.Black}(${build.pwd.path.fullname}) ))")
              Out.println(ansi"${colors.DodgerBlue}()")
              
              val funnel: Funnel[Progress.Update] = Funnel()
              funnel.put(Progress.Update.Resize(columns))
              val totalTasks: Int = build.steps.size

              val owners: Map[DiskPath[Unix], Step] = build.steps.flatMap: step =>
                step.sources.map(_.path -> step)
              .to(Map)
              
              val tasks = build.graph.traversal[Task[Result]]: (set, step) =>
                set.sequence.flatMap: results =>
                  step.jars.map(Irk.fetchFile(_, Some(funnel))).sequence.flatMap: downloads =>
                    if cancel.ready then Task(step.id.show)(Result.Aborted) else Task(step.id.show):
                      val verb = Verb.Compile(ansi"${Green}(${step.name})")
                      val result: Result = results.foldLeft(Result.Complete())(_ + _)
                      
                      if result.success then
                        val hash = build.hashes(step)
                        try
                          if oldHashes.get(step) != build.hashes.get(step)
                          then
                            funnel.put(Progress.Update.Add(verb, hash))
                            val newResult = step.compile(build.hashes, oldHashes, build, scriptFile, cancel, owners)
                            val status = if cancel.ready then Result.Aborted else newResult
                            funnel.put(Progress.Update.Remove(verb, status))
                            
                            if newResult.success && build.plugins.contains(step.id) then
                              val verb2 = Verb.Build(ansi"compiler plugin ${palette.File}(${step.id})")
                              val path = Irk.libJar(build.hashes(step))
                              if !path.exists() then
                                funnel.put(Progress.Update.Add(verb2, build.hashes(step)))
                                val artifact = Artifact(path, step.main, Format.CompilerPlugin)
                                
                                Artifact.build(artifact, irkJar(scriptFile), step.name, step.version, step.classpath(build).to(List),
                                    step.allResources(build).to(List), step.main)
                                
                                funnel.put(Progress.Update.Remove(verb2, Result.Complete()))
                            
                            if newResult.success then step.artifact.foreach: artifact =>
                              val verb = Verb.Build(ansi"artifact ${palette.File}(${artifact.path.relativeTo(env.pwd).show})")
                              funnel.put(Progress.Update.Add(verb, build.hashes(step)))
                              
                              Artifact.build(artifact, irkJar(scriptFile), step.name, step.version,
                                  step.classpath(build).to(List), step.allResources(build).to(List),
                                  artifact.main.orElse(step.main))
                              
                              funnel.put(Progress.Update.Remove(verb, Result.Complete()))
                            
                            result + newResult
                    
                          else
                            funnel.put(Progress.Update.SkipOne)
                            result
                        catch
                          case err: ExcessDataError =>
                            val result = Result.Terminal(ansi"Too much data was received during ${step.name}")
                            funnel.put(Progress.Update.Remove(verb, result))
                            result
          
                          case err: StreamCutError =>
                            val result = Result.Terminal(ansi"An I/O stream failed during ${step.name}")
                            funnel.put(Progress.Update.Remove(verb, result))
                            result
                      else
                        result
  
              val pulsar = Pulsar(using timekeeping.long)(100)
              
              val ui = Task(t"ui"):
                funnel.stream.multiplexWith:
                  pulsar.stream.map(Progress.Update.Print.waive)
                .foldLeft(Progress(TreeMap(), Nil, totalTasks = totalTasks))(_(_))
              
              val resultSet = tasks.values.sequence.await().to(Set)
              val result = resultSet.foldLeft(Result.Complete())(_ + _)
              
              val subprocesses: List[Jvm] = if !result.success then running else
                build.linearization.map: step =>
                  val subprocess: Option[Jvm] = step.exec.fold[Option[Jvm]](None):
                    case Exec(browsers, url, start, stop) =>
                      val verb = Verb.Exec(ansi"main class ${palette.Class}($start)")
                      
                      val mainTask = Task(t"main"):
                        val hash = t"${build.hashes.get(step)}$start".digest[Crc32]
                        funnel.put(Progress.Update.Add(verb, hash))
                        running.foreach(_.abort())
                        val resources = step.allResources(build).map(_.path)
                        val classpath = (step.classpath(build) ++ resources).to(List)
                        val jvm = Run.main(irkJar(scriptFile).path :: classpath)(start, stop)
                        jvm

                      val subprocess: Jvm = mainTask.await()
                      
                      val newResult: Result = subprocess.await() match
                        case ExitStatus.Ok =>
                          subprocess.stdout().foreach: data =>
                            funnel.put(Progress.Update.Stdout(verb, data)) 
                          result
                        case ExitStatus.Fail(n) =>
                          subprocess.stdout().foreach: data =>
                            funnel.put(Progress.Update.Stdout(verb, data)) 
                          Result.Terminal(ansi"Process returned exit status $n")
                      
                      funnel.put(Progress.Update.Remove(verb, newResult))
                      for b <- browser; u <- url do
                        if running.isEmpty then b.navigateTo(Url.parse(u)) else b.refresh()
                      
                      Some(subprocess)
                  
                  if publishSonatype then Sonatype.publish(build, env(t"SONATYPE_PASSWORD").option)
                  
                  subprocess
                .flatten
              
              pulsar.stop()
              funnel.stop()
              ui.await()
              Out.print(report(result, columns))

              val arrowColor = if result.success then colors.YellowGreen else colors.OrangeRed
              Out.print(ansi"${Bg(arrowColor)}(  )")
              Out.print(ansi"${arrowColor}() ")

              if result == Result.Aborted
              then Out.println(ansi"Build was ${colors.SteelBlue}(aborted)")
              else if !result.success
              then
                Out.print(ansi"Build ${colors.OrangeRed}(failed) with ")
                Out.println(ansi"${colors.Gold}(${result.errors.size}) ${Numerous(t"error")(result.errors)}")
              else
                Out.print(ansi"Build ${colors.Green}(succeeded)")
                
                if result.issues.size > 0
                then Out.println(ansi" with ${colors.Gold}(${result.issues.size}) ${Numerous(t"warning")(result.issues)}")
                else Out.println(ansi"")

              Out.println(t"\e[0m\e[?25h\e[A")

              if watch then
                Out.print(Progress.titleText(t"Irk: waiting for changes"))
                Out.print(ansi"${t"\n"}${Bg(colors.Orange)}(  )")
                Out.print(ansi"${colors.Orange}() ")
                Out.println(ansi"Watching ${colors.Gold}(${watcher.directories.size}) directories for changes...")
              
              tap.open()
              loop(false, stream.tail, build, result.success, browser, subprocesses, count + 1, columns)
          
      val fileChanges: LazyList[Event] =
        if !watch then LazyList()
        else interrupts.multiplexWith(watcher.stream.regulate(tap).cluster(100).map(Event.Changeset(_)))
      
      val build = generateBuild()
      
      internet:
        if exec then Chrome.session(8869):
          loop(true, Event.Changeset(Nil) #:: fileChanges, build, false, List(browser))
        else loop(true, Event.Changeset(Nil) #:: fileChanges, build, false, Nil)

case class ExecError(err: Exception)
extends Error(err"an exception was thrown while executing the task: ${err.getMessage.nn.show}")

object Run:
  import java.net.*

  given Classpath()
  
  lazy val adoptium: Adoptium throws ClasspathRefError | StreamCutError | IoError | NoValidJdkError =
    given Environment = environments.system
    Adoptium.install()
  
  lazy val jdk: Jdk throws ClasspathRefError | StreamCutError | IoError | EnvError | NoValidJdkError =
    given Environment = environments.system
    adoptium.get(18, jre = true, early = true, force = false)

  def main(classpath: List[DiskPath[Unix]])(start: Text, stop: Option[Text])(using Stdout, Environment)
          : Jvm throws ClasspathRefError | IoError | StreamCutError | EnvError | NoValidJdkError =
    jdk.launch(classpath, start, Nil)

case class Subprocess(status: Option[ExitStatus], terminate: Option[() => Unit] = None):
  def stop(): Unit = terminate.foreach(_())

object Verb:
  given Ordering[Verb] = Ordering[AnsiText].on(_.name)

enum Verb:
  def name: AnsiText

  case Build(name: AnsiText)
  case Exec(name: AnsiText)
  case Compile(name: AnsiText)
  case Download(name: AnsiText)

  def present: AnsiText = this match
    case Build(name)    => ansi"Building $name"
    case Exec(name)     => ansi"Executing $name"
    case Compile(name)  => ansi"Compiling $name"
    case Download(name) => ansi"Downloading $name"
  
  def past: AnsiText = this match
    case Build(name)    => ansi"Built $name"
    case Exec(name)     => ansi"Finished execution of $name"
    case Compile(name)  => ansi"Compiled $name"
    case Download(name) => ansi"Downloaded $name"
