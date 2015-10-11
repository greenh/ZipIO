# ZipIO
ZipIO is an Akka extension for actor-based asynchronous I/O operations for zip and similar
composite file types (jar, war, ...). ZipIO provides requests for
opening and creating zip files, for determining their content, and for
reading, writing, adding, deleting, and updating files within the zip file.

ZipIO represents requests and their corresponding responses as conventional Akka
messages. In most cases, each request executes in a separate thread and in general can
proceed concurrently with respect to other operations.

ZipIO operations take place in a separate execution environment, the characteristics
of which
(executor type, number of threads, etc.) can be configured as required to meet
application needs.

ZipIO is based on the
[[https://docs.oracle.com/javase/7/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
zip file system provider]] developed by Oracle as part of the JDK demos.
