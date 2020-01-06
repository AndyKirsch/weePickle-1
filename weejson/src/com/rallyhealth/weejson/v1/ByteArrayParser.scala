package com.rallyhealth.weejson.v0

import java.nio.charset.StandardCharsets

import com.rallyhealth.weepickle.v0.core.{Platform, Visitor}
/**
  * Basic ByteBuffer parser.
  *
  * This assumes that the provided ByteBuffer is ready to be read. The
  * user is responsible for any necessary flipping/resetting of the
  * ByteBuffer before parsing.
  *
  * The parser makes absolute calls to the ByteBuffer, which will not
  * update its own mutable position fields.
  */
final class ByteArrayParser[J](src: Array[Byte], start: Int = 0, limit: Int = 0) extends Parser[J] with ByteBasedParser[J] {
  private[this] var lineState = 0
  protected[this] def line(): Int = lineState

  protected[this] final def newline(i: Int) = { lineState += 1 }
  protected[this] final def column(i: Int) = i

  protected[this] final def close(): Unit = {}
  protected[this] final def dropBufferUntil(i: Int): Unit = ()
  protected[this] final def byte(i: Int): Byte = Platform.byteAt(src, i + start)
  protected[this] final def char(i: Int): Char = Platform.byteAt(src, i + start).toChar

  protected[this] final def sliceString(i: Int, k: Int): CharSequence = {
    new String(src, i, k - i, StandardCharsets.UTF_8)
  }

  protected[this] final def atEof(i: Int) = i >= limit
}

object ByteArrayParser extends Transformer[Array[Byte]]{
  def transform[T](j: Array[Byte], f: Visitor[_, T]) = new ByteArrayParser(j, 0, j.length).parse(f)
}
