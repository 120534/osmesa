package osmesa.ingest

import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import cats.implicits._
import com.monovore.decline._
import osmesa.common.ProcessOSM

/*
 * Usage example:
 *
 * sbt "project ingest" assembly
 *
 * spark-submit \
 *   --driver-memory 4g \
 *   --class osmesa.ingest.RegionStatsApp \
 *   ingest/target/scala-2.11/osmesa-ingest.jar \
 *   --node-geoms $HOME/data/ri-point-geoms \
 *   --way-geoms $HOME/data/ri-way-geoms \
 *   --out-regions $HOME/data/geom-regions \
 *   --out-changesets $HOME/data/changeset-regions
 *
 * spark-submit \
 *   --driver-memory=10g \
 *   --conf spark.sql.shuffle.partitions=5000 \
 *   --conf spark.speculation=true \
 *   --conf spark.yarn.executor.memoryOverhead=2g \
 *   --class osmesa.ingest.RegionStatsApp \
 *   s3://osm-pds-tmp/osmesa-ingest.jar \
 *   --node-geoms s3://osm-pds-tmp/point-geoms-20180212 \
 *   --way-geoms s3://osm-pds-tmp/way-geoms-20180212 \
 *   --out-regions s3://osm-pds-tmp/geom-countries-20180212 \
 *   --out-changesets s3://osm-pds-tmp/changeset-countries-20180212
 */
object RegionStatsApp extends CommandApp(
  name = "osmesa-region-stats",
  header = "Generate region statistics",
  main = {

    /* CLI option handling */
    val nodeGeomsO = Opts.option[String]("node-geoms", help = "Location of the ORC file containing point geometries")
    val wayGeomsO = Opts.option[String]("way-geoms", help = "Location of the ORC file containing way geometries")
    val outRegionsO = Opts.option[String]("out-regions", help = "Geometries by region output ORC path")
    val outChangesetsO = Opts.option[String]("out-changesets", help = "Changesets by region output ORC path")

    (nodeGeomsO, wayGeomsO, outRegionsO, outChangesetsO).mapN { (nodeGeomsSrc, wayGeomsSrc, outRegions, outChangesets) =>
      println(s"node geoms: $nodeGeomsSrc")
      println(s"way geoms: $wayGeomsSrc")

      /* Settings compatible for both local and EMR execution */
      val conf = new SparkConf()
        .setIfMissing("spark.master", "local[*]")
        .setAppName("osmesa-region-stats") // TODO how to use this.name?
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.kryo.registrator", classOf[geotrellis.spark.io.kryo.KryoRegistrator].getName)

      implicit val ss: SparkSession = SparkSession.builder
        .config(conf)
        .enableHiveSupport
        .getOrCreate

      /* Silence the damn INFO logger */
      Logger.getRootLogger.setLevel(Level.WARN)

      val nodeGeoms = ss.read.orc(nodeGeomsSrc)
        .withColumn("minorVersion", lit(0))
      val wayGeoms = ss.read.orc(wayGeomsSrc)

      val geometriesByRegion = ProcessOSM.geocode(nodeGeoms.union(wayGeoms)).cache

      geometriesByRegion.repartition(10).write.format("orc").save(outRegions)

      val regionsByChangeset = ProcessOSM.regionsByChangeset(geometriesByRegion)

      regionsByChangeset.repartition(1).write.format("orc").save(outChangesets)

      ss.stop()
    }
  }
)
