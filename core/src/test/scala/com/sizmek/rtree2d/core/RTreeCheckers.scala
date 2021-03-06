package com.sizmek.rtree2d.core

import java.lang.Math._
import java.util.concurrent.atomic.AtomicInteger

import com.sizmek.rtree2d.core.GeoUtils._
import org.scalacheck.Prop._
import org.scalacheck.Gen
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import org.scalatest.prop.Checkers

class RTreeCheckers extends WordSpec with Checkers {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 100)
  private val lastId = new AtomicInteger
  private val floatGen = Gen.choose[Float](-1000, 1000)
  private val latGen = Gen.choose[Float](-90, 90)
  private val lonGen = Gen.choose[Float](-180, 180)
  private val positiveFloatGen = Gen.choose[Float](0, 200)
  private val entryGen = for {
    x <- floatGen
    y <- floatGen
    w <- positiveFloatGen
    h <- positiveFloatGen
  } yield RTreeEntry(x, y, x + w, y + h, lastId.getAndIncrement())
  private val latLonEntryGen = for {
    x1 <- latGen
    y1 <- lonGen
    x2 <- latGen
    y2 <- lonGen
  } yield RTreeEntry(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), lastId.getAndIncrement())
  private val entryListGen = Gen.oneOf(0, 1, 10, 100, 1000).flatMap(n => Gen.listOfN(n, entryGen))

  "RTree" when {
    "update" should {
      "withdraw matched entries from a rtree" in check {
        forAll(entryListGen, entryListGen) {
          (entries1: List[RTreeEntry[Int]], entries2: List[RTreeEntry[Int]]) =>
            val entries12 = entries1 ++ entries2
            val expected = entries1.sorted
            RTree.update(RTree(entries12), entries2, Nil).entries.sorted ?= expected
        }
      }
      "build new rtree with old and inserted entries" in check {
        forAll(entryListGen, entryListGen) {
          (entries1: List[RTreeEntry[Int]], entries3: List[RTreeEntry[Int]]) =>
            val expected = (entries1 ++ entries3).sorted
            RTree.update(RTree(entries1), Nil, entries3).entries.sorted ?= expected
        }
      }
      "remove and insert at the same time properly" in check {
        forAll(entryListGen, entryListGen, entryListGen) {
          (entries1: List[RTreeEntry[Int]], entries2: List[RTreeEntry[Int]], entries3: List[RTreeEntry[Int]]) =>
            val entries12 = entries1 ++ entries2
            val expected = (entries1 ++ entries3).sorted
            RTree.update(RTree(entries12), entries2, entries3).entries.sorted ?= expected
        }
      }
    }
    "asked for entries" should {
      "return all entries" in check {
        forAll(entryListGen) {
          (entries: List[RTreeEntry[Int]]) =>
            val expected = entries.sorted
            RTree(entries).entries.sorted ?= expected
        }
      }
    }
    "asked for nearest" should {
      "return any of entries which intersects by point" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            import EuclideanPlaneDistanceCalculator._
            val sorted = entries.map(e => (calculator.distance(x, y, e), e)).sortBy(_._1)
            propBoolean(sorted.nonEmpty && sorted.exists { case (d, e) => d == 0.0f }) ==> {
              val result = RTree(entries).nearest(x, y)
              sorted.map(Some(_)).contains(result)
            }
        }
      }
      "return the nearest entry if point is out of all entries" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            import EuclideanPlaneDistanceCalculator._
            val sorted = entries.map(e => (calculator.distance(x, y, e), e)).sortBy(_._1)
            propBoolean(sorted.nonEmpty && !sorted.exists { case (d, e) => d == 0.0f }) ==> {
              RTree(entries).nearest(x, y) ?= Some(sorted.head)
            }
        }
      }
      "return the nearest entry with in a specified distance limit or none if all entries are out of the limit" in check {
        forAll(entryListGen, floatGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float, maxDist: Float) =>
            import EuclideanPlaneDistanceCalculator._
            val sorted = entries.map(e => (calculator.distance(x, y, e), e)).filter(_._1 < maxDist).sortBy(_._1)
            propBoolean(sorted.nonEmpty) ==> {
              val result = RTree(entries).nearest(x, y, maxDist)
              sorted.map(Some(_)).contains(result)
            }
        }
      }
      "don't return any entry for empty tree" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            import EuclideanPlaneDistanceCalculator._
            propBoolean(entries.isEmpty) ==> {
              RTree(entries).nearest(x, y) ?= None
            }
        }
      }
    }
    "full searched by point" should {
      "receive value of all matched entries" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            val expected = intersects(entries, x, y).sorted
            propBoolean(expected.nonEmpty) ==> {
              RTree(entries).searchAll(x, y).sorted ?= expected
            }
        }
      }
      "don't receive any value if no matches" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            val expected = intersects(entries, x, y)
            propBoolean(expected.isEmpty) ==> {
              RTree(entries).searchAll(x, y).isEmpty
            }
        }
      }
    }
    "searched by point for first match only" should {
      "receive one entry from expected" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            val expected = intersects(entries, x, y).toSet
            propBoolean(expected.nonEmpty) ==> {
              var found: RTreeEntry[Int] = null
              RTree(entries).search(x, y) { e =>
                found = e
                true
              }
              expected(found)
            }
        }
      }
    }
    "full searched by rectangle" should {
      "receive value of all matched entries" in check {
        forAll(entryListGen, floatGen, floatGen, positiveFloatGen, positiveFloatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float, w: Float, h: Float) =>
            val (x1, y1, x2, y2) = (x, y, x + w, y + h)
            val expected = intersects(entries, x1, y1, x2, y2).sorted
            propBoolean(expected.nonEmpty) ==> {
              RTree(entries).searchAll(x1, y1, x2, y2).sorted ?= expected
            }
        }
      }
      "don't receive any value if no matches" in check {
        forAll(entryListGen, floatGen, floatGen, positiveFloatGen, positiveFloatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float, w: Float, h: Float) =>
            val (x1, y1, x2, y2) = (x, y, x + w, y + h)
            val expected = intersects(entries, x1, y1, x2, y2)
            propBoolean(expected.isEmpty) ==> {
              RTree(entries).searchAll(x1, y1, x2, y2).isEmpty
            }
        }
      }
    }
    "searched by rectangle for first match only" should {
      "receive one entry from expected" in check {
        forAll(entryListGen, floatGen, floatGen, positiveFloatGen, positiveFloatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float, w: Float, h: Float) =>
            val (x1, y1, x2, y2) = (x, y, x + w, y + h)
            val expected = intersects(entries, x1, y1, x2, y2).toSet
            propBoolean(expected.nonEmpty) ==> {
              var found: RTreeEntry[Int] = null
              RTree(entries).search(x1, y1, x2, y2) { e =>
                found = e
                true
              }
              expected(found)
            }
        }
      }
    }
  }
  "EuclideanPlaneDistanceCalculator.calculator" when {
    "asked to calculate distance from point to an RTree" should {
      "return a distance to a nearest part of the RTree bounding box or 0 if the point is inside it" in check {
        forAll(entryListGen, floatGen, floatGen) {
          (entries: List[RTreeEntry[Int]], x: Float, y: Float) =>
            val t = RTree(entries)
            propBoolean(entries.nonEmpty && !intersects(t, x, y)) ==> {
              val expected = euclideanDistance(x, y, t)
              EuclideanPlaneDistanceCalculator.calculator.distance(x, y, t) === expected +- 0.001f
            }
        }
      }
    }
  }
  "SphericalEarthDistanceCalculator.calculator" when {
    "asked to calculate distance from point to an RTree" should {
      "return 0 if the point is inside it" in check {
        forAll(latLonEntryGen, Gen.choose[Float](0, 1), Gen.choose[Float](0, 1)) {
          (t: RTreeEntry[Int], rdx: Float, rdy: Float) =>
            val lat = t.x1 + rdx * (t.x2 - t.x1)
            val lon = t.y1 + rdy * (t.y2 - t.y1)
            propBoolean(intersects(t, lat, lon)) ==> {
              SphericalEarthDistanceCalculator.calculator.distance(lat, lon, t) === 0.0f +- 0.1f
            }
        }
      }
      "return a distance to the nearest edge of the RTree bounding box if point doesn't intersect and is aligned vertically" in check {
        forAll(latLonEntryGen, latGen, lonGen) {
          (t: RTreeEntry[Int], lat: Float, lon: Float) =>
            propBoolean(!intersects(t, lat, lon) && alignedVertically(t, lat, lon)) ==> {
              val distancesForCorners = Seq(
                greatCircleDistance1(lat, lon, t.x1, lon),
                greatCircleDistance1(lat, lon, t.x2, lon),
                greatCircleDistance1(lat, lon, t.x1, t.y1),
                greatCircleDistance1(lat, lon, t.x1, t.y2),
                greatCircleDistance1(lat, lon, t.x2, t.y1),
                greatCircleDistance1(lat, lon, t.x2, t.y2)
              )
              val expected = distancesForCorners.min
              val result = SphericalEarthDistanceCalculator.calculator.distance(lat, lon, t)
              result <= expected + 0.1f
            }
        }
      }
      "return a distance to the nearest edge of the RTree bounding box if point doesn't not intersect and is aligned horizontally" in check {
        forAll(latLonEntryGen, latGen, lonGen) {
          (t: RTreeEntry[Int], lat: Float, lon: Float) =>
            propBoolean(!intersects(t, lat, lon) && alignedHorizontally(t, lat, lon)) ==> {
              val distancesForCorners = Seq(
                greatCircleDistance1(lat, lon, lat, t.y1),
                greatCircleDistance1(lat, lon, lat, t.y2),
                greatCircleDistance1(lat, lon, t.x1, t.y1),
                greatCircleDistance1(lat, lon, t.x1, t.y2),
                greatCircleDistance1(lat, lon, t.x2, t.y1),
                greatCircleDistance1(lat, lon, t.x2, t.y2)
              )
              val expected = distancesForCorners.min
              val result = SphericalEarthDistanceCalculator.calculator.distance(lat, lon, t)
              result <= expected + 0.1f
            }
        }
      }
      "return a distance to the nearest corner of the RTree bounding box if point doesn't not intersect and is not aligned vertically or horizontally" in check {
        forAll(latLonEntryGen, latGen, lonGen) {
          (t: RTreeEntry[Int], lat: Float, lon: Float) =>
            propBoolean(!intersects(t, lat, lon) && !alignedHorizontally(t, lat, lon) && !alignedVertically(t, lat, lon)) ==> {
              val distancesForCorners = Seq(
                greatCircleDistance1(lat, lon, t.x1, t.y1),
                greatCircleDistance1(lat, lon, t.x1, t.y2),
                greatCircleDistance1(lat, lon, t.x2, t.y1),
                greatCircleDistance1(lat, lon, t.x2, t.y2)
              )
              val expected = distancesForCorners.min
              val result = SphericalEarthDistanceCalculator.calculator.distance(lat, lon, t)
              result <= expected + 0.1f
            }
        }
      }
    }
  }

  private def intersects[T](es: Seq[RTreeEntry[T]], x: Float, y: Float): Seq[RTreeEntry[T]] =
    intersects(es, x, y, x, y)

  private def intersects[T](es: Seq[RTreeEntry[T]], x1: Float, y1: Float, x2: Float, y2: Float): Seq[RTreeEntry[T]] =
    es.filter(e => intersects(e, x1, y1, x2, y2))

  private def intersects[T](e: RTree[T], x: Float, y: Float): Boolean =
    e.x1 <= x && x <= e.x2 && e.y1 <= y && y <= e.y2

  private def intersects[T](e: RTree[T], x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
    e.x1 <= x2 && x1 <= e.x2 && e.y1 <= y2 && y1 <= e.y2

  private def euclideanDistance[T](x: Float, y: Float, t: RTree[T]): Float = {
    val dx = Math.max(Math.abs((t.x1 + t.x2) / 2 - x) - (t.x2 - t.x1) / 2, 0)
    val dy = Math.max(Math.abs((t.y1 + t.y2) / 2 - y) - (t.y2 - t.y1) / 2, 0)
    Math.sqrt(dx * dx + dy * dy).toFloat
  }

  private def alignedHorizontally[T](e: RTree[T], lat: Float, lon: Float): Boolean =
    e.x1 <= lat && lat <= e.x2 && (lon < e.y1 || e.y2 < lon)

  private def alignedVertically[T](e: RTree[T], lat: Float, lon: Float): Boolean =
    e.y1 <= lon && lon <= e.y2 && (lat < e.x1 || e.x2 < lat)

  implicit private def orderingByName[A <: RTreeEntry[Int]]: Ordering[A] =
    Ordering.by(e => (e.x1, e.y1, e.x2, e.y2, e.value))
}
