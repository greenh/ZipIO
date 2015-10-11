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
import java.nio.file.{FileSystem, Files, Paths}

import akka.actor.{ActorRef, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import com.sun.nio.zipfs.ZipFileAttributes
import com.typesafe.config.{ConfigFactory, Config}
import io.zipio.Zip._
import org.scalatest.{BeforeAndAfterAll, Inspectors, MustMatchers, WordSpecLike}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


object ZipGoop {

  val testConfig = """
    akka.io.zip.test {
      testVictim = "src/test/data/zip1.zip"
      testDir = "src/test/data"

      createVictim = "testdata/xxx.zip"

    }

  """

}


class ZipIOSpec extends TestKit(ActorSystem("TSSpec"))
    with ImplicitSender with WordSpecLike with MustMatchers with Inspectors
    with BeforeAndAfterAll {

  import ZipGoop._

  val config = ConfigFactory.load().withFallback(ConfigFactory.parseString(testConfig))

  val victimPath = Paths.get(config.getString("akka.io.zip.test.testVictim")).toAbsolutePath
  val victimURI = victimPath.toUri
  val sourceDir = config.getString("akka.io.zip.test.testDir")
  val emptyEnv = Map[String,String]()

  val createPath = Paths.get(config.getString("akka.io.zip.test.createVictim")).toAbsolutePath
  val createURI = createPath.toUri


  "Zip IO" should {
    "create a ZipSystem actor" in {
      import io.zipio.ZipSystem._

      val sys = ActorSystem("xx")
      val zip = IO(Zip)(sys)
      println(s"Path: ${zip.path.name}")
      assert(zip.path.name.endsWith(Zip.systemName))
      zip ! Ping()
      expectMsg(Pinged())
      sys.shutdown()
    }
  }

  val zipSystem = IO(Zip)

  "A ZipSystem" should {
    "reject an invalid request" in {
      zipSystem ! "poo"
      expectMsgType[ZipException](1 second)
    }
    "return an error for an open request containing an invalid URI" in {
      zipSystem ! OpenZip(new URI("http://poo/doo.zip"), emptyEnv)
      val e = expectMsgType[ZipOpenFailed](1 second)
//      expectTerminated(lastSender)
    }
    "return an error for an open request to a nonexistent zip file" in {
      zipSystem ! OpenZip(Paths.get(sourceDir, "NotThere.zip").toUri, emptyEnv)
      val e = expectMsgType[ZipOpenFailed](1 second)
//      expectTerminated(lastSender)
    }
    "return an error for an open request for a non-zip file" in {
      zipSystem ! OpenZip(Paths.get(sourceDir, "zip1/A.txt").toUri, emptyEnv)
      val e = expectMsgType[ZipOpenFailed](1 second)
//      expectTerminated(lastSender)
    }
//    "return an error for an open request for a non-readable zip file" in { }

    "open and close a zip file by file URI" in {
      val zipFS = zipSystem ! OpenZip(victimURI, emptyEnv)
      val ZipOpened(_, fs) = expectMsgType[ZipOpened](1 second)
      watch(fs)
      fs ! CloseZipFS()
      expectTerminated(fs, 1 second)
    }
    "open and close a zip file by path" in {
      val zipFS = zipSystem ! OpenZip(victimPath, emptyEnv)
      val ZipOpened(_, fs) = expectMsgType[ZipOpened](1 second)
      watch(fs)
      fs ! CloseZipFS()
      expectTerminated(fs, 1 second)
    }

    "create and close a new zip file by URI" in {
      Files.deleteIfExists(createPath)
      val zipFS = zipSystem ! OpenZip(createURI, emptyEnv + ("create" -> "true"))
      val ZipOpened(p, fs) = expectMsgType[ZipOpened](1 second)
//      p must equal (createPath)
      watch(fs)
      fs ! CloseZipFS()
      expectTerminated(fs, 1 second)
      Files.isReadable(createPath) must be (true)
    }

    "create and close a new zip file by path" in {
      Files.deleteIfExists(createPath)
      val zipFS = zipSystem ! OpenZip(createPath, emptyEnv + ("create" -> "true"))
      val ZipOpened(p, fs) = expectMsgType[ZipOpened](1 second)
//      p must equal (createPath)
      watch(fs)
      fs ! CloseZipFS()
      expectTerminated(fs, 1 second)
      Files.isReadable(createPath) must be (true)
    }
    
  }


  var zipFS: ActorRef = ActorRef.noSender

  "A ZipFS" should {
    "slurp the content of a file as bytes" in {
      val fut = zipSystem.ask(OpenZip(victimPath, emptyEnv))(2 seconds)
      val ZipOpened(_, z) = Await.result(fut, 2 seconds)
      zipFS = z

      val victim = "zip1/A.txt"
      zipFS ! SlurpBytes(Paths.get("/", victim))
      val oBytes = Files.readAllBytes(Paths.get(sourceDir, victim))
//      println(s"read ${oBytes.length} bytes for source")
      val resp = expectMsgType[SlurpedBytes](2 seconds)
      val SlurpedBytes(p, bytes, len) = resp
//      println(s"received ${bytes.length} = $len bytes")
      oBytes must equal (bytes)
    }

    "return an error when byte-slurping a nonexistent file" in {
      val victim = "zip1/XXX.txt"
      zipFS ! SlurpBytes(Paths.get("/", victim))
      val resp = expectMsgType[ZipFSRequestFailed](2 seconds)

    }

    "slurp the content of a file as a string" in {
      val victim = "zip1/A.txt"
      zipFS ! SlurpString(Paths.get("/", victim), "UTF-8")
      val oStr = new String(Files.readAllBytes(Paths.get(sourceDir, victim)), "UTF-8")
      val resp = expectMsgType[SlurpedString](2 seconds)
      val SlurpedString(p, str) = resp
      oStr must equal (str)
    }

    "generate an error when string-slurping a nonexistent file" in {
      val victim = "zip1/XXX.txt"
      zipFS ! SlurpString(Paths.get("/", victim), "UTF-8")
      val resp = expectMsgType[ZipFSRequestFailed](2 seconds)
    }

    "generate an error when string-slurping an invalid encoding" in {
      val victim = "zip1/XXX.txt"
      zipFS ! SlurpString(Paths.get("/", victim), "Garbage")
      val resp = expectMsgType[ZipFSRequestFailed](2 seconds)
    }

    "slurp multiple files concurrently" in {
      val results = Seq("A","B","C","D").map(
        id => {
          val fsPath = Paths.get(s"/zip1/$id.txt")
          val bytes = Files.readAllBytes(Paths.get(sourceDir, s"zip1/$id.txt"))
          (fsPath -> bytes)
        }).toMap
      results.foreach { case (path, _) => zipFS ! SlurpBytes(path) }
      val responses = expectMsgAllClassOf[SlurpedBytes](3 seconds, Array.fill(results.size)(classOf[SlurpedBytes]): _*)

      responses.foreach {
        case SlurpedBytes(path, bytes, len) =>
          results must contain key (path)
          results.get(path).get must equal (bytes)
          bytes.length must equal (len)
      }
    }



    "list the content of the root directory" in {

      zipFS ! ListDirectory(Paths.get("/"))
      val DirectoryList(path, paths) = expectMsgType[DirectoryList](2 seconds)
      println(s"paths: $paths")
      path must equal (Paths.get("/"))
      paths.map(_.toString) must contain theSameElementsAs Seq("/zip1")
    }

    "list the content of a non-root directory" in {
      zipFS ! ListDirectory(Paths.get("/zip1"))
      val DirectoryList(path, paths) = expectMsgType[DirectoryList](2 seconds)
      path must equal (Paths.get("/zip1"))
      paths.map(_.toString) must contain theSameElementsAs Seq("A.txt","B.txt","C.txt","D.txt","XXX").map("/zip1/" + _)
    }

    "generate an error when listing a nonexistent directory" in {
      zipFS ! ListDirectory(Paths.get("/NotThere"))
      val result = expectMsgType[ZipFSRequestFailed](2 seconds)
    }

    "generate an error when listing a non-directory" in {
      zipFS ! ListDirectory(Paths.get("/zip1/A.txt"))
      expectMsgType[ZipFSRequestFailed](2 seconds)
    }

    "return the attributes of a file" in {
      zipFS ! GetFileAttributes(Paths.get("/zip1/A.txt"))
      val FileAttributes(Seq(path), Seq(attrs)) = expectMsgType[FileAttributes](2 seconds)
      path must equal (Paths.get("/zip1/A.txt"))
      val fa = attrs.head
      fa.size must equal (191)
      fa.asInstanceOf[ZipFileAttributes].compressedSize must equal (104)
    }

    "generate a null response when retrieving attributes of a nonexistent file" in {
      val p = Paths.get("/NotThere.txt")
      zipFS ! GetFileAttributes(p)
      val FileAttributes(Seq(path), Seq(attrs)) = expectMsgType[FileAttributes](2 seconds)
      path must equal (p)
      attrs must equal (None)
    }

    "return a sequence of attributes of multiple (possibly nonexistent) files" in {
      val paths = Seq("A","B","C","D","XXX","NotThere").map(Paths.get("/zip1", _))
      zipFS ! GetFileAttributes(paths)
      val FileAttributes(ps, as) = expectMsgType[FileAttributes](2 seconds)
      ps must equal (paths)
      // todo check other attrs
      as.last must equal (None)
    }

    "close an open zipFS" in {
      watch(zipFS)
      zipFS ! CloseZipFS()
      expectTerminated(zipFS, 1 second)
    }

    "create a new zip file" in { }

    "spew a string to a new zip file" in { }

    "spew bytes to a new zip file" in { }

    "spew then slurp multiple concurrent files to a new zip file" in { }

    "open an existing zip file for update" in { }

    "delete a file from an update zip file" in { }

    "replace a file in an update zip file" in { }




  }

}
