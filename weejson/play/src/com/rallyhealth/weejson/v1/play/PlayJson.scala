package com.rallyhealth.weejson.v1.play

import play.api.libs.json._
import com.rallyhealth.weepickle.v1.core.Visitor
import com.rallyhealth.weepickle.v1.WeePickle._

import scala.collection.mutable.ArrayBuffer

object PlayJson extends com.rallyhealth.weejson.v1.AstTransformer[JsValue] {
  def transform[T](j: JsValue, f: Visitor[_, T]): T = j match{
    case JsArray(xs) => transformArray(f, xs)
    case JsBoolean(b) => if (b) f.visitTrue(-1) else f.visitFalse(-1)
    case JsNull => f.visitNull(-1)
    case JsNumber(d) => f.visitFloat64String(d.toString, -1)
    case JsObject(kvs) => transformObject(f, kvs)
    case JsString(s) => f.visitString(s, -1)
  }
  def visitArray(length: Int, index: Int) = new AstArrVisitor[Array](JsArray(_))

  def visitObject(length: Int, index: Int) = new AstObjVisitor[ArrayBuffer[(String, JsValue)]](JsObject(_))

  def visitNull(index: Int) = JsNull

  def visitFalse(index: Int) = JsBoolean(false)

  def visitTrue(index: Int) = JsBoolean(true)

  def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) = {
    JsNumber(BigDecimal(s.toString))
  }

  def visitString(s: CharSequence, index: Int) = JsString(s.toString)

  implicit val JsValueWriter: Writer[JsValue] = new Writer[JsValue] {
    def write0[R](out: Visitor[_, R], v: JsValue): R = PlayJson.transform(v, out)
  }

  implicit val JsValueReader: Reader[JsValue] = new Reader.Delegate[JsValue, JsValue](this)

}
