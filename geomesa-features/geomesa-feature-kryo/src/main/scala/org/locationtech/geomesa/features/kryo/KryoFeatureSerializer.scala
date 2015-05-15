/*
 * Copyright (c) 2013-2015 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.features.kryo

import java.util.{Date, UUID}

import com.esotericsoftware.kryo.io.{Input, Output}
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.Geometry
import org.locationtech.geomesa.features.SerializationOption.SerializationOptions
import org.locationtech.geomesa.features._
import org.locationtech.geomesa.features.kryo.serialization.{KryoReader, KryoWriter}
import org.locationtech.geomesa.features.serialization.{CacheKeyGenerator, ObjectType}
import org.locationtech.geomesa.utils.cache.{SoftThreadLocal, SoftThreadLocalCache}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.collection.JavaConversions._

class KryoFeatureSerializer(sft: SimpleFeatureType, val options: SerializationOptions = SerializationOptions.none)
    extends SimpleFeatureSerializer with SimpleFeatureDeserializer with Logging {

  import KryoFeatureSerializer._

  protected[kryo] val cacheKey = CacheKeyGenerator.cacheKeyForSFT(sft)
  protected[kryo] val numAttributes = sft.getAttributeCount

  protected[kryo] val writers = getWriters(cacheKey, sft)
  protected[kryo] val readers = getReaders(cacheKey, sft)

  override def serialize(sf: SimpleFeature): Array[Byte] = doWrite(sf)
  override def lazyDeserialize(bytes: Array[Byte], reusableFeature: SimpleFeature = null): SimpleFeature = {
    val sf = if (reusableFeature == null) {
      new KryoBufferSimpleFeature(sft, readers)
    } else {
      try {
        reusableFeature.asInstanceOf[KryoBufferSimpleFeature]
      } catch {
        case e: Exception =>
          logger.warn(s"Reusable feature must be of type ${classOf[KryoBufferSimpleFeature]}")
          new KryoBufferSimpleFeature(sft, readers)
      }
    }
    sf.setBuffer(bytes)
    sf
  }
  override def deserialize(bytes: Array[Byte]): SimpleFeature = doRead(bytes)
  override def extractFeatureId(bytes: Array[Byte]): String = {
    val input = getInput(bytes)
    input.setPosition(5) // skip version and offsets
    input.readString()
  }

  private val doWrite: (SimpleFeature) => Array[Byte] = if (options.withUserData) writeWithUserData else write
  private val doRead: (Array[Byte]) => SimpleFeature = if (options.withUserData) readWithUserData else read

  protected[kryo] def write(sf: SimpleFeature): Array[Byte] = writeSf(sf).toBytes

  protected[kryo] def writeWithUserData(sf: SimpleFeature): Array[Byte] = {
    val out = writeSf(sf)
    kryoWriter.writeGenericMap(out, sf.getUserData)
    out.toBytes
  }

  protected[kryo] def writeSf(sf: SimpleFeature): Output = {
    val offsets = getOffsets(cacheKey, numAttributes)
    val output = getOutput()
    output.writeInt(VERSION, true)
    assert(output.position() == 1, "VERSION TOOK TOO MUCH SPACE") // TODO
    output.setPosition(5) // leave 4 bytes to write the offsets
    output.writeString(sf.getID)  // TODO optimize for uuids?
    var i = 0
    while (i < numAttributes) {
      offsets(i) = output.position()
      writers(i)(output, sf.getAttribute(i))
      i += 1
    }
    i = 0
    val offsetStart = output.position()
    while (i < numAttributes) {
      output.writeInt(offsets(i), true)
      i += 1
    }
    val total = output.position()
    output.setPosition(1)
    output.writeInt(offsetStart)
    output.setPosition(total)
    output
  }

  protected[kryo] def read(bytes: Array[Byte]): SimpleFeature = readSf(bytes)._1

  protected[kryo] def readWithUserData(bytes: Array[Byte]): SimpleFeature = {
    val (sf, input) = readSf(bytes)
    // skip offset data
    var i = 0
    while (i < numAttributes) {
      input.readInt(true)
      i += 1
    }
    val ud = kryoReader.readGenericMap(VERSION)(input)
    sf.getUserData.putAll(ud)
    sf
  }

  protected[kryo] def readSf(bytes: Array[Byte]): (SimpleFeature, Input) = {
    val input = getInput(bytes)
    input.setPosition(5) // skip version and offsets //TODO versions
    val id = input.readString()
    val attributes = Array.ofDim[AnyRef](numAttributes)
    var i = 0
    while (i < numAttributes) {
      attributes(i) = readers(i)(input)
      i += 1
    }
    (new ScalaSimpleFeature(id, sft, attributes), input)
  }
}

/**
 * @param original the simple feature type that was encoded
 * @param projected the simple feature type to project to when decoding
 * @param options the options what were applied when encoding
 */
class ProjectingKryoFeatureDeserializer(original: SimpleFeatureType,
                                        projected: SimpleFeatureType,
                                        options: SerializationOptions = SerializationOptions.none)
    extends KryoFeatureSerializer(original, options) {

  import KryoFeatureSerializer._

  private val numProjectedAttributes = projected.getAttributeCount

  // TODO we can optimize this some
  override protected[kryo] def readSf(bytes: Array[Byte]): (SimpleFeature, Input) = {
    val input = getInput(bytes)
    input.setPosition(5) // skip version and offsets //TODO versions
    val id = input.readString()
    val attributes = Array.ofDim[AnyRef](numProjectedAttributes)
    var i = 0
    while (i < numAttributes) {
      val index = projected.indexOf(original.getDescriptor(i).getLocalName)
      if (index != -1) {
        attributes(index) = readers(i)(input)
      } else {
        readers(i)(input) // skip entry
      }
      i += 1
    }
    (new ScalaSimpleFeature(id, projected, attributes), input)
  }
}

object KryoFeatureSerializer {

  val VERSION = 2
  assert(VERSION < 8, "Serialization expects version to be in one byte")

  val NULL_BYTE     = 0.asInstanceOf[Byte]
  val NON_NULL_BYTE = 1.asInstanceOf[Byte]

  private[this] val inputs = new SoftThreadLocal[Input]()
  private[this] val outputs = new SoftThreadLocal[Output]()
  private[this] val readers = new SoftThreadLocalCache[String, List[(Input) => AnyRef]]()
  private[this] val writers = new SoftThreadLocalCache[String, List[(Output, AnyRef) => Int]]()
  private[this] val offsets = new SoftThreadLocalCache[String, Array[Int]]()

  lazy val kryoReader = new KryoReader()
  lazy val kryoWriter = new KryoWriter()

  def getInput(bytes: Array[Byte]): Input = {
    val in = inputs.getOrElseUpdate(new Input)
    in.setBuffer(bytes)
    in
  }

  def getOutput(): Output = {
    val out = outputs.getOrElseUpdate(new Output(1024, -1))
    out.clear()
    out
  }

  def getOffsets(sft: String, size: Int): Array[Int] =
    offsets.getOrElseUpdate(sft, Array.ofDim[Int](size))

  def getWriters(key: String, sft: SimpleFeatureType): List[(Output, AnyRef) => Int] = {
    writers.getOrElseUpdate(key, sft.getAttributeDescriptors.map { ad =>
      ObjectType.selectType(ad.getType.getBinding, sft.getUserData) match {
        case (ObjectType.STRING, _) =>
          (o: Output, v: AnyRef) => {
            val pos = o.position()
            o.writeString(v.asInstanceOf[String])
            o.position() - pos
          }
        case (ObjectType.INT, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeInt(v.asInstanceOf[Int])
            4
          }
          writeNullable(w)_
        case (ObjectType.LONG, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeLong(v.asInstanceOf[Long])
            8
          }
          writeNullable(w)_
        case (ObjectType.FLOAT, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeFloat(v.asInstanceOf[Float])
            4
          }
          writeNullable(w)_
        case (ObjectType.DOUBLE, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeDouble(v.asInstanceOf[Double])
            8
          }
          writeNullable(w)_
        case (ObjectType.BOOLEAN, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeBoolean(v.asInstanceOf[Boolean])
            1
          }
          writeNullable(w)_
        case (ObjectType.DATE, _) =>
          val w = (o: Output, v: AnyRef) => {
            o.writeLong(v.asInstanceOf[Date].getTime)
            8
          }
          writeNullable(w)_
        case (ObjectType.UUID, _) =>
          val w = (o: Output, v: AnyRef) => {
            val uuid = v.asInstanceOf[UUID]
            o.writeLong(uuid.getMostSignificantBits)
            o.writeLong(uuid.getLeastSignificantBits)
            16
          }
          writeNullable(w)_
        case (ObjectType.GEOMETRY, _) =>
          val w = (o: Output, v: AnyRef) => {
            val pos = o.position()
            kryoWriter.selectGeometryWriter(o, v.asInstanceOf[Geometry])
            o.position() - pos
          }
          writeNullable(w)_
        case (ObjectType.HINTS, _) => null.asInstanceOf[(Output, AnyRef) => Int] // TODO
        case (ObjectType.LIST, bindings) => null.asInstanceOf[(Output, AnyRef) => Int] // TODO
        case (ObjectType.MAP, bindings) => null.asInstanceOf[(Output, AnyRef) => Int] // TODO
      }
    }.toList)
  }

  def writeNullable(wrapped: (Output, AnyRef) => Int)(o: Output, v: AnyRef): Int = {
    if (v == null) {
      o.write(NULL_BYTE)
      1
    } else {
      o.write(NON_NULL_BYTE)
      wrapped(o, v) + 1
    }
  }

  def getReaders(key: String, sft: SimpleFeatureType): List[(Input) => AnyRef] = {
    readers.getOrElseUpdate(key, sft.getAttributeDescriptors.map { ad =>
      ObjectType.selectType(ad.getType.getBinding, sft.getUserData) match {
        case (ObjectType.STRING, _) => (i: Input) => i.readString()
        case (ObjectType.INT, _) => readNullable((i: Input) => i.readInt().asInstanceOf[AnyRef])_
        case (ObjectType.LONG, _) => readNullable((i: Input) => i.readLong().asInstanceOf[AnyRef])_
        case (ObjectType.FLOAT, _) => readNullable((i: Input) => i.readFloat().asInstanceOf[AnyRef])_
        case (ObjectType.DOUBLE, _) => readNullable((i: Input) => i.readDouble().asInstanceOf[AnyRef])_
        case (ObjectType.BOOLEAN, _) => readNullable((i: Input) => i.readBoolean().asInstanceOf[AnyRef])_
        case (ObjectType.DATE, _) => readNullable((i: Input) => new Date(i.readLong()).asInstanceOf[AnyRef])_
        case (ObjectType.UUID, _) =>
          val w = (i: Input) => {
            val mostSignificantBits = i.readLong()
            val leastSignificantBits = i.readLong()
            new UUID(mostSignificantBits, leastSignificantBits)
          }
          readNullable(w)_
        case (ObjectType.GEOMETRY, _) =>
          readNullable((i: Input) => kryoReader.selectGeometryReader(i))_
        case (ObjectType.HINTS, _) => null.asInstanceOf[(Input) => AnyRef] // TODO
        case (ObjectType.LIST, bindings) => null.asInstanceOf[(Input) => AnyRef] // TODO
        case (ObjectType.MAP, bindings) => null.asInstanceOf[(Input) => AnyRef] // TODO
      }
    }.toList)
  }

  def readNullable(wrapped: (Input) => AnyRef)(i: Input): AnyRef = {
    if (i.read() == NULL_BYTE) {
      null
    } else {
      wrapped(i)
    }
  }
}
