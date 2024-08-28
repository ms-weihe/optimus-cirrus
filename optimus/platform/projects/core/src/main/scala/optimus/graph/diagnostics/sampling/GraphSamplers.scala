/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.graph.diagnostics.sampling
import optimus.core.Collections
import optimus.graph.EnumCounter
import optimus.graph.GCMonitor
import optimus.graph.GCNative
import optimus.graph.OGLocalTables
import optimus.graph.OGSchedulerTimes
import optimus.graph.RemovableLocalTables
import optimus.graph.diagnostics.EvictionReason
import optimus.graph.diagnostics.SchedulerProfileEntry
import optimus.graph.diagnostics.messages.AccumulatedValue
import optimus.graph.diagnostics.messages.Accumulating
import optimus.graph.diagnostics.messages.AllOGCounters
import optimus.graph.diagnostics.sampling.SamplingProfiler.SamplerTrait
import optimus.breadcrumbs.crumbs.Properties._
import optimus.graph.diagnostics.sampling.Cardinality.LogLogCounter
import optimus.graph.diagnostics.sampling.SamplingProfiler.NANOSPERMILLI
import optimus.graph.diagnostics.sampling.SamplingProfiler.Sampler
import optimus.graph.diagnostics.sampling.SamplingProfiler.nanosToMillis
import optimus.graph.tracking.DependencyTrackerQueue.QueueStats
import optimus.graph.tracking.DependencyTrackerRoot

import scala.jdk.CollectionConverters._
import optimus.scalacompat.collection._

import scala.collection.mutable.ArrayBuffer

//noinspection ScalaUnusedSymbol // ServiceLoader
class GraphSamplers extends SamplerProvider {
  import SamplingProfiler._
  def provide(sp: SamplingProfiler): Seq[SamplerTrait[_, _]] = {
    val util = new Util(sp)
    import util._
    val ss = ArrayBuffer.empty[SamplerTrait[_, _]]

    ss += Diff(_ => OGSchedulerTimes.getGraphStallTime(0) / NANOSPERMILLI, profStallTime)

    // graph, cache, self-time during epoch
    ss += new Sampler(
      sp,
      snapper = _ => OGLocalTables.getSchedulerTimes,
      process = (prev: Option[SchedulerProfileEntry], sp: SchedulerProfileEntry) => {
        prev.fold(SchedulerProfileEntry())(sp.since(_))
      },
      publish = (sp: SchedulerProfileEntry) =>
        Elems(
          profGraphTime -> sp.graphTime / NANOSPERMILLI,
          profSelfTime -> sp.selfTime / NANOSPERMILLI,
          profCacheTime -> sp.cacheTime / NANOSPERMILLI,
          profGraphSpinTime -> sp.spinTime / NANOSPERMILLI,
          profGraphWaitTime -> sp.waitTime / NANOSPERMILLI,
          profGraphIdleTime -> sp.idleTime / NANOSPERMILLI,
          profGraphCpuTime -> sp.cpuTime / NANOSPERMILLI
        )
    )

    ss += new Sampler(
      sp,
      snapper = _ => {
        var snaps: List[EnumCounter[EvictionReason]] = Nil
        OGLocalTables.forAllRemovables { (table: RemovableLocalTables) =>
          val ec = table.evictionCounter
          if (ec.total() > 0)
            snaps ::= ec.snap()
        }
        snaps
      },
      process = (prev: Option[List[EnumCounter[EvictionReason]]], curr: List[EnumCounter[EvictionReason]]) => {
        val diffed = new EnumCounter(classOf[EvictionReason])
        for (c <- curr) diffed.add(c)
        for (ps <- prev; p <- ps) diffed.subtract(p)
        diffed
      },
      publish = (counts: EnumCounter[EvictionReason]) => {
        val m = counts.toMap.asScala.collect {
          case (k, v) if v > 0 =>
            k.name() -> v.toLong
        }.toMap
        Elems(profEvictions -> m)
      }
    )

    import optimus.graph.PluginType
    final case class PluginCounts(
        inFlight: Map[String, Long],
        starts: Map[String, Long],
        fullWaitTimes: Map[String, Long])

    // Number of nodes started during period, and number of nodes currently waiting
    ss += new Sampler[PluginType.PluginTracker, PluginCounts](
      sp,
      snapper = (_: Boolean) => PluginType.snapAggregatePluginCounts(),
      process = { (prevOpt: Option[PluginType.PluginTracker], curr: PluginType.PluginTracker) =>
        prevOpt.fold {
          PluginCounts(curr.starts.toMap, curr.inFlight.toMap, curr.fullWaitTimes.toMap.mapValuesNow(nanosToMillis))
        } { prev =>
          val starts = curr.starts.snap().getSafe
          starts.accumulate(prev.starts, -1)
          val fullWaitTimes = curr.fullWaitTimes.snap().getSafe
          fullWaitTimes.accumulate(prev.fullWaitTimes, -1)
          PluginCounts(starts.toMap, curr.inFlight.toMap, fullWaitTimes.toMap.mapValuesNow(nanosToMillis))
        }
      },
      publish = { pc =>
        Elems(pluginInFlight -> pc.inFlight, pluginStarts -> pc.starts, pluginFullWaitTimes -> pc.fullWaitTimes)
      }
    )

    ss += new Sampler(
      sp,
      snapper = _ => {
        val counters = new Cardinality.Counters()
        OGLocalTables.forAllRemovables((table: RemovableLocalTables) => counters.add(table.getCardinalities))
        counters
      },
      process = (_: Option[Cardinality.Counters], curr: Cardinality.Counters) => curr.countEstimateMap,
      publish = (cardinalitiesMap: Map[Cardinality.Category, LogLogCounter]) => {
        val m = cardinalitiesMap.mapValuesNow(_.estimate).map { case (k, v) =>
          k.name -> Math.round(v).toInt
        }

        val estimators = cardinalitiesMap.map { case (k, v) =>
          k.name -> v.estimators
        }

        Elems(cardinalities -> m, cardEstimators -> estimators)
      }
    )

    ss += new Sampler[PluginType.Counter, Map[String, Long]](
      sp,
      snapper = (_: Boolean) => OGSchedulerTimes.snapCumulativePluginStallTimes(),
      process = (prevOption: Option[PluginType.Counter], c: PluginType.Counter) =>
        prevOption.fold[Map[String, Long]](Map.empty) { prev =>
          val stall = c.snap().getSafe
          stall.accumulate(prev, -1)
          stall.toMap.mapValuesNow(_ / NANOSPERMILLI)
        },
      publish = (m: Map[String, Long]) => {
        val s = m.map { case (ptype, time) =>
          Map("p" -> ptype, "t" -> time.toString())
        }.toSeq
        Elems(pluginStallTimes -> m)
      }
    )

    // State of DependencyTracker queues
    {
      type DTSnap = Map[String, QueueStats]
      ss += new Sampler(
        sp,
        snapper = _ => DependencyTrackerRoot.snap(),
        process = (prev: Option[DTSnap], newSnap: DTSnap) =>
          prev.fold(Map.empty: DTSnap) { prev =>
            val builder = Map.newBuilder[String, QueueStats]
            val keys = newSnap.keySet ++ prev.keySet
            for (k <- keys) {
              (prev.get(k), newSnap.get(k)) match {
                case (prev, Some(current)) =>
                  builder += k -> (current - prev.getOrElse(QueueStats.zero))

                // Special case for when the tracker has been GC-ed, we publish no new added tasks, and all remaining
                // tasks as removed:
                case (Some(prev), None) =>
                  builder += k -> QueueStats(0, prev.added - prev.removed)

                case (None, None) =>
              }
            }

            builder.result()
          },
        publish = (diff: DTSnap) =>
          if (diff.isEmpty) Elems.Nil
          else
            Elems(
              profDepTrackerTaskAdded -> diff.mapValuesNow(_.added),
              profDepTrackerTaskProcessed -> diff.mapValuesNow(_.removed)
            )
      )
    }

    // GC costs, according to GCMonitor
    ss += new SamplingProfiler.Sampler[GCMonitor.CumulativeGCStats, GCMonitor.CumulativeGCStats](
      sp,
      snapper = _ => GCMonitor.instance.snapAndResetStatsForSamplingProfiler(),
      process = (_, c) => c,
      publish = c => c.elems
    )

    // Publish diffs for all known accumulating counters
    val accumulatingCounters: Array[Accumulating[_, _ <: AccumulatedValue]] = AllOGCounters.allCounters.collect {
      case c: Accumulating[_, _] => c
    }
    ss += new Sampler[Seq[AccumulatedValue], Seq[AccumulatedValue]](
      sp,
      snapper = (_: Boolean) => accumulatingCounters.map(_.snap),
      process = (prevOption: Option[Seq[AccumulatedValue]], curr: Seq[AccumulatedValue]) =>
        prevOption.fold[Seq[AccumulatedValue]](Seq.empty) { prev: Seq[AccumulatedValue] =>
          Collections.map2(curr, prev) { case (c, p) =>
            c.diff(p)
          }
        },
      publish = (diffs: Seq[AccumulatedValue]) => Elems(diffs.map(_.elems).toSeq: _*)
    ) // toSeq to make 2.13 happy

    def untilGCN[T](after: => T, default: T) = if (GCNative.isLoaded) after else default

    ss += Snap(_ => untilGCN(GCNative.getNativeAllocationMB, 0), gcNativeAlloc)
    ss += Snap(_ => untilGCN(GCNative.managedSizeMB(), 0), gcNativeManagedAlloc)

    if (GCNative.usingJemalloc())
      ss += new Sampler[GCNative.JemallocSizes, Map[String, Int]](
        sp,
        snapper = _ => GCNative.jemallocSizes(),
        process = (_, c) => c.toMapMB.asScala.toMap.mapValuesNow(_.toInt),
        publish = c => Elems(gcNativeStats -> c)
      )

    ss += Diff(_ => OGSchedulerTimes.getPreOptimusStartupTime / NANOSPERMILLI, profPreOptimusStartup)
    ss
  }

}
