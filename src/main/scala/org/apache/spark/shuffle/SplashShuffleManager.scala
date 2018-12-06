/*
 * Modifications copyright (C) 2018 MemVerge Corp
 *
 * Replace the original shuffle class with Splash version classes.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.shuffle

import java.util.concurrent.ConcurrentHashMap

import com.memverge.splash.StorageFactoryHolder
import org.apache.spark.internal.Logging
import org.apache.spark.shuffle.sort.SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE
import org.apache.spark.shuffle.sort.SplashUnsafeShuffleWriter
import org.apache.spark.{ShuffleDependency, SparkConf, TaskContext}

class SplashShuffleManager(conf: SparkConf) extends ShuffleManager with Logging {
  StorageFactoryHolder.setSparkConf(conf)
  private val numMapsForShuffle = new ConcurrentHashMap[Int, Int]()

  StorageFactoryHolder.onApplicationStart()

  /** @inheritdoc*/
  override lazy val shuffleBlockResolver = new SplashShuffleBlockResolver(
    conf.getAppId,
    conf.get(SplashOpts.shuffleFileBufferKB).toInt)

  /** @inheritdoc*/
  override def registerShuffle[K, V, C](
      shuffleId: Int,
      numMaps: Int,
      dependency: ShuffleDependency[K, V, C]): ShuffleHandle = {
    if (useSerializedShuffle(dependency)) {
      // Try to buffer map outputs in a serialized form, since this
      // is more efficient:
      new SplashSerializedShuffleHandle[K, V](
        shuffleId, numMaps, dependency.asInstanceOf[ShuffleDependency[K, V, V]])
    } else {
      // Buffer map outputs in a deserialized form:
      new BaseShuffleHandle(shuffleId, numMaps, dependency)
    }
  }

  /** @inheritdoc*/
  override def getWriter[K, V](
      handle: ShuffleHandle,
      mapId: Int,
      context: TaskContext): ShuffleWriter[K, V] = {
    numMapsForShuffle.putIfAbsent(
      handle.shuffleId,
      handle.asInstanceOf[BaseShuffleHandle[_, _, _]].numMaps)
    handle match {
      case unsafeShuffleHandle: SplashSerializedShuffleHandle[K@unchecked, V@unchecked] =>
        new SplashUnsafeShuffleWriter(
          shuffleBlockResolver,
          unsafeShuffleHandle,
          mapId,
          context,
          SplashSerializer(unsafeShuffleHandle.dependency))
      case other: BaseShuffleHandle[K@unchecked, V@unchecked, _] =>
        new SplashShuffleWriter(
          shuffleBlockResolver,
          other,
          mapId,
          context)
    }
  }

  /** @inheritdoc*/
  override def getReader[K, C](
      handle: ShuffleHandle,
      startPartition: Int,
      endPartition: Int,
      context: TaskContext): ShuffleReader[K, C] = {
    new SplashShuffleReader(
      shuffleBlockResolver,
      handle.asInstanceOf[BaseShuffleHandle[K, _, C]],
      startPartition,
      endPartition,
      context)
  }

  /** @inheritdoc*/
  override def unregisterShuffle(shuffleId: Int): Boolean = {
    logInfo(s"unregister shuffle $shuffleId of app ${conf.getAppId}")
    Option(numMapsForShuffle.remove(shuffleId)).foreach { numMaps =>
      if (conf.get(SplashOpts.clearShuffleOutput)) {
        logInfo(s"remove shuffle $shuffleId data with $numMaps mappers.")
        (0 until numMaps).foreach { mapId =>
          shuffleBlockResolver.removeDataByMap(shuffleId, mapId)
        }
      }
    }
    true
  }

  /** @inheritdoc*/
  override def stop(): Unit = {
    StorageFactoryHolder.onApplicationEnd()
    if (conf.get(SplashOpts.clearShuffleOutput)) {
      shuffleBlockResolver.cleanup()
    }
    shuffleBlockResolver.stop()
  }

  private def useSerializedShuffle(dependency: ShuffleDependency[_, _, _]): Boolean = {
    val optionKey = "spark.shuffle.mvfs.useBaseShuffle"
    val useBaseShuffle = conf.getBoolean(optionKey, defaultValue = false)
    !useBaseShuffle && canUseSerializedShuffle(dependency)
  }

  /**
   * Helper method for determining whether a shuffle should use an optimized
   * serialized shuffle path or whether it should fall back to the original
   * path that operates on deserialized objects.
   */
  private def canUseSerializedShuffle(dependency: ShuffleDependency[_, _, _]): Boolean = {
    val shufId = dependency.shuffleId
    val numPartitions = dependency.partitioner.numPartitions
    if (!dependency.serializer.supportsRelocationOfSerializedObjects) {
      log.debug(s"Can't use serialized shuffle for shuffle $shufId because the serializer, " +
          s"${dependency.serializer.getClass.getName}, does not support object relocation")
      false
    } else if (dependency.aggregator.isDefined) {
      log.debug(
        s"Can't use serialized shuffle for shuffle $shufId because an aggregator is defined")
      false
    } else if (numPartitions > MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE) {
      log.debug(s"Can't use serialized shuffle for shuffle $shufId because it has more than " +
          s"$MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE partitions")
      false
    } else {
      log.debug(s"Can use serialized shuffle for shuffle $shufId")
      true
    }
  }
}

/**
 * Subclass of [[BaseShuffleHandle]], used to identify when we've chosen to use the
 * serialized shuffle.
 */
private[spark] class SplashSerializedShuffleHandle[K, V](
    shuffleId: Int,
    numMaps: Int,
    dependency: ShuffleDependency[K, V, V])
    extends BaseShuffleHandle(shuffleId, numMaps, dependency) {
}