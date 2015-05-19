package org.locationtech.geomesa.accumulo.index

import com.google.common.primitives.{Bytes, Longs, Shorts}
import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.{Geometry, GeometryCollection, Point}
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.data.Range
import org.apache.hadoop.io.Text
import org.geotools.data.Query
import org.joda.time.Weeks
import org.locationtech.geomesa.accumulo.data.AccumuloConnectorCreator
import org.locationtech.geomesa.accumulo.data.tables.Z3Table
import org.locationtech.geomesa.accumulo.{filter, index}
import org.locationtech.geomesa.curve.{Z3Iterator, Z3SFC}
import org.locationtech.geomesa.iterators.{KryoLazyFilterTransformIterator, LazyFilterTransformIterator}
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.expression.Literal
import org.opengis.filter.spatial.BinarySpatialOperator
import org.opengis.filter.{Filter, PropertyIsBetween}

import scala.collection.JavaConversions._

class Z3IdxStrategy extends Strategy with Logging with IndexFilterHelpers  {

  import FilterHelper._
  import Z3IdxStrategy._
  import filter._
  val Z3_CURVE = new Z3SFC

  /**
   * Plans the query - strategy implementations need to define this
   */
  override def getQueryPlans(query: Query, queryPlanner: QueryPlanner, output: ExplainerOutputType) = {
    val sft = queryPlanner.sft
    val acc = queryPlanner.acc

    val dtgField = getDtgFieldName(sft)

    // TODO: Select only the geometry filters which involve the indexed geometry type.
    // https://geomesa.atlassian.net/browse/GEOMESA-200
    // Simiarly, we should only extract temporal filters for the index date field.
    val (geomFilters, otherFilters) = partitionGeom(query.getFilter, sft)
    val (temporalFilters, ecqlFilters) = partitionTemporal(otherFilters, dtgField)

    // TODO: Currently, we assume no additional predicates other than space and time in Z3
    output(s"Geometry filters: $geomFilters")
    output(s"Temporal filters: $temporalFilters")
    output(s"Other filters: $ecqlFilters")

    val tweakedGeomFilters = geomFilters.map(updateTopologicalFilters(_, sft))

    output(s"Tweaked geom filters are $tweakedGeomFilters")

    // standardize the two key query arguments:  polygon and date-range
    val geomsToCover = tweakedGeomFilters.flatMap(decomposeToGeometry)

    output(s"GeomsToCover: $geomsToCover")

    val collectionToCover: Geometry = geomsToCover match {
      case Nil => null
      case seq: Seq[Geometry] => new GeometryCollection(geomsToCover.toArray, geomsToCover.head.getFactory)
    }

    val temporal = extractTemporal(dtgField)(temporalFilters)
    val interval = netInterval(temporal)
    val geometryToCover = netGeom(collectionToCover)

    val filter = buildFilter(geometryToCover, interval)
    // This catches the case when a whole world query slips through DNF/CNF
    // The union on this geometry collection is necessary at the moment but is not true
    // If given spatial predicates like disjoint.
    val ofilter =
      if (isWholeWorld(geometryToCover)) filterListAsAnd(temporalFilters)
      else filterListAsAnd(tweakedGeomFilters ++ temporalFilters)

    if (ofilter.isEmpty) {
      logger.warn(s"Querying Accumulo without SpatioTemporal filter.")
    }

    output(s"Interval:  $interval")
    output(s"Filter: ${Option(filter).getOrElse("No Filter")}")

    val iteratorSettings = {
      val transforms = index.getTransformDefinition(query)
      val ecql = ecqlFilters.length match {
        case 0 => None
        case 1 => Some(ecqlFilters.head)
        case _ => Some(ff.and(ecqlFilters))
      }

      (ecql, transforms) match {
        case (None, None) => None
        case _ =>
          Some(LazyFilterTransformIterator.configure[KryoLazyFilterTransformIterator](sft,
            ecql, transforms, FILTERING_ITER_PRIORITY))
      }
    }

    val finalSft = getTransformSchema(query).getOrElse(sft)
    val z3table = acc.getZ3Table(sft)

    // setup Z3 iterator
    val env = geometryToCover.getEnvelopeInternal
    val (lx, ly, ux, uy) = (env.getMinX, env.getMinY, env.getMaxX, env.getMaxY)
    val epochWeekStart = Weeks.weeksBetween(Z3Table.EPOCH, interval.getStart)
    val epochWeekEnd = Weeks.weeksBetween(Z3Table.EPOCH, interval.getEnd)
    val weeks = scala.Range.inclusive(epochWeekStart.getWeeks, epochWeekEnd.getWeeks)
    if (weeks.length == 1)
      Seq(queryPlanForPrefix(weeks.head,
        Z3Table.secondsInCurrentWeek(interval.getStart, epochWeekStart),
        Z3Table.secondsInCurrentWeek(interval.getEnd, epochWeekStart),
        lx, ly, ux, uy, z3table, finalSft, iteratorSettings, contained = false))
    else {
      val oneWeekInSeconds = Weeks.ONE.toStandardSeconds.getSeconds
      val head +: xs :+ last = weeks.toList
      val middleQPs = xs.map { w =>
        queryPlanForPrefix(w, 0, oneWeekInSeconds, lx, ly, ux, uy, z3table, finalSft, iteratorSettings, contained = true)
      }
      val startQP = queryPlanForPrefix(head, Z3Table.secondsInCurrentWeek(interval.getStart, epochWeekStart), oneWeekInSeconds,
        lx, ly, ux, uy, z3table, finalSft, iteratorSettings, contained = false)
      val endQP   = queryPlanForPrefix(head, 0, Z3Table.secondsInCurrentWeek(interval.getEnd, epochWeekEnd),
        lx, ly, ux, uy, z3table, finalSft, iteratorSettings, contained = false)
      Seq(startQP, endQP) ++ middleQPs
    }
  }

  def queryPlanForPrefix(week: Int, lt: Long, ut: Long,
                         lx: Double, ly: Double, ux: Double, uy: Double,
                         table: String, finalSft: SimpleFeatureType, is: Option[IteratorSetting],
                         contained: Boolean = true) = {
    val epochWeekStart = Weeks.weeks(week)

    val z3ranges = Z3_CURVE.ranges(lx, ly, ux, uy, lt, ut, 8)

    val prefix = Shorts.toByteArray(epochWeekStart.getWeeks.toShort)

    val accRanges = z3ranges.map { case (s, e) =>
      val startRowBytes = Bytes.concat(prefix, Longs.toByteArray(s))
      val endRowBytes = Bytes.concat(prefix, Longs.toByteArray(e))
      new Range(
        new Text(startRowBytes), true,
        Range.followingPrefix(new Text(endRowBytes)), false)
    }

    val iter = Z3Iterator.configure(Z3_CURVE.index(lx, ly, lt), Z3_CURVE.index(ux, uy, ut), Z3_ITER_PRIORITY)

    val adaptIter = Z3Table.adaptZ3KryoIterator(finalSft)
    BatchScanPlan(table, accRanges, Seq(Some(iter), is).flatten, Seq(Z3Table.FULL_ROW), adaptIter, 8, hasDuplicates = false)
  }
}

object Z3IdxStrategy extends StrategyProvider {
  import FilterHelper._
  import filter._

  val Z3_ITER_PRIORITY = 21
  val FILTERING_ITER_PRIORITY = 25
  /**
   * Returns details on a potential strategy if the filter is valid for this strategy.
   *
   * @param filter
   * @param sft
   * @return
   */
  override def getStrategy(filter: Filter, sft: SimpleFeatureType, hints: StrategyHints): Option[StrategyDecision] = {
    if (sft.getGeometryDescriptor.getType.getBinding != classOf[Point] || index.getDtgDescriptor(sft).isEmpty) {
      return None
    }
    val (geomFilter, other) = partitionGeom(filter, sft)
    val (temporal, _) = partitionTemporal(other, getDtgFieldName(sft))
    if(geomFilter.size == 0 || temporal.size == 0) {
      None
    } else if (spatialFilters(geomFilter.head) && !isFilterWholeWorld(geomFilter.head)) {
      val temporalFilter = temporal.head
      val between = temporalFilter.asInstanceOf[PropertyIsBetween]
      val s = between.getLowerBoundary.asInstanceOf[Literal].getValue
      val e = between.getUpperBoundary.asInstanceOf[Literal].getValue
      val geom = sft.getGeometryDescriptor.getLocalName
      val e1 = geomFilter.head.asInstanceOf[BinarySpatialOperator].getExpression1
      val e2 = geomFilter.head.asInstanceOf[BinarySpatialOperator].getExpression2
      checkOrder(e1, e2).filter(_.name == geom).map(_ => StrategyDecision(new Z3IdxStrategy, -1))
     } else {
      None
    }
  }
}
