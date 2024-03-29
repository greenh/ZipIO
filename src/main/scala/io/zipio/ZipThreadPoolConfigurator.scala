package io.zipio

import com.typesafe.config.Config

/**
 * Configurator for the underlying thread pool used by the Zip IO implementation.
 *
 * This is extracted verbatim from
 * [[https://github.com/drexin/akka-io-file/blob/master/src/main/scala/akka/io/ThreadPoolConfigurator.scala
 * the Scala Async IO implementation]], and we may see our way to using that implementation
 * directly at some point.
 *
 * @param config The configuration to use.
 */
private class ZipThreadPoolConfigurator(config: Config) {
  import java.util.concurrent._
  import akka.dispatch.{MonitorableThreadFactory, ThreadPoolConfig}

  val pool: ExecutorService = config.getString("type") match {
    case "fork-join-executor" => createForkJoinExecutor(config.getConfig("fork-join-executor"))

    case "thread-pool-executor" => createThreadPoolExecutor(config.getConfig("thread-pool-executor"))
  }

  private def createForkJoinExecutor(config: Config) =
    new ForkJoinPool(
      ThreadPoolConfig.scaledPoolSize(
        config.getInt("parallelism-min"),
        config.getDouble("parallelism-factor"),
        config.getInt("parallelism-max")),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      MonitorableThreadFactory.doNothing, true)

  private def createThreadPoolExecutor(config: Config) = {
    def createQueue(tpe: String, size: Int): BlockingQueue[Runnable] = tpe match {
      case "array"        => new ArrayBlockingQueue[Runnable](size, false)
      case "" | "linked"  => new LinkedBlockingQueue[Runnable](size)
      case x              => throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!" format x)
    }

    val corePoolSize = ThreadPoolConfig.scaledPoolSize(config.getInt("core-pool-size-min"), config.getDouble("core-pool-size-factor"), config.getInt("core-pool-size-max"))
    val maxPoolSize = ThreadPoolConfig.scaledPoolSize(config.getInt("max-pool-size-min"), config.getDouble("max-pool-size-factor"), config.getInt("max-pool-size-max"))

    new ThreadPoolExecutor(
      corePoolSize,
      maxPoolSize,
      config.getDuration("keep-alive-time", TimeUnit.MILLISECONDS),
      TimeUnit.MILLISECONDS,
      createQueue(config.getString("task-queue-type"), config.getInt("task-queue-size"))
    )
  }
} // ZipThreadPoolConfigurator


