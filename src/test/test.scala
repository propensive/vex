package fury

import probably.*
import gossamer.*
import rudiments.*
import serpentine.*
import cellulose.*

import unsafeExceptions.canThrowAny

object Tests extends Suite(t"Fury Model Tests"):
  def run(): Unit =
    suite(t"ID tests"):
      suite(t"Module IDs"):
        test(t"Parse a valid module ID"):
          ModuleId(t"moduleid")
        .assert(_ == ModuleId(t"moduleid"))
        
        test(t"Module ID can have a dash"):
          ModuleId(t"module-id")
        .assert(_ == ModuleId(t"module-id"))
        
        test(t"Module ID cannot have two adjacent dashes"):
          capture(ModuleId(t"module--id"))
        .assert(_ == InvalidRefError(t"module--id", Ids.ModuleId))

        for sym <- List('!', ':', '/', '\\', ',', '.', '?', '|', ';', ' ') do
          test(t"Module ID cannot contain a '$sym'"):
            capture(ModuleId(t"module${sym}id"))
          .assert(_ == InvalidRefError(t"module${sym}id", Ids.ModuleId))
        
        test(t"Module cannot contain capital letters"):
          capture(ModuleId(t"moduleId"))
        .assert(_ == InvalidRefError(t"moduleId", Ids.ModuleId))
      
      suite(t"Git refs"):
        test(t"Parse a Git Commit"):
          Commit(t"0123456789abcdef0123456789abcdef01234567")
        .assert(_ == Commit(t"0123456789abcdef0123456789abcdef01234567"))
        
        test(t"Parse a Git Commit must have length of 40"):
          capture(Commit(t"0123456789abcdef0123456789abcdef0123456"))
        .assert(_ == InvalidRefError(t"0123456789abcdef0123456789abcdef0123456", Ids.Commit))
        
        test(t"Parse a Git Commit cannot contain non-hex characters"):
          capture(Commit(t"0123456789abcdef0123456789gbcdef0123456"))
        .assert(_ == InvalidRefError(t"0123456789abcdef0123456789gbcdef0123456", Ids.Commit))

        test(t"Parse a Git Branch with a '/'"):
          Branch(t"git/branch")
        .assert(_ == Branch(t"git/branch"))
        
        test(t"A Git Branch part cannot start with a '.'"):
          capture(Branch(t"git/.branch"))
        .assert(_ == InvalidRefError(t"git/.branch", Ids.Branch))
        
        test(t"A Git Branch part cannot endWith '.lock'"):
          capture(Branch(t"git/branch.lock"))
        .assert(_ == InvalidRefError(t"git/branch.lock", Ids.Branch))
        
        test(t"A Git Branch part cannot contain consecutive '.'s"):
          capture(Branch(t"git/branch..name"))
        .assert(_ == InvalidRefError(t"git/branch..name", Ids.Branch))
        
        test(t"A Git Branch part cannot contain consecutive '/'s"):
          capture(Branch(t"git/branch//name"))
        .matches(_ == InvalidRefError(t"git/branch//name", Ids.Branch))
        
        test(t"A Git Branch part cannot end with a '.'"):
          capture(Branch(t"git/branch."))
        .assert(_ == InvalidRefError(t"git/branch.", Ids.Branch))
        
        test(t"A Git Branch cannot contain a '@{'"):
          capture(Branch(t"git/bran@{ch}"))
        .assert(_ == InvalidRefError(t"git/bran@{ch}", Ids.Branch))
        
        for sym <- List(' ', '?', '*', '~', '^', ':', '[', '\\') do
          test(t"A Git Branch part cannot contain '$sym'"):
            capture(Branch(t"git/branch${sym}name"))
          .assert(_ == InvalidRefError(t"git/branch${sym}name", Ids.Branch))
      
      suite(t"Git refs"):
        test(t"Package names cannot contain hyphens"):
          capture(Package(t"com.package-name"))
        .assert(_ == InvalidRefError(t"com.package-name", Ids.Package))
        
        test(t"Package names can contain hyphens"):
          Package(t"com.package_name")
        .assert(_ == Package(t"com.package_name"))
        
        test(t"Package names cannot contain two dots"):
          capture(Package(t"com..packagename"))
        .assert(_ == InvalidRefError(t"com..packagename", Ids.Package))
        
        for sym <- List(' ', '?', '*', '~', '^', ':', '[', '\\') do
          test(t"Package names cannot contain '$sym'"):
            capture(Package(t"com${sym}packagename"))
          .assert(_ == InvalidRefError(t"com${sym}packagename", Ids.Package))
        
        test(t"Package name parts cannot start with a number"):
          capture(Package(t"com.123abc"))
        .assert(_ == InvalidRefError(t"com.123abc", Ids.Package))
          
    suite(t"CoDL parsing tests"):
      val buildFile = t"""
        :<< "##"
            This is a Fury source file
        overlay  overlayid  https://example.com/  0000000000000000000000000000000000000000  main
        command  build  myaction/foo
        default  build
        
        project main-project
          module core
            sources  src/core
            provide  org.mainpkg
          
          module test
            sources  src/test
      """


      // val build = test(t"Parse a build file as a Build instance"):
      //   Codl.parse(buildFile)
      // .check()

      // test(t"Check exactly one project"):
      //   build.projects.length
      // .assert(_ == 1)

      // test(t"Check exactly two modules"):
      //   build.projects.head.modules.length
      // .assert(_ == 2)

      val vaultFile = t"""
        release myproject current
          
          lifetime  365
          license   apache-2
          tags      active software clever scala
          date      2021-11-11
          
          description
              A project to demonstrate Fury
          
          repo      https://github.com/   0000000000000000000000000000000000000000   main
          website   https://example.com/
          provide   com.example
          
        release another current
          description
              _Another_ project to demonstrate Fury
          
          tags      alternative scala project
          license   apache-2
          date      2022-12-20
          lifetime  30
          repo      https://github.com/propensive/two/  0000000000000000000000000000000000000000  main
      """

      val vault: Vault = test(t"Parse a Vault file"):
        val readable: turbulence.Readable[Text, Text] = summon[turbulence.Readable[Text, Text]]
        Codl.tokenize(readable.read(vaultFile))

        Codl.read[Vault](vaultFile)
      .check()

      val localFile = t"""
        fork  rudiments  /home/propensive/dev/one/mod/rudiments
        fork  gossamer   /home/propensive/dev/one/mod/gossamer
      """

      val forks = test(t"Parse forks"):
        Codl.read[Local](localFile)
      .assert(_ == Local(List(
        Fork(BuildId(t"rudiments"), ? / p"home" / p"propensive" / p"dev" / p"one" / p"mod" / p"rudiments"),
        Fork(BuildId(t"gossamer"), ? / p"home" / p"propensive" / p"dev" / p"one" / p"mod" / p"gossamer"),
      )))
