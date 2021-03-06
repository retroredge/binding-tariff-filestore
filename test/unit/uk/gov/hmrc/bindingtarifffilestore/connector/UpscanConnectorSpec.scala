/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.bindingtarifffilestore.connector

import akka.actor.ActorSystem
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.BDDMockito.given
import org.scalatest.BeforeAndAfterEach
import org.scalatest.mockito.MockitoSugar
import play.api.Environment
import play.api.http.Status
import play.api.libs.Files.TemporaryFile
import play.api.libs.ws.WSClient
import uk.gov.hmrc.bindingtarifffilestore.config.AppConfig
import uk.gov.hmrc.bindingtarifffilestore.model.upscan.{UpscanTemplate, UploadSettings, UpscanInitiateResponse}
import uk.gov.hmrc.bindingtarifffilestore.model.{FileMetadata, FileWithMetadata}
import uk.gov.hmrc.bindingtarifffilestore.util.{ResourceFiles, WiremockTestServer}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.audit.DefaultAuditConnector
import uk.gov.hmrc.play.bootstrap.http.DefaultHttpClient
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}

import scala.concurrent.ExecutionContext.Implicits.global

class UpscanConnectorSpec extends UnitSpec with WithFakeApplication with WiremockTestServer
  with MockitoSugar with BeforeAndAfterEach with ResourceFiles {

  private val config = mock[AppConfig]

  private val actorSystem = ActorSystem.create("test")
  private val wsClient: WSClient = fakeApplication.injector.instanceOf[WSClient]
  private val auditConnector = new DefaultAuditConnector(fakeApplication.configuration, fakeApplication.injector.instanceOf[Environment])
  private val hmrcWsClient = new DefaultHttpClient(fakeApplication.configuration, auditConnector, wsClient, actorSystem)

  private implicit val headers: HeaderCarrier = HeaderCarrier()

  private val connector = new UpscanConnector(config, hmrcWsClient)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    given(config.upscanInitiateUrl).willReturn(wireMockUrl)
  }

  "Connector" should {
    "Initiate" in {
      stubFor(
        post("/upscan/initiate")
          .willReturn(
            aResponse()
              .withBody(fromFile("upscan/initiate_response.json"))
          )
      )

      val response = await(connector.initiate(UploadSettings("callback", 1, 1000)))
      response shouldBe UpscanInitiateResponse(
        reference = "reference",
        uploadRequest = UpscanTemplate(
          href = "href",
          fields = Map(
            "key" -> "value"
          )
        )
      )
    }

    "Upload" in {
      stubFor(
        post("/path")
          .willReturn(
            aResponse()
              .withStatus(Status.NO_CONTENT)
          )
      )

      val templateUploading = UpscanTemplate(
        href = s"$wireMockUrl/path",
        fields = Map(
          "key" -> "value"
        )
      )
      val fileUploading = FileWithMetadata(
        TemporaryFile("example-file.json"),
        FileMetadata("id", "file.txt", "text/plain")
      )

      await(connector.upload(templateUploading, fileUploading))
    }

    "Upload with error handling" in {
      stubFor(
        post("/path")
          .willReturn(
            aResponse()
              .withStatus(Status.BAD_GATEWAY)
              .withBody("content")
          )
      )

      val templateUploading = UpscanTemplate(
        href = s"$wireMockUrl/path",
        fields = Map(
          "key" -> "value"
        )
      )
      val fileUploading = FileWithMetadata(
        TemporaryFile("example-file.json"),
        FileMetadata("id", "file.txt", "text/plain")
      )

      intercept[RuntimeException] {
        await(connector.upload(templateUploading, fileUploading))
      }.getMessage shouldBe "Bad AWS response for file [id] with status [502] body [content]"
    }
  }

}
