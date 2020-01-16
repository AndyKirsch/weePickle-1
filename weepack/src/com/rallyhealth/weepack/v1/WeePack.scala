package com.rallyhealth.weepack.v1

import com.rallyhealth.weepickle.v1.core.NoOpVisitor

object WeePack {

  def transform[T](t: Readable, v: com.rallyhealth.weepickle.v1.core.Visitor[_, T]): T = t.transform(v)

  /**
    * Read the given MessagePack input into a MessagePack struct
    */
  def read(s: Readable): Msg = transform(s, Msg)

  def copy(t: Msg): Msg = transform(t, Msg)

  /**
    * Write the given MessagePack struct as a binary
    */
  def write(t: Msg): Array[Byte] = {
    transform(t, new MsgPackWriter()).toByteArray
  }

  /**
    * Write the given MessagePack struct as a binary to the given OutputStream
    */
  def writeTo(t: Msg, out: java.io.OutputStream): Unit = {
    transform(t, new MsgPackWriter(out))
  }

  /**
    * Parse the given MessagePack input, failing if it is invalid
    */
  def validate(s: Readable): Unit = transform(s, NoOpVisitor)
}
