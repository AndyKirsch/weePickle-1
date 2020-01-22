package com.rallyhealth.weejson.v1.argonaut

import argonaut.{Json, JsonNumber, JsonObject}
import com.rallyhealth.weepickle.v1.core.Visitor

import scala.collection.mutable.ArrayBuffer

object ArgonautJson extends com.rallyhealth.weejson.v1.AstTransformer[Json]{
  override def transform[T](j: Json, f: Visitor[_, T]) = j.fold(
    f.visitNull(-1),
    if (_) f.visitTrue(-1) else f.visitFalse(-1),
    n => n.toDouble match{
      case Some(d) => f.visitFloat64(d, -1)
      case None => f.visitFloat64String(n.asJson.toString(), -1)
    },
    f.visitString(_, -1),
    arr => transformArray(f, arr),
    obj => transformObject(f, obj.toList)
  )

  def visitArray(length: Int, index: Int) = new AstArrVisitor[List](xs => Json.jArray(xs))
  def visitObject(length: Int, index: Int) = new AstObjVisitor[ArrayBuffer[(String, Json)]](
    vs => Json.jObject(JsonObject.fromTraversableOnce(vs))
  )

  def visitNull(index: Int) = Json.jNull

  def visitFalse(index: Int) = Json.jFalse

  def visitTrue(index: Int) = Json.jTrue

  def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) = {
    Json.jNumber(JsonNumber.unsafeDecimal(s.toString))
  }

  def visitString(s: CharSequence, index: Int) = Json.jString(s.toString)
}
