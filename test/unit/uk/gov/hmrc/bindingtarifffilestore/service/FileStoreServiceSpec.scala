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

package uk.gov.hmrc.bindingtarifffilestore.service

import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.{never, reset, times, verify, verifyNoMoreInteractions, verifyZeroInteractions, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import play.api.libs.Files.TemporaryFile
import uk.gov.hmrc.bindingtarifffilestore.audit.AuditService
import uk.gov.hmrc.bindingtarifffilestore.config.{AppConfig, FileStoreSizeConfiguration}
import uk.gov.hmrc.bindingtarifffilestore.connector.{AmazonS3Connector, UpscanConnector}
import uk.gov.hmrc.bindingtarifffilestore.model.upscan._
import uk.gov.hmrc.bindingtarifffilestore.model.{FileMetadata, FileWithMetadata, ScanStatus}
import uk.gov.hmrc.bindingtarifffilestore.repository.FileMetadataRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.duration._
import scala.concurrent.Future.successful

class FileStoreServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  private val config = mock[AppConfig]
  private val s3Connector = mock[AmazonS3Connector]
  private val repository = mock[FileMetadataRepository]
  private val upscanConnector = mock[UpscanConnector]
  private val auditService = mock[AuditService]

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val service = new FileStoreService(config, s3Connector, repository, upscanConnector, auditService)

  private final val emulatedFailure = new RuntimeException("Emulated failure.")

  override protected def afterEach(): Unit = {
    super.afterEach()
    reset(config, s3Connector, repository, upscanConnector, auditService)
  }

  "Service 'delete all' " should {

    "Clear the Database & File Store" in {
      when(repository.deleteAll()).thenReturn(successful(()))

      await(service.deleteAll()) shouldBe ((): Unit)

      verify(repository).deleteAll()
      verify(s3Connector).deleteAll()
    }

    "Propagate any error" in {
      when(repository.deleteAll()).thenThrow(emulatedFailure)

      val caught = intercept[RuntimeException] {
        await(service.deleteAll())
      }
      caught shouldBe emulatedFailure
    }
  }

  "Service 'delete one' " should {

    "Clear the Database & File Store" in {
      when(repository.delete("id")).thenReturn(successful(()))

      await(service.delete("id")) shouldBe ((): Unit)

      verify(repository).delete("id")
      verify(s3Connector).delete("id")
    }

    "Propagate any error" in {
      when(repository.deleteAll()).thenThrow(emulatedFailure)

      val caught = intercept[RuntimeException] {
        await(service.deleteAll())
      }
      caught shouldBe emulatedFailure
    }
  }

  "Service 'getAll by id' " should {

    "Delegate to Connector" in {
      val attachment = mock[FileMetadata]
      val attachmentSigned = mock[FileMetadata]

      given(attachment.published).willReturn(true)
      given(repository.get("id")).willReturn(successful(Some(attachment)))
      given(s3Connector.sign(attachment)).willReturn(attachmentSigned)

      await(service.getById("id")) shouldBe Some(attachmentSigned)
    }

    "Not sign unpublished files" in {
      val attachment = mock[FileMetadata]
      given(repository.get("id")).willReturn(successful(Some(attachment)))

      await(service.getById("id")) shouldBe Some(attachment)

      verify(s3Connector, never()).sign(any[FileMetadata])
    }
  }

  "Service 'getAll by id list' " should {

    "return empty when no ids are requested" in {
      given(repository.get(Seq.empty)).willReturn(successful(Seq.empty))

      await(service.getByIds(Seq())) shouldBe Seq.empty
    }

    "return empty when no ids are found on the db" in {
      given(repository.get(Seq("unknownFile"))).willReturn(successful(Seq.empty))

      await(service.getByIds(Seq("unknownFile"))) shouldBe Seq.empty
    }

    "return all attachment requested already signed" in {
      val attachment1 = mock[FileMetadata]
      val attSigned1 = mock[FileMetadata]

      val attachment2 = mock[FileMetadata]
      val attSigned2 = mock[FileMetadata]

      given(attachment1.published).willReturn(true)
      given(s3Connector.sign(attachment1)).willReturn(attSigned1)

      given(attachment2.published).willReturn(true)
      given(s3Connector.sign(attachment2)).willReturn(attSigned2)

      given(repository.get(Seq("filename_1","filename_2"))).willReturn(successful(Seq(attachment1,attachment2)))

      await(service.getByIds(Seq("filename_1","filename_2"))) shouldBe Seq(attSigned1,attSigned2)
    }
  }

  "Service 'upload' " should {

    "Delegate to Connector" in {
      val file = mock[TemporaryFile]
      val fileMetadata = FileMetadata(id = "id", fileName = "file", mimeType = "text/plain")
      val fileWithMetadata = FileWithMetadata(file, fileMetadata)
      val fileMetaDataCreated = mock[FileMetadata]
      val uploadTemplate = mock[UploadRequestTemplate]
      val initiateResponse = UpscanInitiateResponse("ref", uploadTemplate)

      given(config.fileStoreSizeConfiguration).willReturn(FileStoreSizeConfiguration(1, 1000))
      given(repository.insert(fileMetadata)).willReturn(successful(fileMetaDataCreated))
      given(upscanConnector.initiate(any[UploadSettings])(any[HeaderCarrier])).willReturn(successful(initiateResponse))

      await(service.upload(fileWithMetadata)) shouldBe fileMetaDataCreated

      eventually(timeout(2.seconds), interval(10.milliseconds)) {
        verify(auditService, times(1)).auditUpScanInitiated(fileId = "id", fileName = "file", upScanRef = "ref")
        verifyNoMoreInteractions(auditService)
      }
    }
  }

  "Service 'notify' " should {

    "Update the attachment for Successful Scan and Delegate to Connector" in {
      val attachment = FileMetadata(id = "id", fileName = "file", mimeType = "type")
      val attachmentUpdated = mock[FileMetadata]("updated")
      val scanResult = SuccessfulScanResult("ref", "url", mock[UploadDetails])
      val expectedAttachment = attachment.copy(url = Some("url"), scanStatus = Some(ScanStatus.READY))

      given(repository.update(expectedAttachment)).willReturn(successful(Some(attachmentUpdated)))

      await(service.notify(attachment, scanResult)) shouldBe Some(attachmentUpdated)

      verify(auditService, times(1)).auditFileScanned(fileId = "id", fileName = "file", upScanRef = "ref", upScanStatus = "READY")
      verifyNoMoreInteractions(auditService)
    }

    "Call publish when notifying published files" in {
      val attachment = mock[FileMetadata]("Attachment")
      val scanResult = SuccessfulScanResult("ref", "url", mock[UploadDetails])
      val attachmentUpdating = mock[FileMetadata]("AttachmentUpdating")
      val attachmentUpdated = mock[FileMetadata]("AttachmentUpdated")
      val attachmentUploaded = mock[FileMetadata]("AttachmentUploaded")
      val attachmentUploadedUpdated = mock[FileMetadata]("AttachmentUploadedAndUpdated")
      val attachmentSigned = mock[FileMetadata]("AttachmentSigned")

      given(attachment.copy(scanStatus = Some(ScanStatus.READY), url = Some("url"))).willReturn(attachmentUpdating)
      given(attachment.published).willReturn(true)
      given(attachment.id).willReturn("id")
      given(attachment.fileName).willReturn("file")

      given(attachmentUpdating.published).willReturn(true)

      given(attachmentUpdated.published).willReturn(true)
      given(attachmentUpdated.scanStatus).willReturn(Some(ScanStatus.READY))
      given(attachmentUpdated.id).willReturn("id")
      given(attachmentUpdated.fileName).willReturn("file")

      given(attachmentUploaded.published).willReturn(true)

      given(attachmentUploadedUpdated.published).willReturn(true)

      given(repository.update(attachmentUpdating)).willReturn(successful(Some(attachmentUpdated)))
      given(s3Connector.upload(attachmentUpdated)).willReturn(attachmentUploaded)
      given(repository.update(attachmentUploaded)).willReturn(successful(Some(attachmentUploadedUpdated)))
      given(s3Connector.sign(attachmentUploadedUpdated)).willReturn(attachmentSigned)

      await(service.notify(attachment, scanResult)) shouldBe Some(attachmentSigned)

      verify(auditService, times(1)).auditFileScanned(fileId = "id", fileName = "file", upScanRef = "ref", upScanStatus = "READY")
      verify(auditService, times(1)).auditFilePublished(fileId = "id", fileName = "file")
      verifyNoMoreInteractions(auditService)
    }

    "Update the attachment for Failed Scan and Delegate to Connector" in {
      val attachment = FileMetadata(id = "id", fileName = "file", mimeType = "type")
      val scanResult = FailedScanResult("ref", mock[FailureDetails])
      val expectedAttachment = attachment.copy(scanStatus = Some(ScanStatus.FAILED))
      val attachmentUpdated = mock[FileMetadata]("updated")

      given(repository.update(expectedAttachment)).willReturn(successful(Some(attachmentUpdated)))

      await(service.notify(attachment, scanResult)) shouldBe Some(attachmentUpdated)

      verify(auditService, times(1)).auditFileScanned(fileId = "id", fileName = "file", upScanRef = "ref", upScanStatus = "FAILED")
      verifyNoMoreInteractions(auditService)
    }

  }

  "Service 'publish' " should {

    "Delegate to the File Store if Scanned Safe" in {
      val fileUploading = mock[FileMetadata]("Uploading")
      val fileUploaded = mock[FileMetadata]("Uploaded")
      val fileUpdating = mock[FileMetadata]("Updating")
      val fileUpdated = mock[FileMetadata]("Updated")
      val fileSigned = mock[FileMetadata]("Signed")

      given(fileUploading.scanStatus).willReturn(Some(ScanStatus.READY))
      given(fileUploading.id).willReturn("id")
      given(fileUploading.fileName).willReturn("file")

      given(fileUploaded.copy(published = true)).willReturn(fileUpdating)

      given(fileUpdated.published).willReturn(true)

      given(s3Connector.upload(fileUploading)).willReturn(fileUploaded)
      given(repository.update(any[FileMetadata])).willReturn(successful(Some(fileUpdated)))
      given(s3Connector.sign(fileUpdated)).willReturn(fileSigned)

      await(service.publish(fileUploading)) shouldBe Some(fileSigned)

      verify(auditService, times(1)).auditFilePublished(fileId = "id", fileName = "file")
      verifyNoMoreInteractions(auditService)
    }

    "Not delegate to the File Store if Scanned UnSafe" in {
      val fileUploading = mock[FileMetadata]("Uploading")
      val fileUpdating = mock[FileMetadata]("Updating")
      val fileUpdated = mock[FileMetadata]("Updated")

      given(fileUploading.scanStatus).willReturn(Some(ScanStatus.FAILED))
      given(fileUploading.copy(published = true)).willReturn(fileUpdating)

      given(repository.update(any[FileMetadata])).willReturn(successful(Some(fileUpdated)))

      await(service.publish(fileUploading)) shouldBe Some(fileUpdated)

      verifyZeroInteractions(auditService, s3Connector)
    }

    "Not delegate to the File Store if Unscanned" in {
      val fileUploading = mock[FileMetadata]("Uploading")
      val fileUpdating = mock[FileMetadata]("Updating")
      val fileUpdated = mock[FileMetadata]("Updated")

      given(fileUploading.scanStatus).willReturn(None)
      given(fileUploading.copy(published = true)).willReturn(fileUpdating)

      given(repository.update(any[FileMetadata])).willReturn(successful(Some(fileUpdated)))

      await(service.publish(fileUploading)) shouldBe Some(fileUpdated)

      verifyZeroInteractions(auditService, s3Connector)
    }
  }

}
