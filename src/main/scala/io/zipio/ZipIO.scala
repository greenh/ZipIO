/*
 *   Copyright (c) 2015 Howard Green. All rights reserved.
 *
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 *   the terms of this license.
 *
 *   You must not remove this notice, or any other, from this software.
 */

package io.zipio

import java.net.URI
import java.nio.file.{Paths, OpenOption, Path}
import java.nio.file.attribute.FileAttribute

import akka.actor._
import akka.io.IO
import com.sun.nio.zipfs.ZipFileAttributes

/**
 * Zip IO system interface, notably containing the classes used for communicating
 * with the actors within the system.
 *
 * There are (so far) two major types of actor involved:
 *
 * - A single [[ZipSystem]] actor is at the top level of the Zip hierarchy.
 * `IO(ZipIO)` returns the reference to this actor. It accepts requests
 * derived from the [[io.zipio.Zip.ZipRequest]] trait open zip files,
 * and spawns a [[ZipSystem.ZipFS]] actor for each zip file.
 *
 * - Each [[ZipSystem.ZipFS]] actor constitutes the point of access to a
 * single zip file. A [[ZipSystem.ZipFS]] actor responds to [[Zip.ZipFSRequest]]
 * derived requests for purposes such as reading or writing files
 * contained within the zip file. Generally speaking, it processes
 * such requests concurrently, and responds to them with messages
 * derived from [[Zip.ZipFSResponse]].
 */
object Zip extends ExtensionId[ZipExt] with ExtensionIdProvider {


  /**
   * Trait for all requests to the zip system actor
   */
  trait ZipRequest

  case class OpenZip(val what: AnyRef, val env: Map[String,String]) extends ZipRequest

  object OpenZip {
    def apply(path: Path, env: Map[String,String]): OpenZip = new OpenZip(path, env)
    def apply(uri: URI, env: Map[String,String]): OpenZip = new OpenZip(uri, env)
  }

//  /**
//   * Request to open a zip file specified by its path.
//   * @param zipFilePath The path of a putative zip file.
//   * @param env
//   */
//  case class OpenZipPath(zipFilePath: Path, env: Map[String,String]) extends ZipRequest
//
//  /**
//   * Request to open a zip file specified by a URI.
//   * @param zipFileURI The URI of the zip file.
//   *
//   * This can be either a standard "file://..." URI, or a jar-format URI, where
//   * a file URI is wrapped by "jar:<file uri>!/"
//   * @param env
//   */
//  case class OpenZipURI(zipFileURI: URI, env: Map[String,String]) extends ZipRequest

  trait ZipResponse

  case class ZipOpened(uri: URI, zipper: ActorRef) extends ZipResponse

  /**
   * Failure indication for a zip file open request.
   * @param uri
   * @param cause
   */
  case class ZipOpenFailed(uri: URI, cause: Throwable) extends ZipResponse

  /**
   * Trait common to all requests delivered to a [[io.zipio.ZipSystem.ZipFS]] actor.
   *
   * These requests all
   * represent file system-level operations, like retrieving directory content or
   * attributes of files, retrieving file content, and opening streams on
   * individual files.
   * Or, if we get to a state of affairs that allows zip file modification, these
   * would include deleting, renaming, adding files, opening output streams,
   * and the like.
   */
  trait ZipFSRequest

  /**
   * Trait common to all responses made to [[ZipFSRequest]]s.
   */
  trait ZipFSResponse


  //  case class GetRootDirectories() extends ZipFSRequest

  /**
   * Given the path of a directory, returns a list of paths of children of
   * that directory.
   * @param dirPath The path of the directory to list.
   */
  case class ListDirectory(dirPath: Path) extends ZipFSRequest

  /**
   * Requests the sizes of the files designated by a sequence of paths.
   * @param filePaths A sequence path of the file of interest.
   */
  case class GetFileAttributes(filePaths: Seq[Path]) extends ZipFSRequest

  object GetFileAttributes {
    def apply(path: Path) = new GetFileAttributes(Seq(path))
  }

  /**
   * Requests that a specified file be read, and its complete content be returned
   * as a byte array. The normal response is a [[SlurpedBytes]] message.
   * @param path The path of the file to slurp.
   */
  case class SlurpBytes(path: Path) extends ZipFSRequest

  case class SpewBytes(path: Path, bytes: Array[Byte], options: Array[OpenOption]) extends ZipFSRequest


  /**
   * Requests that the complete content of a specified be read, and the content
   * decoded and returned as a string.
   * @param path The path of the file to be read.
   * @param encoding The name of the character set (e.g. "UTF-8") to use for
   *                 encoding the string
   */
  case class SlurpString(path: Path, encoding: String) extends ZipFSRequest

  /**
   * Writes a string as the complete content of a file to the zip file.
   * @param path The name of the file to write
   * @param string The string containing the file's data.
   * @param encoding A string containing the name of the character set to be used to encode
   *                    the string.
   * @param options An array of options for the
   */
  case class SpewString(path: Path, string: String, encoding: String, options: Array[OpenOption])  extends ZipFSRequest


  /**
   * Closes a ZipFS session, and terminates the actor that mechanizes it. Note
   * that there will be *NO RESPONSE* to this request.
   */
  case class CloseZipFS() extends ZipFSRequest



//  /**
//   * Response to a [[GetRootDirectories]] requiest.
//   * @param dirs A list of the paths of root directories for the zip file system.
//   */
//  case class RootDirectories(dirs: List[Path]) extends ZipFSResponse

  /**
   * Response to a [[ListDirectory]] request.
   * @param dir The path of the requested directory.
   * @param content A list of paths of the children of the directory
   */
  case class DirectoryList(dir: Path, content: List[Path]) extends ZipFSResponse

  case class FileAttributes(path: Seq[Path], attrs: Seq[Option[ZipFileAttributes]]) extends ZipFSResponse

  case class SlurpedBytes(path: Path, content: Array[Byte], bytes: Long) extends ZipFSResponse
  case class SlurpedString(path: Path, content: String)  extends ZipFSResponse
  
  // case class ZipFSClosed()

  /**
   * Response returned by successfully completed requests that don't return any
   * additional information.
   * @param request The originating request.
   */
  case class ZipFSRequestOK(request: ZipFSRequest) extends ZipFSResponse

  /**
   * Response that signals that a requested command failed.
   * @param request The failed command, as originally sent.
   * @param cause The Throwable that describes the failure.
   */
  case class ZipFSRequestFailed(request: ZipFSRequest, cause: Throwable) extends ZipFSResponse


  /**
   * General-purpose exception reporting message.
   * @param cmd The request that caused the exception.
   * @param cause The exception.
   */
  case class ZipException(cmd: Any, cause: Throwable)



  val zipScheme = "jar"

  /**
   * COnverts the URI of a locally accessible zip/jar file into a URI with the jar URI , which must be the URI of a local
   * zip file file.
   * @param uri The URI of a locally accessible file.
   * @return The new
   */
  def zipURI(uri: URI): URI = {
    if (uri.getScheme != "file")
      throw new IllegalArgumentException(s"URI scheme is not 'file'")
    new URI(zipScheme + ":" + uri.toString + "!/")
  }
  def zipURI(path: Path): URI = zipURI(path.toUri)

  /*
    Extension boilerplate
   */

  override def lookup() = Zip

  override def createExtension(system: ExtendedActorSystem): ZipExt = new ZipExt(system)

  override def get(system: ActorSystem): ZipExt = super.get(system)

  val systemName = "IO-ZIP"

} // ZipIO

/**
 * The Zip extension object for Akka IO.
 *
 * The only function this performs is to fire off a [[ZipSystem]] actor.
 * @param system The actor system to be used for the zip thingy
 */
class ZipExt(system: ExtendedActorSystem) extends IO.Extension {
  val manager: ActorRef = {

    system.actorOf(
      props = Props(classOf[ZipSystem]).withDeploy(Deploy.local),
      name = Zip.systemName)
//    system.asInstanceOf[ActorSystemImpl].systemActorOf(
//      props = Props(classOf[ZipSystem]).withDeploy(Deploy.local),
//      name = "IO-ZIP")
  }
} // ZipExt



