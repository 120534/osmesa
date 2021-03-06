package osmesa.common.model

import java.sql.Timestamp

import org.apache.spark.sql.types._
import org.joda.time.DateTime

import scala.util.Try

case class Changeset(sequence: Int,
                     id: Long,
                     createdAt: Timestamp,
                     closedAt: Option[Timestamp],
                     open: Boolean,
                     numChanges: Int,
                     user: String,
                     uid: Long,
                     minLat: Option[Float],
                     maxLat: Option[Float],
                     minLon: Option[Float],
                     maxLon: Option[Float],
                     commentsCount: Int,
                     tags: Map[String, String],
                     comments: Seq[ChangesetComment])

object Changeset {
  val Schema = StructType(
    StructField("sequence", IntegerType) ::
      StructField("id", LongType) ::
      StructField("createdAt", TimestampType, nullable = false) ::
      StructField("closedAt", TimestampType, nullable = true) ::
      StructField("open", BooleanType, nullable = false) ::
      StructField("numChanges", IntegerType, nullable = false) ::
      StructField("user", StringType, nullable = false) ::
      StructField("uid", LongType, nullable = false) ::
      StructField("minLat", FloatType, nullable = true) ::
      StructField("maxLat", FloatType, nullable = true) ::
      StructField("minLon", FloatType, nullable = true) ::
      StructField("maxLon", FloatType, nullable = true) ::
      StructField("commentsCount", IntegerType, nullable = false) ::
      StructField(
        "tags",
        MapType(StringType, StringType, valueContainsNull = false),
        nullable = false
    ) ::
      StructField(
      "comments",
      DataTypes.createArrayType(
        StructType(
          StructField("date", TimestampType, nullable = false) ::
            StructField("user", StringType, nullable = false) ::
            StructField("uid", LongType, nullable = false) ::
            StructField("body", StringType, nullable = false) ::
              Nil
        )
      ),
      nullable = true
    ) ::
      Nil
  )

  implicit def stringToTimestamp(s: String): Timestamp =
    Timestamp.from(DateTime.parse(s).toDate.toInstant)

  implicit def stringToOptionalTimestamp(s: String): Option[Timestamp] =
    s match {
      case "" => None
      case ts => Some(ts)
    }

  implicit def stringToOptionalFloat(s: String): Option[Float] =
    s match {
      case "" => None
      case c  => Some(c.toFloat)
    }

  def fromXML(node: scala.xml.Node, sequence: Int): Changeset = {
    val id = (node \@ "id").toLong
    // Old changesets lack the appropriate field
    val commentsCount = Try((node \@ "comments_count").toInt).toOption.getOrElse(0)
    val uid = (node \@ "uid").toLong
    val user = node \@ "user"
    val numChanges = Try((node \@ "num_changes").toInt).toOption.getOrElse(0)
    val open = (node \@ "open").toBoolean
    val closedAt = node \@ "closed_at"
    val createdAt = node \@ "created_at"

    val maxLon = node \@ "max_lon"
    val minLon = node \@ "min_lon"
    val maxLat = node \@ "max_lon"
    val minLat = node \@ "min_lon"
    val tags =
      (node \ "tag").map(tag => (tag \@ "k", tag \@ "v")).toMap
    val comments = (node \ "discussion" \ "comment").map(ChangesetComment.fromXML)

    Changeset(sequence,
              id,
              createdAt,
              closedAt,
              open,
              numChanges,
              user,
              uid,
              minLat,
              maxLat,
              minLon,
              maxLon,
              commentsCount,
              tags,
              comments)
  }
}
