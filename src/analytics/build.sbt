import Dependencies._

name := "osmesa-analytics"

dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-core" % "2.6.7"
dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-databind" % "2.6.7"
dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.11" % "2.6.7"

// dependencyOverrides += "org.json4s" % "json4s-jackson" % "3.2.11"
// dependencyOverrides += "org.json4s" % "json4s-core" % "3.2.11"
// dependencyOverrides += "org.json4s" % "json4s-ast" % "3.2.11"


libraryDependencies ++= Seq(
  decline,
  hive % "provided",
  gtGeotools exclude("com.google.protobuf", "protobuf-java"),
  gtS3 exclude("com.google.protobuf", "protobuf-java"),
  gtSpark exclude("com.google.protobuf", "protobuf-java"),
  gtVector exclude("com.google.protobuf", "protobuf-java"),
  gtVectorTile exclude("com.google.protobuf", "protobuf-java"),
  "com.google.protobuf" % "protobuf-java" % "2.5.0",
  vectorpipe exclude("com.google.protobuf", "protobuf-java"),
  // geomesaHbaseDatastore,
  sparkHive % "provided",
  sparkSql % "provided",
  cats,
  hbaseClient % "provided",
  hbaseCommon % "provided",
  hbaseServer % "provided",
  scalactic,
  scalatest,
  "com.madhukaraphatak" %% "java-sizeof" % "0.1"
)

fork in Test := true

javaOptions ++= Seq("-Xmx5G")

initialCommands in console :=
  """
  """

assemblyJarName in assembly := "osmesa-analytics.jar"

assemblyShadeRules in assembly := {
  val shadePackage = "com.azavea.shaded.demo"
  Seq(
    ShadeRule.rename("com.google.common.**" -> s"$shadePackage.google.common.@1")
      .inLibrary("com.locationtech.geotrellis" %% "geotrellis-cassandra" % Version.geotrellis).inAll,
    ShadeRule.rename("io.netty.**" -> s"$shadePackage.io.netty.@1")
      .inLibrary("com.locationtech.geotrellis" %% "geotrellis-hbase" % Version.geotrellis).inAll,
    ShadeRule.rename("com.fasterxml.jackson.**" -> s"$shadePackage.com.fasterxml.jackson.@1")
      .inLibrary("com.networknt" % "json-schema-validator" % "0.1.7").inAll,
    ShadeRule.rename("org.apache.avro.**" -> s"$shadePackage.org.apache.avro.@1")
      .inLibrary("com.locationtech.geotrellis" %% "geotrellis-spark" % Version.geotrellis).inAll
  )
}

assemblyMergeStrategy in assembly := {
  case s if s.startsWith("META-INF/services") => MergeStrategy.concat
  case "reference.conf" | "application.conf"  => MergeStrategy.concat
  case "META-INF/MANIFEST.MF" | "META-INF\\MANIFEST.MF" => MergeStrategy.discard
  case "META-INF/ECLIPSEF.RSA" | "META-INF/ECLIPSEF.SF" => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)