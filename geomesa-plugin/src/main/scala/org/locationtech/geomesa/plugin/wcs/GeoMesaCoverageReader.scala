/*
 * Copyright 2014 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.locationtech.geomesa.plugin.wcs

import java.awt.Rectangle

import com.typesafe.scalalogging.slf4j.Logging
import org.geotools.coverage.CoverageFactoryFinder
import org.geotools.coverage.grid.io.{AbstractGridCoverage2DReader, AbstractGridFormat}
import org.geotools.coverage.grid.{GridCoverage2D, GridEnvelope2D}
import org.geotools.factory.Hints
import org.geotools.geometry.GeneralEnvelope
import org.geotools.util.Utilities
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import org.locationtech.geomesa.raster.data.AccumuloCoverageStore
import org.opengis.parameter.GeneralParameterValue

import scala.collection.JavaConversions._

object GeoMesaCoverageReader {
  val GeoServerDateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  val DefaultDateString = GeoServerDateFormat.print(new DateTime(DateTimeZone.forID("UTC")))
  val FORMAT = """accumulo://(.*):(.*)@(.*)/(.*)#geohash=(.*)#resolution=([0-9]*)#timeStamp=(.*)#rasterName=(.*)#zookeepers=([^#]*)(?:#auths=)?(.*)$""".r
}

import org.locationtech.geomesa.plugin.wcs.GeoMesaCoverageReader._

class GeoMesaCoverageReader(val url: String, hints: Hints) extends AbstractGridCoverage2DReader() with Logging {

  //TODO: WCS: Implement function/class for parsing our "new" url
  // right now we want to extract the table name and magnification like this "dataSource_mag"
  // later, if the magnification is not provided in the URL, we should estimate it later in the read() method

  // JNH: This todo is for Jake.
  logger.debug(s"""creating coverage reader for url "${url.replaceAll(":.*@", ":********@").replaceAll("#auths=.*","#auths=********")}"""")

  val FORMAT(user, password, instanceId, table, geohash, resolutionStr, timeStamp, rasterName, zookeepers, authtokens) = url

  logger.debug(s"extracted user $user, password ********, instance id $instanceId, table $table, zookeepers $zookeepers, auths ********")

  coverageName = table + ":" + rasterName



  // TODO: Either this is needed for rasterToCoverages or remove it.
  this.crs = AbstractGridFormat.getDefaultCRS
  this.originalEnvelope = new GeneralEnvelope(Array(-180.0, -90.0), Array(180.0, 90.0))
  this.originalEnvelope.setCoordinateReferenceSystem(this.crs)
  this.originalGridRange = new GridEnvelope2D(new Rectangle(0, 0, 1024, 512))
  this.coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints)
  // TODO: Provide writeVisibilites??  Sort out read visibilites
  val coverageStoreParams = Map[java.lang.String, java.io.Serializable](
    "instanceId" -> instanceId,
    "zookeepers" -> zookeepers,
    "user" -> user,
    "password" -> password,
    "tableName" -> table,
    "auths" -> authtokens
  )
  val coverageStore = AccumuloCoverageStore(coverageStoreParams)

  /**
   * Default implementation does not allow a non-default coverage name
   * @param coverageName
   * @return
   */
  override protected def checkName(coverageName: String) = {
    Utilities.ensureNonNull("coverageName", coverageName)
    true
  }

  override def getCoordinateReferenceSystem = this.crs

  override def getCoordinateReferenceSystem(coverageName: String) = this.getCoordinateReferenceSystem

  override def getFormat = new GeoMesaCoverageFormat

  def getGeohashPrecision = resolutionStr.toInt


  def read(parameters: Array[GeneralParameterValue]): GridCoverage2D = {
    val params = new GeoMesaCoverageQueryParams(parameters)
    //TODO: WCS: Generate RasterQueryObject here and use it with getRasters
    /** it would look like this
      *  val rq = RasterQuery(params.bbox, params.accResolution.toString, None, None)
      *  val rasters: Iterator[feature.Raster] = coverageStore.getRasters(rq)
      *  then convert from feature.Raster to what is needed,
      *  and then mosiac, etc.
      */
    val image = coverageStore.getChunk(geohash, getGeohashPrecision)

    /**
     * Included for when mosaicing and final key structure are utilized
     *
     * val image = getChunk(geohash, params.resolution.getOrElse(getGeohashPrecision))
     * val chunks = getChunks(geohash, getGeohashPrecision, None, params.bbox)
     * val image = mosaicGridCoverages(chunks, env = params.env)
     * this.coverageFactory.create(coverageName, image, params.env)
     */

    this.coverageFactory.create(coverageName, image, params.envelope)
  }
}