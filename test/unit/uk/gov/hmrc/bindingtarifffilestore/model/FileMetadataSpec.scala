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

package uk.gov.hmrc.bindingtarifffilestore.model

import java.time.Instant

import play.api.libs.json.{JsNumber, JsObject, JsString, Json}
import uk.gov.hmrc.play.test.UnitSpec

class FileMetadataSpec extends UnitSpec {

  "File Meta Data" should {

    val model = FileMetadata(
      id = "id",
      fileName = "fileName",
      mimeType = "type",
      url = Some("url"),
      scanStatus = Some(ScanStatus.READY),
      lastUpdated = Instant.EPOCH
    )

    val jsonMongo: JsObject = Json.obj(
      "id" -> JsString("id"),
      "fileName" -> JsString("fileName"),
      "mimeType" -> JsString("type"),
      "url" -> JsString("url"),
      "scanStatus" -> JsString("READY"),
      "lastUpdated" -> Json.obj("$date" -> JsNumber(0))
    )

    val jsonREST: JsObject = Json.obj(
      "id" -> JsString("id"),
      "fileName" -> JsString("fileName"),
      "mimeType" -> JsString("type"),
      "url" -> JsString("url"),
      "scanStatus" -> JsString("READY"),
      "lastUpdated" -> JsString("1970-01-01T00:00:00Z")
    )

    "Convert to Mongo JSON" in {
      val value = Json.toJson(model)(FileMetadataMongo.format)
      value.toString() shouldBe jsonMongo.toString()
    }

    "Convert from Mongo JSON" in {
      val value = Json.fromJson[FileMetadata](jsonMongo)(FileMetadataMongo.format).get
      value shouldBe model
    }

    "Convert to REST JSON" in {
      val value = Json.toJson(model)(FileMetadataREST.format)
      value.toString() shouldBe jsonREST.toString()
    }

    "Convert from REST JSON" in {
      val value = Json.fromJson[FileMetadata](jsonREST)(FileMetadataREST.format).get
      value shouldBe model
    }

  }

}
