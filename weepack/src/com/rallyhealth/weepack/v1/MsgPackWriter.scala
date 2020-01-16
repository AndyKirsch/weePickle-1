package com.rallyhealth.weepack.v1
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatter

import com.rallyhealth.weepack.v1.Msg.visitString
import com.rallyhealth.weepack.v1.{MsgPackKeys => MPK}
import com.rallyhealth.weepickle.v1.core.{ArrVisitor, ObjVisitor, Visitor}
class MsgPackWriter[T <: java.io.OutputStream](out: T = new ByteArrayOutputStream())
    extends MsgVisitor[T, T] {
  override def visitArray(length: Int, index: Int) = new ArrVisitor[T, T] {
    require(length != -1, "Length of com.rallyhealth.weepack.v1 array must be known up front")
    if (length <= 15){
      out.write(MPK.FixArrMask | length)
    }else if (length <= 65535){
      out.write(MPK.Array16)
      writeUInt16(length)
    }else {
      out.write(MPK.Array32)
      writeUInt32(length)
    }
    def subVisitor = MsgPackWriter.this
    def visitValue(v: T, index: Int): Unit = () // do nothing
    def visitEnd(index: Int) = out // do nothing
  }

  override def visitObject(length: Int, index: Int) = new ObjVisitor[T, T] {
    require(length != -1, "Length of com.rallyhealth.weepack.v1 object must be known up front")
    if (length <= 15){
      out.write(MPK.FixMapMask | length)
    }else if (length <= 65535){
      out.write(MPK.Map16)
      writeUInt16(length)
    }else {
      out.write(MPK.Map32)
      writeUInt32(length)
    }
    def subVisitor = MsgPackWriter.this
    def visitKey(index: Int)= MsgPackWriter.this
    def visitKeyValue(s: Any): Unit = () // do nothing
    def visitValue(v: T, index: Int): Unit = () // do nothing
    def visitEnd(index: Int) = out // do nothing
  }


  override def visitNull(index: Int) = {
    out.write(MPK.Nil)
    out
  }

  override def visitFalse(index: Int) = {
    out.write(MPK.False)
    out
  }

  override def visitTrue(index: Int) = {
    out.write(MPK.True)
    out
  }

  override def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) = {
    visitFloat64(s.toString.toDouble, index)
  }

  override def visitFloat64(d: Double, index: Int) = {
    out.write(MPK.Float64)
    writeUInt64(java.lang.Double.doubleToLongBits(d))
    out
  }
  override def visitFloat32(d: Float, index: Int) = {
    out.write(MPK.Float32)
    writeUInt32(java.lang.Float.floatToIntBits(d))
    out
  }
  override def visitInt32(i: Int, index: Int) = {
    if (i >= 0){
      if (i <= 127) out.write(i)
      else if (i <= 255){
        out.write(MPK.UInt8)
        out.write(i)
      } else if(i <= Short.MaxValue){
        out.write(MPK.Int16)
        writeUInt16(i)
      } else if (i <= 0xffff){
        out.write(MPK.UInt16)
        writeUInt16(i)
      } else{
        out.write(MPK.Int32)
        writeUInt32(i)
      }
    }else{
      if (i >= -32) out.write(i | 0xe0)
      else if(i >= -128){
        out.write(MPK.Int8)
        out.write(i)
      }else if (i >= Short.MinValue) {
        out.write(MPK.Int16)
        writeUInt16(i)
      } else{
        out.write(MPK.Int32)
        writeUInt32(i)
      }
    }

    out
  }

  override def visitInt64(i: Long, index: Int) = {
    if (i >= Int.MinValue && i <= Int.MaxValue){
      visitInt32(i.toInt, index)
    }else if (i >= 0 && i <= 0xffffffffL){
      out.write(MPK.UInt32)
      writeUInt32(i.toInt)
    }else{
      out.write(MPK.Int64)
      writeUInt64(i)
    }
    out
  }

  override def visitUInt64(i: Long, index: Int) = {
    if (i >= 0) visitInt64(i, index)
    else{
      out.write(MPK.UInt64)
      writeUInt64(i)
    }
    out
  }

  override def visitString(s: CharSequence, index: Int) = {
    val bytes = s.toString.getBytes(StandardCharsets.UTF_8)
    val length = bytes.length
    if (length <= 31){
      out.write(MPK.FixStrMask | length)
    } else if (length <= 255){
      out.write(MPK.Str8)
      writeUInt8(length)
    }else if (length <= 65535){
      out.write(MPK.Str16)
      writeUInt16(length)
    }else {
      out.write(MPK.Str32)
      writeUInt32(length)
    }

    out.write(bytes, 0, length)
    out
  }
  override def visitBinary(bytes: Array[Byte], offset: Int, len: Int, index: Int) = {
    if (len <= 255) {
      out.write(MPK.Bin8)
      writeUInt8(len)
    } else if (len <= 65535) {
      out.write(MPK.Bin16)
      writeUInt16(len)
    } else {
      out.write(MPK.Bin32)
      writeUInt32(len)
    }

    out.write(bytes, offset, len)
    out
  }
  def writeUInt8(i: Int) = out.write(i)
  def writeUInt16(i: Int) = {
    out.write((i >> 8) & 0xff)
    out.write((i >> 0) & 0xff)
  }
  def writeUInt32(i: Int) = {
    out.write((i >> 24) & 0xff)
    out.write((i >> 16) & 0xff)
    out.write((i >> 8) & 0xff)
    out.write((i >> 0) & 0xff)
  }
  def writeUInt64(i: Long) = {
    out.write(((i >> 56) & 0xff).toInt)
    out.write(((i >> 48) & 0xff).toInt)
    out.write(((i >> 40) & 0xff).toInt)
    out.write(((i >> 32) & 0xff).toInt)
    out.write(((i >> 24) & 0xff).toInt)
    out.write(((i >> 16) & 0xff).toInt)
    out.write(((i >> 8) & 0xff).toInt)
    out.write(((i >> 0) & 0xff).toInt)
  }

  def visitExt(tag: Byte, bytes: Array[Byte], offset: Int, len: Int, index: Int) = {
    len match{
      case 1 => out.write(MPK.FixExt1)
      case 2 => out.write(MPK.FixExt2)
      case 4 => out.write(MPK.FixExt4)
      case 8 => out.write(MPK.FixExt8)
      case 16 => out.write(MPK.FixExt16)
      case _ =>
        if (len <= 255){
          out.write(MPK.Ext8)
          writeUInt8(len)
        }else if (len <= 65535){
          out.write(MPK.Ext16)
          writeUInt16(len)
        }else{
          writeUInt32(len)
          out.write(MPK.Ext32)
        }
    }
    out.write(tag)
    out.write(bytes, offset, len)
    out
  }

  def visitChar(s: Char, index: Int) = {
    out.write(MPK.UInt16)
    writeUInt16(s)
    out
  }

  override def visitTimestamp(instant: Instant, index: Int): T = {
    val seconds: Long = instant.getEpochSecond
    val nanos: Int = instant.getNano
    if (nanos == 0 && (seconds & 0xffffffff00000000L) == 0L) {
      /**
        * timestamp 32 stores the number of seconds that have elapsed since 1970-01-01 00:00:00 UTC
        * in an 32-bit unsigned integer:
        * +--------+--------+--------+--------+--------+--------+
        * |  0xd6  |   -1   |   seconds in 32-bit unsigned int  |
        * +--------+--------+--------+--------+--------+--------+
        */
      writeUInt8(MPK.FixExt4)
      out.write(-1)
      writeUInt32(seconds.toInt)
    } else {
      val seconds34 = seconds & ((1L << 34) - 1)
      if (seconds34 == seconds) {
        /**
          * timestamp 64 stores the number of seconds and nanoseconds that have elapsed since 1970-01-01 00:00:00 UTC
          * in 32-bit unsigned integers:
          * +--------+--------+--------+--------+--------+--------+--------+--------+--------+--------+
          * |  0xd7  |   -1   |nanoseconds in 30-bit unsigned int |  seconds in 34-bit unsigned int   |
          * +--------+--------+--------+--------+--------+--------+--------+--------+--------+--------+
          */
        val nano30secs34 = (nanos.toLong << 34) | seconds34
        writeUInt8(MPK.FixExt8)
        out.write(-1)
        writeUInt64(nano30secs34)
      } else {
        /**
          * timestamp 96 stores the number of seconds and nanoseconds that have elapsed since 1970-01-01 00:00:00 UTC
          * in 64-bit signed integer and 32-bit unsigned integer:
          * +--------+--------+--------+--------+--------+--------+--------+
          * |  0xc7  |   12   |   -1   |nanoseconds in 32-bit unsigned int |
          * +--------+--------+--------+--------+--------+--------+--------+
          * +--------+--------+--------+--------+--------+--------+--------+--------+
          * seconds in 64-bit signed int                        |
          * +--------+--------+--------+--------+--------+--------+--------+--------+
          */
        writeUInt8(MPK.Ext8)
        out.write(12)
        out.write(-1)
        writeUInt32(nanos)
        writeUInt64(seconds) // correct even though signed
      }
    }
    out
  }
}
