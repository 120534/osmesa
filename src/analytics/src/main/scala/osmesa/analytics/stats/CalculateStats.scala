package osmesa.analytics.stats

import osmesa.analytics._

import cats.implicits._
import com.monovore.decline._
import com.vividsolutions.jts.geom.Coordinate
import geotrellis.vector.{Feature, Line, Point}
import geotrellis.util.Haversine
import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.functions._
import vectorpipe._

import java.time.Instant
import scala.collection.mutable
import scala.util.{Try, Success, Failure}

object CalculateStats {
  private implicit def changeTopicEncoder: Encoder[Array[StatTopic]] = ExpressionEncoder()

  case class Options(
    changesetPartitionCount: Int = 1000,
    wayPartitionCount: Int = 1000,
    nodePartitionCount: Int = 10000
  )

  object Options {
    def DEFAULT = Options()
  }

  def statTopics(col: Column): TypedColumn[Any, Array[StatTopic]] =
    udf[Array[StatTopic], Map[String, String]](StatTopics.tagsToTopics).
      apply(col).
      as[Array[StatTopic]]

  def computeChangesetStats(history: DataFrame, changesets: DataFrame, options: Options = Options.DEFAULT)(implicit ss: SparkSession): RDD[(Long, ChangesetStats)] = {
    import ss.implicits._

    val changesetPartitioner = new HashPartitioner(options.changesetPartitionCount)
    val wayPartitioner = new HashPartitioner(options.wayPartitionCount)
    val nodePartitioner = new HashPartitioner(options.nodePartitionCount)

    def generateUpstreamTopics[T <: OsmId](rdd: RDD[(Long, Iterable[(T, Long, Set[StatTopic])])]): RDD[(Long, List[(T, UpstreamTopics)])] =
      rdd.
        mapValues { idsAndChangesets =>
          (idsAndChangesets.foldLeft(Map[T, Map[Long, Set[StatTopic]]]()) { case (acc, (osmId, changeset, statTopics)) =>
            acc.get(osmId) match {
              case Some(m) => acc + (osmId -> (m + (changeset ->  statTopics)))
              case _ => acc + (osmId -> Map(changeset -> statTopics))
            }
          }).toList.map { case (k, v) => (k, new UpstreamTopics(v)) }
        }


    // Create the base set of ChangesetStats we'll be joining against
    val initialChangesetStats =
      changesets.
        select($"id", $"uid", $"user", $"created_at", $"closed_at", $"tags", hashtags($"tags").alias("hashtags")).
        map { row =>
          val changeset = row.getAs[Long]("id")
          val userId = row.getAs[Long]("uid")
          val userName = row.getAs[String]("user")
          val createdAt = row.getAs[java.sql.Timestamp]("created_at")
          val closedAt = row.getAs[java.sql.Timestamp]("closed_at")
          val tags = row.getAs[Map[String, String]]("tags")
          val editor = tags.get("created_by")

          val hashtags = row.getAs[Seq[String]]("hashtags").toList

          (changeset, userId, userName, createdAt, closedAt, editor, hashtags)
        }.
        rdd.
        map { case (changeset, userId, userName, createdAt, closedAt, editor, hashtags) =>
          val stats =
            ChangesetStats(
              changeset,
              userId,
              userName,
              createdAt.toInstant,
              closedAt.toInstant,
              editor,
              hashtags
            )

          (changeset, stats)
        }
        .partitionBy(changesetPartitioner)

    // **  Roll up stats based on changeset ** //

    val relevantRelations =
      history.
        where("type == 'relation'").
        select(
          $"id",
          $"changeset",
          $"version",
          $"members",
          statTopics($"tags").as("statTopics")
        ).
        where(size($"statTopics") > 0)

    val relationStatChanges: RDD[(Long, StatCounter)] =
      relevantRelations.
        select($"id", $"changeset", $"version", $"statTopics").
        rdd.
        map { row =>
          val osmId = RelationId(row.getAs[Long]("id"))
          val changeset = row.getAs[Long]("changeset")
          val version = row.getAs[Long]("version")
          val topics = row.getAs[Seq[StatTopic]]("statTopics").toSet
          (changeset, StatCounter(osmId, changeset, version, topics))
        }

    val relationMembers =
      relevantRelations.
        select($"id", $"changeset", $"statTopics", explode($"members").as("member"))

    val relationsForWays: RDD[(Long, List[(RelationId, UpstreamTopics)])] =
      generateUpstreamTopics(
        relationMembers.
          where($"member.type" === "way").
          select($"id", $"changeset", $"statTopics", $"member.ref".as("wayId")).
          rdd.
          map { row =>
            val osmId = RelationId(row.getAs[Long]("id"))
            val changeset = row.getAs[Long]("changeset")
            val topics = row.getAs[Seq[StatTopic]]("statTopics").toSet
            val wayId = row.getAs[Long]("wayId")

            (wayId, (osmId, changeset, topics))
          }.
          groupByKey(wayPartitioner)
      )

    val relationsForNodes: RDD[(Long, List[(RelationId, UpstreamTopics)])] =
      generateUpstreamTopics(
        relationMembers.
          where($"member.type" === "node").
          select($"id", $"changeset", $"statTopics", $"member.ref".as("nodeId")).
          rdd.
          map { row =>
            val osmId = RelationId(row.getAs[Long]("id"))
            val changeset = row.getAs[Long]("changeset")
            val topics = row.getAs[Seq[StatTopic]]("statTopics").toSet
            val nodeId = row.getAs[Long]("nodeId")

            (nodeId, (osmId, changeset, topics))
          }.
          groupByKey(nodePartitioner)
      )

    val wayInfo =
      history.
        where("type == 'way'").
        select(
          $"id",
          $"changeset",
          $"version",
          $"nds.ref".as("nodes"),
          statTopics($"tags").as("statTopics")
        ).
        rdd.
        map { row =>
          val id = row.getAs[Long]("id")
          val changeset = row.getAs[Long]("changeset")
          val version = row.getAs[Long]("version")
          val nodeIds = row.getAs[Seq[Long]]("nodes").toArray
          val topics = row.getAs[Seq[StatTopic]]("statTopics").toSet
          (id, (changeset, version, nodeIds, topics))
        }

    val waysToRelations =
      wayInfo.
        leftOuterJoin(relationsForWays).
        filter { case (_, ((_, _, _, topics), relationOpt)) =>
          !topics.isEmpty || relationOpt.isDefined
        }

    val wayStatChanges =
      waysToRelations.
        map { case (wayId, ((changeset, version, nodeIds, topics), relationsOpt)) =>
          var statCounter = StatCounter()

          relationsOpt match {
            // List[(RelationId, Map[Long, Set[StatTopic]])]
            case Some(relations) =>
              for((relationId, upstreamTopics) <- relations) {
                val upstreamTopicSet = upstreamTopics.forChangeset(changeset)
                if(!upstreamTopicSet.isEmpty) {
                  statCounter = statCounter + (ChangeItem(relationId, changeset, false), upstreamTopicSet)
                }
              }
            case None => ()
          }

          if(!topics.isEmpty) {
            statCounter = statCounter + (ChangeItem(WayId(wayId), changeset, version == 1L), topics)
          }

          (changeset, statCounter)
        }

    val wayRelationsForNodes: RDD[(Long, List[(RelationId, UpstreamTopics)])] =
      generateUpstreamTopics(
        waysToRelations.
          flatMap { case (wayId, ((_, _, nodeIds, _), relationsOpt)) =>
            val relationTopics =
              relationsOpt match {
                case Some(relations) =>
                  (for(
                    (relationId, upstreamTopics) <- relations;
                    (changeset, topics) <- upstreamTopics.topicMap
                  ) yield {
                    (relationId, changeset, topics)
                  }).toList

                case None => List()
              }

            nodeIds map ((_, relationTopics))
          }
      )

    val waysForNodes: RDD[(Long, List[(WayId, UpstreamTopics)])] =
      generateUpstreamTopics(
        wayInfo.
          filter { case (_, (_, _, _, topics)) => !topics.isEmpty }.
          flatMap { case (wayId, (changeset, _, nodeIds, topics)) =>
            nodeIds.map { nodeId =>
              (nodeId, (WayId(wayId), changeset, topics))
            }
          }.
          groupByKey(nodePartitioner)
      )

    val nodeInfo =
      history.
        where("type == 'node'").
        select(
          $"id",
          $"changeset",
          $"version",
          $"lat",
          $"lon",
          statTopics($"tags").as("statTopics")
        ).
        rdd.
        map { row =>
          val id = row.getAs[Long]("id")
          val changeset = row.getAs[Long]("changeset")
          val version = row.getAs[Long]("version")
          val lat = row.getAs[Double]("lat")
          val lon = row.getAs[Double]("lon")
          val topics = row.getAs[Seq[StatTopic]]("statTopics").toSet
          (id, (changeset, version, new Coordinate(lon, lat), topics))
        }

    val groupedNodes =
      nodeInfo.
        cogroup(relationsForNodes, waysForNodes, wayRelationsForNodes).
        filter { case (_, (nodes, ways, relations, wayRelations)) =>
          nodes.foldLeft(false) { case (acc, (_, _, _, statTopics)) => acc || !statTopics.isEmpty } ||
          !ways.isEmpty ||
          !relations.isEmpty ||
          !wayRelations.isEmpty
        }

    val bcCountryLookup = ss.sparkContext.broadcast(new CountryLookup())

    val nodeStatChanges =
      groupedNodes.
        mapPartitions { partition =>
          val countryLookup = bcCountryLookup.value
          partition.
            flatMap { case (nodeId, (nodes, ways, relations, wayRelations)) =>
              for((changeset, version, coord, topics) <- nodes) yield {
                var statCounter = StatCounter()

                // Figure out the countries
                val countryOpt = countryLookup.lookup(coord)

                for((upstreamId, upstreamTopics) <- (relations ++ wayRelations ++ ways).flatten) {
                  val upstreamTopicSet = upstreamTopics.forChangeset(changeset)
                  if(!upstreamTopicSet.isEmpty) {
                    val item = ChangeItem(upstreamId, changeset, false)
                    statCounter = statCounter + (item, upstreamTopicSet)
                    countryOpt.foreach { country =>
                      statCounter = statCounter + (item, country)
                    }
                  }
                }

                if(!topics.isEmpty) {
                  val item = ChangeItem(NodeId(nodeId), changeset, version == 1L)
                  statCounter = statCounter + (item, topics)
                  countryOpt.foreach { country =>
                    statCounter = statCounter + (item, country)
                  }
                }

                (changeset, statCounter)
              }
            }
        }

    // val nodesToWays =
    //   groupedNodes.
    //     flatMap { case (nodeId, (nodes, ways, relations, wayRelations)) =>
    //       ways.map { case (wayId, upstreamTopics) =>
    //         (wayId, (changeset, coord))
    //       }
    //     }


    val mergedStatChanges =
      ss.sparkContext.union(relationStatChanges, wayStatChanges, nodeStatChanges).
        reduceByKey(changesetPartitioner, _ merge _)

    initialChangesetStats.
      leftOuterJoin(mergedStatChanges).
      mapValues { case (stats, counterOpt) =>
        counterOpt match {
          case Some(counter) =>
            stats.copy(
              roadsAdded = counter.roadsAdded,
              roadsModified = counter.roadsModified,
              buildingsAdded = counter.buildingsAdded,
              buildingsModified = counter.buildingsModified,
              waterwaysAdded = counter.waterwaysAdded,
              waterwaysModified = counter.waterwaysModified,
              poisAdded = counter.poisAdded,
              poisModified = counter.poisModified// ,
              // kmRoadAdded = 0.0,
              // kmRoadModified = 0.0,
              // kmWaterwayAdded = 0.0,
              // kmWaterwayModified = 0.0,
              // countries = List()
            )
          case None => stats
        }
      }
  }

  def computeUserStats(changesetStats: RDD[(Long, ChangesetStats)]): RDD[UserStats] =
    changesetStats
      .map { case (_, changesetStat) =>
        (changesetStat.userId, UserStats.fromChangesetStats(changesetStat))
      }
      .reduceByKey(_ merge _ )
      .map(_._2)

  def computeHashtagStats(changesetStats: RDD[(Long, ChangesetStats)]): RDD[HashtagStats] = ???

  def compute(history: DataFrame, changesets: DataFrame)(implicit ss: SparkSession): (RDD[HashtagStats], RDD[UserStats]) = {
    val changesetStats = computeChangesetStats(history, changesets)

    (computeHashtagStats(changesetStats), computeUserStats(changesetStats))
  }
}