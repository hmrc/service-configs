resolvers += Resolver.url(
  "HMRC Sbt Plugin Releases",
  url("https://dl.bintray.com/hmrc/sbt-plugin-releases")
)(Resolver.ivyStylePatterns)

resolvers += Resolver.bintrayRepo("hmrc", "releases")

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("uk.gov.hmrc" % "sbt-auto-build" % "1.16.0")

addSbtPlugin("uk.gov.hmrc" % "sbt-git-versioning" % "1.19.0")

addSbtPlugin("uk.gov.hmrc" % "sbt-artifactory" % "0.19.0")

addSbtPlugin("uk.gov.hmrc" % "sbt-distributables" % "1.6.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.23")
