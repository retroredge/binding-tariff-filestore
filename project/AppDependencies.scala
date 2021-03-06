import sbt._

object AppDependencies {

  private lazy val apacheHttpVersion = "4.5.8"

  val compile = Seq(
    "io.megl"                     %% "play-json-extra"            % "2.4.3",
    "com.amazonaws"               %  "aws-java-sdk-s3"            % "1.11.532",
    "uk.gov.hmrc"                 %% "bootstrap-play-25"          % "4.10.0",
    "uk.gov.hmrc"                 %% "play-json-union-formatter"  % "1.5.0",
    "uk.gov.hmrc"                 %% "simple-reactivemongo"       % "7.16.0-play-25",
    "org.apache.httpcomponents"   %  "httpclient"                 % apacheHttpVersion,
    "org.apache.httpcomponents"   %  "httpmime"                   % apacheHttpVersion
  )

  lazy val scope: String = "test,it"

  val test = Seq(
    "com.github.tomakehurst"        %  "wiremock"               % "2.22.0"         % scope,
    "org.mockito"                   %  "mockito-core"           % "2.26.0"         % scope,
    "org.pegdown"                   %  "pegdown"                % "1.6.0"          % scope,
    "org.scalaj"                    %% "scalaj-http"            % "2.4.1"          % scope,
    "org.scalatestplus.play"        %% "scalatestplus-play"     % "2.0.1"          % scope,
    "uk.gov.hmrc"                   %% "hmrctest"               % "3.6.0-play-25"  % scope,
    "uk.gov.hmrc"                   %% "http-verbs-test"        % "1.4.0-play-25"  % scope,
    "uk.gov.hmrc"                   %% "reactivemongo-test"     % "4.10.0-play-25" % scope
  )

}
