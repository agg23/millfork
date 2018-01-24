name := "millfork"

version := "0.1"

scalaVersion := "2.12.3"

resolvers += Resolver.mavenLocal

libraryDependencies += "com.lihaoyi" %% "fastparse" % "1.0.0"

libraryDependencies += "org.apache.commons" % "commons-configuration2" % "2.2"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.4"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"

// these two not in Maven Central or any other public repo
// get them from the following links or just build millfork without tests:
// https://github.com/sethm/symon
// https://github.com/andrew-hoffman/halfnes/tree/061

libraryDependencies += "com.loomcom.symon" % "symon" % "1.3.0-SNAPSHOT" % "test"

libraryDependencies += "com.grapeshot" % "halfnes" % "061" % "test"

mainClass in Compile := Some("millfork.Main")

assemblyJarName := "millfork.jar"

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "millfork.buildinfo"
  )

import sbtassembly.AssemblyKeys

val releaseDist = TaskKey[File]("release-dist", "Creates a distributable zip file.")

releaseDist := {
  val jar = AssemblyKeys.assembly.value
  val base = Keys.baseDirectory.value
  val target = Keys.target.value
  val name = Keys.name.value
  val version = Keys.version.value
  val distDir = target / (name + "-" + version)
  val releasesDir = base / "releases"
  val zipFile = releasesDir / (name + "-" + version + ".zip")
  IO.delete(zipFile)
  IO.delete(distDir)
  IO.createDirectory(releasesDir)
  IO.createDirectory(distDir)
  IO.copyFile(jar, distDir / jar.name)
  IO.copyFile(base / "LICENSE", distDir / "LICENSE")
  IO.copyFile(base / "README.md", distDir / "README.md")
  def copyDir(name: String): Unit = {
    IO.createDirectory(distDir / name)
    IO.copyDirectory(base / name, distDir / name)
  }
  copyDir("include")
  copyDir("doc")
  def entries(f: File): List[File] = f :: (if (f.isDirectory) IO.listFiles(f).toList.flatMap(entries) else Nil)
  IO.zip(entries(distDir).map(d => (d, d.getAbsolutePath.substring(distDir.getParent.length + 1))), zipFile)
  IO.delete(distDir)
  zipFile
}
