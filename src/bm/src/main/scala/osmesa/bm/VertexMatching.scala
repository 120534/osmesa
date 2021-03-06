package osmesa.bm

import geotrellis.vector._
import geotrellis.vector.io._

import com.vividsolutions.jts.algorithm.{Centroid, CGAlgorithms}
import com.vividsolutions.jts.geom.Coordinate


object VertexMatching {

  private def matcher(
    points1: Array[Point], points2: Array[Point],
    offsetx: Double, offsety: Double,
    list: List[(Point, Point)] = List.empty[(Point, Point)]
  ): List[(Point, Point)] = {
    if (points1.isEmpty || points2.isEmpty) list
    else {
      val (_, i) = argmin(points1.head, points2, offsetx, offsety)
      matcher(
        points1.drop(1), points2.drop(i+1),
        offsetx, offsety,
        list ++ List((points1.head, points2(i)))
      )
    }
  }

  private def argmin(
    p: Point, ps: Array[Point],
    offsetx: Double, offsety: Double
  ): (Double, Int) = {
    ps
      .map({ p2 =>
        val temp = Point(p2.x - offsetx, p2.y - offsety)
        temp.distance(p)
      })
      .zipWithIndex
      .reduce({ (pair1, pair2) =>
        if (pair1._1 <= pair2._1) pair1
        else pair2
      })
  }

  private def polygonToPolygon(_p1: Polygon, _p2: Polygon, relative: Boolean) = {
    val (p1, p2) =
      if (_p1.vertices.length < _p2.vertices.length) (_p1, _p2)
      else (_p2, _p1)

    val (centroidx, centroidy) = {
      val centroid = Centroid.getCentroid(p1.jtsGeom)
      (centroid.x, centroid.y)
    }

    val (offsetx: Double, offsety: Double) =
      if (relative) {
        val centroid = Centroid.getCentroid(p2.jtsGeom)
        (centroid.x - centroidx, centroid.y - centroidy)
      }
      else (0.0, 0.0)

    val points1 = {
      val pts = p1.jtsGeom.getCoordinates
      if (CGAlgorithms.isCCW(pts)) pts
      else pts.reverse
    }.drop(1).map({ p => Point(p.x, p.y) })

    val points2 = {
      val points = {
        val pts = p2.jtsGeom.getCoordinates
        if (CGAlgorithms.isCCW(pts)) pts
        else pts.reverse
      }.drop(1).map({ p => Point(p.x, p.y) })
      val (_, i) = argmin(points1.head, points, offsetx, offsety)
      points.drop(i) ++ points.take(i)
    }

    val pairs = matcher(points1, points2, offsetx, offsety)

    Homography.dlt(
      if (pairs.length >= 4) pairs; else points1.zip(points2).take(4).toList,
      centroidx, centroidy
    )
  }

  def score(p1: Polygon, p2: Polygon): Double = {
    val h1 = polygonToPolygon(p1, p2, false).toArray
    val Δ1 = math.abs(h1(0)-1.0) + math.abs(h1(1)) + math.abs(h1(2)) + math.abs(h1(3)) + math.abs(h1(4)-1.0) + math.abs(h1(5))

    val h2 = polygonToPolygon(p1, p2, true).toArray
    val Δ2 = math.abs(h2(0)-1.0) + math.abs(h2(1)) + math.abs(h2(2)) + math.abs(h2(3)) + math.abs(h2(4)-1.0) + math.abs(h2(5))

    math.min(Δ1, Δ2)
  }

  def main(args: Array[String]): Unit = {
    val polygon1 =
      if (args(0).endsWith(".geojson"))
        scala.io.Source.fromFile(args(0)).mkString.parseGeoJson[Polygon]
      else
        args(0).parseGeoJson[Polygon]

    val polygon2 =
      if (args(1).endsWith(".geojson"))
        scala.io.Source.fromFile(args(1)).mkString.parseGeoJson[Polygon]
      else
        args(1).parseGeoJson[Polygon]

    println(polygon1.distance(polygon2))
    println(Centroid.getCentroid(polygon1.jtsGeom).distance(Centroid.getCentroid(polygon2.jtsGeom)))
    println(polygonToPolygon(polygon1, polygon2, false))
    println(polygonToPolygon(polygon1, polygon2, true))
  }

}
