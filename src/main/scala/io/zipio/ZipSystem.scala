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

import java.net.{URISyntaxException, URI}
import java.nio.charset.Charset
import java.nio.file._
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import com.sun.nio.zipfs.{ZipFileAttributeView, ZipFileAttributes, ZipFileSystem}
import io.zipio.Zip._
import scala.collection.JavaConversions._
import scala.language.postfixOps

/**
 * The top-level actor in the Zip IO system, responsible for handling requests to
 * open zip files so that their content can be accessed.
 */
class ZipSystem extends Actor with ActorLogging {
  import Zip._
  import ZipSystem._

  // val openedZips = Seq[Path]

  val poolConfig = context.system.settings.config.getConfig("akka.io.zip.executor")
  lazy val pool = new ZipThreadPoolConfigurator(poolConfig).pool

  override def preStart(): Unit = {
    log.info("ZipSystem prestarting")
  }

  override def receive: Receive = {
    case OpenZip(uri: URI, env) =>
      println(s"open for ${uri.toString}")
      try {
        val fileURI: URI = uri.getScheme match {
          case "file" => uri

          case "jar" =>
              val spec = uri.getRawSchemeSpecificPart
              val sep = spec.indexOf("!/")
              val u = new URI(if (sep == -1) spec else spec.substring(0, sep))
              if (u.getScheme != "file")
                throw new IllegalArgumentException(s"URI scheme is not '$zipScheme:file'")
              u
          case s @_ =>
            throw new IllegalArgumentException(s"unrecognized URI scheme: $s")
        }
        context.actorOf(Props(new ZipFS(Paths.get(fileURI), env))) ! DoOpen(sender)
      } catch {
        case e: Exception =>
          sender ! ZipOpenFailed(uri, new IllegalArgumentException(s"could not open zip file", e))
      }

    case OpenZip(path: Path, env) =>
      context.actorOf(Props(new ZipFS(path, env))) ! DoOpen(sender)

    case Ping() => sender ! Pinged()

    case req @ _ =>
      sender ! ZipException(req, new Exception("Unrecognized command"))
  } // receive

  /**
   * Actor that fronts for a specific zip file (and, equivalently, manages a specific zip file system),
   * and mechanizes operations on the contents of that zip file/file system.
   *
   * @param path The path designating the (local) zip file.
   * @param env A map containing options describing how the zip file is to be accessed.
   */
  class ZipFS(val path: Path, val env: Map[String,String]) extends Actor with ActorLogging {
    import Zip._
    import ZipSystem._

    var fs: ZipFileSystem = null
    val inProgress = new AtomicInteger(0)
    val uri = zipURI(path)



    override def receive: Receive = {

      case DoOpen(originator) =>
        log.info(s"opening $path")
        try {
          fs = FileSystems.newFileSystem(uri, env).asInstanceOf[ZipFileSystem]
          originator ! ZipOpened(uri, self)
//          log.info(s"open OK")
        } catch {
          case e: FileSystemAlreadyExistsException =>
            try {
              fs = FileSystems.getFileSystem(uri).asInstanceOf[ZipFileSystem]
              originator ! ZipOpened(uri, self)
//              log.info(s"open OK")
            } catch {
              case t: Throwable =>
                originator ! ZipOpenFailed(uri, t)
//                log.error(t, s"open failed")
                context.stop(self)
            }
          case t: Throwable =>
            originator ! ZipOpenFailed(uri, t)
            context.stop(self)
//            log.error(t, s"open failed")
        }

      case req @ SlurpBytes(path) =>
        val fsPath = fs.getPath(path.toString)
        log.info(s"slurping bytes from $fsPath")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePath = path
          val mySelf = self
          inProgress.getAndIncrement()
          def run(): Unit = {
//            log.info(s"slurp running for $fsPath = ${fsPath.getClass.getName}")
            try {
              val bytes = Files.readAllBytes(fsPath)
              originator.!(SlurpedBytes(thePath, bytes, bytes.length))(mySelf)
              val n = inProgress.decrementAndGet()
              log.info(s"slurped ${bytes.length} from $path, $n in progress")
            } catch {
              case e: Exception =>
//                e.printStackTrace()
                val n = inProgress.getAndDecrement()
                log.warning(s"exception on slurp for $fsPath: ${e.getMessage}, $n in progress")
                originator.!(ZipFSRequestFailed(request, e))(mySelf)
            }
          } // run
        })

      case req @ SlurpString(path, encoding) =>
        val fsPath = fs.getPath(path.toString)
        log.info(s"slurping string from $fsPath")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePath = path
          val mySelf = self
          inProgress.getAndIncrement()
          def run(): Unit = {
//            log.info(s"slurp running for $fsPath = ${fsPath.getClass.getName}")
            try {
              val bytes = Files.readAllBytes(fsPath)
              val s = new String(bytes, Charset.forName(encoding))
              originator.!(SlurpedString(thePath, s))(mySelf)
              val n = inProgress.decrementAndGet()
              log.info(s"slurped ${s.length} from $path, $n in progress")
            } catch {
              case e: Exception =>
//                e.printStackTrace()
                val n = inProgress.getAndDecrement()
                log.warning(s"exception on slurp for $fsPath: ${e.getMessage}, $n in progress")
                originator.!(ZipFSRequestFailed(request, e))(mySelf)
            }
          } // run
        })

      case req @ SpewBytes(path, bytes, opts) =>
        val fsPath = fs.getPath(path.toString)
        log.info(s"spewing bytes from $fsPath")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePath = path
          val mySelf = self
          inProgress.getAndIncrement()
          def run(): Unit = {
            //            log.info(s"slurp running for $fsPath = ${fsPath.getClass.getName}")
            try {
              Files.write(fsPath, bytes, StandardOpenOption.CREATE)
              originator.!(ZipFSRequestOK(req))(mySelf)
              val n = inProgress.decrementAndGet()
              log.info(s"spewed ${bytes.length} to $path, $n in progress")
            } catch {
              case e: Exception =>
//                e.printStackTrace()
                val n = inProgress.getAndDecrement()
                log.warning(s"exception on spew for $fsPath: ${e.getMessage}, $n in progress")
                originator.!(ZipFSRequestFailed(req, e))(mySelf)
            }
          } // run
        })

      case req @ SpewString(path, string, charsetName, options) =>
        val fsPath = fs.getPath(path.toString)
        log.info(s"spewing string from $fsPath")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePath = path
          val mySelf = self
          inProgress.getAndIncrement()
          override def run(): Unit = {
            //            log.info(s"slurp running for $fsPath = ${fsPath.getClass.getName}")
            try {
              val bytes = string.getBytes(Charset.forName(charsetName))
              Files.write(fsPath, bytes, options:_*)
              originator.!(ZipFSRequestOK(req))(mySelf)
              val n = inProgress.decrementAndGet()
              log.info(s"spewed ${bytes.length} to $path, $n in progress")
            } catch {
              case e: Exception =>
                //                e.printStackTrace()
                val n = inProgress.getAndDecrement()
                log.warning(s"exception on spew for $fsPath: ${e.getMessage}, $n in progress")
                originator.!(ZipFSRequestFailed(req, e))(mySelf)
            }
          } // run
        })

      case req @ ListDirectory(path) =>
        val fsPath = fs.getPath(path.toString)
        log.info(s"listDirectory $path")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePath = path
          val mySelf = self

          override def run(): Unit = {
            try {
              val ds = (List[Path]() ++ Files.newDirectoryStream(fsPath)).map(
                path => {
                  val pn = path.toString
                  if (pn.endsWith("/"))
                    fs.getPath(pn.substring(0, pn.length - 1))
                  else
                    path
                })
              originator.!(DirectoryList(thePath, ds))(mySelf)
            } catch {
              case e: Exception =>
                val n = inProgress.getAndDecrement()
                log.warning(s"exception on ListDirectory for $fsPath: ${e.getMessage}, $n in progress")
                originator.!(ZipFSRequestFailed(req, e))(mySelf)
            }
          } // run
        })

      case req @ GetFileAttributes(paths) =>
        val fsPaths = paths.map(p =>  fs.getPath(p.toString))
        log.info(s"attributes $paths")
        pool.execute(new Runnable() {
          val request = req
          val originator = sender
          val thePaths = paths
          val mySelf = self

          override def run(): Unit = {
            val attrs = fsPaths.map(
              p =>
                try {
                  Some(Files.getFileAttributeView(p, classOf[ZipFileAttributeView]).readAttributes())
                } catch {
                  case e: Exception => None
                })
            originator.!(FileAttributes(thePaths, attrs))(mySelf)
          } // run
        })

      case req @ CloseZipFS() =>
        log.info(s"closing $path")
        pool.execute(new Runnable() {
          val originator = sender
          def run(): Unit = {
            fs.close()
            log.info(s"closed $path")
          }
        })
        context.stop(self)


    } // receive

  } // class ZipFS


  /**
   * ZipFS companion object.
   */
//  object ZipFS {
//    def apply(path: Path, env: Map[String,String]): ZipFS = new ZipFS(new URI("jar:" + path.toUri.toString + "!/"), env)
//
//    def apply(uri: URI, env: Map[String,String]): ZipFS = new ZipFS(uri, env)
//
//
//  }

  class ZipHandler(val zfs: ZipFS) extends Actor with ActorLogging {

    override def receive: Receive = {

      ???
    }

  }


} // class ZipSystem

object ZipSystem {

  /**
   * Idiot request for test purposes
   */
  private [zipio] case class Ping() extends ZipRequest

  /**
   * Idiot response for test purposes
   */
  private [zipio] case class Pinged() extends ZipResponse

  private [zipio] case class DoOpen(originator: ActorRef)

} // object ZipSystem



