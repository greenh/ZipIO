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

package io

/**
 * ZipIO is an extension to the [[http://doc.akka.io/api/akka/2.3.14/akka/io/IO$.html
 * Akka I/O layer]] that supports for actor-based asynchronous I/O
 * operations for zip and similar
 * composite files (jar, war, ...) that are located in a locally accessible file system.
 * ZipIO provides requests for
 * opening and creating zip files, for determining their content, and for
 * reading, writing, adding, deleting, and updating files within the zip file.
 *
 * ZipIO represents requests and their corresponding responses as conventional Akka
 * messages, and spawns a hierarchy of actors to process the requests.
 * In most cases, each request executes in a separate thread and in general can
 * proceed concurrently with respect to other operations. Threads for executing requests
 * are drawn from a separate dedicated execution environment, the characteristics of which
 * (executor type, number of threads, etc.) can be configured as required to meet
 * application needs.
 *
 * ZipIO is based on the
 * [[https://docs.oracle.com/javase/7/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
 * zip file system provider]] developed by Oracle as a JDK demo, a version of which is included
 * with ZipIO. Opening a zip file establishes
 * a zip file system corresponding to that file. As it's part of the global JVM environment,
 * this file system can be accessed outside of the ZipIO context, but is of course subject
 * to all the normal caveats regarding concurrent access and blocking behavior.
 *
 */
package object zipio {

}
